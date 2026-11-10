import Foundation
import Observation

/// The app's state: data, filters, flashcard and quiz decks, progress and settings.
/// Mirrors the revision site's behaviour and saves everything on the device.
@MainActor
@Observable
public final class RevisionStore {
    public nonisolated static let storageKey = "hnc-revision-state-v1"
    public static let staleInterval: TimeInterval = 30 * 60

    // MARK: Data

    public private(set) var data: RevisionData
    public private(set) var origin: DataOrigin
    public private(set) var isRefreshing = false
    public private(set) var lastCheck: Date?
    /// The outcome of the last refresh this session (nil before the first one finishes).
    public private(set) var lastRefreshSucceeded: Bool?

    // MARK: Filters and navigation

    public private(set) var filter: Filter
    public var tab: AppTab = .study { didSet { if tab != oldValue { save() } } }
    public private(set) var cardMode: CardMode = .study
    public private(set) var ratingFilter: RatingFilter = .both
    public var quizMode: QuizMode = .practice {
        didSet {
            guard quizMode != oldValue else { return }
            if quizMode == .mock && active == .quiz { active = .none }
            save()
        }
    }
    /// The card shown on its own (from the widget or a `hncrevision://card/<id>` link).
    public private(set) var focusCardId: Int?

    // MARK: Progress

    public private(set) var ratings: [Int: Rating] = [:]
    public private(set) var quizProgress: [String: QuizResult] = [:]
    public private(set) var cardSeen: Set<Int> = []
    public private(set) var quizSeen: Set<String> = []

    public private(set) var cardDeck = CardDeck()
    public private(set) var quizDeck = QuizDeck()
    private var savedCardDecks: [String: CardDeck] = [:]
    private var savedQuizDecks: [String: QuizDeck] = [:]
    private var vlsmWorking: [String: String] = [:]

    // MARK: 1.1: spaced repetition, bookmarks, activity, mock exams, drills, content changes

    public private(set) var schedule: [Int: CardSchedule] = [:]
    public private(set) var starred: Set<Int> = []
    public private(set) var notes: [Int: String] = [:]
    public private(set) var activity: [Day: Int] = [:]
    public private(set) var bestStreak = 0
    public private(set) var mockAttempt: MockAttempt?
    public private(set) var mockHistory: [MockRecord] = []
    public private(set) var drillBest = 0
    /// Cards new or changed on the website since the last load (may include ids that no longer exist).
    public private(set) var newCardIds: Set<Int> = []
    public private(set) var lastChange: ContentChange?
    private var fingerprints: ContentFingerprints?

    public static let noteLimit = 2000

    // MARK: Settings

    public var speechEnabled = false { didSet { if speechEnabled != oldValue { save() } } }
    public var dyslexiaMode = false { didSet { if dyslexiaMode != oldValue { save() } } }
    public var reminderEnabled = false { didSet { if reminderEnabled != oldValue { save() } } }
    /// Minutes after local midnight (default 18:00).
    public var reminderMinutes = Reminder.defaultMinutes { didSet { if reminderMinutes != oldValue { save() } } }

    private enum ActiveDeck { case none, cards, quiz }
    @ObservationIgnored private var active: ActiveDeck = .none
    @ObservationIgnored private let storage: StateStorage
    @ObservationIgnored private let repository: DataRepository?
    @ObservationIgnored public var rng: any RandomNumberGenerator = SystemRandomNumberGenerator()
    @ObservationIgnored public var now: () -> Date
    /// The device's local calendar (days for schedules, streaks and reminders).
    @ObservationIgnored public var calendar: Calendar
    /// Called after every save (the app refreshes the widget and reminders).
    @ObservationIgnored public var onSave: (@MainActor () -> Void)?
    @ObservationIgnored private var loading = true

    // MARK: - Setup

    /// Loads the cached or bundled data immediately; call `refresh()` afterwards to check the website.
    public convenience init(repository: DataRepository, storage: StateStorage = UserDefaults.standard) {
        let initial = repository.loadInitial()
        self.init(data: initial.data, origin: initial.origin, storage: storage, repository: repository)
    }

