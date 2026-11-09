import Foundation
import Testing
@testable import RevisionCore

struct Item: Identifiable, Equatable {
    let id: Int
}

/// Deterministic generator for shuffles.
struct SeededGenerator: RandomNumberGenerator {
    var state: UInt64
    mutating func next() -> UInt64 {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return state
    }
}

@Suite("Filters")
struct FilterTests {
    let data = try! bundledFiles().parse()

    @Test func unitOutcomeTopic() {
        var f = Filter()
        #expect(f.unit == "J0H934")
        let unit = data.unitCards(f)
        #expect(!unit.isEmpty && unit.allSatisfy { $0.unit == "J0H934" })
        f.outcome = "1"
        let base = data.baseCards(f)
        #expect(base.allSatisfy { $0.outcomes.contains("1") })
        #expect(base.count < unit.count)
        let topics = data.topics(f)
        #expect(topics.first == TopicCount(topic: "All topics", count: base.count))
        #expect(topics.dropFirst().map(\.count).reduce(0, +) == base.count)
        // Topics appear in data order.
        #expect(topics[1].topic == base[0].topic)
        f.topic = topics[1].topic
        #expect(data.selectedCards(f).count == topics[1].count)
        #expect(data.selectedQuestions(f).allSatisfy { $0.unit == "J0H934" && $0.outcomes.contains("1") && $0.topic == f.topic })
    }

    @Test func allUnits() {
        let f = Filter(unit: Filter.allUnits)
        #expect(data.selectedCards(f).count == 182)
        #expect(data.selectedQuestions(f).count == 84)
        #expect(data.unitInfo(unit: "all") == "Study all seven units together.")
        #expect(data.specURL(unit: "all") == "https://www.sqa.org.uk/files_ccc/GP1015.pdf")
    }

    @Test func labels() {
        #expect(data.unitInfo(unit: "FR2435") == "Networking Technology · SCQF level 8")
        #expect(data.unitInfo(unit: "J0H934") == "Data Security · SCQF level 7")
        #expect(data.outcomeOptions(unit: "J0H934").first?.label == "All outcomes")
        #expect(data.outcomeOptions(unit: "J0H934")[1].label.hasPrefix("Outcome 1 · "))
        let graded = data.outcomeOptions(unit: "J0J734")
        #expect(graded.first?.label == "All sections")
        #expect(!graded[1].label.hasPrefix("Outcome"))
        #expect(outcomeLabel(unit: "J0J734", outcomes: ["1", "2"]) == "Sections 1, 2")
        #expect(outcomeLabel(unit: "J0H934", outcomes: ["1", "2"]) == "Outcomes 1, 2")
    }

    @Test func referencesDedupeAndAppendSpec() {
        let card = Card(id: 9, q: "", a: "", topic: "", unit: "J0H934",
                        references: [Reference(label: "A", url: "https://a"), Reference(label: "A again", url: "https://a")],
                        legalSource: "https://law")
        let refs = data.references(for: card)
        #expect(refs.map(\.url) == ["https://a", "https://law", "https://www.sqa.org.uk/files/hn/J0H934.pdf"])
        #expect(refs[1].label == "Legislation or regulator guidance")
        #expect(refs[2].label == "SQA · Unit specification and outcomes")
        let dupLegal = Card(id: 9, q: "", a: "", topic: "", unit: "J0H934",
                            references: [Reference(label: "Law", url: "https://law")], legalSource: "https://law")
        #expect(data.references(for: dupLegal).count == 2)
        let q = data.questionsById["q1"]!
        let qRefs = data.references(for: q)
        #expect(qRefs.first == data.cardsById[1]!.references.first)
        #expect(qRefs.last?.url == data.coverage["J0H934"]!.source)
    }
}

