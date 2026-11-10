import Foundation

/// Revision activity per local day: rating a card, checking a Practice answer, answering or submitting a mock.
public enum Activity {
    /// Consecutive active days ending `today`, or ending yesterday when there's no activity yet today.
    public static func streak(_ activity: [Day: Int], today: Day) -> Int {
        func active(_ day: Day) -> Bool { (activity[day] ?? 0) > 0 }
        var day = active(today) ? today : today.adding(days: -1)
        var count = 0
        while active(day) {
            count += 1
            day = day.adding(days: -1)
        }
        return count
    }

    /// The longest run of consecutive active days anywhere in `activity`.
    public static func longestStreak(_ activity: [Day: Int]) -> Int {
        let days = Set(activity.filter { $0.value > 0 }.keys.map(\.dayNumber))
        var best = 0
        for n in days where !days.contains(n - 1) {
            var length = 1
            while days.contains(n + length) { length += 1 }
            best = max(best, length)
        }
        return best
    }

    /// Counts for the `count` days ending `today`, oldest first.
    public static func recent(_ activity: [Day: Int], today: Day, count: Int = 14) -> [(day: Day, count: Int)] {
        (0..<count).reversed().map { offset in
            let day = today.adding(days: -offset)
            return (day, activity[day] ?? 0)
        }
    }

    /// "🔥 3-day streak", or nil when there's no streak.
    public static func streakLabel(_ streak: Int) -> String? {
        streak >= 1 ? "🔥 \(streak)-day streak" : nil
    }
}

/// The daily reminder's text.
public enum Reminder {
    public static let title = "HNC Revision"
    public static let defaultMinutes = 18 * 60

    public static func body(dueCount: Int, streak: Int) -> String {
        guard dueCount > 0 else { return "A few minutes of revision keeps it fresh." }
        let cards = dueCount == 1 ? "1 card is due" : "\(dueCount) cards are due"
        if streak >= 1 { return "\(cards). Keep your \(streak)-day streak going." }
        return "\(cards) today."
    }

    public struct Planned: Equatable, Sendable {
        public let day: Day
        public let fireDate: Date
        public let body: String
    }

    /// Notifications for the next `days` days at `minutes` past midnight, skipping a time already passed today.
    /// Each day's text uses the cards due by then and the streak as it would stand that morning.
    public static func plan(days: Int = 7, now: Date, calendar: Calendar, minutes: Int,
                            cards: [Card], schedule: [Int: CardSchedule], activity: [Day: Int]) -> [Planned] {
        let today = Day(now, calendar: calendar)
        var out: [Planned] = []
        for offset in 0..<(days + 1) where out.count < days {
            let day = today.adding(days: offset)
            guard let fire = calendar.date(byAdding: .minute, value: minutes, to: day.startDate(in: calendar)),
                  fire > now else { continue }
            let due = Scheduler.dueCount(cards, schedule: schedule, on: day)
            let streak = Activity.streak(activity, today: day)
            out.append(Planned(day: day, fireDate: fire, body: body(dueCount: due, streak: streak)))
        }
        return out
    }
}
