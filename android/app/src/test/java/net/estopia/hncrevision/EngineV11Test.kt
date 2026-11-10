package net.estopia.hncrevision

import net.estopia.hncrevision.logic.CardMode
import net.estopia.hncrevision.logic.MemoryStore
import net.estopia.hncrevision.logic.ProgressStore
import net.estopia.hncrevision.logic.QuizMode
import net.estopia.hncrevision.logic.Rating
import net.estopia.hncrevision.logic.RevisionEngine
import net.estopia.hncrevision.logic.ScheduleEntry
import net.estopia.hncrevision.logic.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** A clock tests can move forward. */
class TestClock(var instant: Instant, private val zone: ZoneId = ZoneId.of("Europe/London")) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = TestClock(instant, zone)
    override fun instant(): Instant = instant
    fun advance(d: Duration) { instant = instant.plus(d) }
    fun advanceDays(n: Long) = advance(Duration.ofDays(n))
}

class EngineV11Test {

    private val data = TestData.data
    private val start = LocalDate.of(2026, 11, 10)
    private fun clock() = TestClock(Instant.parse("2026-11-10T10:00:00Z"))
    private fun engine(store: MemoryStore, clock: Clock = clock(), d: net.estopia.hncrevision.data.RevisionData = data) =
        RevisionEngine(d, ProgressStore(store), Random(42), clock)

    /** Exactly what 1.0 wrote to SharedPreferences. */
    private fun store10(): MemoryStore {
        val unit = data.cards.filter { it.unit == "J0H934" }
        val (a, b, c) = Triple(unit[0].id, unit[1].id, unit[2].id)
        val q = data.questions.first { it.unit == "J0H934" }.id
        return MemoryStore(mutableMapOf(
            "progress" to """{"$a":"known","$b":"review","$c":"review"}""",
            "quizProgress" to """{"$q":{"correct":true,"choice":1,"answeredAt":"2026-11-01T09:00:00Z"}}""",
            "session" to """{"cardSeen":[$a,$b,$c],"quizSeen":["$q"],"ratingFilter":"review",""" +
                """"view":{"unit":"J0H934","outcome":"all","topic":"All topics","tab":"study","cardMode":"review"},""" +
                """"study":{"scope":"[\"J0H934\",\"all\",\"All topics\",\"study\",\"\"]","ids":[${unit.drop(3).joinToString(",") { it.id.toString() }}],"index":1,"revealed":true}}""",
            "vlsmWorking" to """{"0":"my working"}""",
            "readAloud" to "on",
            "dyslexiaReading" to "off",
        ))
    }

    @Test fun loads10SavedDataAndMigratesOnce() {
        val store = store10()
        val unit = data.cards.filter { it.unit == "J0H934" }
        val clock = clock()
        val e = engine(store, clock)
        // 1.0 values still load.
        assertEquals(Rating.KNOWN, e.rating(unit[0].id))
        assertEquals(Rating.REVIEW, e.rating(unit[1].id))
        assertEquals(true, e.quizResult(data.questions.first { it.unit == "J0H934" }.id)?.correct)
        assertEquals(CardMode.RATED, e.cardMode)
        assertEquals(1, e.snapshot().study.index)
        assertTrue(e.snapshot().study.revealed)
        assertEquals("my working", ProgressStore(store).loadVlsmWorking()[0])
        assertTrue(ProgressStore(store).getFlag(ProgressStore.SPEECH))
        // Migration: Revisit → box 0 due today; Confident → box 1 due in 3 days.
        assertEquals(ScheduleEntry(1, start.plusDays(3)), e.scheduleOf(unit[0].id))
        assertEquals(ScheduleEntry(0, start), e.scheduleOf(unit[1].id))
        assertEquals(ScheduleEntry(0, start), e.scheduleOf(unit[2].id))
        assertNull(e.scheduleOf(unit[3].id))
        assertEquals(2, e.snapshot().stats.due)
        assertEquals(2, e.snapshot().dueAll)
        assertNotNull(store.values["schedule"])
        // New keys default safely.
        assertFalse(e.isStarred(unit[0].id))
        assertNull(e.note(unit[0].id))
        assertEquals(0, e.streak())
        assertEquals(QuizMode.PRACTICE, e.quizMode)
        // First load just records fingerprints: nothing is "new".
        assertEquals(0, e.snapshot().newCardCount)

        // Runs once: a later launch keeps the saved schedule rather than migrating again.
        clock.advanceDays(10)
        val later = engine(store, clock)
        assertEquals(ScheduleEntry(1, start.plusDays(3)), later.scheduleOf(unit[0].id))
        assertEquals(3, later.snapshot().stats.due)
        // Even with no schedule entries (after a reset), the migration doesn't run again.
        later.resetProgress()
        store.values["progress"] = """{"${unit[5].id}":"known"}"""
        val again = engine(store, clock)
        assertNull(again.scheduleOf(unit[5].id))
    }

