import Foundation
import Testing
@testable import RevisionCore

/// A fixed clock the tests can move.
final class TestClock: @unchecked Sendable {
    var date: Date
    init(_ date: Date) { self.date = date }
    func advance(days: Int) { date = date.addingTimeInterval(TimeInterval(days) * 86_400) }
    func advance(seconds: TimeInterval) { date = date.addingTimeInterval(seconds) }
}

let utc: Calendar = {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "UTC")!
    return c
}()

/// 2026-11-12 09:00 UTC.
let novemberMorning = Date(timeIntervalSince1970: 1_794_474_000)

@MainActor
func makeStore(_ data: RevisionData, storage: StateStorage = MemoryStorage(), clock: TestClock,
               seed: UInt64 = 11) -> RevisionStore {
    let store = RevisionStore(data: data, storage: storage, now: { clock.date }, calendar: utc)
    store.rng = SeededGenerator(state: seed)
    return store
}

// MARK: - Days and the Leitner schedule

@Suite("Days")
struct DayTests {
    @Test func stringsAndArithmetic() throws {
        let day = try #require(Day(string: "2026-11-12"))
        #expect(day.description == "2026-11-12")
        #expect(Day(year: 1970, month: 1, day: 1).dayNumber == 0)
        #expect(Day(dayNumber: day.dayNumber) == day)
        #expect(day.adding(days: 30).description == "2026-12-12")
        #expect(day.adding(days: 50).description == "2027-01-01")
        #expect(Day(string: "2028-02-28")!.adding(days: 1).description == "2028-02-29")
        #expect(Day(string: "2027-02-28")!.adding(days: 1).description == "2027-03-01")
        #expect(day.days(until: day.adding(days: 7)) == 7)
        #expect(Day(string: "nope") == nil)
        #expect(Day(string: "2026-13-01") == nil)
        #expect(Day(novemberMorning, calendar: utc) == day)
    }

    @Test func localDayFollowsTheCalendarTimeZone() {
        // 23:30 UTC on 11 Nov is already 12 Nov in Tokyo.
        let late = Date(timeIntervalSince1970: 1_794_439_800)
        var tokyo = Calendar(identifier: .gregorian)
        tokyo.timeZone = TimeZone(identifier: "Asia/Tokyo")!
        #expect(Day(late, calendar: utc).description == "2026-11-11")
        #expect(Day(late, calendar: tokyo).description == "2026-11-12")
    }

    @Test func codableAsString() throws {
        let schedule = CardSchedule(box: 2, due: Day(string: "2026-11-19")!)
        let json = String(decoding: try JSONEncoder().encode(schedule), as: UTF8.self)
        #expect(json.contains(#""due":"2026-11-19""#))
        #expect(try JSONDecoder().decode(CardSchedule.self, from: Data(json.utf8)) == schedule)
    }
}

@Suite("Leitner scheduler")
struct SchedulerTests {
    let today = Day(string: "2026-11-12")!

    @Test func confidentClimbsAndRevisitResets() {
        var s = Scheduler.next(nil, rating: .known, today: today)
        #expect(s == CardSchedule(box: 1, due: today.adding(days: 3)))
        s = Scheduler.next(s, rating: .known, today: today)
        #expect(s == CardSchedule(box: 2, due: today.adding(days: 7)))
        s = Scheduler.next(s, rating: .known, today: today)
        #expect(s == CardSchedule(box: 3, due: today.adding(days: 14)))
        s = Scheduler.next(s, rating: .known, today: today)
        #expect(s == CardSchedule(box: 4, due: today.adding(days: 30)))
        s = Scheduler.next(s, rating: .known, today: today)
        #expect(s == CardSchedule(box: 4, due: today.adding(days: 30)))
        s = Scheduler.next(s, rating: .review, today: today)
        #expect(s == CardSchedule(box: 0, due: today.adding(days: 1)))
        #expect(Scheduler.next(nil, rating: .review, today: today) == CardSchedule(box: 0, due: today.adding(days: 1)))
    }

    @Test func migration() {
        let migrated = Scheduler.migrate(ratings: [1: .review, 2: .known], today: today)
        #expect(migrated[1] == CardSchedule(box: 0, due: today))
        #expect(migrated[2] == CardSchedule(box: 1, due: today.adding(days: 3)))
    }

    @Test func dueCardsSortedByDueThenDataOrder() {
        let cards = (1...5).map { Card(id: $0, q: "", a: "", topic: "", unit: "U") }
        let schedule: [Int: CardSchedule] = [
            5: CardSchedule(box: 0, due: today.adding(days: -2)),
            2: CardSchedule(box: 0, due: today),
            4: CardSchedule(box: 0, due: today),
            3: CardSchedule(box: 1, due: today.adding(days: 1)),
        ]
        #expect(Scheduler.dueCards(cards, schedule: schedule, on: today).map(\.id) == [5, 2, 4])
        #expect(Scheduler.dueCount(cards, schedule: schedule, on: today) == 3)
        #expect(Scheduler.nextDue(cards, schedule: schedule, after: today) == today.adding(days: 1))
        #expect(Scheduler.dueCount(cards, schedule: schedule, on: today.adding(days: 1)) == 4)
    }
}

// MARK: - Saved data from 1.0

@MainActor
@Suite("Upgrading from 1.0")
struct UpgradeTests {
    let data = try! bundledFiles().parse()