@Suite("Rounds")
struct RoundTests {
    @Test func returnsUnseenInOrder() {
        var seen: Set<Int> = [2]
        let deck = nextRound([Item(id: 1), Item(id: 2), Item(id: 3)], seen: &seen)
        #expect(deck.map(\.id) == [1, 3])
        #expect(seen == [2])
    }

    @Test func allSeenStartsShuffledRound() {
        var seen: Set<Int> = [1, 2, 3, 99]
        var rng = SeededGenerator(state: 7)
        let deck = nextRound((1...3).map(Item.init), seen: &seen, using: &rng)
        #expect(Set(deck.map(\.id)) == [1, 2, 3])
        #expect(seen == [99])
    }

    @Test func emptyItems() {
        var seen: Set<Int> = [1]
        #expect(nextRound([Item](), seen: &seen).isEmpty)
        #expect(seen == [1])
    }
}

@MainActor
@Suite("Flashcard decks")
struct CardDeckTests {
    let data = try! bundledFiles().parse()

    func makeStore(_ storage: MemoryStorage = MemoryStorage()) -> RevisionStore {
        let store = RevisionStore(data: data, storage: storage)
        store.rng = SeededGenerator(state: 42)
        return store
    }

    @Test func studyDeckIsSelectedCardsInOrder() {
        let store = makeStore()
        store.activateCards(.study)
        #expect(store.cardDeck.ids == store.selectedCards.map(\.id))
        #expect(store.cardDeck.index == 0)
        #expect(!store.cardDeck.revealed)
    }

    @Test func revealMarksSeenAndMovingForwardMarksSeen() {
        let store = makeStore()
        store.activateCards(.study)
        let first = store.currentCard!.id
        store.reveal()
        #expect(store.cardSeen.contains(first))
        store.move(1)
        let second = store.currentCard!.id
        #expect(!store.cardDeck.revealed)
        store.move(1) // skip without revealing
        #expect(store.cardSeen.contains(second))
        store.move(-1)
        #expect(store.currentCard!.id == second)
    }

    @Test func lastCardStartsNextRound() {
        let store = makeStore()
        store.setUnit("J0H934")
        store.setTopic(store.topics[1].topic)
        store.activateCards(.study)
        let ids = Set(store.cardDeck.ids)
        for _ in 0..<(ids.count - 1) { store.move(1) }
        #expect(store.isLastCard)
        store.move(1)
        // Every card was seen, so the next round has them all again, shuffled, and `seen` is cleared for them.
        #expect(Set(store.cardDeck.ids) == ids)
        #expect(store.cardSeen.isDisjoint(with: ids))
        #expect(store.cardDeck.index == 0)
    }

    @Test func rateMovesOnAndCounts() {
        let store = makeStore()
        store.activateCards(.study)
        let first = store.currentCard!.id
        store.rate(.known) // ignored until revealed
        #expect(store.rating(for: first) == nil)
        store.reveal()
        store.rate(.known)
        #expect(store.rating(for: first) == .known)
        #expect(store.statusLabel(for: first) == "Confident")
        #expect(store.cardDeck.index == 1)
        #expect(store.confidentCount == 1)
        #expect(store.selectedConfidentCount == 1)
    }

    @Test func ratedDeckFiltersAndRemovesExcluded() {
        let store = makeStore()
        store.activateCards(.study)
        let a = store.currentCard!.id
        store.reveal(); store.rate(.known)
        let b = store.currentCard!.id
        store.reveal(); store.rate(.review)
        let c = store.currentCard!.id
        store.reveal(); store.rate(.review)

        store.activateCards(.review)
        // Cards a, b, c are all seen, so the rated deck is a fresh shuffled round of them.
        #expect(Set(store.cardDeck.ids) == [a, b, c])

        store.setRatingFilter(.review)
        #expect(Set(store.cardDeck.ids) == [b, c])
        let current = store.currentCard!.id
        store.reveal()
        store.rate(.known)
        #expect(!store.cardDeck.ids.contains(current))
        #expect(store.cardDeck.ids.count == 1)

        // Now Confident: `a` (unseen this round) and `current` (just seen), so the deck is the unseen one.
        store.setRatingFilter(.known)
        #expect(store.eligibleCards.map(\.id).sorted() == [a, current].sorted())
        #expect(store.cardDeck.ids == [a])
    }

