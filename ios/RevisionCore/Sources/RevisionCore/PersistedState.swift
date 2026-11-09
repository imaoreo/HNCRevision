import Foundation

public enum Rating: String, Codable, Sendable {
    /// Confident.
    case known
    /// Revisit.
    case review
}

public enum RatingFilter: String, Codable, CaseIterable, Sendable, Identifiable {
    case both, known, review
    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .both: return "Confident and Revisit"
        case .known: return "Confident only"
        case .review: return "Revisit only"
        }
    }

    func includes(_ rating: Rating?) -> Bool {
        guard let rating else { return false }
        switch self {
        case .both: return true
        case .known: return rating == .known
        case .review: return rating == .review
        }
    }
}

/// Study shows every card; Rated shows only cards with a rating.
public enum CardMode: String, Codable, CaseIterable, Sendable {
    case study, review
}

public enum AppTab: String, Codable, Sendable, Hashable {
    case study, quiz, browse, more
}

public struct QuizResult: Codable, Equatable, Sendable {
    public var correct: Bool
    public var choice: Int
    public var answeredAt: Date

    public init(correct: Bool, choice: Int, answeredAt: Date) {
        self.correct = correct
        self.choice = choice
        self.answeredAt = answeredAt
    }
}

/// A Study or Rated-cards deck in progress.
public struct CardDeck: Codable, Equatable, Sendable {
    public var scope: String
    public var ids: [Int]
    public var index: Int
    public var revealed: Bool

    public init(scope: String = "", ids: [Int] = [], index: Int = 0, revealed: Bool = false) {
        self.scope = scope
        self.ids = ids
        self.index = index
        self.revealed = revealed
    }
}

public struct QuizResponse: Codable, Equatable, Sendable {
    /// Index into the question's original `options`.
    public var choice: Int
    public var checked: Bool

    public init(choice: Int, checked: Bool) {
        self.choice = choice
        self.checked = checked
    }
}

/// A quiz attempt in progress.
public struct QuizDeck: Codable, Equatable, Sendable {
    public var scope: String
    public var ids: [String]
    /// For each question, the display order of its options (indices into `options`).
    public var orders: [[Int]]
    public var index: Int
    public var responses: [String: QuizResponse]
    public var done: Bool

    public init(scope: String = "", ids: [String] = [], orders: [[Int]] = [], index: Int = 0,
                responses: [String: QuizResponse] = [:], done: Bool = false) {
        self.scope = scope
        self.ids = ids
        self.orders = orders
        self.index = index
        self.responses = responses
        self.done = done
    }
}

public struct SavedView: Codable, Equatable, Sendable {
    public var unit: String
    public var outcome: String
    public var topic: String
    public var tab: AppTab
    public var cardMode: CardMode
}

/// Everything saved on the device, stored as one JSON blob.
struct PersistedState: Codable, Equatable {
    var ratings: [String: Rating] = [:]
    var quizProgress: [String: QuizResult] = [:]
    var cardSeen: [Int]?
    var quizSeen: [String]?
    var ratingFilter: RatingFilter = .both
    var view: SavedView?
    var cardDecks: [String: CardDeck] = [:]
    var quizDecks: [String: QuizDeck] = [:]
    var vlsmWorking: [String: String] = [:]
    var speechEnabled = false
    var dyslexiaMode = false

    init() {}

    enum CodingKeys: String, CodingKey {
        case ratings, quizProgress, cardSeen, quizSeen, ratingFilter, view, cardDecks, quizDecks, vlsmWorking,
             speechEnabled, dyslexiaMode
    }

    // Lenient: a field that fails to decode falls back to its default instead of losing everything.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ratings = (try? c.decodeIfPresent([String: Rating].self, forKey: .ratings)) ?? [:]
        quizProgress = (try? c.decodeIfPresent([String: QuizResult].self, forKey: .quizProgress)) ?? [:]
        cardSeen = try? c.decodeIfPresent([Int].self, forKey: .cardSeen)
        quizSeen = try? c.decodeIfPresent([String].self, forKey: .quizSeen)
        ratingFilter = (try? c.decodeIfPresent(RatingFilter.self, forKey: .ratingFilter)) ?? .both
        view = try? c.decodeIfPresent(SavedView.self, forKey: .view)
        cardDecks = (try? c.decodeIfPresent([String: CardDeck].self, forKey: .cardDecks)) ?? [:]
        quizDecks = (try? c.decodeIfPresent([String: QuizDeck].self, forKey: .quizDecks)) ?? [:]
        vlsmWorking = (try? c.decodeIfPresent([String: String].self, forKey: .vlsmWorking)) ?? [:]
        speechEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .speechEnabled)) ?? false
        dyslexiaMode = (try? c.decodeIfPresent(Bool.self, forKey: .dyslexiaMode)) ?? false
    }
}

/// Where `RevisionStore` keeps its state. `UserDefaults` conforms.
public protocol StateStorage: AnyObject {
    func data(forKey key: String) -> Data?
    func set(_ value: Any?, forKey key: String)
}

extension UserDefaults: StateStorage {}

/// An in-memory storage, for tests and previews.
public final class MemoryStorage: StateStorage {
    private var values: [String: Data] = [:]
    public init() {}
    public func data(forKey key: String) -> Data? { values[key] }
    public func set(_ value: Any?, forKey key: String) { values[key] = value as? Data }
}
