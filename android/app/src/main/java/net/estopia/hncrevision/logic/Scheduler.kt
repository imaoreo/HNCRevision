package net.estopia.hncrevision.logic

import net.estopia.hncrevision.data.Card
import java.time.LocalDate

/** A card's Leitner box and the local day it is next due. */
data class ScheduleEntry(val box: Int, val due: LocalDate)

/**
 * Leitner-box spaced repetition: Confident moves a card up a box (max 4), Revisit sends it back
 * to box 0, and the card is due again after the box's interval.
 */
object Scheduler {
    /** Days until due for boxes 0…4. */
    val INTERVALS = listOf(1, 3, 7, 14, 30)
    const val MAX_BOX = 4

    fun interval(box: Int): Int = INTERVALS[box.coerceIn(0, MAX_BOX)]

    /** The new entry after rating a card today. Unscheduled cards start from box 0. */
    fun rate(current: ScheduleEntry?, rating: Rating, today: LocalDate): ScheduleEntry {
        val box = when (rating) {
            Rating.KNOWN -> minOf((current?.box ?: 0) + 1, MAX_BOX)
            Rating.REVIEW -> 0
        }
        return ScheduleEntry(box, today.plusDays(interval(box).toLong()))
    }

    /** One-off 1.0 → 1.1 migration: Revisit cards are due today, Confident ones in 3 days (box 1). */
    fun migrate(progress: Map<Int, Rating>, today: LocalDate): Map<Int, ScheduleEntry> =
        progress.mapValues { (_, rating) ->
            when (rating) {
                Rating.REVIEW -> ScheduleEntry(0, today)
                Rating.KNOWN -> ScheduleEntry(1, today.plusDays(interval(1).toLong()))
            }
        }

    fun isDue(entry: ScheduleEntry?, today: LocalDate): Boolean = entry != null && !entry.due.isAfter(today)

    /** Cards due on or before today, earliest due first, then in data order. */
    fun dueCards(cards: List<Card>, schedule: Map<Int, ScheduleEntry>, today: LocalDate): List<Card> =
        cards.withIndex()
            .filter { isDue(schedule[it.value.id], today) }
            .sortedWith(compareBy({ schedule.getValue(it.value.id).due }, { it.index }))
            .map { it.value }

    fun dueCount(cards: List<Card>, schedule: Map<Int, ScheduleEntry>, today: LocalDate): Int =
        cards.count { isDue(schedule[it.id], today) }

    /** The earliest due date after today among [cards], if any are scheduled. */
    fun nextDue(cards: List<Card>, schedule: Map<Int, ScheduleEntry>, today: LocalDate): LocalDate? =
        cards.mapNotNull { schedule[it.id]?.due }.filter { it.isAfter(today) }.minOrNull()
}
