package net.estopia.hncrevision.logic

import net.estopia.hncrevision.data.Card
import net.estopia.hncrevision.data.Json
import net.estopia.hncrevision.data.Question
import net.estopia.hncrevision.data.Reference
import net.estopia.hncrevision.data.RevisionData
import java.time.Instant
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
    private val now: () -> String = { Instant.now().toString() },
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
        ratingFilter = session.ratingFilter
        val view = session.view
        if (view != null && (view.unit == ALL || data.coverage.containsKey(view.unit))) {
            unit = view.unit
            outcome = if (outcomeOptions().any { it.first == view.outcome }) view.outcome else ALL
            topic = if (baseCards().any { it.topic == view.topic }) view.topic else ALL_TOPICS
            tab = view.tab
            cardMode = view.cardMode
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

    fun eligibleCards(mode: CardMode): List<Card> =
        if (mode == CardMode.STUDY) selected() else selected().filter { ratingFilter.accepts(progress[it.id]) }

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
        cardMode = mode
        rebuildCards(mode, resume = true)
    }

    fun setRatingFilter(filter: RatingFilter) {
        ratingFilter = filter
        rebuildCards(CardMode.RATED, resume = true)
    }

    /** Swaps in freshly downloaded data, keeping filters and decks where they still apply. */
    fun replaceData(newData: RevisionData) {
        data = newData
        if (unit != ALL && !newData.coverage.containsKey(unit) && newData.unit(unit) == null) unit = ALL
        if (outcomeOptions().none { it.first == outcome }) outcome = ALL
        if (topic != ALL_TOPICS && baseCards().none { it.topic == topic }) topic = ALL_TOPICS
        rebuildAll(resume = true)
    }

    // endregion

    // region Flashcards

    private fun scope(vararg parts: String) = Json.write(listOf(unit, outcome, topic) + parts)

    private fun cardScope(mode: CardMode) = scope(mode.key, if (mode == CardMode.RATED) ratingFilter.key else "")

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
            CardDeck(scope, nextRound(items, cardSeen, random) { it.id }.toMutableList(), 0, false)
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
        save()
    }

    fun move(mode: CardMode, direction: Int) {
        val d = deck(mode)
        val card = d.cards.getOrNull(d.index) ?: return
        if (direction > 0) {
            cardSeen.add(card.id)
            if (d.index == d.cards.size - 1) return rebuildCards(mode, resume = false)
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
        if (mode == CardMode.RATED && !ratingFilter.accepts(rating)) {
            d.cards.removeAt(d.index)
            d.revealed = false
            if (d.index >= d.cards.size) return rebuildCards(mode, resume = false)
            return save()
        }
        move(mode, 1)
    }

    fun shuffle(mode: CardMode) {
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

    /** Clears ratings, quiz answers, seen sets and saved decks. */
    fun resetProgress() {
        progress.clear()
        quizProgress.clear()
        cardSeen.clear()
        quizSeen.clear()
        session = Session()
        store.clearProgress()
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
            ),
            selected = selected.map(::cardView),
            study = deckView(CardMode.STUDY, selected),
            rated = deckView(CardMode.RATED, selected),
            quiz = quizView(),
            totalCards = data.cards.size,
            totalQuestions = data.questions.size,
        )
    }

    private fun cardView(card: Card) = CardView(
        card = card,
        tag = "${card.unit} · ${card.topic}",
        status = statusLabel(card.id),
        outcomes = outcomeLabel(card.unit, card.outcomes),
        references = cardReferences(card),
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
                    ResultItem(
                        question = q,
                        correct = choice == q.answer,
                        yourAnswer = q.options.getOrElse(choice) { "" },
                        correctAnswer = q.options[q.answer],
                        references = questionReferences(q),
                        footer = "${q.unit} · ${q.topic} · ${outcomeLabel(q.unit, q.outcomes)} · Flashcard ${q.cardId}",
                    )
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

data class Stats(val flashcards: Int, val confident: Int, val revisit: Int)

data class CardView(
    val card: Card,
    val tag: String,
    val status: String,
    val outcomes: String,
    val references: List<Reference>,
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

data class Snapshot(
    val data: RevisionData,
    val tab: Tab,
    val cardMode: CardMode,
    val ratingFilter: RatingFilter,
    val filters: FilterView,
    val stats: Stats,
    val selected: List<CardView>,
    val study: DeckView,
    val rated: DeckView,
    val quiz: QuizView,
    val totalCards: Int,
    val totalQuestions: Int,
) {
    fun deck(mode: CardMode) = if (mode == CardMode.STUDY) study else rated
}