    /// Exactly the shape 1.0 saved (no 1.1 fields).
    static let savedBy10 = """
    {"ratings":{"1":"review","2":"known","156":"known"},
     "quizProgress":{"q1":{"correct":true,"choice":0,"answeredAt":"2026-11-01T10:00:00Z"}},
     "cardSeen":[1,2,156],"quizSeen":["q1"],"ratingFilter":"review",
     "view":{"unit":"J0H934","outcome":"all","topic":"All topics","tab":"quiz","cardMode":"review"},
     "cardDecks":{},"quizDecks":{},"vlsmWorking":{"0":"my working"},"speechEnabled":true,"dyslexiaMode":false}
    """

    @Test func loadsAndMigratesOnce() throws {
        let storage = MemoryStorage()
        storage.set(Data(Self.savedBy10.utf8), forKey: RevisionStore.storageKey)
        let clock = TestClock(novemberMorning)
        let today = Day(novemberMorning, calendar: utc)

        let store = makeStore(data, storage: storage, clock: clock)
        // 1.0 data is all still there.
        #expect(store.rating(for: 1) == .review)
        #expect(store.rating(for: 2) == .known)
        #expect(store.quizResult(for: "q1")?.correct == true)
        #expect(store.cardSeen == [1, 2, 156])
        #expect(store.ratingFilter == .review)
        #expect(store.tab == .quiz)
        #expect(store.cardMode == .review)
        #expect(store.quizMode == .practice)
        #expect(store.speechEnabled)
        #expect(store.vlsmWorking(for: 0) == "my working")
        // New fields take their defaults.
        #expect(store.starred.isEmpty && store.notes.isEmpty && store.activity.isEmpty)
        #expect(store.reminderMinutes == 18 * 60 && !store.reminderEnabled)
        #expect(store.mockAttempt == nil && store.mockHistory.isEmpty)
        // First load of the content is silent.
        #expect(store.newCardIds.isEmpty && store.lastChange == nil)

        // The Leitner migration.
        #expect(store.schedule(for: 1) == CardSchedule(box: 0, due: today))
        #expect(store.schedule(for: 2) == CardSchedule(box: 1, due: today.adding(days: 3)))
        #expect(store.schedule(for: 156) == CardSchedule(box: 1, due: today.adding(days: 3)))
        #expect(store.totalDueCount == 1)

        // It ran once: a later launch keeps the saved schedule rather than migrating again.
        clock.advance(days: 10)
        let reopened = makeStore(data, storage: storage, clock: clock)
        #expect(reopened.schedule(for: 1) == CardSchedule(box: 0, due: today))
        #expect(reopened.schedule(for: 2) == CardSchedule(box: 1, due: today.adding(days: 3)))
        #expect(reopened.totalDueCount == 3)

        // A saved (even empty) schedule means the migration has already run.
        var json = Self.savedBy10
        json.removeLast()
        json += #","schedule":{}}"#
        let migrated = MemoryStorage()
        migrated.set(Data(json.utf8), forKey: RevisionStore.storageKey)
        let again = makeStore(data, storage: migrated, clock: clock)
        #expect(again.rating(for: 1) == .review)
        #expect(again.schedule.isEmpty)
    }

    @Test func badNewFieldsFallBackToDefaults() throws {
        var json = Self.savedBy10
        json.removeLast()
        json += #","starred":"oops","notes":[1],"activity":{"2026-11-12":"x"},"mockHistory":7,"reminderMinutes":99999,"schedule":{"1":{"box":1,"due":"2026-11-15"}}}"#
        let storage = MemoryStorage()
        storage.set(Data(json.utf8), forKey: RevisionStore.storageKey)
        let store = makeStore(data, storage: storage, clock: TestClock(novemberMorning))
        #expect(store.rating(for: 2) == .known)
        #expect(store.starred.isEmpty && store.notes.isEmpty && store.activity.isEmpty && store.mockHistory.isEmpty)
        #expect(store.reminderMinutes == 18 * 60)
        // A valid saved schedule is used as-is (no migration of the other ratings).
        #expect(store.schedule(for: 1) == CardSchedule(box: 1, due: Day(string: "2026-11-15")!))
        #expect(store.schedule(for: 2) == nil)
    }

    @Test func movesStateIntoTheAppGroupOnce() {
        let old = MemoryStorage(), group = MemoryStorage()
        old.set(Data("old".utf8), forKey: "k")
        #expect(StateMigration.copyOnce(key: "k", from: old, to: group))
        #expect(group.data(forKey: "k") == Data("old".utf8))
        group.set(Data("new".utf8), forKey: "k")
        old.set(Data("older".utf8), forKey: "k")
        #expect(!StateMigration.copyOnce(key: "k", from: old, to: group))
        #expect(group.data(forKey: "k") == Data("new".utf8))

        // A fresh install with nothing to copy just sets the flag.
        let empty = MemoryStorage(), group2 = MemoryStorage()
        #expect(!StateMigration.copyOnce(key: "k", from: empty, to: group2))
        empty.set(Data("later".utf8), forKey: "k")
        #expect(!StateMigration.copyOnce(key: "k", from: empty, to: group2))
        #expect(group2.data(forKey: "k") == nil)
    }
}

// MARK: - Due mode, rating and the stat line

@MainActor
@Suite("Due mode")
struct DueModeTests {
    let data = try! bundledFiles().parse()