    @Test fun corruptNewKeysFallBackToDefaults() {
        val store = store10()
        listOf("starred", "notes", "activity", "bestStreak", "mockHistory", "mockAttempt", "fingerprints",
            "newCardIds", "lastChange", "reminder", "widgetSnapshot").forEach { store.values[it] = "{not json" }
        store.values["schedule"] = "[broken"
        val e = engine(store)
        val unit = data.cards.filter { it.unit == "J0H934" }
        // An unreadable schedule is treated as missing, so the ratings migrate.
        assertEquals(ScheduleEntry(0, start), e.scheduleOf(unit[1].id))
        val s = e.snapshot()
        assertEquals(0, s.streak)
        assertNull(s.mock.run)
        assertTrue(s.mock.setup.history.isEmpty())
        assertEquals(0, s.newCardCount)
        val ps = ProgressStore(store)
        assertFalse(ps.loadReminder().enabled)
        assertEquals(18, ps.loadReminder().hour)
        assertNull(ps.loadWidgetSnapshot())
        // Partly bad entries keep the good ones.
        store.values["notes"] = """{"5":"keep","x":"bad","6":42}"""
        assertEquals(mapOf(5 to "keep"), ProgressStore(store).loadNotes())
    }

    @Test fun dueModeDeckRatingAndEmptyState() {
        val store = MemoryStore()
        val clock = clock()
        val e = engine(store, clock)
        e.setUnit("J0HL34")
        val cards = e.selected()
        // Rate three cards: Revisit, Confident, Revisit.
        e.reveal(CardMode.STUDY); e.rate(CardMode.STUDY, Rating.REVIEW)
        e.reveal(CardMode.STUDY); e.rate(CardMode.STUDY, Rating.KNOWN)
        e.reveal(CardMode.STUDY); e.rate(CardMode.STUDY, Rating.REVIEW)
        assertEquals(ScheduleEntry(0, start.plusDays(1)), e.scheduleOf(cards[0].id))
        assertEquals(ScheduleEntry(1, start.plusDays(3)), e.scheduleOf(cards[1].id))
        e.setCardMode(CardMode.DUE)
        val empty = e.snapshot().deck(CardMode.DUE)
        assertEquals(0, empty.size)
        assertEquals(start.plusDays(1), empty.nextDue)

        clock.advanceDays(1)
        e.setCardMode(CardMode.DUE)
        val due = e.snapshot().deck(CardMode.DUE)
        assertEquals(cards[0].id, due.current!!.card.id)
        assertEquals(2, due.size)
        assertEquals(2, e.snapshot().stats.due)
        e.reveal(CardMode.DUE)
        e.rate(CardMode.DUE, Rating.KNOWN) // no longer due: removed
        val after = e.snapshot().deck(CardMode.DUE)
        assertEquals(1, after.size)
        assertEquals(cards[2].id, after.current!!.card.id)
        assertEquals(ScheduleEntry(1, start.plusDays(4)), e.scheduleOf(cards[0].id))
        e.reveal(CardMode.DUE)
        e.rate(CardMode.DUE, Rating.REVIEW)
        assertEquals(0, e.snapshot().deck(CardMode.DUE).size)
        assertEquals(0, e.snapshot().dueAll)
        // The 1.0 rating is still written.
        assertEquals(Rating.REVIEW, e.rating(cards[2].id))
    }

