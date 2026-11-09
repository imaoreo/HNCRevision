package net.estopia.hncrevision

import net.estopia.hncrevision.logic.ALL
import net.estopia.hncrevision.logic.ALL_TOPICS
import net.estopia.hncrevision.logic.CardMode
import net.estopia.hncrevision.logic.MemoryStore
import net.estopia.hncrevision.logic.ProgressStore
import net.estopia.hncrevision.logic.Rating
import net.estopia.hncrevision.logic.RatingFilter
import net.estopia.hncrevision.logic.RevisionEngine
import net.estopia.hncrevision.logic.Tab
import net.estopia.hncrevision.logic.nextRound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EngineTest {

    private val data = TestData.data
    private fun engine(store: MemoryStore = MemoryStore()) =
        RevisionEngine(data, ProgressStore(store), Random(42), now = { "2026-11-09T12:00:00Z" })

    @Test fun nextRoundReturnsUnseenInOrderThenShufflesAFreshRound() {
        val items = (1..10).toList()
        val seen = mutableSetOf(2, 4)
        assertEquals(listOf(1, 3, 5, 6, 7, 8, 9, 10), nextRound(items, seen, Random(1)) { it })
        assertEquals(setOf(2, 4), seen)
        seen.addAll(items)
        seen.add(99)
        val round = nextRound(items, seen, Random(1)) { it }
        assertEquals(items.toSet(), round.toSet())
        assertEquals(setOf(99), seen)
        assertEquals(emptyList<Int>(), nextRound(emptyList<Int>(), seen, Random(1)) { it })
    }

    @Test fun defaultFiltersAndTopics() {
        val e = engine()
        assertEquals("J0H934", e.unit)
        val unitCards = data.cards.filter { it.unit == "J0H934" }
        assertEquals(unitCards, e.selected())
        val topics = e.topics()
        assertEquals(ALL_TOPICS to unitCards.size, topics.first())
        assertEquals(unitCards.map { it.topic }.distinct(), topics.drop(1).map { it.first })
        assertEquals(unitCards.size, topics.drop(1).sumOf { it.second })
        val s = e.snapshot()
        assertEquals("Data Security · SCQF level 7", s.filters.unitInfo)
        assertTrue(s.filters.outcomeEnabled)
        assertEquals("All outcomes", s.filters.outcomes.first().second)
        assertTrue(s.filters.outcomes[1].second.startsWith("Outcome 1 · "))
    }

    @Test fun filtersByOutcomeAndTopicAndResets() {
        val e = engine()
        e.setOutcome("2")
        assertTrue(e.selected().isNotEmpty())
        assertTrue(e.selected().all { "2" in it.outcomes && it.unit == "J0H934" })
        assertTrue(e.selectedQuestions().all { "2" in it.outcomes && it.unit == "J0H934" })
        val topic = e.topics()[1].first
        e.setTopic(topic)
        assertTrue(e.selected().all { it.topic == topic })
        e.setOutcome(ALL)
        assertEquals(ALL_TOPICS, e.topic)
        e.setTopic(topic)
        e.setUnit("FR2435")
        assertEquals(ALL, e.outcome)
        assertEquals(ALL_TOPICS, e.topic)
        assertEquals("Networking Technology · SCQF level 8", e.snapshot().filters.unitInfo)
        e.setUnit("J0J734")
        assertEquals("All sections", e.snapshot().filters.outcomes.first().second)
        assertFalse(e.snapshot().filters.outcomes[1].second.startsWith("Outcome"))
        e.setUnit(ALL)
        assertEquals(182, e.selected().size)
        assertEquals(84, e.selectedQuestions().size)
        val f = e.snapshot().filters
        assertFalse(f.outcomeEnabled)
        assertEquals("Study all seven units together.", f.unitInfo)
        assertEquals("https://www.sqa.org.uk/files_ccc/GP1015.pdf", f.unitSource)
    }

    @Test fun studyRoundsRevealRateAndNextRound() {
        val e = engine()
        e.setUnit("J0HL34")
        val n = e.selected().size
        val s0 = e.snapshot().study
        assertEquals(n, s0.size)
        assertEquals(e.selected().first().id, s0.current?.card?.id) // first round in data order
        assertEquals("Not rated", s0.current?.status)
        e.reveal(CardMode.STUDY)
        assertTrue(e.snapshot().study.revealed)
        e.rate(CardMode.STUDY, Rating.KNOWN)
        val s1 = e.snapshot()
        assertEquals(1, s1.study.index)
        assertFalse(s1.study.revealed)
        assertEquals(1, s1.study.confident)
        assertEquals(1, s1.stats.confident)
        assertEquals(1, s1.study.tracker.seen.size)
        // Rating requires a revealed card.
        e.rate(CardMode.STUDY, Rating.REVIEW)
        assertNull(e.rating(s1.study.current!!.card.id))
        // Move to the end; moving forward from the last card starts the next round.
        repeat(n - 1) { e.move(CardMode.STUDY, 1) }
        val fresh = e.snapshot().study
        assertEquals(0, fresh.index)
        assertEquals(n, fresh.size)
        assertEquals(n, fresh.tracker.unseen.size)
    }

    @Test fun previousAtStartDoesNothing() {
        val e = engine()
        e.move(CardMode.STUDY, -1)
        assertEquals(0, e.snapshot().study.index)
    }

    @Test fun ratedDeckFiltersAndDropsExcludedRatings() {
        val e = engine()
        e.setUnit("J0HL34")
        val ids = e.selected().take(3).map { it.id }
        e.reveal(CardMode.STUDY); e.rate(CardMode.STUDY, Rating.KNOWN)
        e.reveal(CardMode.STUDY); e.rate(CardMode.STUDY, Rating.REVIEW)
        e.reveal(CardMode.STUDY); e.rate(CardMode.STUDY, Rating.REVIEW)
        e.setCardMode(CardMode.RATED)
        e.restart(CardMode.RATED)
        assertEquals(3, e.snapshot().rated.size)
        e.setRatingFilter(RatingFilter.REVIEW)
        val r = e.snapshot().rated
        assertEquals(2, r.size)
        assertEquals(ids[1], r.current!!.card.id)
        e.reveal(CardMode.RATED)
        e.rate(CardMode.RATED, Rating.KNOWN) // excluded by "Revisit only": removed from the deck
        val after = e.snapshot().rated
        assertEquals(1, after.size)
        assertEquals(ids[2], after.current!!.card.id)
        assertEquals("Revisit", after.current.status)
    }

    @Test fun shuffleUsesUnseenAndRestartClearsSeen() {
        val e = engine()
        e.setUnit("J0HL34")
        val n = e.selected().size
        e.reveal(CardMode.STUDY)
        e.move(CardMode.STUDY, 1)
        e.shuffle(CardMode.STUDY)
        assertEquals(n - 1, e.snapshot().study.size)
        e.restart(CardMode.STUDY)
        assertEquals(n, e.snapshot().study.size)
        assertEquals(0, e.snapshot().study.tracker.seen.size)
    }

    @Test fun quizScoringAndPersistence() {
        val store = MemoryStore()
        val e = engine(store)
        e.setTab(Tab.QUIZ)
        val qs = e.selectedQuestions()
        val v0 = e.snapshot().quiz
        assertEquals(qs.size, v0.size)
        val q = v0.current!!
        assertEquals(qs.first().id, q.question.id)
        assertEquals(setOf(0, 1, 2, 3), q.options.map { it.original }.toSet())
        assertEquals(listOf("A", "B", "C", "D"), q.options.map { it.letter })
        // Next is blocked until checked.
        e.quizNext()
        assertEquals(0, e.snapshot().quiz.index)
        e.check() // nothing chosen yet
        assertFalse(e.snapshot().quiz.current!!.checked)
        val wrong = (q.question.answer + 1) % 4
        e.choose(wrong)
        e.check()
        e.choose(q.question.answer) // locked after checking: the first checked answer counts
        val v1 = e.snapshot().quiz
        assertTrue(v1.current!!.checked)
        assertEquals(wrong, v1.current.choice)
        assertFalse(v1.current.isCorrect)
        assertEquals(0, v1.correctSoFar)
        assertEquals(1, v1.answeredCount)
        assertEquals(1, v1.savedTotal)
        assertEquals(1, v1.tracker.seen.size)
        e.quizNext()
        val q2 = e.snapshot().quiz.current!!
        e.choose(q2.question.answer); e.check()
        assertEquals(1, e.snapshot().quiz.correctSoFar)
        e.finishQuiz()
        val res = e.snapshot().quiz.results!!
        assertEquals(1, res.correct)
        assertEquals(2, res.answered)
        assertEquals(1, res.wrong)
        assertEquals(qs.size - 2, res.unanswered)
        assertEquals(listOf(false, true), res.items.map { it.correct })

        // Reopening restores results, saved answers and the deck.
        val reopened = engine(store)
        assertEquals(Tab.QUIZ, reopened.tab)
        assertNotNull(reopened.snapshot().quiz.results)
        assertEquals(false, reopened.quizResult(q.question.id)?.correct)
        assertEquals(wrong, reopened.quizResult(q.question.id)?.choice)
        assertEquals("2026-11-09T12:00:00Z", reopened.quizResult(q.question.id)?.answeredAt)

        reopened.retryIncorrect()
        val retry = reopened.snapshot().quiz
        assertEquals(1, retry.size)
        assertEquals(q.question.id, retry.current!!.question.id)
        assertNull(retry.results)

        reopened.finishQuiz()
        reopened.continueUnanswered() // the retried question is still unanswered
        assertNull(reopened.snapshot().quiz.results)
        reopened.quizNextRound()
        // Two questions were seen, so the next round is the remaining unseen ones.
        assertEquals(qs.size - 2, reopened.snapshot().quiz.size)
    }

    @Test fun resumesStudyDeckAndDropsItWhenIdsDisappear() {
        val store = MemoryStore()
        val e = engine(store)
        e.move(CardMode.STUDY, 1)
        e.move(CardMode.STUDY, 1)
        e.reveal(CardMode.STUDY)
        val id = e.snapshot().study.current!!.card.id
        val reopened = engine(store)
        assertEquals(2, reopened.snapshot().study.index)
        assertTrue(reopened.snapshot().study.revealed)
        assertEquals(id, reopened.snapshot().study.current!!.card.id)

        val trimmed = data.copy(cards = data.cards.filter { it.id != id })
        val e3 = RevisionEngine(trimmed, ProgressStore(store), Random(1))
        assertEquals(0, e3.snapshot().study.index)
        assertFalse(e3.snapshot().study.current!!.card.id == id)
    }

    @Test fun resetClearsEverything() {
        val store = MemoryStore()
        val e = engine(store)
        e.reveal(CardMode.STUDY); e.rate(CardMode.STUDY, Rating.KNOWN)
        e.setTab(Tab.QUIZ)
        e.choose(0); e.check()
        e.resetProgress()
        val s = e.snapshot()
        assertEquals(0, s.stats.confident)
        assertEquals(0, s.quiz.savedTotal)
        assertEquals(0, s.study.tracker.seen.size)
        val reopened = engine(store)
        assertEquals(0, reopened.snapshot().stats.confident)
    }

    @Test fun referencesAddLegislationAndSpecOnceEach() {
        val e = engine()
        val card = data.cards.first { it.legalSource != null }
        val refs = e.cardReferences(card)
        assertEquals(refs.map { it.url }.distinct(), refs.map { it.url })
        assertTrue(refs.any { it.url == card.legalSource })
        assertEquals("SQA · Unit specification and outcomes", refs.last().label)
        assertEquals(data.coverage[card.unit]?.source, refs.last().url)
        val q = data.questions.first()
        assertEquals(data.card(q.cardId)!!.references.first().url, e.questionReferences(q).first().url)
    }

    @Test fun outcomeLabels() {
        val e = engine()
        assertEquals("Outcomes 1, 2", e.outcomeLabel("J0H934", listOf("1", "2")))
        assertEquals("Sections 1", e.outcomeLabel("J0J734", listOf("1")))
    }
}
