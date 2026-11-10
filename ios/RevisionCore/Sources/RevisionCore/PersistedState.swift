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

/// Study shows every card; Rated shows only cards with a rating; Due shows cards whose Leitner date has come;
/// Starred shows bookmarked cards; New shows cards new or changed on the website; Single shows one card
/// (opened from the widget or a link).
public enum CardMode: String, Codable, CaseIterable, Sendable {
    case study, review, due, starred, new, single

    /// The modes in the segmented control, in order.
    public static let pickerModes: [CardMode] = [.study, .due, .review, .starred]

    /// Modes that deal rounds from `cardSeen`, like the site's Study.
    public var usesRounds: Bool { self == .study || self == .review || self == .starred }
}

/// The Quiz tab: multiple-choice practice or a mock exam.
public enum QuizMode: String, Codable, CaseIterable, Sendable {
    case practice, mock
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
    /// Added in 1.1.
    public var quizMode: QuizMode = .practice

    public init(unit: String, outcome: String, topic: String, tab: AppTab, cardMode: CardMode, quizMode: QuizMode = .practice) {
        self.unit = unit
        self.outcome = outcome
        self.topic = topic
        self.tab = tab
        self.cardMode = cardMode
        self.quizMode = quizMode
    }

    enum CodingKeys: String, CodingKey { case unit, outcome, topic, tab, cardMode, quizMode }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        unit = try c.decode(String.self, forKey: .unit)
        outcome = try c.decode(String.self, forKey: .outcome)
        topic = try c.decode(String.self, forKey: .topic)
        tab = (try? c.decodeIfPresent(AppTab.self, forKey: .tab)) ?? .study
        cardMode = (try? c.decodeIfPresent(CardMode.self, forKey: .cardMode)) ?? .study
        quizMode = (try? c.decodeIfPresent(QuizMode.self, forKey: .quizMode)) ?? .practice
    }
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

    // 1.1
    /// Card id → Leitner box and due day. Nil until the first 1.1 launch migrates the 1.0 ratings.
    var schedule: [String: CardSchedule]?
    var starred: [Int] = []
    var notes: [String: String] = [:]
    /// "yyyy-MM-dd" → number of revision actions that day.
    var activity: [String: Int] = [:]
    var bestStreak = 0
    var reminderEnabled = false
    var reminderMinutes = Reminder.defaultMinutes
    var mockAttempt: MockAttempt?
    var mockHistory: [MockRecord] = []
    var drillBest = 0
    var fingerprints: ContentFingerprints?
    var newCardIds: [Int] = []
    var lastChange: ContentChange?
    var focusCardId: Int?

    init() {}

    enum CodingKeys: String, CodingKey {
        case ratings, quizProgress, cardSeen, quizSeen, ratingFilter, view, cardDecks, quizDecks, vlsmWorking,
             speechEnabled, dyslexiaMode
        case schedule, starred, notes, activity, bestStreak, reminderEnabled, reminderMinutes, mockAttempt,
             mockHistory, drillBest, fingerprints, newCardIds, lastChange, focusCardId
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

        schedule = try? c.decodeIfPresent([String: CardSchedule].self, forKey: .schedule)
        starred = (try? c.decodeIfPresent([Int].self, forKey: .starred)) ?? []
        notes = (try? c.decodeIfPresent([String: String].self, forKey: .notes)) ?? [:]
        activity = (try? c.decodeIfPresent([String: Int].self, forKey: .activity)) ?? [:]
        bestStreak = (try? c.decodeIfPresent(Int.self, forKey: .bestStreak)) ?? 0
        reminderEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .reminderEnabled)) ?? false
        reminderMinutes = (try? c.decodeIfPresent(Int.self, forKey: .reminderMinutes)) ?? Reminder.defaultMinutes
        mockAttempt = try? c.decodeIfPresent(MockAttempt.self, forKey: .mockAttempt)
        mockHistory = (try? c.decodeIfPresent([MockRecord].self, forKey: .mockHistory)) ?? []
        drillBest = (try? c.decodeIfPresent(Int.self, forKey: .drillBest)) ?? 0
        fingerprints = try? c.decodeIfPresent(ContentFingerprints.self, forKey: .fingerprints)
        newCardIds = (try? c.decodeIfPresent([Int].self, forKey: .newCardIds)) ?? []
        lastChange = try? c.decodeIfPresent(ContentChange.self, forKey: .lastChange)
        focusCardId = try? c.decodeIfPresent(Int.self, forKey: .focusCardId)
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
