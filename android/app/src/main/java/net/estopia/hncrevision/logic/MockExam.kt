package net.estopia.hncrevision.logic

import net.estopia.hncrevision.data.Question
import kotlin.random.Random

/** A question in a mock attempt with its option display order (indices into the question's options). */
data class MockQuestion(val id: String, val order: List<Int>)

/**
 * A mock exam attempt. Times are epoch millis. [answers] maps question id → original option index.
 * [submittedAt] is set once submitted; until then the attempt is in progress.
 */
data class MockAttempt(
    val units: List<String>,
    val questions: List<MockQuestion>,
    val answers: Map<String, Int>,
    val index: Int,
    val startedAt: Long,
    val deadline: Long?,
    val submittedAt: Long?,
    val preset: Boolean,
) {
    val isSubmitted get() = submittedAt != null
}

/** Correct / asked for one unit. */
data class UnitScore(val unit: String, val correct: Int, val asked: Int) {
    val percent get() = percent(correct, asked)
}

/** One saved attempt: `{date, units, total, correct, durationSeconds, perUnit}`. [date] is local ISO date-time. */
data class MockHistoryEntry(
    val date: String,
    val units: List<String>,
    val total: Int,
    val correct: Int,
    val durationSeconds: Long,
    val perUnit: List<UnitScore>,
) {
    val percent get() = percent(correct, total)
}

fun percent(correct: Int, total: Int): Int = if (total <= 0) 0 else Math.round(correct * 100.0 / total).toInt()

object MockExam {
    const val SECONDS_PER_QUESTION = 90
    val COUNTS = listOf(10, 20, 40)
    const val HISTORY_LIMIT = 20

    /** "Graded Unit Section 1 style": 5 questions from each of these units, 30 minutes. */
    val PRESET_UNITS = listOf("J0H934", "J0HL34", "J0HJ34", "J0J734")
    const val PRESET_PER_UNIT = 5
    const val PRESET_SECONDS = 30 * 60

    fun pool(questions: List<Question>, units: Collection<String>) = questions.filter { it.unit in units }

    /** A random sample of up to [count] questions across [units]. */
    fun select(questions: List<Question>, units: Collection<String>, count: Int, random: Random): List<Question> =
        pool(questions, units).shuffled(random).take(count)

    /** [PRESET_PER_UNIT] random questions from each preset unit, grouped by unit. */
    fun selectPreset(questions: List<Question>, random: Random): List<Question> =
        PRESET_UNITS.flatMap { u -> questions.filter { it.unit == u }.shuffled(random).take(PRESET_PER_UNIT) }

    fun start(
        selected: List<Question>,
        units: List<String>,
        timeLimitSeconds: Int?,
        now: Long,
        random: Random,
        preset: Boolean = false,
    ) = MockAttempt(
        units = units,
        questions = selected.map { q -> MockQuestion(q.id, q.options.indices.shuffled(random)) },
        answers = emptyMap(),
        index = 0,
        startedAt = now,
        deadline = timeLimitSeconds?.let { now + it * 1000L },
        submittedAt = null,
        preset = preset,
    )

    /** Seconds left (never negative), or null when untimed. */
    fun remainingSeconds(attempt: MockAttempt, now: Long): Long? =
        attempt.deadline?.let { maxOf(0L, (it - now + 999) / 1000) }

    fun isExpired(attempt: MockAttempt, now: Long) = attempt.deadline != null && now >= attempt.deadline

    /** Submits at [now], or at the deadline if that has already passed. */
    fun submit(attempt: MockAttempt, now: Long): MockAttempt =
        attempt.copy(submittedAt = attempt.deadline?.let { minOf(it, now) } ?: now)

    fun durationSeconds(attempt: MockAttempt): Long =
        maxOf(0L, ((attempt.submittedAt ?: attempt.startedAt) - attempt.startedAt) / 1000)

    fun isCorrect(attempt: MockAttempt, q: Question) = attempt.answers[q.id] == q.answer

    /** Per-unit scores in the order units first appear in the attempt. */
    fun perUnit(attempt: MockAttempt, byId: Map<String, Question>): List<UnitScore> {
        val qs = attempt.questions.mapNotNull { byId[it.id] }
        return qs.map { it.unit }.distinct().map { u ->
            val inUnit = qs.filter { it.unit == u }
            UnitScore(u, inUnit.count { isCorrect(attempt, it) }, inUnit.size)
        }
    }

    fun historyEntry(attempt: MockAttempt, byId: Map<String, Question>, date: String): MockHistoryEntry {
        val scores = perUnit(attempt, byId)
        return MockHistoryEntry(
            date = date,
            units = attempt.units,
            total = attempt.questions.size,
            correct = scores.sumOf { it.correct },
            durationSeconds = durationSeconds(attempt),
            perUnit = scores,
        )
    }

    /** Newest first, capped at [HISTORY_LIMIT]. */
    fun addToHistory(history: List<MockHistoryEntry>, entry: MockHistoryEntry) = (listOf(entry) + history).take(HISTORY_LIMIT)

    /** "m:ss", or "h:mm:ss" past an hour. */
    fun formatDuration(seconds: Long): String {
        val s = maxOf(0L, seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
