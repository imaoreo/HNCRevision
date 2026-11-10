import Foundation

/// IPv4 address helpers.
public enum IPv4 {
    public static func parse(_ text: String) -> UInt32? {
        let parts = text.trimmingCharacters(in: .whitespaces).split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var value: UInt32 = 0
        for part in parts {
            guard !part.isEmpty, part.count <= 3, part.allSatisfy(\.isASCII), let n = UInt32(part), n <= 255 else { return nil }
            value = value << 8 | n
        }
        return value
    }

    public static func format(_ value: UInt32) -> String {
        "\(value >> 24 & 255).\(value >> 16 & 255).\(value >> 8 & 255).\(value & 255)"
    }

    /// Parses "a.b.c.d/n".
    public static func parseCIDR(_ text: String) -> (address: UInt32, prefix: Int)? {
        let parts = text.split(separator: "/")
        guard parts.count == 2, let address = parse(String(parts[0])),
              let prefix = Int(parts[1].trimmingCharacters(in: .whitespaces)), (0...32).contains(prefix) else { return nil }
        return (address, prefix)
    }

    public static func maskValue(prefix: Int) -> UInt32 {
        prefix <= 0 ? 0 : UInt32.max << UInt32(32 - min(prefix, 32))
    }

    public static func mask(prefix: Int) -> String { format(maskValue(prefix: prefix)) }

    /// The prefix length of a contiguous mask, or nil.
    public static func prefix(ofMask text: String) -> Int? {
        guard let value = parse(text) else { return nil }
        let ones = value.nonzeroBitCount
        return maskValue(prefix: ones) == value ? ones : nil
    }

    public static func blockSize(prefix: Int) -> UInt64 { UInt64(1) << UInt64(32 - prefix) }
}

/// Allocates VLSM subnets the way the site's worked solutions do.
public enum VLSMSolver {
    /// Smallest power of two ≥ hosts + 2, at least 4.
    public static func blockSize(hosts: Int) -> Int {
        var block = 4
        while block < hosts + 2 { block *= 2 }
        return block
    }

    /// Largest first, ties by LAN name; each block starts where the last one ended.
    public static func order(_ requests: [VLSMRequest]) -> [VLSMRequest] {
        requests.sorted { $0.hosts != $1.hosts ? $0.hosts > $1.hosts : $0.lan < $1.lan }
    }

    /// The allocation rows, or nil if the parent is malformed or the LANs don't fit inside it.
    public static func solve(parent: String, requests: [VLSMRequest]) -> [VLSMRow]? {
        guard let (address, parentPrefix) = IPv4.parseCIDR(parent) else { return nil }
        let base = UInt64(address & IPv4.maskValue(prefix: parentPrefix))
        let end = base + IPv4.blockSize(prefix: parentPrefix)
        var next = base
        var rows: [VLSMRow] = []
        for request in order(requests) {
            guard request.hosts >= 0 else { return nil }
            let block = UInt64(blockSize(hosts: request.hosts))
            guard block <= (UInt64(1) << 32) else { return nil }
            let prefix = 32 - (block.trailingZeroBitCount)
            let network = next
            let broadcast = network + block - 1
            guard broadcast < end else { return nil }
            rows.append(VLSMRow(
                lan: request.lan, hosts: request.hosts,
                network: "\(IPv4.format(UInt32(network)))/\(prefix)",
                mask: IPv4.mask(prefix: prefix),
                firstUsable: IPv4.format(UInt32(network + 1)),
                lastUsable: IPv4.format(UInt32(broadcast - 1)),
                broadcast: IPv4.format(UInt32(broadcast)),
                usableCapacity: Int(block) - 2
            ))
            next = broadcast + 1
        }
        return rows
    }

    /// Three hints for a generated exercise, from general to specific.
    public static func hints(for exercise: VLSMExercise) -> [String] {
        let ordered = order(exercise.requests)
        let sizes = ordered.map { "\($0.lan): \(blockSize(hosts: $0.hosts))" }.joined(separator: ", ")
        let first = exercise.rows.first
        return [
            "Order the LANs by host count, largest first: " + ordered.map { "\($0.lan) (\($0.hosts))" }.joined(separator: ", ") + ".",
            "Add 2 to each host count and round up to a power of two. Block sizes: \(sizes).",
            first.map { "\($0.lan) starts at the parent's network address: \($0.network). Each next block starts right after the previous broadcast." }
                ?? "Start at the parent's network address.",
        ]
    }
}

/// Answer checking for typed IPv4 answers: spaces are ignored.
public enum VLSMAnswer {
    public enum Field: String, CaseIterable, Sendable {
        case network, mask, first, last, broadcast

        public var label: String {
            switch self {
            case .network: return "Network / prefix"
            case .mask: return "Subnet mask"
            case .first: return "First usable"
            case .last: return "Last usable"
            case .broadcast: return "Broadcast"
            }
        }