    public init(data: RevisionData, origin: DataOrigin = .bundled, storage: StateStorage, repository: DataRepository? = nil,
                now: @escaping () -> Date = Date.init, calendar: Calendar = .current) {
        self.data = data
        self.origin = origin
        self.storage = storage
        self.repository = repository
        self.now = now
        self.calendar = calendar
        self.filter = Filter(unit: data.unit(Filter.defaultUnit) != nil || data.units.isEmpty ? Filter.defaultUnit : Filter.allUnits)

        var state = PersistedState()
        if let raw = storage.data(forKey: Self.storageKey),
           let decoded = try? Self.decoder.decode(PersistedState.self, from: raw) {
            state = decoded
        }
        ratings = Dictionary(state.ratings.compactMap { key, value in Int(key).map { ($0, value) } },
                             uniquingKeysWith: { a, _ in a })
        quizProgress = state.quizProgress
        cardSeen = Set(state.cardSeen ?? Array(ratings.keys))
        quizSeen = Set(state.quizSeen ?? Array(quizProgress.keys))
        ratingFilter = state.ratingFilter
        savedCardDecks = state.cardDecks
        savedQuizDecks = state.quizDecks
        vlsmWorking = state.vlsmWorking
        speechEnabled = state.speechEnabled
        dyslexiaMode = state.dyslexiaMode

        var needsSave = false
        if let saved = state.schedule {
            schedule = Dictionary(saved.compactMap { key, value in Int(key).map { ($0, value) } },
                                  uniquingKeysWith: { a, _ in a })
        } else {
            // First 1.1 launch: put the 1.0 ratings into Leitner boxes.
            schedule = Scheduler.migrate(ratings: ratings, today: Day(now(), calendar: calendar))
            needsSave = true
        }
        starred = Set(state.starred)
        notes = Dictionary(state.notes.compactMap { key, value in Int(key).map { ($0, value) } },
                           uniquingKeysWith: { a, _ in a })
        activity = Dictionary(state.activity.compactMap { key, value in Day(string: key).map { ($0, value) } },
                              uniquingKeysWith: +)
        bestStreak = max(state.bestStreak, Activity.longestStreak(activity))
        reminderEnabled = state.reminderEnabled
        reminderMinutes = (0..<(24 * 60)).contains(state.reminderMinutes) ? state.reminderMinutes : Reminder.defaultMinutes
        mockAttempt = state.mockAttempt
        mockHistory = state.mockHistory
        drillBest = state.drillBest
        fingerprints = state.fingerprints
        newCardIds = Set(state.newCardIds)
        lastChange = state.lastChange
        focusCardId = state.focusCardId

        if let view = state.view {
            tab = view.tab
            cardMode = view.cardMode
            quizMode = view.quizMode
            if view.unit == Filter.allUnits || data.unit(view.unit) != nil {
                filter = Filter(unit: view.unit, outcome: view.outcome, topic: view.topic)
            }
        }
        if cardMode == .single && focusCardId.flatMap({ data.cardsById[$0] }) == nil { cardMode = .study }
        validateFilter()
        validateMockAttempt()
        if processContentChange() { needsSave = true }
        loading = false
        if needsSave { save() } else { writeWidgetSnapshot() }
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    private func save() {
        guard !loading else { return }
        var state = PersistedState()
        state.ratings = Dictionary(uniqueKeysWithValues: ratings.map { (String($0.key), $0.value) })
        state.quizProgress = quizProgress
        state.cardSeen = cardSeen.sorted()
        state.quizSeen = quizSeen.sorted()
        state.ratingFilter = ratingFilter
        state.view = SavedView(unit: filter.unit, outcome: filter.outcome, topic: filter.topic, tab: tab, cardMode: cardMode)
        state.cardDecks = savedCardDecks
        state.quizDecks = savedQuizDecks
        state.vlsmWorking = vlsmWorking
        state.speechEnabled = speechEnabled
        state.dyslexiaMode = dyslexiaMode
        state.view?.quizMode = quizMode
        state.schedule = Dictionary(uniqueKeysWithValues: schedule.map { (String($0.key), $0.value) })
        state.starred = starred.sorted()
        state.notes = Dictionary(uniqueKeysWithValues: notes.map { (String($0.key), $0.value) })
        state.activity = Dictionary(uniqueKeysWithValues: activity.map { ($0.key.description, $0.value) })
        state.bestStreak = bestStreak
        state.reminderEnabled = reminderEnabled
        state.reminderMinutes = reminderMinutes
        state.mockAttempt = mockAttempt
        state.mockHistory = mockHistory
        state.drillBest = drillBest
        state.fingerprints = fingerprints
        state.newCardIds = newCardIds.sorted()
        state.lastChange = lastChange
        state.focusCardId = focusCardId
        if let raw = try? Self.encoder.encode(state) {
            storage.set(raw, forKey: Self.storageKey)
        }
        writeWidgetSnapshot()
        onSave?()
    }

    private func writeWidgetSnapshot() {
        if let raw = widgetSnapshot().encoded() {
            storage.set(raw, forKey: WidgetSnapshot.storageKey)
        }
    }

    /// What the home screen widget shows: due count, streak and card of the day, for today and the next week.
    public func widgetSnapshot() -> WidgetSnapshot {
        WidgetSnapshot.make(cards: data.cards, schedule: schedule, activity: activity, today: today, now: now())
    }

    /// Keeps the filter valid for the current data (after launch or a refresh).
    private func validateFilter() {
        var f = filter
        if f.unit != Filter.allUnits && data.unit(f.unit) == nil {
            f = Filter(unit: data.unit(Filter.defaultUnit) != nil ? Filter.defaultUnit : Filter.allUnits)
        }
        if f.unit == Filter.allUnits || !data.outcomeOptions(unit: f.unit).contains(where: { $0.id == f.outcome }) {
            f.outcome = Filter.allOutcomes
        }
        if f.topic != Filter.allTopics && !data.baseCards(f).contains(where: { $0.topic == f.topic }) {
            f.topic = Filter.allTopics
        }
        filter = f
    }

    // MARK: - Refreshing

    /// Downloads the latest data from the website. Failures keep the current data.
    public func refresh() async {
        guard let repository, !isRefreshing else { return }
        isRefreshing = true
        lastCheck = now()
        let fresh = await repository.refresh()
        isRefreshing = false
        lastRefreshSucceeded = fresh != nil
        if let fresh { apply(fresh, origin: .live(now())) }
    }

    /// Refreshes if the last check was more than 30 minutes ago (or never).
    public func refreshIfStale() async {
        if let lastCheck, now().timeIntervalSince(lastCheck) < Self.staleInterval { return }
        await refresh()
    }

    /// Swaps in new data, keeping filters and decks where they still make sense.
    public func apply(_ newData: RevisionData, origin newOrigin: DataOrigin) {
        data = newData
        origin = newOrigin
        validateFilter()
        if cardMode == .single && focusCardId.flatMap({ data.cardsById[$0] }) == nil { cardMode = .study }
        validateMockAttempt()
        processContentChange()
        rebuildActive(resume: true)
        save()
    }

    // MARK: - New-content alerts

    /// Compares the loaded data with the saved fingerprints. The first time, saves them silently.
    /// Returns true when anything was saved.
    @discardableResult
    private func processContentChange() -> Bool {
        guard data.isUsable else { return false }
        let current = ContentFingerprints(data)
        guard let old = fingerprints else {
            fingerprints = current
            return true
        }
        let diff = ContentDiff.compare(old: old, new: current)
        guard current != old else { return false }
        fingerprints = current
        if !diff.isEmpty {
            newCardIds.formUnion(diff.added)
            newCardIds.formUnion(diff.updated)
            lastChange = ContentChange(date: now(), added: diff.added.count, updated: diff.updated.count,
                                       questionsChanged: diff.questionsChanged)
        }
        return true
    }

    /// New or updated cards that still exist, in data order.
    public var newCards: [Card] { data.cards.filter { newCardIds.contains($0.id) } }

    /// The banner's close button.
    public func dismissNewCards() {
        newCardIds = []
        if cardMode == .new && active == .cards { rebuildCards(resume: false) }
        save()
    }

    /// "Study them": the New card mode.
    public func studyNewCards() {
        tab = .study
        activateCards(.new)
    }

    // MARK: - Filters

    public var unitCards: [Card] { data.unitCards(filter) }
    public var baseCards: [Card] { data.baseCards(filter) }
    public var selectedCards: [Card] { data.selectedCards(filter) }
    public var selectedQuestions: [Question] { data.selectedQuestions(filter) }
    public var topics: [TopicCount] { data.topics(filter) }
    public var outcomeOptions: [OutcomeOption] { data.outcomeOptions(unit: filter.unit) }
    public var unitInfo: String { data.unitInfo(unit: filter.unit) }
    public var specURL: String { data.specURL(unit: filter.unit) }

    public var confidentCount: Int { unitCards.filter { ratings[$0.id] == .known }.count }
    public var revisitCount: Int { unitCards.filter { ratings[$0.id] == .review }.count }

    // MARK: - Days, due cards and streaks

    public var today: Day { Day(now(), calendar: calendar) }

    public func schedule(for cardId: Int) -> CardSchedule? { schedule[cardId] }

    /// Cards due in the current unit (for the stat line).
    public var unitDueCount: Int { Scheduler.dueCount(unitCards, schedule: schedule, on: today) }
    /// Cards due across all units (for the Study tab badge).
    public var totalDueCount: Int { Scheduler.dueCount(data.cards, schedule: schedule, on: today) }
    /// The next due date among the filtered cards, for the Due empty state.
    public var nextDueDay: Day? { Scheduler.nextDue(selectedCards, schedule: schedule, after: today) }

    public var currentStreak: Int { Activity.streak(activity, today: today) }

    /// Counts a revision action today (rating, checked answer, mock answer or submission).
    private func recordActivity() {
        activity[today, default: 0] += 1
        bestStreak = max(bestStreak, currentStreak)
    }

    public func recentActivity(days: Int = 14) -> [(day: Day, count: Int)] {
        Activity.recent(activity, today: today, count: days)
    }

    /// The reminders to schedule for the next week.
    public func reminderPlan(days: Int = 7) -> [Reminder.Planned] {
        Reminder.plan(days: days, now: now(), calendar: calendar, minutes: reminderMinutes,
                      cards: data.cards, schedule: schedule, activity: activity)
    }

    // MARK: - Progress screen

    public struct UnitProgress: Identifiable, Sendable {
        public let unit: StudyUnit
        public let cards: Int
        public let confident: Int
        public let due: Int
        public let quizCorrect: Int
        public let quizAnswered: Int
        public let latestMock: MockUnitScore?
        public var id: String { unit.code }
        public var quizPercent: Int { MockExam.percent(quizCorrect, quizAnswered) }
    }

    public var unitProgress: [UnitProgress] {
        let day = today
        return data.units.map { unit in
            let cards = data.cards.filter { $0.unit == unit.code }
            let results = data.questions.filter { $0.unit == unit.code }.compactMap { quizProgress[$0.id] }
            let mock = mockHistory.lazy.compactMap { $0.perUnit.first { $0.unit == unit.code } }.first
            return UnitProgress(unit: unit, cards: cards.count,
                                confident: cards.filter { ratings[$0.id] == .known }.count,
                                due: Scheduler.dueCount(cards, schedule: schedule, on: day),
                                quizCorrect: results.filter(\.correct).count, quizAnswered: results.count,
                                latestMock: mock)
        }
    }

    // MARK: - Stars and notes

    public func isStarred(_ cardId: Int) -> Bool { starred.contains(cardId) }

    public func toggleStar(_ cardId: Int) {
        guard data.cardsById[cardId] != nil else { return }
        if starred.contains(cardId) { starred.remove(cardId) } else { starred.insert(cardId) }
        save()
    }

    public func note(for cardId: Int) -> String? { notes[cardId] }

    /// Saves a note (up to 2000 characters); an empty note deletes it.
    public func setNote(_ text: String, for cardId: Int) {
        let trimmed = String(text.prefix(Self.noteLimit))
        if trimmed.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            notes[cardId] = nil
        } else {
            notes[cardId] = trimmed
        }
        save()
    }

