import Foundation

/// A local calendar day, saved as "yyyy-MM-dd".
public struct Day: Hashable, Comparable, Codable, Sendable, CustomStringConvertible {
    public let year: Int
    public let month: Int
    public let day: Int

    public init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// The day `date` falls on in `calendar` (the device's local calendar and time zone in the app).
    public init(_ date: Date, calendar: Calendar) {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        self.init(year: c.year ?? 1970, month: c.month ?? 1, day: c.day ?? 1)
    }

    /// Parses "yyyy-MM-dd".
    public init?(string: String) {
        let parts = string.split(separator: "-")
        guard parts.count == 3, let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), (1...31).contains(d) else { return nil }
        self.init(year: y, month: m, day: d)
    }

    public var description: String {
        String(format: "%04d-%02d-%02d", year, month, day)
    }

    /// Days since 1970-01-01 (proleptic Gregorian), for date arithmetic without a calendar.
    public var dayNumber: Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = (month + 9) % 12
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    public init(dayNumber z0: Int) {
        let z = z0 + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        self.init(year: yoe + era * 400 + (m <= 2 ? 1 : 0), month: m, day: d)
    }

    public func adding(days: Int) -> Day { Day(dayNumber: dayNumber + days) }

    public func days(until other: Day) -> Int { other.dayNumber - dayNumber }

    /// The start of this day in `calendar`.
    public func startDate(in calendar: Calendar) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day)) ?? Date(timeIntervalSince1970: 0)
    }

    public static func < (lhs: Day, rhs: Day) -> Bool { lhs.dayNumber < rhs.dayNumber }

    public init(from decoder: Decoder) throws {
        let text = try decoder.singleValueContainer().decode(String.self)
        guard let day = Day(string: text) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Bad day \(text)"))
        }
        self = day
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(description)
    }
}

/// A card's Leitner box and the day it's next due.
public struct CardSchedule: Codable, Equatable, Sendable {
    public var box: Int
    public var due: Day

    public init(box: Int, due: Day) {
        self.box = box
        self.due = due
    }
}

/// Leitner-box spaced repetition.
public enum Scheduler {
    public static let maxBox = 4
    /// Days until a card in each box is due again.
    public static let intervals = [1, 3, 7, 14, 30]

    public static func interval(box: Int) -> Int { intervals[max(0, min(box, maxBox))] }

    /// Confident moves a card up a box (unscheduled cards start from box 0); Revisit sends it back to box 0.
    public static func next(_ current: CardSchedule?, rating: Rating, today: Day) -> CardSchedule {
        let box = rating == .known ? min((current?.box ?? 0) + 1, maxBox) : 0
        return CardSchedule(box: box, due: today.adding(days: interval(box: box)))
    }

    /// The first 1.1 launch: Revisit cards are due today in box 0; Confident cards go to box 1, due in 3 days.
    public static func migrate(ratings: [Int: Rating], today: Day) -> [Int: CardSchedule] {
        ratings.mapValues { rating in
            rating == .known ? CardSchedule(box: 1, due: today.adding(days: interval(box: 1)))
                             : CardSchedule(box: 0, due: today)
        }
    }

    public static func isDue(_ schedule: CardSchedule?, on day: Day) -> Bool {
        guard let schedule else { return false }
        return schedule.due <= day
    }

    /// Cards due on or before `day`, sorted by due date then data order.
    public static func dueCards(_ cards: [Card], schedule: [Int: CardSchedule], on day: Day) -> [Card] {
        cards.enumerated()
            .compactMap { offset, card -> (Day, Int, Card)? in
                guard let s = schedule[card.id], s.due <= day else { return nil }
                return (s.due, offset, card)
            }
            .sorted { ($0.0, $0.1) < ($1.0, $1.1) }
            .map(\.2)
    }

    public static func dueCount(_ cards: [Card], schedule: [Int: CardSchedule], on day: Day) -> Int {
        cards.reduce(0) { $0 + (isDue(schedule[$1.id], on: day) ? 1 : 0) }
    }

    /// The earliest due date after `day` among `cards`, if any.
    public static func nextDue(_ cards: [Card], schedule: [Int: CardSchedule], after day: Day) -> Day? {
        cards.compactMap { schedule[$0.id]?.due }.filter { $0 > day }.min()
    }
}