    @Test func ratingSchedulesAndDueDeckEmpties() {
        let clock = TestClock(novemberMorning)
        let store = makeStore(data, clock: clock)
        let today = store.today
        store.activateCards(.study)
        let a = store.currentCard!.id
        store.reveal(); store.rate(.review)
        let b = store.currentCard!.id
        store.reveal(); store.rate(.known)
        #expect(store.rating(for: a) == .review) // ratings still written as in 1.0
        #expect(store.schedule(for: a) == CardSchedule(box: 0, due: today.adding(days: 1)))
        #expect(store.schedule(for: b) == CardSchedule(box: 1, due: today.adding(days: 3)))

        store.activateCards(.due)
        #expect(store.cardDeck.ids.isEmpty)
        #expect(store.nextDueDay == today.adding(days: 1))

        clock.advance(days: 1)
        #expect(store.unitDueCount == 1)
        #expect(store.totalDueCount == 1)
        store.activateCards(.due)
        #expect(store.cardDeck.ids == [a])

        clock.advance(days: 2)
        store.activateCards(.due)
        #expect(store.cardDeck.ids == [a, b].sorted { store.schedule(for: $0)!.due < store.schedule(for: $1)!.due })
        // Rating removes the card from the Due deck, whatever the rating.
        store.reveal(); store.rate(.review)
        #expect(store.cardDeck.ids.count == 1)
        store.reveal(); store.rate(.known)
        #expect(store.cardDeck.ids.isEmpty)
        #expect(store.currentCard == nil)
    }

    @Test func dueDeckFollowsTheFilterAndDueOrder() {
        let clock = TestClock(novemberMorning)
        let store = makeStore(data, clock: clock)
        // Rate three cards in different units, on different days.
        store.setUnit(Filter.allUnits)
        store.activateCards(.study)
        let first = store.currentCard!
        store.reveal(); store.rate(.review)
        clock.advance(days: 1)
        let second = store.currentCard!
        store.reveal(); store.rate(.review)
        clock.advance(days: 5)
        store.activateCards(.due)
        #expect(store.cardDeck.ids == [first.id, second.id])
        store.setUnit(second.unit)
        #expect(store.cardDeck.ids == (first.unit == second.unit ? [first.id, second.id] : [second.id]))
    }

    @Test func skippingThroughDueStartsFromTheTop() {
        let clock = TestClock(novemberMorning)
        let store = makeStore(data, clock: clock)
        store.activateCards(.study)
        for _ in 0..<3 { store.reveal(); store.rate(.review) }
        clock.advance(days: 1)
        store.activateCards(.due)
        let ids = store.cardDeck.ids
        #expect(ids.count == 3)
        store.move(1); store.move(1)
        #expect(store.isLastCard)
        store.move(1)
        #expect(store.cardDeck.ids == ids && store.cardDeck.index == 0)
    }

    @Test func resetClearsSchedules() {
        let store = makeStore(data, clock: TestClock(novemberMorning))
        store.activateCards(.study)
        store.reveal(); store.rate(.review)
        #expect(!store.schedule.isEmpty)
        store.resetProgress()
        #expect(store.schedule.isEmpty)
    }
}

// MARK: - Activity, streaks and reminders

@Suite("Streaks")
struct StreakTests {
    let today = Day(string: "2026-11-12")!

    func activity(_ offsets: [Int]) -> [Day: Int] {
        Dictionary(uniqueKeysWithValues: offsets.map { (today.adding(days: $0), 1) })
    }

    @Test func streakEndsTodayOrYesterday() {
        #expect(Activity.streak([:], today: today) == 0)
        #expect(Activity.streak(activity([0]), today: today) == 1)
        #expect(Activity.streak(activity([0, -1, -2]), today: today) == 3)
        #expect(Activity.streak(activity([-1, -2]), today: today) == 2) // nothing yet today
        #expect(Activity.streak(activity([-2, -3]), today: today) == 0) // missed yesterday
        #expect(Activity.streak(activity([0, -2, -3]), today: today) == 1)
        #expect(Activity.streak([today: 0, today.adding(days: -1): 2], today: today) == 1)
    }

    @Test func longestAndRecent() {
        #expect(Activity.longestStreak(activity([0, -1, -5, -6, -7, -8])) == 4)
        let recent = Activity.recent([today: 3, today.adding(days: -13): 1, today.adding(days: -14): 9], today: today)
        #expect(recent.count == 14)
        #expect(recent.first!.day == today.adding(days: -13) && recent.first!.count == 1)
        #expect(recent.last!.day == today && recent.last!.count == 3)
        #expect(Activity.streakLabel(0) == nil)
        #expect(Activity.streakLabel(4) == "🔥 4-day streak")
    }

    @Test func reminderText() {
        #expect(Reminder.body(dueCount: 5, streak: 3) == "5 cards are due. Keep your 3-day streak going.")
        #expect(Reminder.body(dueCount: 5, streak: 0) == "5 cards are due today.")
        #expect(Reminder.body(dueCount: 0, streak: 3) == "A few minutes of revision keeps it fresh.")
        #expect(Reminder.body(dueCount: 1, streak: 0) == "1 card is due today.")
    }