    // MARK: - Links and notifications

    /// Shows one card on its own in Study. Returns false if the card doesn't exist.
    @discardableResult
    public func openCard(_ cardId: Int) -> Bool {
        guard data.cardsById[cardId] != nil else { return false }
        focusCardId = cardId
        tab = .study
        activateCards(.single)
        return true
    }

    /// Study → Due (from a reminder).
    public func openDue() {
        tab = .study
        activateCards(.due)
    }

    @discardableResult
    public func open(_ url: URL) -> Bool {
        switch DeepLink.target(of: url) {
        case .card(let id): return openCard(id)
        case .due: openDue(); return true
        case nil: return false
        }
    }

    public func setUnit(_ unit: String) {
        filter = Filter(unit: unit)
        filterChanged()
    }

    public func setOutcome(_ outcome: String) {
        guard filter.unit != Filter.allUnits || outcome == Filter.allOutcomes else { return }
        filter.outcome = outcome
        filter.topic = Filter.allTopics
        filterChanged()
    }

    public func setTopic(_ topic: String) {
        filter.topic = topic
        filterChanged()
    }

    /// "Study this outcome": sets unit + outcome, all topics, and switches to Study.
    public func studyOutcome(unit: String, outcome: String) {
        filter = Filter(unit: unit, outcome: outcome)
        validateFilter()
        tab = .study
        activateCards(.study)
    }

