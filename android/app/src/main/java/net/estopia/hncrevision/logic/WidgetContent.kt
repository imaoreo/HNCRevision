package net.estopia.hncrevision.logic

import net.estopia.hncrevision.data.Card
import java.time.LocalDate

data class CardOfDay(val id: Int, val unit: String, val topic: String, val q: String)

/** What the home screen widget shows, written by the app whenever state saves or data loads. */
data class WidgetSnapshot(val dueCount: Int, val streak: Int, val cardOfDay: CardOfDay?, val updatedAt: String) {
    /** Same content, ignoring the timestamp. */
    fun sameContent(other: WidgetSnapshot?) = other != null && copy(updatedAt = "") == other.copy(updatedAt = "")
}

object WidgetContent {

    /**
     * A deterministic pick for the day: day number mod count over cards sorted by id, choosing from
     * the due cards when any are due.
     */
    fun cardOfDay(cards: List<Card>, schedule: Map<Int, ScheduleEntry>, today: LocalDate): Card? {
        val due = cards.filter { Scheduler.isDue(schedule[it.id], today) }
        val pool = due.ifEmpty { cards }.sortedBy { it.id }
        if (pool.isEmpty()) return null
        return pool[Math.floorMod(today.toEpochDay(), pool.size.toLong()).toInt()]
    }

    fun build(cards: List<Card>, schedule: Map<Int, ScheduleEntry>, activity: ActivityLog, today: LocalDate, updatedAt: String) =
        WidgetSnapshot(
            dueCount = Scheduler.dueCount(cards, schedule, today),
            streak = activity.streak(today),
            cardOfDay = cardOfDay(cards, schedule, today)?.let { CardOfDay(it.id, it.unit, it.topic, it.q) },
            updatedAt = updatedAt,
        )
}

/**
 * Widget and reminder content computed from saved state alone (used by the receivers when the
 * app isn't running). A 1.0 user who hasn't opened 1.1 yet gets their ratings migrated on the fly
 * (not saved; the app does that on next launch).
 */
fun WidgetContent.fromStore(cards: List<Card>, store: ProgressStore, today: LocalDate, updatedAt: String): WidgetSnapshot {
    val schedule = store.loadSchedule() ?: Scheduler.migrate(store.loadProgress(), today)
    return build(cards, schedule, store.loadActivity(), today, updatedAt)
}
