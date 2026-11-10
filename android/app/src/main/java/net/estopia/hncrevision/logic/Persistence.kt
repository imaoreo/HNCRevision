package net.estopia.hncrevision.logic

import net.estopia.hncrevision.data.Json
import net.estopia.hncrevision.data.JsLiteral
import java.time.LocalDate

/** String key/value storage (SharedPreferences in the app, a map in tests). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MemoryStore(val values: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

enum class Rating(val key: String) {
    KNOWN("known"), REVIEW("review");
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } }
}

enum class RatingFilter(val key: String, val label: String) {
    BOTH("both", "Confident and Revisit"), KNOWN("known", "Confident only"), REVIEW("review", "Revisit only");
    fun accepts(rating: Rating?) = when (this) {
        BOTH -> rating != null
        KNOWN -> rating == Rating.KNOWN
        REVIEW -> rating == Rating.REVIEW
    }
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: BOTH }
}

/**
 * Flashcard decks. Study shows every card; Rated only cards already rated (the site's "review"
 * mode); Due the spaced-repetition cards due today; Starred the bookmarked ones; New the cards
 * added or changed on the website; Card a single card opened from the widget.
 */
enum class CardMode(val key: String, val title: String) {
    STUDY("study", "Study"),
    DUE("due", "Due"),
    RATED("review", "Rated cards"),
    STARRED("starred", "Starred"),
    NEW("new", "New cards"),
    CARD("card", "Card of the day");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: STUDY
        /** The modes in Study's segmented control. */
        val SEGMENTED = listOf(STUDY, DUE, RATED, STARRED)
    }
}

enum class QuizMode(val key: String) {
    PRACTICE("practice"), MOCK("mock");
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: PRACTICE }
}

enum class Tab(val key: String) {
    STUDY("study"), QUIZ("quiz"), BROWSE("browse"), MORE("more");
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: STUDY }
}

data class QuizResult(val correct: Boolean, val choice: Int, val answeredAt: String)

data class QuizResponse(val choice: Int, val checked: Boolean)

data class SavedView(val unit: String, val outcome: String, val topic: String, val tab: Tab, val cardMode: CardMode)

data class SavedCardDeck(val scope: String, val ids: List<Int>, val index: Int, val revealed: Boolean)

data class SavedQuizDeck(
    val scope: String,
    val ids: List<String>,
    val orders: List<List<Int>>,
    val index: Int,
    val responses: Map<String, QuizResponse>,
    val done: Boolean,
)

data class Session(
    val cardSeen: Set<Int>? = null,
    val quizSeen: Set<String>? = null,
    val ratingFilter: RatingFilter = RatingFilter.BOTH,
    val view: SavedView? = null,
    val decks: Map<CardMode, SavedCardDeck> = emptyMap(),
    val quiz: SavedQuizDeck? = null,
    val quizMode: QuizMode = QuizMode.PRACTICE,
    /** The card shown by [CardMode.CARD]. */
    val focusCard: Int? = null,
)

/** Reads and writes all on-device progress as small JSON documents in a [KeyValueStore]. */
class ProgressStore(private val store: KeyValueStore) {

    fun loadProgress(): MutableMap<Int, Rating> {
        val m = read(PROGRESS) as? Map<*, *> ?: return mutableMapOf()
        return m.entries.mapNotNull { (k, v) ->
            val id = k.toString().toIntOrNull() ?: return@mapNotNull null
            val r = Rating.of(v as? String) ?: return@mapNotNull null
            id to r
        }.toMap(mutableMapOf())
    }

    fun saveProgress(progress: Map<Int, Rating>) =
        write(PROGRESS, progress.entries.associate { (k, v) -> k.toString() to v.key })

    fun loadQuizProgress(): MutableMap<String, QuizResult> {
        val m = read(QUIZ_PROGRESS) as? Map<*, *> ?: return mutableMapOf()
        return m.entries.mapNotNull { (k, v) ->
            val r = v as? Map<*, *> ?: return@mapNotNull null
            k.toString() to QuizResult(
                correct = r["correct"] == true,
                choice = (r["choice"] as? Number)?.toInt() ?: return@mapNotNull null,
                answeredAt = r["answeredAt"] as? String ?: "",
            )
        }.toMap(mutableMapOf())
    }

    fun saveQuizProgress(results: Map<String, QuizResult>) = write(
        QUIZ_PROGRESS,
        results.mapValues { (_, r) -> mapOf("correct" to r.correct, "choice" to r.choice, "answeredAt" to r.answeredAt) },
    )