    @Test fun dueDeckSortsByDueThenDataOrder() {
        val store = MemoryStore()
        val clock = clock()
        val e = engine(store, clock)
        e.setUnit("J0HL34")
        val cards = e.selected()
        // Card 0 Revisit on day 0 (due day 1); card 1 Revisit on day 1 (due day 2); on day 3 both due, card 0 first.
        e.reveal(CardMode.STUDY); e.rate(CardMode.STUDY, Rating.REVIEW)
        clock.advanceDays(1)
        e.reveal(CardMode.STUDY); e.rate(CardMode.STUDY, Rating.REVIEW)
        clock.advanceDays(2)
        e.setCardMode(CardMode.DUE)
        e.move(CardMode.DUE, 1)
        assertEquals(cards[1].id, e.snapshot().deck(CardMode.DUE).current!!.card.id)
        assertEquals(2, e.snapshot().deck(CardMode.DUE).size)
        assertFalse(e.snapshot().deck(CardMode.DUE).usesRounds)
    }

    @Test fun streakAndActivityFromRatingsAndQuiz() {
        val store = MemoryStore()
        val clock = clock()
        val e = engine(store, clock)
        e.reveal(CardMode.STUDY); e.rate(CardMode.STUDY, Rating.KNOWN)
        assertEquals(1, e.snapshot().streak)
        clock.advanceDays(1)
        assertEquals(1, e.snapshot().streak) // yesterday still counts until today ends
        e.setTab(Tab.QUIZ)
        e.choose(0); e.check()
        assertEquals(2, e.snapshot().streak)
        assertEquals(2, e.snapshot().bestStreak)
        val days = e.snapshot().progress.days
        assertEquals(listOf(1, 1), days.takeLast(2).map { it.second })
        clock.advanceDays(3)
        val reopened = engine(store, clock)
        assertEquals(0, reopened.snapshot().streak)
        assertEquals(2, reopened.snapshot().bestStreak)
    }

    @Test fun starsNotesAndStarredMode() {
        val store = MemoryStore()
        val e = engine(store)
        val cards = e.selected()
        e.toggleStar(cards[3].id)
        e.toggleStar(cards[1].id)
        e.setCardMode(CardMode.STARRED)
        val d = e.snapshot().deck(CardMode.STARRED)
        assertEquals(listOf(cards[1].id), listOfNotNull(d.current?.card?.id))
        assertEquals(2, d.size)
        assertTrue(d.current!!.starred)
        e.setNote(cards[1].id, "  my note  ")
        e.setNote(cards[2].id, "x".repeat(2500))
        assertEquals(2000, e.note(cards[2].id)!!.length)
        e.setNote(cards[2].id, "   ")
        assertNull(e.note(cards[2].id))
        val reopened = engine(store)
        assertTrue(reopened.isStarred(cards[3].id))
        assertEquals("  my note  ", reopened.note(cards[1].id))
        assertEquals("  my note  ", reopened.snapshot().selected.first { it.card.id == cards[1].id }.note)
        reopened.toggleStar(cards[3].id)
        assertFalse(reopened.isStarred(cards[3].id))

        // Reset keeps stars and notes unless asked.
        reopened.resetProgress()
        assertTrue(reopened.isStarred(cards[1].id))
        assertEquals("  my note  ", reopened.note(cards[1].id))
        reopened.resetProgress(starsAndNotes = true)
        assertFalse(reopened.isStarred(cards[1].id))
        assertNull(engine(store).note(cards[1].id))
    }

    @Test fun resetClearsSchedules() {
        val store = MemoryStore()
        val e = engine(store)
        val id = e.snapshot().study.current!!.card.id
        e.reveal(CardMode.STUDY); e.rate(CardMode.STUDY, Rating.REVIEW)
        assertNotNull(e.scheduleOf(id))
        e.resetProgress()
        assertNull(e.scheduleOf(id))
        assertNull(engine(store).scheduleOf(id))
    }

