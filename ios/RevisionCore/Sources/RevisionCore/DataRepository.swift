import Foundation

/// Where the data on screen came from.
public enum DataOrigin: Equatable, Sendable {
    /// Downloaded from the website during this session.
    case live(Date)
    /// The last successful download, saved on the device.
    case saved(Date)
    /// The copy built into the app.
    case bundled
}

/// The three raw scripts.
public struct RawDataFiles: Equatable, Sendable {
    public var cards: String
    public var quiz: String
    public var vlsm: String

    public init(cards: String, quiz: String, vlsm: String) {
        self.cards = cards
        self.quiz = quiz
        self.vlsm = vlsm
    }

    public func parse() throws -> RevisionData {
        try RevisionData.parse(cards: cards, quiz: quiz, vlsm: vlsm)
    }
}

public protocol HTTPFetching: Sendable {
    func text(from url: URL) async throws -> String
}

public struct HTTPError: Error, Equatable, Sendable {
    public let status: Int
}

/// Plain URLSession fetching with no caching (ephemeral session, ~15 s timeout).
public struct URLSessionFetcher: HTTPFetching {
    let session: URLSession

    public init(timeout: TimeInterval = 15) {
        let config = URLSessionConfiguration.ephemeral
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        config.urlCache = nil
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout * 2
        config.waitsForConnectivity = false
        session = URLSession(configuration: config)
    }

    public func text(from url: URL) async throws -> String {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData)
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw HTTPError(status: http.statusCode)
        }
        return String(decoding: data, as: UTF8.self)
    }
}

public enum ScriptLocator {
    public static let names = ["cards", "quiz", "vlsm"]

    /// Finds the current URLs of cards.js, quiz.js and vlsm.js (with their `?v=N` cache-busters)
    /// in the site's HTML, falling back to the bare filenames.
    public static func scriptURLs(html: String, base: URL) -> [String: URL] {
        var found: [String: String] = [:]
        if let regex = try? NSRegularExpression(pattern: #"src="((?:\./)?(cards|quiz|vlsm)\.js[^"]*)""#) {
            let ns = html as NSString
            for match in regex.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
                let name = ns.substring(with: match.range(at: 2))
                if found[name] == nil { found[name] = ns.substring(with: match.range(at: 1)) }
            }
        }
        var urls: [String: URL] = [:]
        for name in names {
            let path = found[name] ?? "\(name).js"
            urls[name] = URL(string: path, relativeTo: base)?.absoluteURL ?? base.appendingPathComponent("\(name).js")
        }
        return urls
    }
}

/// Loads the bundled or cached data and refreshes it from the website.
public struct DataRepository: Sendable {
    public static let siteURL = URL(string: "https://data-security-revision-cards.exquisitemeadow.chatgpt.site/")!

    public let siteURL: URL
    public let bundledDirectory: URL
    public let cacheDirectory: URL
    public let fetcher: any HTTPFetching

    public init(siteURL: URL = DataRepository.siteURL, bundledDirectory: URL, cacheDirectory: URL,
                fetcher: any HTTPFetching = URLSessionFetcher()) {
        self.siteURL = siteURL
        self.bundledDirectory = bundledDirectory
        self.cacheDirectory = cacheDirectory
        self.fetcher = fetcher
    }

    /// The default cache location: `Caches/SiteData`.
    public static var defaultCacheDirectory: URL {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return caches.appendingPathComponent("SiteData", isDirectory: true)
    }

    private var metaURL: URL { cacheDirectory.appendingPathComponent("saved-at.txt") }

    func readFiles(in directory: URL) throws -> RawDataFiles {
        func read(_ name: String) throws -> String {
            try String(contentsOf: directory.appendingPathComponent("\(name).js"), encoding: .utf8)
        }
        return RawDataFiles(cards: try read("cards"), quiz: try read("quiz"), vlsm: try read("vlsm"))
    }

    /// The last saved live copy if it parses, otherwise the bundled copy. Never touches the network.
    public func loadInitial() -> (data: RevisionData, origin: DataOrigin) {
        if let files = try? readFiles(in: cacheDirectory), let data = try? files.parse(), data.isUsable {
            return (data, .saved(savedAt() ?? Date.distantPast))
        }
        if let files = try? readFiles(in: bundledDirectory), let data = try? files.parse() {
            return (data, .bundled)
        }
        return (.empty, .bundled)
    }

    func savedAt() -> Date? {
        guard let text = try? String(contentsOf: metaURL, encoding: .utf8),
              let seconds = TimeInterval(text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            // Fall back to the file date.
            let attrs = try? FileManager.default.attributesOfItem(atPath: cacheDirectory.appendingPathComponent("cards.js").path)
            return attrs?[.modificationDate] as? Date
        }
        return Date(timeIntervalSince1970: seconds)
    }

    /// Downloads the three scripts. Throws unless all of them parse into usable data.
    public func fetchLatest() async throws -> (data: RevisionData, files: RawDataFiles) {
        let html = (try? await fetcher.text(from: siteURL)) ?? ""
        let urls = ScriptLocator.scriptURLs(html: html, base: siteURL)
        async let cards = fetcher.text(from: urls["cards"]!)
        async let quiz = fetcher.text(from: urls["quiz"]!)
        async let vlsm = fetcher.text(from: urls["vlsm"]!)
        let files = RawDataFiles(cards: try await cards, quiz: try await quiz, vlsm: try await vlsm)
        let data = try files.parse()
        guard data.isUsable else { throw RevisionDataError.unusable }
        return (data, files)
    }

    /// Saves a successful download as the new cached copy.
    public func save(_ files: RawDataFiles, at date: Date = Date()) throws {
        try FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
        for (name, text) in [("cards", files.cards), ("quiz", files.quiz), ("vlsm", files.vlsm)] {
            try Data(text.utf8).write(to: cacheDirectory.appendingPathComponent("\(name).js"), options: .atomic)
        }
        try Data(String(date.timeIntervalSince1970).utf8).write(to: metaURL, options: .atomic)
    }

    /// Fetches, validates and saves. Returns nil (keeping the current data) on any failure.
    public func refresh() async -> RevisionData? {
        do {
            let (data, files) = try await fetchLatest()
            try? save(files)
            return data
        } catch {
            return nil
        }
    }
}