    @Test func reminderPlanCoversAWeek() {
        let cards = (1...3).map { Card(id: $0, q: "", a: "", topic: "", unit: "U") }
        let schedule: [Int: CardSchedule] = [
            1: CardSchedule(box: 0, due: today),
            2: CardSchedule(box: 1, due: today.adding(days: 1)),
            3: CardSchedule(box: 2, due: today.adding(days: 3)),
        ]
        let streakDays = activity([0, -1]) // active today and yesterday
        // 09:00: today's 18:00 reminder is still to come.
        let morning = Reminder.plan(now: novemberMorning, calendar: utc, minutes: 18 * 60, cards: cards,
                                    schedule: schedule, activity: streakDays)
        #expect(morning.count == 7)
        #expect(morning.map(\.day) == (0..<7).map { today.adding(days: $0) })
        #expect(morning[0].fireDate == novemberMorning.addingTimeInterval(9 * 3600))
        #expect(morning[0].body == "1 card is due. Keep your 2-day streak going.")
        #expect(morning[1].body == "2 cards are due. Keep your 2-day streak going.")
        #expect(morning[2].body == "2 cards are due today.") // streak lapses unless they revise tomorrow
        #expect(morning[3].body == "3 cards are due today.")
        // 07:00 has passed, so the first reminder is tomorrow's.
        let late = Reminder.plan(now: novemberMorning, calendar: utc, minutes: 7 * 60, cards: cards,
                                 schedule: schedule, activity: [:])
        #expect(late.count == 7)
        #expect(late.first?.day == today.adding(days: 1))
        #expect(Reminder.plan(now: novemberMorning, calendar: utc, minutes: 0, cards: [], schedule: [:], activity: [:])
            .allSatisfy { $0.body == "A few minutes of revision keeps it fresh." })
    }
}

@MainActor
@Suite("Activity in the store")
struct StoreActivityTests {
    let data = try! bundledFiles().parse()

    @Test func ratingsAnswersAndMocksCountAsActivity() {
        let storage = MemoryStorage()
        let clock = TestClock(novemberMorning)
        let store = makeStore(data, storage: storage, clock: clock)
        store.activateCards(.study)
        store.reveal(); store.rate(.known)
        store.activateQuiz()
        store.choose(0)
        store.checkAnswer()
        #expect(store.activity[store.today] == 2)
        #expect(store.currentStreak == 1)

        clock.advance(days: 1)
        #expect(store.currentStreak == 1) // yesterday counts until today ends
        store.startMock(units: ["J0H934"], count: 10, timed: false)
        store.mockChoose(store.currentMockOrder[0])
        store.mockChoose(store.currentMockOrder[1]) // changing an answer isn't another action
        store.submitMock()
        #expect(store.activity[store.today] == 2)
        #expect(store.currentStreak == 2)
        #expect(store.bestStreak == 2)

        clock.advance(days: 3)
        #expect(store.currentStreak == 0)
        let reopened = makeStore(data, storage: storage, clock: clock)
        #expect(reopened.bestStreak == 2)
        #expect(reopened.recentActivity().suffix(5).map(\.count) == [2, 2, 0, 0, 0])
    }

    @Test func reminderSettingsPersist() {
        let storage = MemoryStorage()
        let store = makeStore(data, storage: storage, clock: TestClock(novemberMorning))
        store.reminderEnabled = true
        store.reminderMinutes = 7 * 60 + 30
        let reopened = makeStore(data, storage: storage, clock: TestClock(novemberMorning))
        #expect(reopened.reminderEnabled)
        #expect(reopened.reminderMinutes == 450)
        #expect(reopened.reminderPlan().count == 7)
    }

    @Test func saveCallsTheHook() {
        let store = makeStore(data, clock: TestClock(novemberMorning))
        var calls = 0
        store.onSave = { calls += 1 }
        store.toggleStar(1)
        #expect(calls == 1)
    }
}

// MARK: - Mock exam

@MainActor
@Suite("Mock exam")
struct MockExamTests {
    let data = try! bundledFiles().parse()

    @Test func selectionIsCappedAndFromTheChosenUnits() {
        var rng = SeededGenerator(state: 5)
        let units: Set<String> = ["J0HL34", "J0J734"]
        let available = MockExam.available(data.questions, units: units)
        #expect(available.count == 16)
        let picked = MockExam.select(data.questions, units: units, count: 40, using: &rng)
        #expect(picked.count == 16)
        #expect(Set(picked.map(\.id)).count == 16)
        #expect(picked.allSatisfy { units.contains($0.unit) })
        let ten = MockExam.select(data.questions, units: units, count: 10, using: &rng)
        #expect(ten.count == 10)
    }

    @Test func presetIsFiveFromEachOfFourUnits() {
        var rng = SeededGenerator(state: 9)
        let picked = MockExam.selectPreset(data.questions, using: &rng)
        #expect(picked.count == 20)
        for unit in MockExam.presetUnits {
            #expect(picked.filter { $0.unit == unit }.count == 5)
        }
        let store = makeStore(data, clock: TestClock(novemberMorning))
        store.startPresetMock()
        let attempt = store.mockAttempt!
        #expect(attempt.ids.count == 20)
        #expect(attempt.deadline == novemberMorning.addingTimeInterval(1800))
        #expect(attempt.units == MockExam.presetUnits)
        #expect(attempt.orders.allSatisfy { $0.sorted() == [0, 1, 2, 3] })
    }

