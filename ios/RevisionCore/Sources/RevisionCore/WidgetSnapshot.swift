import Foundation

/// What the home screen widget shows, written by the app into the shared App Group.
public struct WidgetSnapshot: Codable, Equatable, Sendable {
    public static let storageKey = "hnc-revision-widget-v1"
    public static let appGroup = "group.net.estopia.hncrevision"

    public struct CardOfDay: Codable, Equatable, Sendable {
        public var id: Int
        public var unit: String
        public var topic: String
        public var q: String

        public init(id: Int, unit: String, topic: String, q: String) {
            self.id = id
            self.unit = unit
            self.topic = topic
            self.q = q
        }
    }

    /// One day's numbers, so the widget can roll over at midnight without the app running.
    public struct DayEntry: Codable, Equatable, Sendable {
        public var day: Day
        public var dueCount: Int
        public var streak: Int
        public var cardOfDay: CardOfDay?
    }

    public var dueCount: Int
    public var streak: Int
    public var cardOfDay: CardOfDay?
    public var updatedAt: Date
    /// Today and the next few days (today first).
    public var days: [DayEntry]

    public init(dueCount: Int, streak: Int, cardOfDay: CardOfDay?, updatedAt: Date, days: [DayEntry] = []) {
        self.dueCount = dueCount
        self.streak = streak
        self.cardOfDay = cardOfDay
        self.updatedAt = updatedAt
        self.days = days
    }

    /// A deterministic pick for `day`: day number mod count over cards sorted by id, from the due cards when any are due.
    public static func cardOfDay(cards: [Card], schedule: [Int: CardSchedule], day: Day) -> Card? {
        let sorted = cards.sorted { $0.id < $1.id }
        let due = sorted.filter { Scheduler.isDue(schedule[$0.id], on: day) }
        let pool = due.isEmpty ? sorted : due
        guard !pool.isEmpty else { return nil }
        let n = day.dayNumber % pool.count
        return pool[n < 0 ? n + pool.count : n]
    }

    public static func make(cards: [Card], schedule: [Int: CardSchedule], activity: [Day: Int], today: Day,
                            now: Date, days count: Int = 8) -> WidgetSnapshot {
        let entries = (0..<max(1, count)).map { offset -> DayEntry in
            let day = today.adding(days: offset)
            let card = cardOfDay(cards: cards, schedule: schedule, day: day)
            return DayEntry(day: day,
                            dueCount: Scheduler.dueCount(cards, schedule: schedule, on: day),
                            streak: Activity.streak(activity, today: day),
                            cardOfDay: card.map { CardOfDay(id: $0.id, unit: $0.unit, topic: $0.topic, q: $0.q) })
        }
        let first = entries[0]
        return WidgetSnapshot(dueCount: first.dueCount, streak: first.streak, cardOfDay: first.cardOfDay,
                              updatedAt: now, days: entries)
    }

    /// The entry for `day`: the saved one if the snapshot covers it, else the last known numbers with no streak.
    public func entry(for day: Day) -> DayEntry {
        if let match = days.first(where: { $0.day == day }) { return match }
        if let last = days.last, last.day < day {
            return DayEntry(day: day, dueCount: last.dueCount, streak: 0, cardOfDay: last.cardOfDay)
        }
        return DayEntry(day: day, dueCount: dueCount, streak: streak, cardOfDay: cardOfDay)
    }

    public static func decode(_ data: Data) -> WidgetSnapshot? {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return try? d.decode(WidgetSnapshot.self, from: data)
    }

    public func encoded() -> Data? {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return try? e.encode(self)
    }
}

/// Moving the saved state into the App Group (1.1).
public enum StateMigration {
    public static let copiedFlag = "hnc-revision-moved-to-group"

    /// Copies `key` from the old storage to the new one once, unless the new one already has a value.
    /// Returns true when it copied something.
    @discardableResult
    public static func copyOnce(key: String, from old: StateStorage, to new: StateStorage) -> Bool {
        guard new.data(forKey: copiedFlag) == nil else { return false }
        var copied = false
        if new.data(forKey: key) == nil, let value = old.data(forKey: key) {
            new.set(value, forKey: key)
            copied = true
        }
        new.set(Data("1".utf8), forKey: copiedFlag)
        return copied
    }
}