    fun loadSession(): Session {
        val m = read(SESSION) as? Map<*, *> ?: return Session()
        val view = (m["view"] as? Map<*, *>)?.let { v ->
            SavedView(
                unit = v["unit"] as? String ?: return@let null,
                outcome = v["outcome"] as? String ?: ALL,
                topic = v["topic"] as? String ?: ALL_TOPICS,
                tab = Tab.of(v["tab"] as? String),
                cardMode = CardMode.of(v["cardMode"] as? String),
            )
        }
        val decks = CardMode.entries.mapNotNull { mode ->
            val d = m[mode.key] as? Map<*, *> ?: return@mapNotNull null
            mode to SavedCardDeck(
                scope = d["scope"] as? String ?: return@mapNotNull null,
                ids = (d["ids"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() },
                index = (d["index"] as? Number)?.toInt() ?: 0,
                revealed = d["revealed"] == true,
            )
        }.toMap()
        val quiz = (m["quiz"] as? Map<*, *>)?.let { q ->
            SavedQuizDeck(
                scope = q["scope"] as? String ?: return@let null,
                ids = (q["ids"] as? List<*>).orEmpty().mapNotNull { it as? String },
                orders = (q["orders"] as? List<*>).orEmpty().map { o -> (o as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() } },
                index = (q["index"] as? Number)?.toInt() ?: 0,
                responses = (q["responses"] as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) ->
                    val r = v as? Map<*, *> ?: return@mapNotNull null
                    k.toString() to QuizResponse((r["choice"] as? Number)?.toInt() ?: return@mapNotNull null, r["checked"] == true)
                }.toMap(),
                done = q["done"] == true,
            )
        }
        return Session(
            cardSeen = (m["cardSeen"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.toSet(),
            quizSeen = (m["quizSeen"] as? List<*>)?.mapNotNull { it as? String }?.toSet(),
            ratingFilter = RatingFilter.of(m["ratingFilter"] as? String),
            view = view,
            decks = decks,
            quiz = quiz,
            quizMode = QuizMode.of(m["quizMode"] as? String),
            focusCard = int(m["focusCard"]),
        )
    }

    fun saveSession(session: Session) {
        val m = linkedMapOf<String, Any?>(
            "cardSeen" to session.cardSeen?.toList(),
            "quizSeen" to session.quizSeen?.toList(),
            "ratingFilter" to session.ratingFilter.key,
        )
        session.view?.let { v ->
            m["view"] = mapOf("unit" to v.unit, "outcome" to v.outcome, "topic" to v.topic, "tab" to v.tab.key, "cardMode" to v.cardMode.key)
        }
        for ((mode, d) in session.decks) {
            m[mode.key] = mapOf("scope" to d.scope, "ids" to d.ids, "index" to d.index, "revealed" to d.revealed)
        }
        session.quiz?.let { q ->
            m["quiz"] = mapOf(
                "scope" to q.scope, "ids" to q.ids, "orders" to q.orders, "index" to q.index,
                "responses" to q.responses.mapValues { (_, r) -> mapOf("choice" to r.choice, "checked" to r.checked) },
                "done" to q.done,
            )
        }
        m["quizMode"] = session.quizMode.key
        session.focusCard?.let { m["focusCard"] = it }
        write(SESSION, m)
    }

    fun loadVlsmWorking(): Map<Int, String> =
        (read(VLSM_WORKING) as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) ->
            (k.toString().toIntOrNull() ?: return@mapNotNull null) to (v as? String ?: return@mapNotNull null)
        }.toMap()

    fun saveVlsmWorking(working: Map<Int, String>) =
        write(VLSM_WORKING, working.entries.associate { (k, v) -> k.toString() to v })

    fun getFlag(key: String): Boolean = store.get(key) == "on"
    fun setFlag(key: String, on: Boolean) = store.put(key, if (on) "on" else "off")

    // region 1.1: spaced repetition, stars and notes, activity, mock exams, content alerts, reminders, widget

    /** Leitner schedule, or null when never saved (or unreadable), which triggers the 1.0 migration. */
    fun loadSchedule(): MutableMap<Int, ScheduleEntry>? {
        val m = read(SCHEDULE) as? Map<*, *> ?: return null
        return m.entries.mapNotNull { (k, v) ->
            val id = k.toString().toIntOrNull() ?: return@mapNotNull null
            val e = v as? Map<*, *> ?: return@mapNotNull null
            val box = int(e["box"])?.coerceIn(0, Scheduler.MAX_BOX) ?: return@mapNotNull null
            val due = date(e["due"]) ?: return@mapNotNull null
            id to ScheduleEntry(box, due)
        }.toMap(mutableMapOf())
    }

    fun saveSchedule(schedule: Map<Int, ScheduleEntry>) =
        write(SCHEDULE, schedule.entries.associate { (k, v) -> k.toString() to mapOf("box" to v.box, "due" to v.due.toString()) })

    fun loadStarred(): MutableSet<Int> = intSet(read(STARRED))
    fun saveStarred(ids: Set<Int>) = write(STARRED, ids.sorted())

    fun loadNotes(): MutableMap<Int, String> =
        (read(NOTES) as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) ->
            val id = k.toString().toIntOrNull() ?: return@mapNotNull null
            val text = (v as? String)?.take(NOTE_LIMIT)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to text
        }.toMap(mutableMapOf())