    /// Called when a screen without a deck (Browse, Outcomes…) appears, so filter changes there
    /// don't start new flashcard or quiz rounds in the background.
    public func deactivateDecks() {
        active = .none
    }

    /// Switches to the Study tab showing Rated cards.
    public func showRatedCards() {
        tab = .study
        activateCards(.review)
    }

    private func filterChanged() {
        validateFilter()
        rebuildActive(resume: true)
        save()
    }

    private func rebuildActive(resume: Bool) {
        switch active {
        case .cards: rebuildCards(resume: resume)
        case .quiz: rebuildQuiz(resume: resume)
        case .none: break
        }
    }

    // MARK: - Flashcards (Study and Rated cards)

    /// The cards the current deck may draw from: the filtered set, limited to rated cards in Rated mode.
    public var eligibleCards: [Card] {
        switch cardMode {
        case .study: return selectedCards
        case .review: return selectedCards.filter { ratingFilter.includes(ratings[$0.id]) }
        case .due: return Scheduler.dueCards(selectedCards, schedule: schedule, on: today)
        case .starred: return selectedCards.filter { starred.contains($0.id) }
        case .new: return newCards
        case .single: return focusCardId.flatMap { data.cardsById[$0] }.map { [$0] } ?? []
        }
    }

    public var deckCards: [Card] { cardDeck.ids.compactMap { data.cardsById[$0] } }

