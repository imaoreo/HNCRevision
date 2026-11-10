package net.estopia.hncrevision.logic

import java.time.LocalTime
import java.time.ZonedDateTime

/** Daily reminder on/off and time of day (default 18:00). */
data class ReminderSettings(val enabled: Boolean = false, val hour: Int = 18, val minute: Int = 0) {
    val label: String get() = "%02d:%02d".format(hour, minute)
}

/** Daily reminder notification text and timing. */
object ReminderText {
    const val TITLE = "HNC Revision"

    fun body(due: Int, streak: Int): String = when {
        due > 0 && streak >= 1 -> "${cards(due)} due. Keep your $streak-day streak going."
        due > 0 -> "${cards(due)} due today."
        else -> "A few minutes of revision keeps it fresh."
    }

    private fun cards(n: Int) = if (n == 1) "1 card is" else "$n cards are"

    /** The next time the reminder should fire: today at hour:minute if still ahead, else tomorrow. */
    fun nextTrigger(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        val today = now.toLocalDate().atTime(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1)
            .atTime(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))).atZone(now.zone)
    }

    /** Just after the next local midnight (for the widget's daily refresh). */
    fun nextMidnight(now: ZonedDateTime): ZonedDateTime =
        now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusMinutes(1)
}
