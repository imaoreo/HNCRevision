package net.estopia.hncrevision

import net.estopia.hncrevision.data.Card
import net.estopia.hncrevision.data.Question
import net.estopia.hncrevision.logic.ActivityLog
import net.estopia.hncrevision.logic.ContentDiff
import net.estopia.hncrevision.logic.Fingerprints
import net.estopia.hncrevision.logic.MockExam
import net.estopia.hncrevision.logic.Rating
import net.estopia.hncrevision.logic.ReminderText
import net.estopia.hncrevision.logic.ScheduleEntry
import net.estopia.hncrevision.logic.Scheduler
import net.estopia.hncrevision.logic.WidgetContent
import net.estopia.hncrevision.logic.streakLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** Plain logic added in 1.1: scheduling, activity, content diff, widget, reminders, mock exam helpers. */
class V11LogicTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 10)

    private fun card(id: Int, q: String = "Q$id", a: String = "A$id", hint: String = "") =
        Card(id, q, a, "Topic", "U1", listOf("1"), hint, emptyList())

    // region Scheduler

    @Test fun confidentClimbsBoxesWithIntervals() {
        var e: ScheduleEntry? = null
        val expected = listOf(1 to 3L, 2 to 7L, 3 to 14L, 4 to 30L, 4 to 30L)
        for ((box, days) in expected) {
            e = Scheduler.rate(e, Rating.KNOWN, today)
            assertEquals(box, e.box)
            assertEquals(today.plusDays(days), e.due)
        }
    }

    @Test fun revisitResetsToBoxZeroDueTomorrow() {
        val e = Scheduler.rate(ScheduleEntry(3, today), Rating.REVIEW, today)
        assertEquals(ScheduleEntry(0, today.plusDays(1)), e)
        assertEquals(ScheduleEntry(0, today.plusDays(1)), Scheduler.rate(null, Rating.REVIEW, today))
        assertEquals(listOf(1, 3, 7, 14, 30), (0..4).map(Scheduler::interval))
    }

    @Test fun migrationFromRatings() {
        val m = Scheduler.migrate(mapOf(1 to Rating.REVIEW, 2 to Rating.KNOWN), today)
        assertEquals(ScheduleEntry(0, today), m[1])
        assertEquals(ScheduleEntry(1, today.plusDays(3)), m[2])
    }

    @Test fun dueCardsSortedByDueThenDataOrder() {
        val cards = (1..6).map { card(it) }
        val schedule = mapOf(
            1 to ScheduleEntry(0, today),
            2 to ScheduleEntry(1, today.minusDays(2)),
            3 to ScheduleEntry(1, today.plusDays(1)),
            5 to ScheduleEntry(2, today.minusDays(2)),
            6 to ScheduleEntry(2, today.plusDays(5)),
        )
        assertEquals(listOf(2, 5, 1), Scheduler.dueCards(cards, schedule, today).map { it.id })
        assertEquals(3, Scheduler.dueCount(cards, schedule, today))
        assertEquals(today.plusDays(1), Scheduler.nextDue(cards, schedule, today))
        assertNull(Scheduler.nextDue(cards.take(2), schedule, today))
    }

    // endregion

    // region Activity and streaks

    @Test fun streakEndsTodayOrYesterday() {
        val log = ActivityLog()
        assertEquals(0, log.streak(today))
        log.record(today.minusDays(1))
        log.record(today.minusDays(2), 3)
        log.record(today.minusDays(4))
        assertEquals(2, log.streak(today)) // nothing yet today: counts back from yesterday
        log.record(today)
        assertEquals(3, log.streak(today))
        assertEquals(0, log.streak(today.plusDays(2)))
        assertEquals(3, log.count(today.minusDays(2)))
        val days = log.lastDays(today, 14)
        assertEquals(14, days.size)
        assertEquals(today, days.last().first)
        assertEquals(today.minusDays(13), days.first().first)
        assertEquals(listOf(1, 0, 3, 1, 1), days.takeLast(5).map { it.second })
        assertEquals("🔥 3-day streak", streakLabel(3))
        assertNull(streakLabel(0))
    }

    // endregion

    // region Content diff

    @Test fun fnvMatchesReferenceVectors() {
        assertEquals("cbf29ce484222325", java.lang.Long.toHexString(ContentDiff.fnv1a64("")))
        assertEquals("af63dc4c8601ec8c", java.lang.Long.toHexString(ContentDiff.fnv1a64("a")))
        assertEquals("85944171f73967e8", java.lang.Long.toHexString(ContentDiff.fnv1a64("foobar")))
    }

    @Test fun diffFindsAddedUpdatedAndChangedQuestions() {
        val old = Fingerprints(mapOf(1 to "a", 2 to "b", 3 to "c"), mapOf("q1" to "x", "q2" to "y"))
        val new = Fingerprints(mapOf(1 to "a", 2 to "B", 4 to "d"), mapOf("q1" to "x", "q2" to "Y", "q3" to "z"))
        val d = ContentDiff.diff(old, new)
        assertEquals(setOf(4), d.added)
        assertEquals(setOf(2), d.updated)
        assertEquals(2, d.questionsChanged)
        assertTrue(ContentDiff.diff(new, new).isEmpty)
        // Hashes cover question, answer and hint only.
        assertEquals(ContentDiff.cardHash(card(1)), ContentDiff.cardHash(card(1).copy(topic = "Other")))
        assertFalse(ContentDiff.cardHash(card(1)) == ContentDiff.cardHash(card(1, hint = "new hint")))
        val fp = ContentDiff.fingerprints(TestData.data)
        assertEquals(182, fp.cards.size)
        assertEquals(84, fp.questions.size)
    }

    // endregion

    // region Widget and reminders

    @Test fun cardOfDayIsDeterministicAndPrefersDueCards() {
        val cards = listOf(card(30), card(10), card(20))
        // Sorted by id: 10, 20, 30; index = epochDay mod 3.
        val expected = listOf(10, 20, 30)[Math.floorMod(today.toEpochDay(), 3L).toInt()]
        assertEquals(expected, WidgetContent.cardOfDay(cards, emptyMap(), today)?.id)
        assertEquals(expected, WidgetContent.cardOfDay(cards.reversed(), emptyMap(), today)?.id)
        val next = WidgetContent.cardOfDay(cards, emptyMap(), today.plusDays(1))?.id
        assertFalse(expected == next)
        val schedule = mapOf(20 to ScheduleEntry(0, today), 30 to ScheduleEntry(1, today.plusDays(2)))
        assertEquals(20, WidgetContent.cardOfDay(cards, schedule, today)?.id)
        assertNull(WidgetContent.cardOfDay(emptyList(), emptyMap(), today))

        val log = ActivityLog(mapOf(today to 2, today.minusDays(1) to 1))
        val w = WidgetContent.build(cards, schedule, log, today, "t")
        assertEquals(1, w.dueCount)
        assertEquals(2, w.streak)
        assertEquals(20, w.cardOfDay?.id)
        assertTrue(w.sameContent(w.copy(updatedAt = "later")))
    }

    @Test fun reminderTextAndTiming() {
        assertEquals("12 cards are due. Keep your 3-day streak going.", ReminderText.body(12, 3))
        assertEquals("4 cards are due today.", ReminderText.body(4, 0))
        assertEquals("1 card is due today.", ReminderText.body(1, 0))
        assertEquals("A few minutes of revision keeps it fresh.", ReminderText.body(0, 5))
        val zone = ZoneId.of("Europe/London")
        val morning = ZonedDateTime.of(2026, 11, 10, 9, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 10, 18, 0, 0, 0, zone), ReminderText.nextTrigger(morning, 18, 0))
        val evening = ZonedDateTime.of(2026, 11, 10, 18, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 11, 18, 0, 0, 0, zone), ReminderText.nextTrigger(evening, 18, 0))
        assertEquals(ZonedDateTime.of(2026, 11, 11, 0, 1, 0, 0, zone), ReminderText.nextMidnight(evening))
    }

    // endregion

    // region Mock exam helpers

    private fun question(id: String, unit: String) =
        Question(id, 1, unit, "T", "Q $id", listOf("a", "b", "c", "d"), 1, "E", listOf("1"))

    @Test fun mockSelectionAndScoring() {
        val qs = (1..30).map { question("a$it", "U1") } + (1..5).map { question("b$it", "U2") } + (1..3).map { question("c$it", "U3") }
        val picked = MockExam.select(qs, listOf("U1", "U2"), 20, Random(3))
        assertEquals(20, picked.size)
        assertEquals(20, picked.map { it.id }.distinct().size)
        assertTrue(picked.all { it.unit in setOf("U1", "U2") })
        // Capped at what's available.
        assertEquals(8, MockExam.select(qs, listOf("U2", "U3"), 40, Random(3)).size)

        val a = MockExam.start(picked, listOf("U1", "U2"), 20 * 90, 1_000L, Random(1))
        assertEquals(1_000L + 1_800_000L, a.deadline)
        assertTrue(a.questions.all { it.order.sorted() == listOf(0, 1, 2, 3) })
        assertEquals(1800L, MockExam.remainingSeconds(a, 1_000L))
        assertEquals(1L, MockExam.remainingSeconds(a, 1_800_500L))
        assertEquals(0L, MockExam.remainingSeconds(a, 9_999_999L))
        assertFalse(MockExam.isExpired(a, 1_800_999L))
        assertTrue(MockExam.isExpired(a, 1_801_000L))

        val answered = a.copy(answers = mapOf(picked[0].id to 1, picked[1].id to 0, picked[2].id to 1))
        // Submitting late records the deadline as the end.
        val done = MockExam.submit(answered, 5_000_000L)
        assertEquals(a.deadline, done.submittedAt)
        assertEquals(1800L, MockExam.durationSeconds(done))
        val byId = qs.associateBy { it.id }
        val entry = MockExam.historyEntry(done, byId, "2026-11-12T10:00")
        assertEquals(2, entry.correct)
        assertEquals(20, entry.total)
        assertEquals(10, entry.percent)
        assertEquals(20, entry.perUnit.sumOf { it.asked })
        assertEquals(2, entry.perUnit.sumOf { it.correct })

        var history = emptyList<net.estopia.hncrevision.logic.MockHistoryEntry>()
        repeat(25) { history = MockExam.addToHistory(history, entry.copy(correct = it)) }
        assertEquals(20, history.size)
        assertEquals(24, history.first().correct)
        assertEquals("1:30", MockExam.formatDuration(90))
        assertEquals("1:00:05", MockExam.formatDuration(3605))
    }

    @Test fun presetTakesFivePerUnitFromRealData() {
        val picked = MockExam.selectPreset(TestData.data.questions, Random(7))
        assertEquals(20, picked.size)
        for (u in MockExam.PRESET_UNITS) assertEquals(5, picked.count { it.unit == u })
        assertEquals(20, picked.map { it.id }.distinct().size)
    }

    // endregion
}