    public var currentCard: Card? {
        guard cardDeck.ids.indices.contains(cardDeck.index) else { return nil }
        return data.cardsById[cardDeck.ids[cardDeck.index]]
    }

    public var isLastCard: Bool { !cardDeck.ids.isEmpty && cardDeck.index == cardDeck.ids.count - 1 }
    public var canShuffle: Bool { cardDeck.ids.count >= 2 && cardMode != .single }
    public var canRestart: Bool { !cardDeck.ids.isEmpty }
    public var selectedConfidentCount: Int { selectedCards.filter { ratings[$0.id] == .known }.count }

    public func rating(for cardId: Int) -> Rating? { ratings[cardId] }

    public func statusLabel(for cardId: Int) -> String {
        switch ratings[cardId] {
        case .known: return "Confident"
        case .review: return "Revisit"
        case nil: return "Not rated"
        }
    }

    private func cardScope(_ mode: CardMode) -> String {
        switch mode {
        case .new: return "new"
        case .single: return "single|\(focusCardId ?? 0)"
        default:
            return [filter.unit, filter.outcome, filter.topic, mode.rawValue, mode == .review ? ratingFilter.rawValue : ""]
                .joined(separator: "|")
        }
    }

    /// Called when the Study or Rated screen appears: resumes the saved deck for this scope or starts one.
    public func activateCards(_ mode: CardMode) {
        active = .cards
        if cardMode != mode { cardMode = mode }
        rebuildCards(resume: true)
        save()
    }

    public func setRatingFilter(_ value: RatingFilter) {
        ratingFilter = value
        if active == .cards { rebuildCards(resume: true) }
        save()
    }

    private func rebuildCards(resume: Bool) {
        let items = eligibleCards
        let scope = cardScope(cardMode)
        let ids = Set(items.map(\.id))
        // Due and New decks have no rounds: resume only if they still hold exactly the eligible cards.
        let valid: (CardDeck) -> Bool = { [cardMode] saved in
            cardMode.usesRounds ? saved.ids.allSatisfy(ids.contains) : Set(saved.ids) == ids
        }
        if resume, let saved = savedCardDecks[scope], !saved.ids.isEmpty, valid(saved) {
            var deck = saved
            deck.index = max(0, min(saved.index, saved.ids.count - 1))
            cardDeck = deck
        } else if cardMode.usesRounds {
            cardDeck = CardDeck(scope: scope, ids: nextRound(items, seen: &cardSeen, using: &rng).map(\.id))
        } else {
            cardDeck = CardDeck(scope: scope, ids: items.map(\.id))
        }
        storeCardDeck()
    }

    private func storeCardDeck() {
        if let card = currentCard, cardDeck.revealed { cardSeen.insert(card.id) }
        savedCardDecks[cardDeck.scope] = cardDeck
        save()
    }

