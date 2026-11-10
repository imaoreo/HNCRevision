import Foundation

/// FNV-1a 64-bit hash, as 16 hex digits. Stable across launches and devices (unlike `Hasher`).
public func fnv1a64(_ text: String) -> String {
    var hash: UInt64 = 0xcbf2_9ce4_8422_2325
    for byte in text.utf8 {
        hash ^= UInt64(byte)
        hash = hash &* 0x0000_0100_0000_01b3
    }
    return String(format: "%016llx", hash)
}

/// Per-item hashes of the content, to spot new or changed cards and questions after a data load.
public struct ContentFingerprints: Codable, Equatable, Sendable {
    /// Card id (as a string) → hash of question, answer and hint.
    public var cards: [String: String]
    /// Question id → hash of question, options, answer and explanation.
    public var questions: [String: String]

    public init(cards: [String: String] = [:], questions: [String: String] = [:]) {
        self.cards = cards
        self.questions = questions
    }

    public init(_ data: RevisionData) {
        var cards: [String: String] = [:]
        for card in data.cards { cards[String(card.id)] = fnv1a64(card.q + "\u{1F}" + card.a + "\u{1F}" + card.hint) }
        var questions: [String: String] = [:]
        for q in data.questions {
            questions[q.id] = fnv1a64(([q.q] + q.options + [String(q.answer), q.explanation]).joined(separator: "\u{1F}"))
        }
        self.init(cards: cards, questions: questions)
    }
}

/// "Last change: 3 added, 2 updated · 12 Nov".
public struct ContentChange: Codable, Equatable, Sendable {
    public var date: Date
    public var added: Int
    public var updated: Int
    public var questionsChanged: Int

    public init(date: Date, added: Int, updated: Int, questionsChanged: Int) {
        self.date = date
        self.added = added
        self.updated = updated
        self.questionsChanged = questionsChanged
    }
}

public enum ContentDiff {
    public struct Result: Equatable, Sendable {
        public var added: [Int]
        public var updated: [Int]
        public var questionsChanged: Int

        public var isEmpty: Bool { added.isEmpty && updated.isEmpty && questionsChanged == 0 }
    }

    /// Cards that are new or whose hash changed, and the number of new or changed questions.
    /// Removed items don't count.
    public static func compare(old: ContentFingerprints, new: ContentFingerprints) -> Result {
        var added: [Int] = []
        var updated: [Int] = []
        for (key, hash) in new.cards {
            guard let id = Int(key) else { continue }
            if let previous = old.cards[key] {
                if previous != hash { updated.append(id) }
            } else {
                added.append(id)
            }
        }
        let questions = new.questions.filter { old.questions[$0.key] != $0.value }.count
        return Result(added: added.sorted(), updated: updated.sorted(), questionsChanged: questions)
    }
}

/// `hncrevision://card/<id>` opens a card in Study.
public enum DeepLink {
    public static let scheme = "hncrevision"

    public static func cardURL(_ id: Int) -> URL { URL(string: "\(scheme)://card/\(id)")! }

    /// `hncrevision://due` opens Study in Due mode.
    public static let dueURL = URL(string: "\(scheme)://due")!

    public enum Target: Equatable, Sendable {
        case card(Int)
        case due
    }

    public static func target(of url: URL) -> Target? {
        guard url.scheme?.lowercased() == scheme else { return nil }
        let parts = ([url.host ?? ""] + url.pathComponents.filter { $0 != "/" }).filter { !$0.isEmpty }
        switch parts.first?.lowercased() {
        case "card":
            guard parts.count >= 2, let id = Int(parts[1]) else { return nil }
            return .card(id)
        case "due":
            return .due
        default:
            return nil
        }
    }
}
