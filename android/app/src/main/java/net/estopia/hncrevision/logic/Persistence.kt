package net.estopia.hncrevision.logic

import net.estopia.hncrevision.data.Json
import net.estopia.hncrevision.data.JsLiteral

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

/** Study shows every card; Rated shows only cards already rated (the site's "review" mode). */
enum class CardMode(val key: String) {
    STUDY("study"), RATED("review");
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: STUDY }
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

    /** Clears ratings, quiz results, seen sets and saved decks (keeps settings and VLSM working). */
    fun clearProgress() {
        store.put(PROGRESS, null)
        store.put(QUIZ_PROGRESS, null)
        store.put(SESSION, null)
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
    }
}