    public func reveal() {
        guard let card = currentCard, !cardDeck.revealed else { return }
        cardDeck.revealed = true
        newCardIds.remove(card.id)
        storeCardDeck()
    }

    /// Moves forward (+1) or back (-1). Moving forward marks the card seen; from the last card it starts the next round.
    public func move(_ direction: Int) {
        guard let card = currentCard else { return }
        if direction > 0 {
            cardSeen.insert(card.id)
            if cardDeck.index == cardDeck.ids.count - 1 {
                if cardMode == .single {
                    activateCards(.study)
                } else {
                    rebuildCards(resume: false)
                }
                return
            }
        }
        guard cardDeck.index + direction >= 0 else { return }
        cardDeck.index += direction
        cardDeck.revealed = false
        storeCardDeck()
    }

    /// Rates the revealed card, reschedules it (Leitner boxes) and moves on. In Rated mode, a rating the filter
    /// excludes removes the card from the deck; in Due mode every rated card leaves the deck (it's no longer due).
    public func rate(_ rating: Rating) {
        guard let card = currentCard, cardDeck.revealed else { return }
        ratings[card.id] = rating
        schedule[card.id] = Scheduler.next(schedule[card.id], rating: rating, today: today)
        recordActivity()
        let excluded = cardMode == .review && ratingFilter != .both && !ratingFilter.includes(rating)
        if excluded || cardMode == .due {
            cardSeen.insert(card.id)
            cardDeck.ids.remove(at: cardDeck.index)
            cardDeck.revealed = false
            if cardDeck.index >= cardDeck.ids.count {
                rebuildCards(resume: false)
            } else {
                storeCardDeck()
            }
            return
        }
        move(1)
    }

    /// Deals the unseen eligible cards in random order (or starts a new round if none are left).
    public func shuffle() {
        guard cardMode.usesRounds else {
            cardDeck = CardDeck(scope: cardScope(cardMode), ids: eligibleCards.shuffled(using: &rng).map(\.id))
            storeCardDeck()
            return
        }
        let unseen = eligibleCards.filter { !cardSeen.contains($0.id) }
        guard !unseen.isEmpty else {
            rebuildCards(resume: false)
            return
        }
        cardDeck = CardDeck(scope: cardScope(cardMode), ids: unseen.shuffled(using: &rng).map(\.id))
        storeCardDeck()
    }

    /// Forgets which eligible cards were seen and starts again.
    public func restart() {
        for card in eligibleCards { cardSeen.remove(card.id) }
        rebuildCards(resume: false)
    }

    // MARK: - Quiz

    private var quizScope: String {
        [filter.unit, filter.outcome, filter.topic, "quiz"].joined(separator: "|")
    }

    /// Called when the Quiz screen appears.
    public func activateQuiz() {
        active = .quiz
        rebuildQuiz(resume: true)
        save()
    }

    private func rebuildQuiz(resume: Bool) {
        let items = selectedQuestions
        let ids = Set(items.map(\.id))
        if resume, let saved = savedQuizDecks[quizScope], !saved.ids.isEmpty, saved.ids.allSatisfy(ids.contains),
           saved.orders.count == saved.ids.count {
            var deck = saved
            deck.index = max(0, min(saved.index, saved.ids.count - 1))
            quizDeck = deck
            storeQuizDeck()
            return
        }
        startQuizRound()
    }

    private func startQuizRound() {
        let deck = nextRound(selectedQuestions, seen: &quizSeen, using: &rng)
        quizDeck = makeQuizDeck(deck)
        storeQuizDeck()
    }

    private func makeQuizDeck(_ questions: [Question]) -> QuizDeck {
        QuizDeck(scope: quizScope, ids: questions.map(\.id),
                 orders: questions.map { Array($0.options.indices).shuffled(using: &rng) })
    }

    private func storeQuizDeck() {
        savedQuizDecks[quizDeck.scope] = quizDeck
        save()
    }

    public var quizQuestions: [Question] { quizDeck.ids.compactMap { data.questionsById[$0] } }

    public var currentQuestion: Question? {
        guard quizDeck.ids.indices.contains(quizDeck.index) else { return nil }
        return data.questionsById[quizDeck.ids[quizDeck.index]]
    }