    @Test fun newContentIsDetectedAndClearedByViewing() {
        val store = MemoryStore()
        val clock = clock()
        engine(store, clock) // first load: fingerprints saved silently
        val changedId = data.cards[5].id
        val edited = data.copy(
            cards = data.cards.map { if (it.id == changedId) it.copy(a = it.a + " (updated)") else it } +
                data.cards[0].copy(id = 9999, q = "Brand new card"),
            questions = data.questions.map { if (it.id == data.questions[0].id) it.copy(explanation = "new") else it },
        )
        clock.advanceDays(2)
        val e = engine(store, clock, edited)
        val s = e.snapshot()
        assertEquals(2, s.newCardCount)
        assertEquals(1, s.lastChange!!.added)
        assertEquals(1, s.lastChange.updated)
        assertEquals(1, s.lastChange.questionsChanged)
        assertEquals(start.plusDays(2), s.lastChange.date)
        // New mode: those cards in data order, ignoring the filter.
        e.setUnit("FR2435")
        e.setCardMode(CardMode.NEW)
        val d = e.snapshot().deck(CardMode.NEW)
        assertEquals(2, d.size)
        assertEquals(changedId, d.current!!.card.id)
        e.reveal(CardMode.NEW)
        assertEquals(1, e.snapshot().newCardCount)
        // Loading the same data again changes nothing.
        val again = engine(store, clock, edited)
        assertEquals(1, again.snapshot().newCardCount)
        again.dismissNewCards()
        assertEquals(0, again.snapshot().newCardCount)
        assertEquals(CardMode.STUDY, again.cardMode)
        // A live refresh with changes adds them.
        // A live refresh with changes adds them: the edited card changing back counts as updated;
        // the removed card 9999 isn't "new".
        again.replaceData(data)
        assertEquals(1, again.snapshot().newCardCount)
        assertEquals(0, again.snapshot().lastChange!!.added)
        assertEquals(1, again.snapshot().lastChange!!.updated)
    }

    @Test fun openCardFromWidgetShowsOneCardDeck() {
        val store = MemoryStore()
        val e = engine(store)
        val id = data.cards.last().id
        e.setTab(Tab.MORE)
        e.openCard(id)
        assertEquals(Tab.STUDY, e.tab)
        assertEquals(CardMode.CARD, e.cardMode)
        val d = e.snapshot().deck(CardMode.CARD)
        assertEquals(1, d.size)
        assertEquals(id, d.current!!.card.id)
        // Resumes after relaunch.
        assertEquals(id, engine(store).snapshot().deck(CardMode.CARD).current!!.card.id)
        e.reveal(CardMode.CARD)
        e.rate(CardMode.CARD, Rating.KNOWN)
        assertEquals(CardMode.STUDY, e.cardMode)
        e.openCard(-5) // unknown ids are ignored
        assertEquals(CardMode.STUDY, e.cardMode)
        e.openDue()
        assertEquals(CardMode.DUE, e.cardMode)
    }