    @Test func shuffleAndRestart() {
        let store = makeStore()
        store.activateCards(.study)
        store.move(1)
        store.move(1)
        let unseen = store.eligibleCards.filter { !store.cardSeen.contains($0.id) }.map(\.id)
        store.shuffle()
        #expect(Set(store.cardDeck.ids) == Set(unseen))
        #expect(store.cardDeck.index == 0)
        store.restart()
        #expect(store.cardDeck.ids == store.selectedCards.map(\.id))
        #expect(store.cardSeen.isEmpty)
    }

    @Test func changingUnitResetsOutcomeAndTopic() {
        let store = makeStore()
        store.setOutcome("2")
        store.setTopic(store.topics[1].topic)
        store.setUnit("J0HA34")
        #expect(store.filter == Filter(unit: "J0HA34"))
        store.setTopic(store.topics[1].topic)
        store.setOutcome("1")
        #expect(store.filter.topic == Filter.allTopics)
        store.setUnit(Filter.allUnits)
        store.setOutcome("1") // outcome is disabled for all units
        #expect(store.filter.outcome == Filter.allOutcomes)
    }

    @Test func deckResumesAfterRelaunch() {
        let storage = MemoryStorage()
        let store = makeStore(storage)
        store.setUnit("J0HL34")
        store.activateCards(.study)
        store.shuffle()
        store.move(1)
        store.reveal()
        let deck = store.cardDeck

        let reopened = makeStore(storage)
        #expect(reopened.filter.unit == "J0HL34")
        reopened.activateCards(.study)
        #expect(reopened.cardDeck == deck)
        #expect(reopened.cardSeen == store.cardSeen)
    }

    @Test func savedDeckDroppedWhenIdsVanish() {
        let storage = MemoryStorage()
        let store = makeStore(storage)
        store.activateCards(.study)
        store.move(1)
        #expect(store.cardDeck.index == 1)
        let fewer = RevisionData(units: data.units, cards: data.cards.filter { $0.id != store.cardDeck.ids[0] },
                                 coverage: data.coverage, questions: data.questions,
                                 vlsmExercises: data.vlsmExercises, vlsmHints: data.vlsmHints)
        store.apply(fewer, origin: .live(Date()))
        #expect(store.cardDeck.index == 0)
        #expect(store.cardDeck.ids.allSatisfy { fewer.cardsById[$0] != nil })
    }
}

@MainActor
@Suite("Quiz")
struct QuizTests {
    let data = try! bundledFiles().parse()

    func makeStore(_ storage: MemoryStorage = MemoryStorage()) -> RevisionStore {
        let store = RevisionStore(data: data, storage: storage)
        store.rng = SeededGenerator(state: 3)
        store.now = { Date(timeIntervalSince1970: 1_800_000_000) }
        return store
    }

    @Test func deckHasShuffledOptionOrders() {
        let store = makeStore()
        store.activateQuiz()
        #expect(store.quizDeck.ids == store.selectedQuestions.map(\.id))
        #expect(store.quizDeck.orders.allSatisfy { $0.sorted() == [0, 1, 2, 3] })
    }