    /// Display order of the current question's options (indices into `options`).
    public var currentOrder: [Int] {
        guard let q = currentQuestion else { return [] }
        let order = quizDeck.orders.indices.contains(quizDeck.index) ? quizDeck.orders[quizDeck.index] : []
        return order.sorted() == Array(q.options.indices) ? order : Array(q.options.indices)
    }

    public func response(for questionId: String) -> QuizResponse? { quizDeck.responses[questionId] }

    public var answeredQuestions: [Question] { quizQuestions.filter { quizDeck.responses[$0.id]?.checked == true } }
    public var correctQuestions: [Question] { answeredQuestions.filter { quizDeck.responses[$0.id]?.choice == $0.answer } }
    public var wrongQuestions: [Question] { answeredQuestions.filter { quizDeck.responses[$0.id]?.choice != $0.answer } }
    public var unansweredCount: Int { quizDeck.ids.count - answeredQuestions.count }

    /// Saved latest answers for the selected questions: (correct, answered).
    public var savedQuizScore: (correct: Int, total: Int) {
        let history = selectedQuestions.compactMap { quizProgress[$0.id] }
        return (history.filter(\.correct).count, history.count)
    }

    public func quizResult(for questionId: String) -> QuizResult? { quizProgress[questionId] }

    /// Picks an option (an index into the question's `options`). Ignored once the answer is checked.
    public func choose(_ option: Int) {
        guard let q = currentQuestion, q.options.indices.contains(option),
              quizDeck.responses[q.id]?.checked != true else { return }
        quizDeck.responses[q.id] = QuizResponse(choice: option, checked: false)
        storeQuizDeck()
    }

    /// Checks the picked answer. The first checked answer counts; it's saved as the question's latest result.
    public func checkAnswer() {
        guard let q = currentQuestion, var response = quizDeck.responses[q.id], !response.checked else { return }
        response.checked = true
        quizDeck.responses[q.id] = response
        quizSeen.insert(q.id)
        quizProgress[q.id] = QuizResult(correct: response.choice == q.answer, choice: response.choice, answeredAt: now())
        recordActivity()
        storeQuizDeck()
    }

    public var isLastQuestion: Bool { !quizDeck.ids.isEmpty && quizDeck.index == quizDeck.ids.count - 1 }

    public func quizNext() {
        guard let q = currentQuestion, quizDeck.responses[q.id]?.checked == true else { return }
        if isLastQuestion { quizDeck.done = true } else { quizDeck.index += 1 }
        storeQuizDeck()
    }

    public func quizPrevious() {
        guard quizDeck.index > 0 else { return }
        quizDeck.index -= 1
        storeQuizDeck()
    }

    public func finishQuiz() {
        guard !quizDeck.ids.isEmpty else { return }
        quizDeck.done = true
        storeQuizDeck()
    }

    /// A new attempt at the questions answered wrongly, shuffled.
    public func retryIncorrect() {
        let wrong = wrongQuestions
        guard !wrong.isEmpty else { return }
        quizDeck = makeQuizDeck(wrong.shuffled(using: &rng))
        storeQuizDeck()
    }

    public func startNextQuizRound() {
        startQuizRound()
    }

    public func continueUnanswered() {
        guard let index = quizQuestions.firstIndex(where: { quizDeck.responses[$0.id]?.checked != true }) else { return }
        quizDeck.done = false
        quizDeck.index = index
        storeQuizDeck()
    }

    // MARK: - VLSM

    public func vlsmWorking(for exercise: Int) -> String { vlsmWorking[String(exercise)] ?? "" }

    public func setVLSMWorking(_ text: String, for exercise: Int) {
        guard vlsmWorking[String(exercise)] != text else { return }
        vlsmWorking[String(exercise)] = text.isEmpty ? nil : text
        save()
    }

    /// Saves a drill round's score if it's the best so far.
    public func recordDrillScore(_ score: Int) {
        guard score > drillBest else { return }
        drillBest = score
        save()
    }

    // MARK: - Mock exam

    /// Questions available for a mock across `units`.
    public func mockAvailable(units: Set<String>) -> Int {
        MockExam.available(data.questions, units: units).count
    }

    /// The units ticked by default: the filter unit, or every unit when the filter is all units.
    public var defaultMockUnits: Set<String> {
        filter.unit == Filter.allUnits ? Set(data.units.map(\.code)) : [filter.unit]
    }

