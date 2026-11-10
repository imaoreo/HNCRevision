package net.estopia.hncrevision.logic

import net.estopia.hncrevision.data.Card
import net.estopia.hncrevision.data.Json
import net.estopia.hncrevision.data.Question
import net.estopia.hncrevision.data.Reference
import net.estopia.hncrevision.data.RevisionData
import net.estopia.hncrevision.data.StudyUnit
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * All revision state and behaviour, mirroring the website's app.js: filters, flashcard decks
 * and rounds, ratings, the multiple-choice quiz and saved progress. Plain Kotlin (no Android),
 * so it is covered by JVM unit tests; the UI reads immutable snapshots from [snapshot].
 */
class RevisionEngine(
    data: RevisionData,
    private val store: ProgressStore,
    private val random: Random = Random.Default,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val now: () -> String = { Instant.now(clock).toString() },
) {
    var data: RevisionData = data
        private set

    var unit = DEFAULT_UNIT; private set
    var outcome = ALL; private set
    var topic = ALL_TOPICS; private set
    var tab = Tab.STUDY; private set
    var cardMode = CardMode.STUDY; private set
    var ratingFilter = RatingFilter.BOTH; private set

    private val progress: MutableMap<Int, Rating> = store.loadProgress()
    private val quizProgress: MutableMap<String, QuizResult> = store.loadQuizProgress()
    private var session: Session = store.loadSession()
    private val cardSeen: MutableSet<Int> = (session.cardSeen ?: progress.keys).toMutableSet()
    private val quizSeen: MutableSet<String> = (session.quizSeen ?: quizProgress.keys).toMutableSet()

    // 1.1 state. Each loads leniently: a missing or unreadable value falls back to its default.
    private val schedule: MutableMap<Int, ScheduleEntry> = store.loadSchedule()
        ?: Scheduler.migrate(progress, today()).toMutableMap().also { store.saveSchedule(it) }
    private val starred: MutableSet<Int> = store.loadStarred()
    private val notes: MutableMap<Int, String> = store.loadNotes()
    private val activity: ActivityLog = store.loadActivity()
    private var bestStreak: Int = store.getInt(ProgressStore.BEST_STREAK)
    private val newCardIds: MutableSet<Int> = store.loadNewCardIds()
    private var lastChange: ContentChange? = store.loadLastChange()
    private var mock: MockAttempt? = store.loadMockAttempt()
    private var mockHistory: List<MockHistoryEntry> = store.loadMockHistory()
    var quizMode: QuizMode = session.quizMode; private set
    private var focusCard: Int? = session.focusCard

    fun today(): LocalDate = LocalDate.now(clock)
    private var lastDay: LocalDate = today()
    private fun nowMillis(): Long = clock.millis()

    private class CardDeck(var scope: String, val cards: MutableList<Card>, var index: Int, var revealed: Boolean)
    private class QuizItem(val question: Question, val order: List<Int>)
    private class QuizDeck(
        var scope: String,
        var items: List<QuizItem>,
        var index: Int,
        val responses: MutableMap<String, QuizResponse>,
        var done: Boolean,
    )

    private val decks = mutableMapOf<CardMode, CardDeck>()
    private var quiz = QuizDeck("", emptyList(), 0, mutableMapOf(), false)

    init {
        applyContent(data)
        validateMock()
        ratingFilter = session.ratingFilter
        val view = session.view
        if (view != null && (view.unit == ALL || data.coverage.containsKey(view.unit))) {
            unit = view.unit
            outcome = if (outcomeOptions().any { it.first == view.outcome }) view.outcome else ALL
            topic = if (baseCards().any { it.topic == view.topic }) view.topic else ALL_TOPICS
            tab = view.tab
            cardMode = view.cardMode
            if (cardMode == CardMode.CARD && focusCard?.let(data::card) == null) cardMode = CardMode.STUDY
        } else if (!data.coverage.containsKey(unit) && data.unit(unit) == null) {
            unit = ALL
        }
        rebuildAll(resume = true)
    }

    // region Filters

    fun unitCards(): List<Card> = data.cards.filter { unit == ALL || it.unit == unit }
    fun baseCards(): List<Card> = unitCards().filter { outcome == ALL || outcome in it.outcomes }
    fun selected(): List<Card> = baseCards().filter { topic == ALL_TOPICS || it.topic == topic }
    fun selectedQuestions(): List<Question> = data.questions.filter {
        (unit == ALL || it.unit == unit) && (outcome == ALL || outcome in it.outcomes) && (topic == ALL_TOPICS || it.topic == topic)
    }

    fun eligibleCards(mode: CardMode): List<Card> = when (mode) {
        CardMode.STUDY -> selected()
        CardMode.RATED -> selected().filter { ratingFilter.accepts(progress[it.id]) }
        CardMode.DUE -> Scheduler.dueCards(selected(), schedule, today())
        CardMode.STARRED -> selected().filter { it.id in starred }
        CardMode.NEW -> data.cards.filter { it.id in newCardIds }
        CardMode.CARD -> listOfNotNull(focusCard?.let(data::card))
    }

    /** "All topics" first, then each topic of baseCards in data order, with its count. */
    fun topics(): List<Pair<String, Int>> {
        val base = baseCards()
        return listOf(ALL_TOPICS to base.size) + base.groupingBy { it.topic }.eachCount().toList()
    }

    /** (id, label) pairs for the outcome picker, "all" first. */
    fun outcomeOptions(forUnit: String = unit): List<Pair<String, String>> {
        val graded = forUnit == GRADED_UNIT
        val all = ALL to if (graded) "All sections" else "All outcomes"
        val coverage = data.coverage[forUnit] ?: return listOf(all)
        return listOf(all) + coverage.outcomes.map { o -> o.id to if (graded) o.title else "Outcome ${o.id} · ${o.title}" }
    }

    fun rating(cardId: Int): Rating? = progress[cardId]
    fun quizResult(id: String): QuizResult? = quizProgress[id]
    fun scheduleOf(cardId: Int): ScheduleEntry? = schedule[cardId]
    fun isStarred(cardId: Int) = cardId in starred
    fun note(cardId: Int): String? = notes[cardId]
    fun newCards(): Set<Int> = newCardIds.toSet()

    fun setUnit(code: String) {
        unit = code
        outcome = ALL
        topic = ALL_TOPICS
        rebuildAll(resume = true)
    }

    fun setOutcome(id: String) {
        outcome = id
        topic = ALL_TOPICS
        rebuildAll(resume = true)
    }

    fun setTopic(name: String) {
        topic = name
        rebuildAll(resume = true)
    }

    /** From the Outcomes screen: study one outcome of a unit. */
    fun studyOutcome(unitCode: String, outcomeId: String) {
        unit = unitCode
        outcome = outcomeId
        topic = ALL_TOPICS
        tab = Tab.STUDY
        cardMode = CardMode.STUDY
        rebuildAll(resume = true)
    }

    fun setTab(value: Tab) {
        tab = value
        when (value) {
            Tab.STUDY -> rebuildCards(cardMode, resume = true)
            Tab.QUIZ -> startQuiz(resume = true)
            else -> save()
        }
    }

    fun setCardMode(mode: CardMode) {
        if (mode == CardMode.CARD && focusCard?.let(data::card) == null) return
        cardMode = mode
        rebuildCards(mode, resume = true)
    }

    /** Opens one card as a one-card deck (from the widget's deep link). */
    fun openCard(id: Int) {
        if (data.card(id) == null) return
        focusCard = id
        tab = Tab.STUDY
        cardMode = CardMode.CARD
        rebuildCards(CardMode.CARD, resume = false)
    }

    /** Opens Study → Due (from the reminder notification). */
    fun openDue() {
        tab = Tab.STUDY
        cardMode = CardMode.DUE
        rebuildCards(CardMode.DUE, resume = true)
    }

    /** Call on resume: after midnight, rebuilds the Due deck so newly due cards appear. */
    fun refreshDay() {
        val t = today()
        if (t == lastDay) return
        lastDay = t
        rebuildCards(CardMode.DUE, resume = false)
    }

    fun setQuizMode(mode: QuizMode) {
        quizMode = mode
        tick()
        save()
    }

    fun setRatingFilter(filter: RatingFilter) {
        ratingFilter = filter
        rebuildCards(CardMode.RATED, resume = true)
    }

    /** Swaps in freshly downloaded data, keeping filters and decks where they still apply. */
    fun replaceData(newData: RevisionData) {
        data = newData
        applyContent(newData)
        validateMock()
        if (unit != ALL && !newData.coverage.containsKey(unit) && newData.unit(unit) == null) unit = ALL
        if (outcomeOptions().none { it.first == outcome }) outcome = ALL
        if (topic != ALL_TOPICS && baseCards().none { it.topic == topic }) topic = ALL_TOPICS
        rebuildAll(resume = true)
    }

    // endregion

    // region Flashcards

    private fun scope(vararg parts: String) = Json.write(listOf(unit, outcome, topic) + parts)

    private fun cardScope(mode: CardMode) = when (mode) {
        CardMode.NEW -> Json.write(listOf(mode.key))
        CardMode.CARD -> Json.write(listOf(mode.key, focusCard ?: 0))
        else -> scope(mode.key, if (mode == CardMode.RATED) ratingFilter.key else "")
    }

    /** Due, New and single-card decks have no rounds: they are simply the eligible cards. */
    private fun usesRounds(mode: CardMode) = mode == CardMode.STUDY || mode == CardMode.RATED || mode == CardMode.STARRED

    private fun rebuildAll(resume: Boolean) {
        CardMode.entries.forEach { rebuildCards(it, resume, persist = false) }
        startQuiz(resume, persist = false)
        save()
    }

    private fun rebuildCards(mode: CardMode, resume: Boolean, persist: Boolean = true) {
        val items = eligibleCards(mode)
        val scope = cardScope(mode)
        val saved = session.decks[mode]
        val byId = items.associateBy { it.id }
        decks[mode] = if (resume && saved != null && saved.scope == scope && saved.ids.isNotEmpty() && saved.ids.all { it in byId }) {
            val cards = saved.ids.map { byId.getValue(it) }.toMutableList()
            CardDeck(scope, cards, saved.index.coerceIn(0, cards.size - 1), saved.revealed)
        } else {
            val cards = if (usesRounds(mode)) nextRound(items, cardSeen, random) { it.id } else items
            CardDeck(scope, cards.toMutableList(), 0, false)
        }
        if (persist) save()
    }

    private fun deck(mode: CardMode) = decks.getValue(mode)

    fun currentCard(mode: CardMode): Card? = deck(mode).let { it.cards.getOrNull(it.index) }

    fun reveal(mode: CardMode) {
        val d = deck(mode)
        val card = d.cards.getOrNull(d.index) ?: return
        d.revealed = true
        cardSeen.add(card.id)
        if (newCardIds.remove(card.id)) store.saveNewCardIds(newCardIds)
        save()
    }

    fun move(mode: CardMode, direction: Int) {
        val d = deck(mode)
        val card = d.cards.getOrNull(d.index) ?: return
        if (direction > 0) {
            cardSeen.add(card.id)
            if (d.index == d.cards.size - 1) {
                // A single card from the widget: moving on returns to Study.
                if (mode == CardMode.CARD) return setCardMode(CardMode.STUDY)
                return rebuildCards(mode, resume = false)
            }
        }
        if (d.index + direction < 0) return
        d.index += direction
        d.revealed = false
        save()
    }

    fun rate(mode: CardMode, rating: Rating) {
        val d = deck(mode)
        val card = d.cards.getOrNull(d.index) ?: return
        if (!d.revealed) return
        progress[card.id] = rating
        store.saveProgress(progress)
        schedule[card.id] = Scheduler.rate(schedule[card.id], rating, today())
        store.saveSchedule(schedule)
        recordActivity()
        // Rated: a rating the filter excludes drops the card. Due: a rated card is no longer due.
        if ((mode == CardMode.RATED && !ratingFilter.accepts(rating)) || mode == CardMode.DUE) {
            d.cards.removeAt(d.index)
            d.revealed = false
            if (d.index >= d.cards.size) return rebuildCards(mode, resume = false)
            return save()
        }
        move(mode, 1)
    }

    fun shuffle(mode: CardMode) {
        if (!usesRounds(mode)) {
            val d = deck(mode)
            d.cards.shuffle(random)
            d.index = 0
            d.revealed = false
            return save()
        }
        val unseen = eligibleCards(mode).filter { it.id !in cardSeen }
        if (unseen.isEmpty()) return rebuildCards(mode, resume = false)
        decks[mode] = CardDeck(cardScope(mode), unseen.shuffled(random).toMutableList(), 0, false)
        save()
    }

    fun restart(mode: CardMode) {
        eligibleCards(mode).forEach { cardSeen.remove(it.id) }
        rebuildCards(mode, resume = false)
    }

    // endregion

    // region Stars, notes and new content

    fun toggleStar(cardId: Int) {
        if (!starred.remove(cardId)) starred.add(cardId)
        store.saveStarred(starred)
        save()
    }

    /** Saves a note (trimmed to [ProgressStore.NOTE_LIMIT] characters); blank text deletes it. */
    fun setNote(cardId: Int, text: String) {
        val t = text.take(ProgressStore.NOTE_LIMIT)
        if (t.isBlank()) notes.remove(cardId) else notes[cardId] = t
        store.saveNotes(notes)
    }

    /** Closes the "new or updated cards" banner. */
    fun dismissNewCards() {
        newCardIds.clear()
        store.saveNewCardIds(newCardIds)
        if (cardMode == CardMode.NEW) cardMode = CardMode.STUDY
        rebuildCards(CardMode.NEW, resume = false)
    }

    /** Compares the loaded data with the saved fingerprints and records new or changed cards. */
    private fun applyContent(newData: RevisionData) {
        val fresh = ContentDiff.fingerprints(newData)
        val old = store.loadFingerprints()
        if (old == null) return store.saveFingerprints(fresh)
        if (old == fresh) return
        val diff = ContentDiff.diff(old, fresh)
        if (!diff.isEmpty) {
            newCardIds += diff.added + diff.updated
            store.saveNewCardIds(newCardIds)
            lastChange = ContentChange(today(), diff.added.size, diff.updated.size, diff.questionsChanged)
            store.saveLastChange(lastChange!!)
        }
        store.saveFingerprints(fresh)
    }

    // endregion

    // region Activity and streaks

    private fun recordActivity(actions: Int = 1) {
        val day = today()
        activity.record(day, actions)
        store.saveActivity(activity)
        val streak = activity.streak(day)
        if (streak > bestStreak) {
            bestStreak = streak
            store.setInt(ProgressStore.BEST_STREAK, bestStreak)
        }
    }

    fun streak(): Int = activity.streak(today())

    fun widgetSnapshot(): WidgetSnapshot = WidgetContent.build(data.cards, schedule, activity, today(), now())

    // endregion

    // region Quiz

    private fun startQuiz(resume: Boolean, persist: Boolean = true) {
        val items = selectedQuestions()
        val scope = scope("quiz")
        val saved = session.quiz
        val byId = items.associateBy { it.id }
        quiz = if (resume && saved != null && saved.scope == scope && saved.ids.isNotEmpty() && saved.ids.all { it in byId }) {
            val deckItems = saved.ids.mapIndexed { i, id ->
                val q = byId.getValue(id)
                QuizItem(q, saved.orders.getOrNull(i)?.takeIf { validOrder(it, q) } ?: shuffledOrder(q))
            }
            QuizDeck(scope, deckItems, saved.index.coerceIn(0, deckItems.size - 1), saved.responses.toMutableMap(), saved.done)
        } else {
            QuizDeck(scope, nextRound(items, quizSeen, random) { it.id }.map { QuizItem(it, shuffledOrder(it)) }, 0, mutableMapOf(), false)
        }
        if (persist) save()
    }

    private fun validOrder(order: List<Int>, q: Question) = order.sorted() == q.options.indices.toList()
    private fun shuffledOrder(q: Question) = q.options.indices.shuffled(random)

    private fun currentQuizItem(): QuizItem? = quiz.items.getOrNull(quiz.index)

    /** Picks option [original] (an index into the question's own options). */
    fun choose(original: Int) {
        val q = currentQuizItem()?.question ?: return
        if (quiz.responses[q.id]?.checked == true) return
        quiz.responses[q.id] = QuizResponse(original, false)
        save()
    }

    fun check() {
        val q = currentQuizItem()?.question ?: return
        val r = quiz.responses[q.id] ?: return
        if (r.checked) return
        quiz.responses[q.id] = r.copy(checked = true)
        quizSeen.add(q.id)
        quizProgress[q.id] = QuizResult(r.choice == q.answer, r.choice, now())
        store.saveQuizProgress(quizProgress)
        recordActivity()
        save()
    }

    fun quizNext() {
        val q = currentQuizItem()?.question ?: return
        if (quiz.responses[q.id]?.checked != true) return
        if (quiz.index == quiz.items.size - 1) quiz.done = true else quiz.index++
        save()
    }

    fun quizPrevious() {
        if (quiz.index > 0) quiz.index--
        save()
    }

    fun finishQuiz() {
        if (quiz.items.isEmpty()) return
        quiz.done = true
        save()
    }

    fun retryIncorrect() {
        val wrong = quiz.items.filter { item ->
            quiz.responses[item.question.id]?.let { it.checked && it.choice != item.question.answer } == true
        }
        if (wrong.isEmpty()) return
        quiz = QuizDeck(quiz.scope, wrong.shuffled(random).map { QuizItem(it.question, shuffledOrder(it.question)) }, 0, mutableMapOf(), false)
        save()
    }

    fun quizNextRound() = startQuiz(resume = false)

    fun continueUnanswered() {
        val i = quiz.items.indexOfFirst { quiz.responses[it.question.id]?.checked != true }
        if (i < 0) return
        quiz.done = false
        quiz.index = i
        save()
    }

    // endregion

    // region Mock exam

    private fun questionsById() = data.questions.associateBy { it.id }

    /** Drops a saved attempt whose questions no longer exist; submits one whose time ran out. */
    private fun validateMock() {
        val a = mock ?: return
        val byId = questionsById()
        if (a.questions.any { it.id !in byId }) {
            mock = null
            store.saveMockAttempt(null)
            return
        }
        val fixed = a.copy(
            questions = a.questions.map { mq ->
                val q = byId.getValue(mq.id)
                if (mq.order.sorted() == q.options.indices.toList()) mq else mq.copy(order = q.options.indices.shuffled(random))
            },
            index = a.index.coerceIn(0, a.questions.size - 1),
        )
        mock = fixed
        tick()
    }

    /** Default units for the setup screen: the filter's unit, or every unit when "all". */
    fun defaultMockUnits(): List<String> = if (unit == ALL) data.units.map { it.code } else listOf(unit)

    fun startMock(units: Collection<String>, count: Int, timed: Boolean) {
        val chosen = data.units.map { it.code }.filter { it in units }
        val picked = MockExam.select(data.questions, chosen, count, random)
        if (picked.isEmpty()) return
        mock = MockExam.start(picked, chosen, if (timed) picked.size * MockExam.SECONDS_PER_QUESTION else null, nowMillis(), random)
        saveMock()
    }

    fun startMockPreset() {
        val picked = MockExam.selectPreset(data.questions, random)
        if (picked.isEmpty()) return
        val units = MockExam.PRESET_UNITS.filter { u -> picked.any { it.unit == u } }
        mock = MockExam.start(picked, units, MockExam.PRESET_SECONDS, nowMillis(), random, preset = true)
        saveMock()
    }

    /** Answers the current mock question with option [original]; answers can be changed until submitted. */
    fun mockChoose(original: Int) {
        val a = mock?.takeIf { !it.isSubmitted } ?: return
        if (tick()) return
        val mq = a.questions[a.index]
        // The first answer to each question counts as activity; changing it doesn't.
        if (mq.id !in a.answers) recordActivity()
        mock = a.copy(answers = a.answers + (mq.id to original))
        saveMock()
    }

    fun mockGoTo(index: Int) {
        val a = mock?.takeIf { !it.isSubmitted } ?: return
        if (tick()) return
        mock = a.copy(index = index.coerceIn(0, a.questions.size - 1))
        saveMock()
    }

    fun mockSubmit() {
        val a = mock?.takeIf { !it.isSubmitted } ?: return
        finishMock(MockExam.submit(a, nowMillis()))
    }

    /** Submits automatically once the deadline passes. Returns true if it just submitted. */
    fun tick(): Boolean {
        val a = mock?.takeIf { !it.isSubmitted } ?: return false
        if (!MockExam.isExpired(a, nowMillis())) return false
        finishMock(MockExam.submit(a, nowMillis()))
        return true
    }

    private fun finishMock(done: MockAttempt) {
        mock = done
        val at = Instant.ofEpochMilli(done.submittedAt ?: nowMillis()).atZone(clock.zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
        mockHistory = MockExam.addToHistory(mockHistory, MockExam.historyEntry(done, questionsById(), at.toString()))
        store.saveMockHistory(mockHistory)
        saveMock()
    }

    /** Leaves the results and returns to the setup screen. */
    fun closeMock() {
        mock = null
        saveMock()
    }

    private fun saveMock() = store.saveMockAttempt(mock)

    // endregion

    /**
     * Clears ratings, schedules, quiz answers, seen sets and saved decks; with [starsAndNotes],
     * also stars and notes.
     */
    fun resetProgress(starsAndNotes: Boolean = false) {
        progress.clear()
        quizProgress.clear()
        schedule.clear()
        cardSeen.clear()
        quizSeen.clear()
        if (starsAndNotes) {
            starred.clear()
            notes.clear()
        }
        session = Session()
        store.clearProgress(starsAndNotes)
        if (cardMode == CardMode.CARD) cardMode = CardMode.STUDY
        rebuildAll(resume = false)
    }

    private fun save() {
        val d = decks.mapValues { (_, d) -> SavedCardDeck(d.scope, d.cards.map { it.id }, d.index, d.revealed) }
        session = session.copy(
            cardSeen = cardSeen.toSet(),
            quizSeen = quizSeen.toSet(),
            ratingFilter = ratingFilter,
            view = SavedView(unit, outcome, topic, tab, cardMode),
            decks = session.decks + d,
            quizMode = quizMode,
            focusCard = focusCard,
            quiz = SavedQuizDeck(
                quiz.scope,
                quiz.items.map { it.question.id },
                quiz.items.map { it.order },
                quiz.index,
                quiz.responses.toMap(),
                quiz.done,
            ),
        )
        store.saveSession(session)
    }

    // region Snapshots for the UI

    fun outcomeLabel(unitCode: String, outcomes: List<String>) =
        (if (unitCode == GRADED_UNIT) "Sections " else "Outcomes ") + outcomes.joinToString(", ")

    /** The card's references, plus legislation and the unit specification, de-duplicated by URL. */
    fun references(card: Card?, legalSource: String?, unitCode: String): List<Reference> {
        val refs = LinkedHashMap<String, String>()
        card?.references?.forEach { refs.putIfAbsent(it.url, it.label) }
        if (legalSource != null) refs.putIfAbsent(legalSource, "Legislation or regulator guidance")
        data.coverage[unitCode]?.source?.let { refs.putIfAbsent(it, "SQA · Unit specification and outcomes") }
        return refs.map { (url, label) -> Reference(label, url) }
    }

    fun cardReferences(card: Card) = references(card, card.legalSource, card.unit)
    fun questionReferences(q: Question) = references(data.card(q.cardId), q.legalSource, q.unit)

    fun statusLabel(cardId: Int) = when (progress[cardId]) {
        Rating.KNOWN -> "Confident"
        Rating.REVIEW -> "Revisit"
        null -> "Not rated"
    }

    fun snapshot(): Snapshot {
        val today = today()
        val streak = activity.streak(today)
        val unitCards = unitCards()
        val selected = selected()
        val coverage = data.coverage[unit]
        val unitInfo = data.unit(unit)
        return Snapshot(
            data = data,
            tab = tab,
            cardMode = cardMode,
            ratingFilter = ratingFilter,
            filters = FilterView(
                unit = unit,
                unitLabel = unitInfo?.let { "${it.code} · ${it.title}" } ?: "All units",
                outcome = outcome,
                outcomeLabel = outcomeOptions().firstOrNull { it.first == outcome }?.second ?: "All outcomes",
                outcomes = outcomeOptions(),
                outcomeEnabled = coverage != null,
                topic = topic,
                topics = topics(),
                unitInfo = unitInfo?.let { it.title + if (unit == LEVEL_8_UNIT) " · SCQF level 8" else " · SCQF level 7" }
                    ?: "Study all seven units together.",
                unitSource = if (unitInfo != null) coverage?.source else ALL_UNITS_SPEC,
            ),
            stats = Stats(
                flashcards = unitCards.size,
                confident = unitCards.count { progress[it.id] == Rating.KNOWN },
                revisit = unitCards.count { progress[it.id] == Rating.REVIEW },
                due = Scheduler.dueCount(unitCards, schedule, today),
            ),
            selected = selected.map(::cardView),
            decks = CardMode.entries.associateWith { deckView(it, selected) },
            quiz = quizView(),
            totalCards = data.cards.size,
            totalQuestions = data.questions.size,
            dueAll = Scheduler.dueCount(data.cards, schedule, today),
            streak = streak,
            bestStreak = maxOf(bestStreak, streak),
            newCardCount = data.cards.count { it.id in newCardIds },
            lastChange = lastChange,
            quizMode = quizMode,
            mock = mockView(),
            progress = progressView(today, streak),
        )
    }

    private fun cardView(card: Card) = CardView(
        card = card,
        tag = "${card.unit} · ${card.topic}",
        status = statusLabel(card.id),
        outcomes = outcomeLabel(card.unit, card.outcomes),
        references = cardReferences(card),
        starred = card.id in starred,
        note = notes[card.id],
    )

    private fun deckView(mode: CardMode, selected: List<Card>): DeckView {
        val d = deck(mode)
        val eligible = eligibleCards(mode)
        val confident = selected.count { progress[it.id] == Rating.KNOWN }
        return DeckView(
            mode = mode,
            current = d.cards.getOrNull(d.index)?.let(::cardView),
            revealed = d.revealed,
            index = d.index,
            size = d.cards.size,
            confident = confident,
            selectedCount = selected.size,
            tracker = TrackerView(
                kind = "Flashcards",
                unseen = eligible.filter { it.id !in cardSeen }.map { it.q },
                seen = eligible.filter { it.id in cardSeen }.map { it.q },
            ),
            nextDue = if (mode == CardMode.DUE) Scheduler.nextDue(selected, schedule, today()) else null,
            usesRounds = usesRounds(mode),
        )
    }

    private fun quizView(): QuizView {
        val selectedQs = selectedQuestions()
        val answered = quiz.items.filter { quiz.responses[it.question.id]?.checked == true }
        val correct = answered.count { quiz.responses[it.question.id]?.choice == it.question.answer }
        val history = selectedQs.filter { quizProgress.containsKey(it.id) }
        val item = currentQuizItem()
        val current = item?.let { qi ->
            val q = qi.question
            val r = quiz.responses[q.id]
            QuizQuestionView(
                question = q,
                tag = "${q.unit} · ${q.topic}",
                meta = "${outcomeLabel(q.unit, q.outcomes)} · Flashcard ${q.cardId}",
                options = qi.order.mapIndexed { i, o -> QuizOption(o, "ABCDEFGH".getOrElse(i) { '?' }.toString(), q.options[o]) },
                choice = r?.choice,
                checked = r?.checked == true,
                references = questionReferences(q),
                cardStarred = q.cardId in starred,
            )
        }
        val results = if (quiz.done) {
            QuizResults(
                correct = correct,
                answered = answered.size,
                wrong = answered.size - correct,
                unanswered = quiz.items.size - answered.size,
                total = quiz.items.size,
                items = answered.map { qi ->
                    val q = qi.question
                    val choice = quiz.responses.getValue(q.id).choice
                    resultItem(q, choice)
                },
            )
        } else null
        return QuizView(
            index = quiz.index,
            size = quiz.items.size,
            correctSoFar = correct,
            answeredCount = answered.size,
            savedCorrect = history.count { quizProgress[it.id]?.correct == true },
            savedTotal = history.size,
            current = current,
            results = results,
            tracker = TrackerView(
                kind = "Multiple choice",
                unseen = selectedQs.filter { it.id !in quizSeen }.map { it.q },
                seen = selectedQs.filter { it.id in quizSeen }.map { it.q },
            ),
        )
    }

    private fun resultItem(q: Question, choice: Int?) = ResultItem(
        question = q,
        correct = choice == q.answer,
        yourAnswer = choice?.let { q.options.getOrElse(it) { "" } } ?: "Not answered",
        correctAnswer = q.options[q.answer],
        references = questionReferences(q),
        footer = "${q.unit} · ${q.topic} · ${outcomeLabel(q.unit, q.outcomes)} · Flashcard ${q.cardId}",
        cardStarred = q.cardId in starred,
    )

    private fun mockView(): MockView {
        val counts = data.questions.groupingBy { it.unit }.eachCount()
        val setup = MockSetupView(
            units = data.units.map { MockUnitOption(it, counts[it.code] ?: 0) },
            defaultUnits = defaultMockUnits().filter { (counts[it] ?: 0) > 0 }.toSet(),
            history = mockHistory,
            presetAvailable = MockExam.PRESET_UNITS.sumOf { minOf(counts[it] ?: 0, MockExam.PRESET_PER_UNIT) },
        )
        val a = mock ?: return MockView(setup, null, null)
        val byId = questionsById()
        if (!a.isSubmitted) {
            val mq = a.questions[a.index]
            val q = byId.getValue(mq.id)
            val current = QuizQuestionView(
                question = q,
                tag = "${q.unit} · ${q.topic}",
                meta = "${outcomeLabel(q.unit, q.outcomes)} · Flashcard ${q.cardId}",
                options = mq.order.mapIndexed { i, o -> QuizOption(o, "ABCDEFGH".getOrElse(i) { '?' }.toString(), q.options[o]) },
                choice = a.answers[q.id],
                checked = false,
                references = questionReferences(q),
                cardStarred = q.cardId in starred,
            )
            return MockView(
                setup, MockRunView(a.index, a.questions.size, current, a.questions.map { it.id in a.answers }, a.deadline, a.startedAt), null,
            )
        }
        val scores = MockExam.perUnit(a, byId)
        val correct = scores.sumOf { it.correct }
        val result = MockResultView(
            correct = correct,
            total = a.questions.size,
            percent = percent(correct, a.questions.size),
            durationSeconds = MockExam.durationSeconds(a),
            timedOut = a.deadline != null && a.submittedAt != null && a.submittedAt >= a.deadline,
            perUnit = scores.map { UnitScoreRow(it, data.unit(it.unit)?.title ?: it.unit) },
            items = a.questions.map { mq -> byId.getValue(mq.id).let { resultItem(it, a.answers[it.id]) } },
        )
        return MockView(setup, null, result)
    }

    private fun progressView(today: LocalDate, streak: Int): ProgressView = ProgressView(
        streak = streak,
        bestStreak = maxOf(bestStreak, streak),
        days = activity.lastDays(today, 14),
        units = data.units.map { u ->
            val cards = data.cards.filter { it.unit == u.code }
            val answered = data.questions.filter { it.unit == u.code }.mapNotNull { quizProgress[it.id] }
            UnitProgress(
                unit = u,
                cards = cards.size,
                confident = cards.count { progress[it.id] == Rating.KNOWN },
                due = Scheduler.dueCount(cards, schedule, today),
                quizCorrect = answered.count { it.correct },
                quizAnswered = answered.size,
                latestMock = mockHistory.firstNotNullOfOrNull { h -> h.perUnit.firstOrNull { it.unit == u.code } },
            )
        },
    )

    // endregion
}

data class FilterView(
    val unit: String,
    val unitLabel: String,
    val outcome: String,
    val outcomeLabel: String,
    val outcomes: List<Pair<String, String>>,
    val outcomeEnabled: Boolean,
    val topic: String,
    val topics: List<Pair<String, Int>>,
    val unitInfo: String,
    val unitSource: String?,
)

data class Stats(val flashcards: Int, val confident: Int, val revisit: Int, val due: Int = 0)

data class CardView(
    val card: Card,
    val tag: String,
    val status: String,
    val outcomes: String,
    val references: List<Reference>,
    val starred: Boolean = false,
    val note: String? = null,
)

data class TrackerView(val kind: String, val unseen: List<String>, val seen: List<String>) {
    val summary get() = "$kind · ${seen.size} seen · ${unseen.size} unseen this round"
}

data class DeckView(
    val mode: CardMode,
    val current: CardView?,
    val revealed: Boolean,
    val index: Int,
    val size: Int,
    val confident: Int,
    val selectedCount: Int,
    val tracker: TrackerView,
    /** Due mode: the next due date after today in this filter, if any. */
    val nextDue: LocalDate? = null,
    val usesRounds: Boolean = true,
) {
    val isLast get() = size > 0 && index == size - 1
    val position get() = if (size > 0) "Card ${index + 1} of $size" else "No cards in this set"
    val progressFraction get() = if (selectedCount > 0) confident.toFloat() / selectedCount else 0f
    val progressLabel get() = "$confident of $selectedCount confident"
}

data class QuizOption(val original: Int, val letter: String, val text: String)

data class QuizQuestionView(
    val question: Question,
    val tag: String,
    val meta: String,
    val options: List<QuizOption>,
    val choice: Int?,
    val checked: Boolean,
    val references: List<Reference>,
    val cardStarred: Boolean = false,
) {
    val isCorrect get() = checked && choice == question.answer
}

data class ResultItem(
    val question: Question,
    val correct: Boolean,
    val yourAnswer: String,
    val correctAnswer: String,
    val references: List<Reference>,
    val footer: String,
    val cardStarred: Boolean = false,
)

data class QuizResults(
    val correct: Int,
    val answered: Int,
    val wrong: Int,
    val unanswered: Int,
    val total: Int,
    val items: List<ResultItem>,
)

data class QuizView(
    val index: Int,
    val size: Int,
    val correctSoFar: Int,
    val answeredCount: Int,
    val savedCorrect: Int,
    val savedTotal: Int,
    val current: QuizQuestionView?,
    val results: QuizResults?,
    val tracker: TrackerView,
) {
    val isEmpty get() = size == 0
    val isLast get() = index == size - 1
}

data class MockUnitOption(val unit: StudyUnit, val available: Int)

data class MockSetupView(
    val units: List<MockUnitOption>,
    val defaultUnits: Set<String>,
    val history: List<MockHistoryEntry>,
    val presetAvailable: Int,
)

data class MockRunView(
    val index: Int,
    val total: Int,
    val current: QuizQuestionView,
    /** Per question: answered yet? */
    val answered: List<Boolean>,
    /** Epoch millis, or null when untimed. */
    val deadline: Long?,
    val startedAt: Long,
) {
    val unanswered get() = answered.count { !it }
    val isLast get() = index == total - 1
}

data class UnitScoreRow(val score: UnitScore, val title: String)

data class MockResultView(
    val correct: Int,
    val total: Int,
    val percent: Int,
    val durationSeconds: Long,
    val timedOut: Boolean,
    val perUnit: List<UnitScoreRow>,
    val items: List<ResultItem>,
)

/** Mock exam: the setup screen, plus either an attempt in progress or the last results. */
data class MockView(val setup: MockSetupView, val run: MockRunView?, val result: MockResultView?)

data class UnitProgress(
    val unit: StudyUnit,
    val cards: Int,
    val confident: Int,
    val due: Int,
    val quizCorrect: Int,
    val quizAnswered: Int,
    val latestMock: UnitScore?,
)

data class ProgressView(
    val streak: Int,
    val bestStreak: Int,
    /** The last 14 days, oldest first, with action counts. */
    val days: List<Pair<LocalDate, Int>>,
    val units: List<UnitProgress>,
)

data class Snapshot(
    val data: RevisionData,
    val tab: Tab,
    val cardMode: CardMode,
    val ratingFilter: RatingFilter,
    val filters: FilterView,
    val stats: Stats,
    val selected: List<CardView>,
    val decks: Map<CardMode, DeckView>,
    val quiz: QuizView,
    val totalCards: Int,
    val totalQuestions: Int,
    /** Cards due across every unit (the Study tab badge). */
    val dueAll: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    /** New or updated cards from the website that still exist. */
    val newCardCount: Int = 0,
    val lastChange: ContentChange? = null,
    val quizMode: QuizMode = QuizMode.PRACTICE,
    val mock: MockView,
    val progress: ProgressView,
) {
    fun deck(mode: CardMode) = decks.getValue(mode)
    val study get() = deck(CardMode.STUDY)
    val rated get() = deck(CardMode.RATED)
}