    @Test func answeringSubmittingAndScoring() throws {
        let storage = MemoryStorage()
        let clock = TestClock(novemberMorning)
        let store = makeStore(data, storage: storage, clock: clock)
        store.startMock(units: ["J0H934", "J0HA34"], count: 10, timed: true)
        var attempt = try #require(store.mockAttempt)
        #expect(attempt.ids.count == 10)
        #expect(attempt.deadline == novemberMorning.addingTimeInterval(900))
        #expect(attempt.units == ["J0HA34", "J0H934"]) // data order

        // Answer the first 6: right, then wrong, alternating. Change one answer.
        let questions = store.mockQuestions
        for (i, q) in questions.prefix(6).enumerated() {
            store.mockGo(to: i)
            store.mockChoose(i % 2 == 0 ? q.answer : (q.answer + 1) % 4)
        }
        store.mockGo(to: 1)
        store.mockChoose(questions[1].answer)
        attempt = try #require(store.mockAttempt)
        #expect(attempt.answers.count == 6)
        #expect(attempt.unansweredCount == 4)
        // Mock answers don't touch Practice.
        #expect(store.quizProgress.isEmpty && store.quizSeen.isEmpty)

        clock.advance(seconds: 125)
        store.submitMock()
        let result = try #require(store.mockResult)
        #expect(result.total == 10)
        #expect(result.correct == 4)
        #expect(result.percent == 40)
        #expect(result.durationSeconds == 125)
        #expect(result.perUnit.map(\.asked).reduce(0, +) == 10)
        #expect(result.perUnit.map(\.correct).reduce(0, +) == 4)
        #expect(result.perUnit.map(\.unit).allSatisfy { ["J0HA34", "J0H934"].contains($0) })
        #expect(store.mockHistory.first == result)
        // Answers lock once submitted.
        store.mockChoose(0)
        #expect(store.mockAttempt?.answers == attempt.answers)

        // The results and history survive a relaunch.
        let reopened = makeStore(data, storage: storage, clock: clock)
        #expect(reopened.mockResult == result)
        #expect(reopened.mockHistory == [result])
        reopened.closeMock()
        #expect(reopened.mockAttempt == nil)
    }

    @Test func timerRunsOutAndSubmitsAutomatically() throws {
        let storage = MemoryStorage()
        let clock = TestClock(novemberMorning)
        let store = makeStore(data, storage: storage, clock: clock)
        store.startMock(units: ["J0H934"], count: 10, timed: true)
        store.mockChoose(store.currentMockOrder[0])
        clock.advance(seconds: 600)

        // Resumed after a relaunch, the deadline is unchanged and time keeps counting.
        let reopened = makeStore(data, storage: storage, clock: clock)
        let attempt = try #require(reopened.mockAttempt)
        #expect(attempt.remaining(at: clock.date) == 300)
        #expect(!reopened.checkMockTimer())
        clock.advance(seconds: 400)
        #expect(reopened.checkMockTimer())
        let result = try #require(reopened.mockResult)
        #expect(result.durationSeconds == 900) // capped at the deadline
        #expect(reopened.mockHistory.count == 1)
    }

    @Test func historyKeepsTheLatestTwenty() {
        let clock = TestClock(novemberMorning)
        let store = makeStore(data, clock: clock)
        for _ in 0..<23 {
            store.startMock(units: ["J0HH34"], count: 10, timed: false)
            clock.advance(seconds: 60)
            store.submitMock()
            store.closeMock()
        }
        #expect(store.mockHistory.count == 20)
        #expect(store.mockHistory.first!.date > store.mockHistory.last!.date)
    }

    @Test func formatting() {
        #expect(MockExam.clock(0) == "0:00")
        #expect(MockExam.clock(1800) == "30:00")
        #expect(MockExam.clock(3725) == "1:02:05")
        #expect(MockExam.spokenDuration(125) == "2 min 5 s")
        #expect(MockExam.percent(15, 20) == 75)
        #expect(MockExam.percent(0, 0) == 0)
    }

    @Test func attemptDroppedWhenQuestionsVanish() {
        let store = makeStore(data, clock: TestClock(novemberMorning))
        store.startMock(units: ["J0H934"], count: 10, timed: false)
        let ids = Set(store.mockAttempt!.ids)
        let fewer = RevisionData(units: data.units, cards: data.cards, coverage: data.coverage,
                                 questions: data.questions.filter { !ids.contains($0.id) },
                                 vlsmExercises: data.vlsmExercises, vlsmHints: data.vlsmHints)
        store.apply(fewer, origin: .live(Date()))
        #expect(store.mockAttempt == nil)
    }
}

// MARK: - VLSM

@Suite("VLSM solver and generator")
struct VLSMTests {
    let data = try! bundledFiles().parse()

    @Test func solverReproducesEverySiteExercise() throws {
        #expect(data.vlsmExercises.count == 6)
        for exercise in data.vlsmExercises {
            let rows = try #require(VLSMSolver.solve(parent: exercise.parent, requests: exercise.requests))
            #expect(rows == exercise.rows, "\(exercise.title)")
        }
    }

    @Test func solverRejectsWhatDoesNotFit() {
        #expect(VLSMSolver.solve(parent: "192.168.1.0/26", requests: [VLSMRequest(lan: "A", hosts: 70)]) == nil)
        #expect(VLSMSolver.solve(parent: "192.168.1.0/26", requests: [VLSMRequest(lan: "A", hosts: 62)])?.first?.network == "192.168.1.0/26")
        #expect(VLSMSolver.solve(parent: "nonsense", requests: []) == nil)
        #expect(VLSMSolver.blockSize(hosts: 2) == 4)
        #expect(VLSMSolver.blockSize(hosts: 1) == 4)
        #expect(VLSMSolver.blockSize(hosts: 30) == 32)
        #expect(VLSMSolver.blockSize(hosts: 31) == 64)
    }