    @Test func scoringAndPersistence() {
        let storage = MemoryStorage()
        let store = makeStore(storage)
        store.activateQuiz()
        let q1 = store.currentQuestion!
        store.quizNext() // not checked yet: ignored
        #expect(store.quizDeck.index == 0)
        store.checkAnswer() // nothing chosen: ignored
        #expect(store.quizResult(for: q1.id) == nil)
        store.choose(q1.answer)
        store.checkAnswer()
        store.choose((q1.answer + 1) % 4) // locked after checking
        #expect(store.response(for: q1.id) == QuizResponse(choice: q1.answer, checked: true))
        #expect(store.quizResult(for: q1.id) == QuizResult(correct: true, choice: q1.answer, answeredAt: store.now()))
        #expect(store.quizSeen.contains(q1.id))

        store.quizNext()
        let q2 = store.currentQuestion!
        let wrong = (q2.answer + 1) % q2.options.count
        store.choose(wrong)
        store.checkAnswer()
        #expect(store.correctQuestions.map(\.id) == [q1.id])
        #expect(store.wrongQuestions.map(\.id) == [q2.id])
        #expect(store.savedQuizScore == (1, 2))
        #expect(store.unansweredCount == store.quizDeck.ids.count - 2)

        store.finishQuiz()
        #expect(store.quizDeck.done)

        // Everything survives a relaunch.
        let reopened = makeStore(storage)
        reopened.activateQuiz()
        #expect(reopened.quizDeck == store.quizDeck)
        #expect(reopened.quizResult(for: q2.id)?.correct == false)
        #expect(reopened.quizSeen == [q1.id, q2.id])

        reopened.continueUnanswered()
        #expect(!reopened.quizDeck.done)
        #expect(reopened.quizDeck.index == 2)

        reopened.retryIncorrect()
        #expect(reopened.quizDeck.ids == [q2.id])
        #expect(reopened.quizDeck.responses.isEmpty)
        #expect(!reopened.quizDeck.done)

        reopened.startNextQuizRound()
        #expect(!reopened.quizDeck.ids.contains(q1.id))
        #expect(!reopened.quizDeck.ids.contains(q2.id))
    }

    @Test func lastQuestionGoesToResults() {
        let store = makeStore()
        store.setUnit("J0J734")
        store.activateQuiz()
        let count = store.quizDeck.ids.count
        #expect(count > 0)
        for _ in 0..<count {
            store.choose(store.currentQuestion!.answer)
            store.checkAnswer()
            store.quizNext()
        }
        #expect(store.quizDeck.done)
        #expect(store.correctQuestions.count == count)
        store.startNextQuizRound()
        #expect(Set(store.quizDeck.ids) == Set(store.selectedQuestions.map(\.id)))
    }

    @Test func emptyFilterHasNoQuestions() {
        let store = makeStore()
        store.setUnit("J0J734")
        store.setOutcome("3")
        store.activateQuiz()
        if store.selectedQuestions.isEmpty {
            #expect(store.quizDeck.ids.isEmpty)
            #expect(store.currentQuestion == nil)
        }
    }

    @Test func resetClearsProgress() {
        let storage = MemoryStorage()
        let store = makeStore(storage)
        store.activateCards(.study)
        store.reveal()
        store.rate(.review)
        store.activateQuiz()
        store.choose(0)
        store.checkAnswer()
        store.setVLSMWorking("my working", for: 2)
        store.resetProgress()
        #expect(store.revisitCount == 0)
        #expect(store.quizResult(for: store.selectedQuestions[0].id) == nil)
        #expect(store.cardSeen.isEmpty && store.quizSeen.isEmpty)
        let reopened = makeStore(storage)
        #expect(reopened.revisitCount == 0)
        #expect(reopened.vlsmWorking(for: 2) == "my working")
    }

    @Test func settingsPersist() {
        let storage = MemoryStorage()
        let store = makeStore(storage)
        store.speechEnabled = true
        store.dyslexiaMode = true
        store.tab = .browse
        let reopened = makeStore(storage)
        #expect(reopened.speechEnabled)
        #expect(reopened.dyslexiaMode)
        #expect(reopened.tab == .browse)
    }
}

/// Serves canned responses by URL.
struct FakeFetcher: HTTPFetching {
    let responses: [String: String]
    func text(from url: URL) async throws -> String {
        guard let text = responses[url.absoluteString] else { throw HTTPError(status: 404) }
        return text
    }
}