    fun saveNotes(notes: Map<Int, String>) = write(NOTES, notes.entries.associate { (k, v) -> k.toString() to v })

    fun loadActivity(): ActivityLog = ActivityLog(
        (read(ACTIVITY) as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) ->
            (date(k) ?: return@mapNotNull null) to (int(v) ?: return@mapNotNull null)
        }.toMap(),
    )

    fun saveActivity(log: ActivityLog) = write(ACTIVITY, log.days.entries.sortedBy { it.key }.associate { (d, n) -> d.toString() to n })

    fun getInt(key: String): Int = store.get(key)?.trim()?.toIntOrNull() ?: 0
    fun setInt(key: String, value: Int) = store.put(key, value.toString())

    fun loadMockHistory(): List<MockHistoryEntry> = (read(MOCK_HISTORY) as? List<*>).orEmpty().mapNotNull { raw ->
        val e = raw as? Map<*, *> ?: return@mapNotNull null
        MockHistoryEntry(
            date = e["date"] as? String ?: return@mapNotNull null,
            units = (e["units"] as? List<*>).orEmpty().mapNotNull { it as? String },
            total = int(e["total"]) ?: return@mapNotNull null,
            correct = int(e["correct"]) ?: return@mapNotNull null,
            durationSeconds = (e["durationSeconds"] as? Number)?.toLong() ?: 0L,
            perUnit = (e["perUnit"] as? Map<*, *>).orEmpty().entries.mapNotNull { (u, v) ->
                val l = v as? List<*> ?: return@mapNotNull null
                UnitScore(u.toString(), int(l.getOrNull(0)) ?: return@mapNotNull null, int(l.getOrNull(1)) ?: return@mapNotNull null)
            },
        )
    }.take(MockExam.HISTORY_LIMIT)

    fun saveMockHistory(history: List<MockHistoryEntry>) = write(MOCK_HISTORY, history.map { e ->
        mapOf(
            "date" to e.date, "units" to e.units, "total" to e.total, "correct" to e.correct,
            "durationSeconds" to e.durationSeconds,
            "perUnit" to e.perUnit.associate { it.unit to listOf(it.correct, it.asked) },
        )
    })

    fun loadMockAttempt(): MockAttempt? {
        val m = read(MOCK_ATTEMPT) as? Map<*, *> ?: return null
        val questions = (m["questions"] as? List<*>).orEmpty().mapNotNull { raw ->
            val q = raw as? Map<*, *> ?: return@mapNotNull null
            MockQuestion(q["id"] as? String ?: return@mapNotNull null, (q["order"] as? List<*>).orEmpty().mapNotNull(::int))
        }
        if (questions.isEmpty()) return null
        return MockAttempt(
            units = (m["units"] as? List<*>).orEmpty().mapNotNull { it as? String },
            questions = questions,
            answers = (m["answers"] as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) -> int(v)?.let { k.toString() to it } }.toMap(),
            index = int(m["index"]) ?: 0,
            startedAt = (m["startedAt"] as? Number)?.toLong() ?: return null,
            deadline = (m["deadline"] as? Number)?.toLong(),
            submittedAt = (m["submittedAt"] as? Number)?.toLong(),
            preset = m["preset"] == true,
        )
    }

    fun saveMockAttempt(a: MockAttempt?) {
        if (a == null) return store.put(MOCK_ATTEMPT, null)
        write(MOCK_ATTEMPT, mapOf(
            "units" to a.units,
            "questions" to a.questions.map { mapOf("id" to it.id, "order" to it.order) },
            "answers" to a.answers,
            "index" to a.index,
            "startedAt" to a.startedAt,
            "deadline" to a.deadline,
            "submittedAt" to a.submittedAt,
            "preset" to a.preset,
        ))
    }

    fun loadFingerprints(): Fingerprints? {
        val m = read(FINGERPRINTS) as? Map<*, *> ?: return null
        val cards = m["cards"] as? Map<*, *> ?: return null
        val questions = m["questions"] as? Map<*, *> ?: return null
        return Fingerprints(
            cards = cards.entries.mapNotNull { (k, v) -> (k.toString().toIntOrNull() ?: return@mapNotNull null) to (v as? String ?: return@mapNotNull null) }.toMap(),
            questions = questions.entries.mapNotNull { (k, v) -> k.toString() to (v as? String ?: return@mapNotNull null) }.toMap(),
        )
    }

    fun saveFingerprints(f: Fingerprints) = write(FINGERPRINTS, mapOf(
        "cards" to f.cards.entries.associate { (k, v) -> k.toString() to v },
        "questions" to f.questions,
    ))

    fun loadNewCardIds(): MutableSet<Int> = intSet(read(NEW_CARDS))
    fun saveNewCardIds(ids: Set<Int>) = write(NEW_CARDS, ids.sorted())

    fun loadLastChange(): ContentChange? {
        val m = read(LAST_CHANGE) as? Map<*, *> ?: return null
        return ContentChange(
            date = date(m["date"]) ?: return null,
            added = int(m["added"]) ?: 0,
            updated = int(m["updated"]) ?: 0,
            questionsChanged = int(m["questionsChanged"]) ?: 0,
        )
    }

    fun saveLastChange(c: ContentChange) = write(LAST_CHANGE, mapOf(
        "date" to c.date.toString(), "added" to c.added, "updated" to c.updated, "questionsChanged" to c.questionsChanged,
    ))

    fun loadReminder(): ReminderSettings {
        val m = read(REMINDER) as? Map<*, *> ?: return ReminderSettings()
        return ReminderSettings(
            enabled = m["enabled"] == true,
            hour = int(m["hour"])?.takeIf { it in 0..23 } ?: 18,
            minute = int(m["minute"])?.takeIf { it in 0..59 } ?: 0,
        )
    }

    fun saveReminder(r: ReminderSettings) = write(REMINDER, mapOf("enabled" to r.enabled, "hour" to r.hour, "minute" to r.minute))

    fun loadWidgetSnapshot(): WidgetSnapshot? {
        val m = read(WIDGET) as? Map<*, *> ?: return null
        val card = (m["cardOfDay"] as? Map<*, *>)?.let { c ->
            CardOfDay(
                id = int(c["id"]) ?: return@let null,
                unit = c["unit"] as? String ?: "",
                topic = c["topic"] as? String ?: "",
                q = c["q"] as? String ?: return@let null,
            )
        }
        return WidgetSnapshot(int(m["dueCount"]) ?: 0, int(m["streak"]) ?: 0, card, m["updatedAt"] as? String ?: "")
    }

    fun saveWidgetSnapshot(w: WidgetSnapshot) = write(WIDGET, mapOf(
        "dueCount" to w.dueCount,
        "streak" to w.streak,
        "cardOfDay" to w.cardOfDay?.let { mapOf("id" to it.id, "unit" to it.unit, "topic" to it.topic, "q" to it.q) },
        "updatedAt" to w.updatedAt,
    ))

    // endregion

    /**
     * Clears ratings, schedules, quiz results, seen sets and saved decks (keeps settings, VLSM
     * working, activity and mock history); with [starsAndNotes], also stars and notes.
     */
    fun clearProgress(starsAndNotes: Boolean = false) {
        store.put(PROGRESS, null)
        store.put(QUIZ_PROGRESS, null)
        store.put(SESSION, null)
        // Saved as empty (not removed) so the 1.0 migration doesn't run again.
        saveSchedule(emptyMap())
        if (starsAndNotes) {
            store.put(STARRED, null)
            store.put(NOTES, null)
        }
    }

    private fun intSet(raw: Any?): MutableSet<Int> = (raw as? List<*>).orEmpty().mapNotNull(::int).toMutableSet()

    private fun int(raw: Any?): Int? = when (raw) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull()
        else -> null
    }

    private fun date(raw: Any?): LocalDate? = try {
        (raw as? String)?.let(LocalDate::parse)
    } catch (_: Exception) {
        null
    }

    private fun read(key: String): Any? = try {
        store.get(key)?.let { JsLiteral.parse(it) }
    } catch (_: Exception) {
        null
    }

    private fun write(key: String, value: Any?) = store.put(key, Json.write(value))

    companion object {
        const val PROGRESS = "progress"
        const val QUIZ_PROGRESS = "quizProgress"
        const val SESSION = "session"
        const val VLSM_WORKING = "vlsmWorking"
        const val SPEECH = "readAloud"
        const val DYSLEXIA = "dyslexiaReading"
        const val SCHEDULE = "schedule"
        const val STARRED = "starred"
        const val NOTES = "notes"
        const val ACTIVITY = "activity"
        const val BEST_STREAK = "bestStreak"
        const val MOCK_HISTORY = "mockHistory"
        const val MOCK_ATTEMPT = "mockAttempt"
        const val DRILL_BEST = "vlsmDrillBest"
        const val FINGERPRINTS = "fingerprints"
        const val NEW_CARDS = "newCardIds"
        const val LAST_CHANGE = "lastChange"
        const val REMINDER = "reminder"
        const val WIDGET = "widgetSnapshot"
        const val NOTE_LIMIT = 2000
    }
}