    @Test func generatedExercisesAlwaysFitTheirParent() throws {
        var rng = SeededGenerator(state: 2026)
        for difficulty in VLSMGenerator.Difficulty.allCases {
            for _ in 0..<300 {
                let exercise = VLSMGenerator.generate(difficulty, using: &rng)
                let (base, prefix) = try #require(IPv4.parseCIDR(exercise.parent))
                // Aligned private network of the right size.
                #expect(base & ~IPv4.maskValue(prefix: prefix) == 0)
                let first = base >> 24, second = base >> 16 & 255
                #expect(first == 192 && second == 168 || first == 172 && (16...31).contains(second) || first == 10)
                switch difficulty {
                case .easy: #expect(prefix == 24 && exercise.requests.count == 3)
                case .medium: #expect(prefix == 24 && (4...5).contains(exercise.requests.count))
                case .hard: #expect([22, 23].contains(prefix) && (5...6).contains(exercise.requests.count))
                }
                let size = Int(IPv4.blockSize(prefix: prefix))
                #expect(exercise.requests.allSatisfy { (2...(size / 2 - 2)).contains($0.hosts) })
                #expect(Set(exercise.requests.map(\.lan)).count == exercise.requests.count)
                // Every row sits inside the parent, in order, without overlapping.
                #expect(exercise.rows.count == exercise.requests.count)
                var next = UInt64(base)
                for row in exercise.rows {
                    let (network, p) = try #require(IPv4.parseCIDR(row.network))
                    #expect(UInt64(network) == next)
                    #expect(row.usableCapacity >= row.hosts)
                    next = UInt64(network) + IPv4.blockSize(prefix: p)
                    #expect(IPv4.parse(row.broadcast).map { UInt64($0) + 1 } == next)
                }
                #expect(next <= UInt64(base) + UInt64(size))
                #expect(VLSMSolver.hints(for: exercise).count == 3)
            }
        }
    }

    @Test func answerChecking() {
        let row = data.vlsmExercises[0].rows[0]
        #expect(VLSMAnswer.isCorrect(" 192.168.25.0 / 26 ", field: .network, row: row))
        #expect(!VLSMAnswer.isCorrect("192.168.25.0", field: .network, row: row))
        #expect(VLSMAnswer.isCorrect("255.255.255.192", field: .mask, row: row))
        #expect(VLSMAnswer.isCorrect("192.168.25.1", field: .first, row: row))
        #expect(VLSMAnswer.isCorrect("192.168.25.62", field: .last, row: row))
        #expect(VLSMAnswer.isCorrect("192.168.25.63 ", field: .broadcast, row: row))
        #expect(!VLSMAnswer.isCorrect("", field: .broadcast, row: row))
    }

    @Test func ipv4Helpers() {
        #expect(IPv4.parse("10.0.0.256") == nil)
        #expect(IPv4.parse("1.2.3") == nil)
        #expect(IPv4.format(IPv4.parse("172.16.5.64")!) == "172.16.5.64")
        #expect(IPv4.mask(prefix: 23) == "255.255.254.0")
        #expect(IPv4.prefix(ofMask: "255.255.255.224") == 27)
        #expect(IPv4.prefix(ofMask: "255.0.255.0") == nil)
    }
}

@Suite("Subnetting drills")
struct DrillTests {
    let address = IPv4.parse("192.168.77.200")!

    @Test func eachKind() {
        let mask = SubnetDrill.make(.prefixToMask, prefix: 26, address: address)
        #expect(mask.prompt == "/26 as a subnet mask?")
        #expect(mask.isCorrect("255.255.255.192"))
        #expect(mask.working.hasPrefix("Block size 64"))

        let prefix = SubnetDrill.make(.maskToPrefix, prefix: 20, address: address)
        #expect(prefix.prompt == "255.255.240.0 as a prefix?")
        #expect(prefix.isCorrect("/20") && prefix.isCorrect(" 20 "))
        #expect(prefix.displayAnswer == "/20")
        #expect(prefix.working.contains("16 + 4 = 20"))

        let hosts = SubnetDrill.make(.usableHosts, prefix: 22, address: address)
        #expect(hosts.answer == "1022")
        #expect(hosts.isCorrect("1,022"))

        let network = SubnetDrill.make(.networkAddress, prefix: 27, address: address)
        #expect(network.prompt == "Network address of 192.168.77.200/27?")
        #expect(network.answer == "192.168.77.192")
        #expect(network.working == "Block size 32 in the fourth octet, so 200 rounds down to 192: 192.168.77.192.")

        let broadcast = SubnetDrill.make(.broadcastAddress, prefix: 21, address: address)
        #expect(broadcast.answer == "192.168.79.255")
        #expect(!broadcast.isCorrect("192.168.79.254"))

        #expect(SubnetDrill.make(.networkAddress, prefix: 16, address: address).answer == "192.168.0.0")
        #expect(SubnetDrill.make(.broadcastAddress, prefix: 30, address: address).answer == "192.168.77.203")
    }