    @Test fun mockExamFlowHistoryAndIsolationFromPractice() {
        val store = MemoryStore()
        val clock = clock()
        val e = engine(store, clock)
        e.setQuizMode(QuizMode.MOCK)
        val setup = e.snapshot().mock.setup
        assertEquals(setOf("J0H934"), setup.defaultUnits)
        assertEquals(20, setup.presetAvailable)
        e.startMock(listOf("J0H934", "J0HL34"), 10, timed = true)
        val run = e.snapshot().mock.run!!
        assertEquals(10, run.total)
        assertEquals(10, run.unanswered)
        assertEquals(clock.millis() + 900_000L, run.deadline)
        assertTrue(e.snapshot().data.questions.first { it.id == run.current.question.id }.unit in setOf("J0H934", "J0HL34"))
        // Answer, change, jump around.
        val q0 = run.current.question
        e.mockChoose((q0.answer + 1) % 4)
        e.mockChoose(q0.answer)
        e.mockGoTo(4)
        val q4 = e.snapshot().mock.run!!.current.question
        e.mockChoose((q4.answer + 1) % 4)
        assertEquals(4, e.snapshot().mock.run!!.index)
        assertEquals(8, e.snapshot().mock.run!!.unanswered)
        // Two questions answered → two activity actions (changes don't count).
        assertEquals(2, e.snapshot().progress.days.last().second)
        // Practice is untouched.
        assertNull(e.quizResult(q0.id))
        assertEquals(0, e.snapshot().quiz.savedTotal)

        // Resumes after relaunch with the time still counting.
        clock.advance(Duration.ofMinutes(5))
        val reopened = engine(store, clock)
        assertEquals(QuizMode.MOCK, reopened.quizMode)
        assertEquals(4, reopened.snapshot().mock.run!!.index)
        assertEquals(run.deadline, reopened.snapshot().mock.run!!.deadline)

        reopened.mockSubmit()
        val result = reopened.snapshot().mock.result!!
        assertEquals(1, result.correct)
        assertEquals(10, result.total)
        assertEquals(10, result.percent)
        assertEquals(300L, result.durationSeconds)
        assertFalse(result.timedOut)
        assertEquals(10, result.items.size)
        assertEquals("Not answered", result.items[1].yourAnswer)
        assertEquals(10, result.perUnit.sumOf { it.score.asked })
        val history = reopened.snapshot().mock.setup.history
        assertEquals(1, history.size)
        assertEquals("2026-11-10T10:05", history[0].date)
        assertEquals(listOf("J0H934", "J0HL34"), history[0].units)
        assertEquals(1, history[0].correct)
        assertTrue(reopened.snapshot().progress.units.any { it.latestMock != null })
        reopened.closeMock()
        assertNull(reopened.snapshot().mock.result)
        assertEquals(1, engine(store, clock).snapshot().mock.setup.history.size)
    }

    @Test fun mockSubmitsAutomaticallyAtDeadlineEvenWhenClosed() {
        val store = MemoryStore()
        val clock = clock()
        val e = engine(store, clock)
        e.startMockPreset()
        val run = e.snapshot().mock.run!!
        assertEquals(20, run.total)
        assertEquals(clock.millis() + 30 * 60_000L, run.deadline)
        clock.advance(Duration.ofMinutes(10))
        assertFalse(e.tick())
        clock.advance(Duration.ofMinutes(25))
        // App relaunched after the deadline: submitted at the deadline.
        val reopened = engine(store, clock)
        val r = reopened.snapshot().mock.result!!
        assertTrue(r.timedOut)
        assertEquals(1800L, r.durationSeconds)
        assertEquals(4, r.perUnit.size)
        assertEquals(1, reopened.snapshot().mock.setup.history.size)
        // And the in-app timer path.
        reopened.closeMock()
        reopened.startMock(listOf("J0H934"), 10, timed = true)
        clock.advance(Duration.ofMinutes(16))
        assertTrue(reopened.tick())
        assertNotNull(reopened.snapshot().mock.result)
        reopened.mockChoose(0) // ignored once submitted
        assertEquals(2, reopened.snapshot().mock.setup.history.size)
    }

    @Test fun mockDroppedWhenQuestionsDisappear() {
        val store = MemoryStore()
        val e = engine(store)
        e.startMock(listOf("J0H934"), 10, timed = false)
        val id = e.snapshot().mock.run!!.current.question.id
        assertNull(e.snapshot().mock.run!!.deadline)
        val trimmed = data.copy(questions = data.questions.filter { it.id != id })
        assertNull(engine(store, d = trimmed).snapshot().mock.run)
    }

    @Test fun widgetSnapshotFromEngine() {
        val store = MemoryStore()
        val e = engine(store)
        val w = e.widgetSnapshot()
        assertEquals(0, w.dueCount)
        assertNotNull(w.cardOfDay)
        e.reveal(CardMode.STUDY); e.rate(CardMode.STUDY, Rating.REVIEW)
        assertEquals(1, e.widgetSnapshot().streak)
        ProgressStore(store).saveWidgetSnapshot(w)
        assertEquals(w, ProgressStore(store).loadWidgetSnapshot())
    }
}
