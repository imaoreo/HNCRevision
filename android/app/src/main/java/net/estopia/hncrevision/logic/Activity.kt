package net.estopia.hncrevision.logic

import java.time.LocalDate

/**
 * Revision activity per local day (rated a card, checked a Practice answer, answered a mock
 * question), used for streaks and the Progress chart.
 */
class ActivityLog(counts: Map<LocalDate, Int> = emptyMap()) {
    private val counts = counts.filterValues { it > 0 }.toMutableMap()

    val days: Map<LocalDate, Int> get() = counts.toMap()

    fun count(day: LocalDate): Int = counts[day] ?: 0

    fun record(day: LocalDate, actions: Int = 1) {
        if (actions <= 0) return
        counts[day] = count(day) + actions
        // Keep two years; the best streak is saved separately.
        val cutoff = day.minusDays(KEEP_DAYS)
        counts.keys.removeAll { it.isBefore(cutoff) }
    }

    /** Consecutive active days ending today, or ending yesterday when nothing is done yet today. */
    fun streak(today: LocalDate): Int {
        var day = if (count(today) > 0) today else today.minusDays(1)
        var n = 0
        while (count(day) > 0) {
            n++
            day = day.minusDays(1)
        }
        return n
    }

    /** The last [n] days ending today, oldest first. */
    fun lastDays(today: LocalDate, n: Int = 14): List<Pair<LocalDate, Int>> =
        (n - 1 downTo 0).map { today.minusDays(it.toLong()).let { d -> d to count(d) } }

    companion object {
        private const val KEEP_DAYS = 730L
    }
}

/** "🔥 n-day streak" text, or null below one day. */
fun streakLabel(streak: Int): String? = if (streak >= 1) "🔥 $streak-day streak" else null