        public func expected(in row: VLSMRow) -> String {
            switch self {
            case .network: return row.network
            case .mask: return row.mask
            case .first: return row.firstUsable
            case .last: return row.lastUsable
            case .broadcast: return row.broadcast
            }
        }
    }

    public static func normalise(_ text: String) -> String {
        text.filter { !$0.isWhitespace }
    }

    public static func isCorrect(_ answer: String, field: Field, row: VLSMRow) -> Bool {
        let given = normalise(answer)
        return !given.isEmpty && given == normalise(field.expected(in: row))
    }
}

/// Random VLSM questions.
public enum VLSMGenerator {
    public enum Difficulty: String, CaseIterable, Sendable, Identifiable, Codable {
        case easy, medium, hard
        public var id: String { rawValue }
        public var label: String {
            switch self {
            case .easy: return "Easy"
            case .medium: return "Medium"
            case .hard: return "Hard"
            }
        }
    }

    /// Easy: /24 with 3 LANs. Medium: /24 with 4–5. Hard: /23 or /22 with 5–6.
    public static func generate(_ difficulty: Difficulty, using rng: inout some RandomNumberGenerator) -> VLSMExercise {
        let prefix: Int
        let lanCount: Int
        switch difficulty {
        case .easy:
            prefix = 24; lanCount = 3
        case .medium:
            prefix = 24; lanCount = Int.random(in: 4...5, using: &rng)
        case .hard:
            prefix = Bool.random(using: &rng) ? 23 : 22; lanCount = Int.random(in: 5...6, using: &rng)
        }
        let parent = "\(IPv4.format(randomPrivateNetwork(prefix: prefix, using: &rng)))/\(prefix)"
        let size = Int(IPv4.blockSize(prefix: prefix))
        let maxHosts = size / 2 - 2
        let names = (0..<lanCount).map { "LAN \(Character(UnicodeScalar(UInt8(65 + $0))))" }

        for attempt in 0..<10_000 {
            // After many misses (very unlikely), narrow the range so generation always finishes.
            let cap = attempt < 5_000 ? maxHosts : max(2, size / (2 * lanCount) - 2)
            let requests = names.map { VLSMRequest(lan: $0, hosts: Int.random(in: 2...cap, using: &rng)) }
            if let rows = VLSMSolver.solve(parent: parent, requests: requests) {
                return VLSMExercise(title: "\(difficulty.label) · generated", parent: parent, requests: requests, rows: rows)
            }
        }
        let requests = names.map { VLSMRequest(lan: $0, hosts: 2) }
        return VLSMExercise(title: "\(difficulty.label) · generated", parent: parent, requests: requests,
                            rows: VLSMSolver.solve(parent: parent, requests: requests) ?? [])
    }

    /// 192.168.x.0, 172.16–31.x.0 or 10.x.y.0, aligned to `prefix` (16…30).
    public static func randomPrivateNetwork(prefix: Int, using rng: inout some RandomNumberGenerator) -> UInt32 {
        let raw: UInt32
        switch Int.random(in: 0..<3, using: &rng) {
        case 0:
            raw = 192 << 24 | 168 << 16 | UInt32.random(in: 0...255, using: &rng) << 8
        case 1:
            raw = 172 << 24 | UInt32.random(in: 16...31, using: &rng) << 16 | UInt32.random(in: 0...255, using: &rng) << 8
        default:
            raw = 10 << 24 | UInt32.random(in: 0...255, using: &rng) << 16 | UInt32.random(in: 0...255, using: &rng) << 8
        }
        return raw & IPv4.maskValue(prefix: prefix)
    }
}

/// Quick-fire subnetting questions.
public struct SubnetDrill: Equatable, Sendable {
    public enum Kind: String, CaseIterable, Sendable {
        case prefixToMask, maskToPrefix, usableHosts, networkAddress, broadcastAddress
    }

    public let kind: Kind
    public let prompt: String
    public let answer: String
    /// A one-line explanation shown after checking.
    public let working: String

    public static let roundLength = 10
    public static let prefixRange = 16...30

    /// Spaces are ignored; prefixes may be written with or without the slash.
    public func isCorrect(_ given: String) -> Bool {
        var text = VLSMAnswer.normalise(given)
        if kind == .maskToPrefix, text.hasPrefix("/") { text.removeFirst() }
        if kind == .usableHosts { text = text.replacingOccurrences(of: ",", with: "") }
        return !text.isEmpty && text == answer
    }

    /// The answer as shown to the student.
    public var displayAnswer: String { kind == .maskToPrefix ? "/\(answer)" : answer }