    @Test func randomDrillsAreConsistent() {
        var rng = SeededGenerator(state: 77)
        var kinds = Set<SubnetDrill.Kind>()
        for _ in 0..<500 {
            let drill = SubnetDrill.random(using: &rng)
            kinds.insert(drill.kind)
            #expect(drill.isCorrect(drill.displayAnswer))
            #expect(drill.isCorrect(drill.answer))
            if drill.kind != .maskToPrefix, let tail = drill.prompt.split(separator: "/").last {
                let p = Int(tail.prefix { $0.isNumber })
                #expect(p.map(SubnetDrill.prefixRange.contains) == true)
            } else {
                #expect(SubnetDrill.prefixRange.contains(Int(drill.answer)!))
            }
        }
        #expect(kinds == Set(SubnetDrill.Kind.allCases))
    }

    @Test func roundScoring() {
        var rng = SeededGenerator(state: 1)
        var round = DrillRound(using: &rng)
        #expect(round.questions.count == 10)
        round.next() // can't skip before checking
        #expect(round.index == 0)
        for i in 0..<10 {
            let q = round.current!
            round.check(i < 7 ? q.answer : "wrong")
            round.check(q.answer) // only the first check counts
            round.next()
        }
        #expect(round.isFinished)
        #expect(round.score == 7)
    }

    @MainActor
    @Test func bestScoreSaved() {
        let storage = MemoryStorage()
        let data = try! bundledFiles().parse()
        let store = makeStore(data, storage: storage, clock: TestClock(novemberMorning))
        store.recordDrillScore(6)
        store.recordDrillScore(4)
        #expect(store.drillBest == 6)
        #expect(makeStore(data, storage: storage, clock: TestClock(novemberMorning)).drillBest == 6)
    }
}

// MARK: - Stars and notes

@MainActor
@Suite("Stars and notes")
struct BookmarkTests {
    let data = try! bundledFiles().parse()

    @Test func starredModeAndNotes() {
        let storage = MemoryStorage()
        let store = makeStore(data, storage: storage, clock: TestClock(novemberMorning))
        store.activateCards(.starred)
        #expect(store.cardDeck.ids.isEmpty)
        let ids = store.selectedCards.map(\.id)
        store.toggleStar(ids[3])
        store.toggleStar(ids[1])
        store.toggleStar(ids[5]); store.toggleStar(ids[5])
        store.toggleStar(99_999) // not a card
        #expect(store.starred == [ids[1], ids[3]])
        store.activateCards(.starred)
        #expect(store.cardDeck.ids == [ids[1], ids[3]]) // data order

        store.setNote(String(repeating: "x", count: 2500), for: ids[1])
        #expect(store.note(for: ids[1])?.count == 2000)
        store.setNote("  ", for: ids[1])
        #expect(store.note(for: ids[1]) == nil)
        store.setNote("Remember GDPR art. 33", for: ids[3])

        let reopened = makeStore(data, storage: storage, clock: TestClock(novemberMorning))
        #expect(reopened.starred == [ids[1], ids[3]])
        #expect(reopened.note(for: ids[3]) == "Remember GDPR art. 33")

        reopened.resetProgress()
        #expect(reopened.starred.count == 2 && reopened.notes.count == 1)
        reopened.resetProgress(includingStarsAndNotes: true)
        #expect(reopened.starred.isEmpty && reopened.notes.isEmpty)
    }
}

// MARK: - New-content alerts

@MainActor
@Suite("New content")
struct ContentChangeTests {
    let data = try! bundledFiles().parse()

    func changed(_ data: RevisionData) -> RevisionData {
        var cards = data.cards
        let old = cards[2]
        cards[2] = Card(id: old.id, q: old.q + " (updated)", a: old.a, topic: old.topic, unit: old.unit,
                        outcomes: old.outcomes, hint: old.hint, references: old.references)
        cards.append(Card(id: 5000, q: "New?", a: "Yes", topic: "New topic", unit: "J0H934"))
        var questions = data.questions
        let q = questions[0]
        questions[0] = Question(id: q.id, cardId: q.cardId, unit: q.unit, topic: q.topic, q: q.q, options: q.options,
                                answer: q.answer, explanation: q.explanation + " More.", outcomes: q.outcomes)
        return RevisionData(units: data.units, cards: cards, coverage: data.coverage, questions: questions,
                            vlsmExercises: data.vlsmExercises, vlsmHints: data.vlsmHints)
    }

    @Test func fingerprintsAreStable() {
        #expect(fnv1a64("") == "cbf29ce484222325")
        #expect(fnv1a64("a") == "af63dc4c8601ec8c")
        #expect(ContentFingerprints(data) == ContentFingerprints(try! bundledFiles().parse()))
        let diff = ContentDiff.compare(old: ContentFingerprints(data), new: ContentFingerprints(changed(data)))
        #expect(diff.added == [5000])
        #expect(diff.updated == [data.cards[2].id])
        #expect(diff.questionsChanged == 1)
    }

