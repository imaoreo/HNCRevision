import Foundation
import Observation

/// The app's state: data, filters, flashcard and quiz decks, progress and settings.
/// Mirrors the revision site's behaviour and saves everything on the device.
@MainActor
@Observable
public final class RevisionStore {
    public static let storageKey = "hnc-revision-state-v1"
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

    // MARK: Settings

    public var speechEnabled = false { didSet { if speechEnabled != oldValue { save() } } }
    public var dyslexiaMode = false { didSet { if dyslexiaMode != oldValue { save() } } }

    private enum ActiveDeck { case none, cards, quiz }
    @ObservationIgnored private var active: ActiveDeck = .none
    @ObservationIgnored private let storage: StateStorage
    @ObservationIgnored private let repository: DataRepository?
    @ObservationIgnored public var rng: any RandomNumberGenerator = SystemRandomNumberGenerator()
    @ObservationIgnored public var now: () -> Date = Date.init
    @ObservationIgnored private var loading = true

    // MARK: - Setup

    /// Loads the cached or bundled data immediately; call `refresh()` afterwards to check the website.
    public convenience init(repository: DataRepository, storage: StateStorage = UserDefaults.standard) {
        let initial = repository.loadInitial()
        self.init(data: initial.data, origin: initial.origin, storage: storage, repository: repository)
    }

    public init(data: RevisionData, origin: DataOrigin = .bundled, storage: StateStorage, repository: DataRepository? = nil) {
        self.data = data
        self.origin = origin
        self.storage = storage
        self.repository = repository
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

        if let view = state.view {
            tab = view.tab
            cardMode = view.cardMode
            if view.unit == Filter.allUnits || data.unit(view.unit) != nil {
                filter = Filter(unit: view.unit, outcome: view.outcome, topic: view.topic)
            }
        }
        validateFilter()
        loading = false
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
        if let raw = try? Self.encoder.encode(state) {
            storage.set(raw, forKey: Self.storageKey)
        }
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
        rebuildActive(resume: true)
        save()
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
        let cards = selectedCards
        guard cardMode == .review else { return cards }
        return cards.filter { ratingFilter.includes(ratings[$0.id]) }
    }

    public var deckCards: [Card] { cardDeck.ids.compactMap { data.cardsById[$0] } }

    public var currentCard: Card? {
        guard cardDeck.ids.indices.contains(cardDeck.index) else { return nil }
        return data.cardsById[cardDeck.ids[cardDeck.index]]
    }

    public var isLastCard: Bool { !cardDeck.ids.isEmpty && cardDeck.index == cardDeck.ids.count - 1 }
    public var canShuffle: Bool { cardDeck.ids.count >= 2 }
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
        [filter.unit, filter.outcome, filter.topic, mode.rawValue, mode == .review ? ratingFilter.rawValue : ""]
            .joined(separator: "|")
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
        if resume, let saved = savedCardDecks[scope], !saved.ids.isEmpty, saved.ids.allSatisfy(ids.contains) {
            var deck = saved
            deck.index = max(0, min(saved.index, saved.ids.count - 1))
            cardDeck = deck
        } else {
            cardDeck = CardDeck(scope: scope, ids: nextRound(items, seen: &cardSeen, using: &rng).map(\.id))
        }
        storeCardDeck()
    }

    private func storeCardDeck() {
        if let card = currentCard, cardDeck.revealed { cardSeen.insert(card.id) }
        savedCardDecks[cardDeck.scope] = cardDeck
        save()
    }

    public func reveal() {
        guard currentCard != nil, !cardDeck.revealed else { return }
        cardDeck.revealed = true
        storeCardDeck()
    }

    /// Moves forward (+1) or back (-1). Moving forward marks the card seen; from the last card it starts the next round.
    public func move(_ direction: Int) {
        guard let card = currentCard else { return }
        if direction > 0 {
            cardSeen.insert(card.id)
            if cardDeck.index == cardDeck.ids.count - 1 {
                rebuildCards(resume: false)
                return
            }
        }
        guard cardDeck.index + direction >= 0 else { return }
        cardDeck.index += direction
        cardDeck.revealed = false
        storeCardDeck()
    }

    /// Rates the revealed card and moves on. In Rated mode, a rating the filter excludes removes the card from the deck.
    public func rate(_ rating: Rating) {
        guard let card = currentCard, cardDeck.revealed else { return }
        ratings[card.id] = rating
        if cardMode == .review && ratingFilter != .both && !ratingFilter.includes(rating) {
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

    // MARK: - Reset

    /// Clears ratings, quiz results, seen sets and saved decks. Settings, filters and VLSM working are kept.
    public func resetProgress() {
        ratings = [:]
        quizProgress = [:]
        cardSeen = []
        quizSeen = []
        savedCardDecks = [:]
        savedQuizDecks = [:]
        cardDeck = CardDeck()
        quizDeck = QuizDeck()
        rebuildActive(resume: false)
        save()
    }
}