@Suite("Loading and caching")
struct RepositoryTests {
    func tempDir() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("RevisionCoreTests-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @Test func bundledThenLiveThenSaved() async throws {
        let cache = tempDir()
        defer { try? FileManager.default.removeItem(at: cache) }
        let files = try bundledFiles()
        let site = URL(string: "https://example.site/")!
        let smallerCards = files.cards.replacingOccurrences(of: #"{"code": "J0HA34", "title": "Computer Programming"}, "#, with: "")
        let fetcher = FakeFetcher(responses: [
            "https://example.site/": #"<script src="cards.js?v=9"></script><script src="quiz.js?v=2"></script>"#,
            "https://example.site/cards.js?v=9": smallerCards,
            "https://example.site/quiz.js?v=2": files.quiz,
            "https://example.site/vlsm.js": files.vlsm,
        ])
        let repo = DataRepository(siteURL: site, bundledDirectory: sharedData, cacheDirectory: cache, fetcher: fetcher)
        let initial = repo.loadInitial()
        #expect(initial.origin == .bundled)
        #expect(initial.data.units.count == 7)

        let fresh = try #require(await repo.refresh())
        #expect(fresh.units.count == 6)

        let next = repo.loadInitial()
        guard case .saved = next.origin else {
            Issue.record("Expected the saved copy, got \(next.origin)")
            return
        }
        #expect(next.data.units.count == 6)
    }

    @Test func failedOrUnusableRefreshKeepsCurrentData() async throws {
        let cache = tempDir()
        defer { try? FileManager.default.removeItem(at: cache) }
        let files = try bundledFiles()
        let site = URL(string: "https://example.site/")!
        let broken = FakeFetcher(responses: [
            "https://example.site/cards.js": "const UNITS = [; oops",
            "https://example.site/quiz.js": files.quiz,
            "https://example.site/vlsm.js": files.vlsm,
        ])
        let repo = DataRepository(siteURL: site, bundledDirectory: sharedData, cacheDirectory: cache, fetcher: broken)
        #expect(await repo.refresh() == nil)
        #expect(repo.loadInitial().origin == .bundled)

        let empty = FakeFetcher(responses: [
            "https://example.site/cards.js": "const UNITS = []; const CARDS = []; const COVERAGE = {};",
            "https://example.site/quiz.js": files.quiz,
            "https://example.site/vlsm.js": files.vlsm,
        ])
        let repo2 = DataRepository(siteURL: site, bundledDirectory: sharedData, cacheDirectory: cache, fetcher: empty)
        #expect(await repo2.refresh() == nil)
        #expect(!FileManager.default.fileExists(atPath: cache.appendingPathComponent("cards.js").path))
    }

    @MainActor
    @Test func storeRefreshSwapsData() async throws {
        let cache = tempDir()
        defer { try? FileManager.default.removeItem(at: cache) }
        let files = try bundledFiles()
        let fetcher = FakeFetcher(responses: [
            "https://example.site/": "",
            "https://example.site/cards.js": files.cards,
            "https://example.site/quiz.js": files.quiz,
            "https://example.site/vlsm.js": files.vlsm,
        ])
        let repo = DataRepository(siteURL: URL(string: "https://example.site/")!, bundledDirectory: sharedData,
                                  cacheDirectory: cache, fetcher: fetcher)
        let store = RevisionStore(repository: repo, storage: MemoryStorage())
        #expect(store.origin == .bundled)
        await store.refresh()
        #expect(store.lastRefreshSucceeded == true)
        guard case .live = store.origin else {
            Issue.record("Expected live data")
            return
        }
        // A second check within 30 minutes is skipped.
        let checked = store.lastCheck
        await store.refreshIfStale()
        #expect(store.lastCheck == checked)
    }
}