    @Test func firstLoadIsSilentThenChangesAreFlagged() {
        let storage = MemoryStorage()
        let clock = TestClock(novemberMorning)
        let store = makeStore(data, storage: storage, clock: clock)
        #expect(store.newCards.isEmpty)
        store.apply(data, origin: .live(clock.date))
        #expect(store.newCards.isEmpty && store.lastChange == nil)

        let updated = changed(data)
        store.apply(updated, origin: .live(clock.date))
        #expect(Set(store.newCards.map(\.id)) == [data.cards[2].id, 5000])
        #expect(store.newCards.map(\.id) == [data.cards[2].id, 5000]) // data order
        #expect(store.lastChange == ContentChange(date: clock.date, added: 1, updated: 1, questionsChanged: 1))

        // The New mode ignores the filter and shows them in data order; viewing one removes it.
        store.setUnit("FR2435")
        store.studyNewCards()
        #expect(store.cardMode == .new)
        #expect(store.cardDeck.ids == [data.cards[2].id, 5000])
        store.reveal()
        #expect(store.newCards.map(\.id) == [5000])

        // Loading the same data again changes nothing; it's saved across launches.
        store.apply(updated, origin: .live(clock.date))
        let reopened = RevisionStore(data: updated, storage: storage, now: { clock.date }, calendar: utc)
        #expect(reopened.newCards.map(\.id) == [5000])
        #expect(reopened.lastChange?.added == 1)

        // Cards that disappear drop out of the banner.
        let without = RevisionData(units: data.units, cards: updated.cards.filter { $0.id != 5000 },
                                   coverage: data.coverage, questions: data.questions,
                                   vlsmExercises: data.vlsmExercises, vlsmHints: data.vlsmHints)
        reopened.apply(without, origin: .live(clock.date))
        #expect(!reopened.newCards.contains { $0.id == 5000 })
        reopened.dismissNewCards()
        #expect(reopened.newCards.isEmpty && reopened.newCardIds.isEmpty)
    }
}

// MARK: - Widget and links

@MainActor
@Suite("Widget and deep links")
struct WidgetTests {
    let data = try! bundledFiles().parse()

    @Test func cardOfTheDayIsDeterministicAndPrefersDueCards() {
        let today = Day(string: "2026-11-12")!
        let sorted = data.cards.sorted { $0.id < $1.id }
        let pick = WidgetSnapshot.cardOfDay(cards: data.cards, schedule: [:], day: today)
        #expect(pick == sorted[today.dayNumber % sorted.count])
        #expect(WidgetSnapshot.cardOfDay(cards: data.cards.reversed(), schedule: [:], day: today) == pick)
        #expect(WidgetSnapshot.cardOfDay(cards: data.cards, schedule: [:], day: today.adding(days: 1)) != pick)

        let schedule = [sorted[7].id: CardSchedule(box: 0, due: today)]
        #expect(WidgetSnapshot.cardOfDay(cards: data.cards, schedule: schedule, day: today) == sorted[7])
        #expect(WidgetSnapshot.cardOfDay(cards: [], schedule: [:], day: today) == nil)
    }

    @Test func snapshotWrittenOnSave() throws {
        let storage = MemoryStorage()
        let clock = TestClock(novemberMorning)
        let store = makeStore(data, storage: storage, clock: clock)
        // Written at launch (data load) too.
        #expect(storage.data(forKey: WidgetSnapshot.storageKey) != nil)
        store.activateCards(.study)
        store.reveal(); store.rate(.review)
        let snapshot = try #require(storage.data(forKey: WidgetSnapshot.storageKey).flatMap(WidgetSnapshot.decode))
        #expect(snapshot.dueCount == 0)
        #expect(snapshot.streak == 1)
        #expect(snapshot.updatedAt == clock.date)
        #expect(snapshot.days.count == 8)
        // Tomorrow the Revisit card is due and the streak still counts.
        let tomorrow = snapshot.entry(for: store.today.adding(days: 1))
        #expect(tomorrow.dueCount == 1 && tomorrow.streak == 1)
        #expect(snapshot.entry(for: store.today.adding(days: 2)).streak == 0)
        #expect(snapshot.entry(for: store.today.adding(days: 30)).streak == 0)
    }

    @Test func deepLinks() {
        #expect(DeepLink.target(of: URL(string: "hncrevision://card/42")!) == .card(42))
        #expect(DeepLink.target(of: DeepLink.cardURL(7)) == .card(7))
        #expect(DeepLink.target(of: URL(string: "hncrevision://due")!) == .due)
        #expect(DeepLink.target(of: URL(string: "hncrevision://card/x")!) == nil)
        #expect(DeepLink.target(of: URL(string: "https://card/42")!) == nil)
    }

    @Test func openCardShowsAOneCardDeckThenBackToStudy() {
        let storage = MemoryStorage()
        let store = makeStore(data, storage: storage, clock: TestClock(novemberMorning))
        store.tab = .browse
        #expect(store.open(URL(string: "hncrevision://card/12")!))
        #expect(store.tab == .study)
        #expect(store.cardMode == .single)
        #expect(store.cardDeck.ids == [12])
        #expect(store.isLastCard)
        #expect(!store.openCard(99_999))

        // Survives a relaunch.
        let reopened = makeStore(data, storage: storage, clock: TestClock(novemberMorning))
        #expect(reopened.cardMode == .single)
        reopened.activateCards(reopened.cardMode)
        #expect(reopened.cardDeck.ids == [12])

        reopened.reveal(); reopened.rate(.known)
        #expect(reopened.cardMode == .study)
        #expect(reopened.schedule(for: 12)?.box == 1)

        reopened.open(DeepLink.dueURL)
        #expect(reopened.cardMode == .due)
    }
}