    public func startMock(units: Set<String>, count: Int, timed: Bool) {
        let questions = MockExam.select(data.questions, units: units, count: count, using: &rng)
        guard !questions.isEmpty else { return }
        let ordered = data.units.map(\.code).filter(units.contains)
        mockAttempt = MockExam.makeAttempt(questions, units: ordered, now: now(),
                                           timeLimit: timed ? questions.count * MockExam.secondsPerQuestion : nil,
                                           using: &rng)
        save()
    }

    /// "Graded Unit Section 1 style": 5 from each of four units, 30 minutes.
    public func startPresetMock() {
        let questions = MockExam.selectPreset(data.questions, using: &rng)
        guard !questions.isEmpty else { return }
        mockAttempt = MockExam.makeAttempt(questions, units: MockExam.presetUnits, now: now(),
                                           timeLimit: MockExam.presetSeconds, using: &rng)
        save()
    }

    public var mockQuestions: [Question] { mockAttempt?.ids.compactMap { data.questionsById[$0] } ?? [] }

    public var currentMockQuestion: Question? {
        guard let attempt = mockAttempt, attempt.ids.indices.contains(attempt.index) else { return nil }
        return data.questionsById[attempt.ids[attempt.index]]
    }

    /// Display order of the current mock question's options.
    public var currentMockOrder: [Int] {
        guard let attempt = mockAttempt, let q = currentMockQuestion else { return [] }
        let order = attempt.orders.indices.contains(attempt.index) ? attempt.orders[attempt.index] : []
        return order.sorted() == Array(q.options.indices) ? order : Array(q.options.indices)
    }

    /// Picks (or changes) the answer to the current question. Ignored after submitting or once time is up.
    public func mockChoose(_ option: Int) {
        guard var attempt = mockAttempt, !attempt.isSubmitted, let q = currentMockQuestion,
              q.options.indices.contains(option) else { return }
        if checkMockTimer() { return }
        let first = attempt.answers[q.id] == nil
        attempt.answers[q.id] = option
        mockAttempt = attempt
        if first { recordActivity() }
        save()
    }

    public func mockGo(to index: Int) {
        guard var attempt = mockAttempt, attempt.ids.indices.contains(index), attempt.index != index else { return }
        attempt.index = index
        mockAttempt = attempt
        save()
    }

    public func submitMock() {
        guard var attempt = mockAttempt, !attempt.isSubmitted else { return }
        attempt.submittedAt = now()
        mockAttempt = attempt
        mockHistory.insert(MockExam.score(attempt, questions: data.questionsById), at: 0)
        if mockHistory.count > MockExam.historyLimit { mockHistory.removeLast(mockHistory.count - MockExam.historyLimit) }
        recordActivity()
        save()
    }

    /// Submits automatically when the timer has run out. Returns true if it submitted.
    @discardableResult
    public func checkMockTimer() -> Bool {
        guard let attempt = mockAttempt, !attempt.isSubmitted, let deadline = attempt.deadline, now() >= deadline else {
            return false
        }
        submitMock()
        return true
    }

    /// The submitted attempt's score.
    public var mockResult: MockRecord? {
        guard let attempt = mockAttempt, attempt.isSubmitted else { return nil }
        return MockExam.score(attempt, questions: data.questionsById)
    }

    /// Leaves the results (or abandons an attempt) and goes back to the setup screen.
    public func closeMock() {
        mockAttempt = nil
        save()
    }

    /// Drops a saved attempt whose questions no longer exist.
    private func validateMockAttempt() {
        guard let attempt = mockAttempt else { return }
        if attempt.ids.isEmpty || !attempt.ids.allSatisfy({ data.questionsById[$0] != nil })
            || attempt.orders.count != attempt.ids.count {
            mockAttempt = nil
        }
    }

    // MARK: - Reset

    /// Clears ratings, schedules, quiz results, seen sets, saved decks and mock exams. Settings, filters, activity
    /// (streaks) and VLSM working are kept. Stars and notes are kept unless `includingStarsAndNotes`.
    public func resetProgress(includingStarsAndNotes: Bool = false) {
        ratings = [:]
        schedule = [:]
        quizProgress = [:]
        cardSeen = []
        quizSeen = []
        savedCardDecks = [:]
        savedQuizDecks = [:]
        cardDeck = CardDeck()
        quizDeck = QuizDeck()
        mockAttempt = nil
        mockHistory = []
        if includingStarsAndNotes {
            starred = []
            notes = [:]
        }
        rebuildActive(resume: false)
        save()
    }
}
