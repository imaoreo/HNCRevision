import Foundation

/// A mock exam in progress (or just submitted, while its results are on screen).
public struct MockAttempt: Codable, Equatable, Sendable {
    public var ids: [String]
    /// For each question, the display order of its options (indices into `options`).
    public var orders: [[Int]]
    /// Question id → chosen option (an index into `options`). Answers can change until submitted.
    public var answers: [String: Int]
    public var index: Int
    public var units: [String]
    public var startedAt: Date
    /// When the timer runs out; nil when untimed.
    public var deadline: Date?
    public var submittedAt: Date?

    public init(ids: [String], orders: [[Int]], answers: [String: Int] = [:], index: Int = 0, units: [String],
                startedAt: Date, deadline: Date?, submittedAt: Date? = nil) {
        self.ids = ids
        self.orders = orders
        self.answers = answers
        self.index = index
        self.units = units
        self.startedAt = startedAt
        self.deadline = deadline
        self.submittedAt = submittedAt
    }

    public var isSubmitted: Bool { submittedAt != nil }
    public var unansweredCount: Int { ids.filter { answers[$0] == nil }.count }

    /// Seconds left on the timer at `now` (nil when untimed).
    public func remaining(at now: Date) -> TimeInterval? {
        deadline.map { max(0, $0.timeIntervalSince(now)) }
    }

    public var durationSeconds: Int {
        guard let submittedAt else { return 0 }
        var end = submittedAt
        if let deadline, deadline < end { end = deadline }
        return max(0, Int(end.timeIntervalSince(startedAt).rounded()))
    }
}

public struct MockUnitScore: Codable, Equatable, Sendable {
    public var unit: String
    public var correct: Int
    public var asked: Int

    public init(unit: String, correct: Int, asked: Int) {
        self.unit = unit
        self.correct = correct
        self.asked = asked
    }

    public var percent: Int { MockExam.percent(correct, asked) }
}

/// A finished attempt, kept in the history.
public struct MockRecord: Codable, Equatable, Sendable {
    public var date: Date
    public var units: [String]
    public var total: Int
    public var correct: Int
    public var durationSeconds: Int
    public var perUnit: [MockUnitScore]

    public init(date: Date, units: [String], total: Int, correct: Int, durationSeconds: Int, perUnit: [MockUnitScore]) {
        self.date = date
        self.units = units
        self.total = total
        self.correct = correct
        self.durationSeconds = durationSeconds
        self.perUnit = perUnit
    }

    public var percent: Int { MockExam.percent(correct, total) }
}

public enum MockExam {
    public static let questionCounts = [10, 20, 40]
    public static let secondsPerQuestion = 90
    public static let historyLimit = 20
    /// "Graded Unit Section 1 style": 5 questions from each of these units, 30 minutes.
    public static let presetUnits = ["J0H934", "J0HL34", "J0HJ34", "J0J734"]
    public static let presetPerUnit = 5
    public static let presetSeconds = 30 * 60

    public static func percent(_ correct: Int, _ total: Int) -> Int {
        total > 0 ? Int((Double(correct) * 100 / Double(total)).rounded()) : 0
    }

    public static func available(_ questions: [Question], units: Set<String>) -> [Question] {
        questions.filter { units.contains($0.unit) }
    }

    /// A random sample of up to `count` questions from the chosen units.
    public static func select(_ questions: [Question], units: Set<String>, count: Int,
                              using rng: inout some RandomNumberGenerator) -> [Question] {
        Array(available(questions, units: units).shuffled(using: &rng).prefix(max(0, count)))
    }

    /// The preset: up to 5 random questions from each preset unit, in unit order.
    public static func selectPreset(_ questions: [Question], using rng: inout some RandomNumberGenerator) -> [Question] {
        presetUnits.flatMap { unit in
            Array(questions.filter { $0.unit == unit }.shuffled(using: &rng).prefix(presetPerUnit))
        }
    }

    public static func makeAttempt(_ questions: [Question], units: [String], now: Date, timeLimit: Int?,
                                   using rng: inout some RandomNumberGenerator) -> MockAttempt {
        MockAttempt(
            ids: questions.map(\.id),
            orders: questions.map { Array($0.options.indices).shuffled(using: &rng) },
            units: units,
            startedAt: now,
            deadline: timeLimit.map { now.addingTimeInterval(TimeInterval($0)) }
        )
    }

    /// Scores a submitted attempt. Units appear in the order of the attempt's units, then any others.
    public static func score(_ attempt: MockAttempt, questions byId: [String: Question]) -> MockRecord {
        var asked: [String: Int] = [:]
        var right: [String: Int] = [:]
        var order: [String] = attempt.units
        var correct = 0
        let questions = attempt.ids.compactMap { byId[$0] }
        for q in questions {
            if !order.contains(q.unit) { order.append(q.unit) }
            asked[q.unit, default: 0] += 1
            if attempt.answers[q.id] == q.answer {
                right[q.unit, default: 0] += 1
                correct += 1
            }
        }
        let perUnit = order.compactMap { unit -> MockUnitScore? in
            guard let n = asked[unit] else { return nil }
            return MockUnitScore(unit: unit, correct: right[unit] ?? 0, asked: n)
        }
        return MockRecord(date: attempt.submittedAt ?? attempt.startedAt, units: attempt.units, total: questions.count,
                          correct: correct, durationSeconds: attempt.durationSeconds, perUnit: perUnit)
    }

    /// "12:05" or "1:02:03".
    public static func clock(_ seconds: Int) -> String {
        let s = max(0, seconds)
        if s >= 3600 { return String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) }
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    /// "12 min 5 s" for VoiceOver and the results.
    public static func spokenDuration(_ seconds: Int) -> String {
        let s = max(0, seconds)
        let m = s / 60, r = s % 60
        if m == 0 { return "\(r) s" }
        return r == 0 ? "\(m) min" : "\(m) min \(r) s"
    }
}