    public static func random(using rng: inout some RandomNumberGenerator) -> SubnetDrill {
        // Mask questions go both ways, so each of the four kinds is equally likely.
        let pick = Int.random(in: 0..<4, using: &rng)
        let kind: Kind
        switch pick {
        case 0: kind = Bool.random(using: &rng) ? .prefixToMask : .maskToPrefix
        case 1: kind = .usableHosts
        case 2: kind = .networkAddress
        default: kind = .broadcastAddress
        }
        let prefix = Int.random(in: prefixRange, using: &rng)
        let address = VLSMGenerator.randomPrivateNetwork(prefix: 24, using: &rng) | UInt32.random(in: 0...255, using: &rng)
        return make(kind, prefix: prefix, address: address)
    }

    public static func round(using rng: inout some RandomNumberGenerator) -> [SubnetDrill] {
        (0..<roundLength).map { _ in random(using: &rng) }
    }

    /// The octet (1–4) the block size applies to, and the block size within it.
    static func interestingOctet(prefix: Int) -> (octet: Int, block: Int) {
        let octet = min(4, prefix / 8 + 1)
        let bitsInOctet = prefix - (octet - 1) * 8
        return (octet, 1 << (8 - bitsInOctet))
    }

    public static func make(_ kind: Kind, prefix: Int, address: UInt32) -> SubnetDrill {
        let mask = IPv4.mask(prefix: prefix)
        let block = IPv4.blockSize(prefix: prefix)
        let (octet, octetBlock) = interestingOctet(prefix: prefix)
        let ordinal = ["first", "second", "third", "fourth"][octet - 1]
        let addr = IPv4.format(address)
        let network = address & IPv4.maskValue(prefix: prefix)
        let broadcast = network | ~IPv4.maskValue(prefix: prefix)
        let octetValue = Int(address >> UInt32(8 * (4 - octet)) & 255)
        let start = octetValue / octetBlock * octetBlock

        switch kind {
        case .prefixToMask:
            return SubnetDrill(kind: kind, prompt: "/\(prefix) as a subnet mask?", answer: mask,
                               working: "Block size \(octetBlock) in the \(ordinal) octet, so that octet is 256 − \(octetBlock) = \(256 - octetBlock): \(mask).")
        case .maskToPrefix:
            return SubnetDrill(kind: kind, prompt: "\(mask) as a prefix?", answer: String(prefix),
                               working: "\(256 - octetBlock) in the \(ordinal) octet means a block size of \(octetBlock), so \((octet - 1) * 8) + \(8 - (octetBlock.trailingZeroBitCount)) = \(prefix) network bits: /\(prefix).")
        case .usableHosts:
            return SubnetDrill(kind: kind, prompt: "Usable hosts in a /\(prefix)?", answer: String(block - 2),
                               working: "Block size 2^\(32 - prefix) = \(block), so \(block) − 2 = \(block - 2) usable (network and broadcast are reserved).")
        case .networkAddress:
            return SubnetDrill(kind: kind, prompt: "Network address of \(addr)/\(prefix)?", answer: IPv4.format(network),
                               working: "Block size \(octetBlock) in the \(ordinal) octet, so \(octetValue) rounds down to \(start)" + (octet < 4 ? " and later octets are 0" : "") + ": \(IPv4.format(network)).")
        case .broadcastAddress:
            return SubnetDrill(kind: kind, prompt: "Broadcast address of \(addr)/\(prefix)?", answer: IPv4.format(broadcast),
                               working: "Block size \(octetBlock) in the \(ordinal) octet, so the block is \(start)–\(start + octetBlock - 1)" + (octet < 4 ? " and later octets are 255" : "") + ": \(IPv4.format(broadcast)).")
        }
    }
}

/// A round of ten drills in progress.
public struct DrillRound: Equatable, Sendable {
    public private(set) var questions: [SubnetDrill]
    public private(set) var results: [Bool?]
    public private(set) var index = 0

    public init(questions: [SubnetDrill]) {
        self.questions = questions
        self.results = Array(repeating: nil, count: questions.count)
    }

    public init(using rng: inout some RandomNumberGenerator) {
        self.init(questions: SubnetDrill.round(using: &rng))
    }

    public var current: SubnetDrill? { questions.indices.contains(index) ? questions[index] : nil }
    public var currentResult: Bool? { results.indices.contains(index) ? results[index] : nil }
    public var score: Int { results.filter { $0 == true }.count }
    public var isFinished: Bool { index >= questions.count }

    /// Checks the current answer (once). Returns whether it was correct.
    @discardableResult
    public mutating func check(_ answer: String) -> Bool {
        guard let q = current, results[index] == nil else { return currentResult ?? false }
        let ok = q.isCorrect(answer)
        results[index] = ok
        return ok
    }

    public mutating func next() {
        guard currentResult != nil else { return }
        index += 1
    }
}
