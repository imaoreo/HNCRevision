package net.estopia.hncrevision

import net.estopia.hncrevision.data.DataParser
import net.estopia.hncrevision.data.DataRepository
import net.estopia.hncrevision.data.DataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DataParserTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun parsesBundledFiles() {
        val d = TestData.data
        assertEquals(7, d.units.size)
        assertEquals(182, d.cards.size)
        assertEquals(84, d.questions.size)
        assertEquals(7, d.coverage.size)
        assertEquals(6, d.vlsm.size)
        assertTrue(d.vlsm.all { it.hints.size == 3 })
        assertTrue(d.questions.all { it.options.size == 4 && it.answer in 0..3 })
        assertEquals("Data Security", d.unit("J0H934")?.title)
        assertEquals("1. Warm-up".substring(3), d.vlsm[0].title)
        assertEquals("192.168.25.0/26", d.vlsm[0].rows[0].network)
        assertTrue(d.vlsm[5].hints[2].startsWith("After LAN B’s 64-address block"))
        assertTrue(d.units.all { d.coverage.containsKey(it.code) })
        assertTrue(d.cards.all { d.unit(it.unit) != null })
    }

    @Test fun skipsBadRecordsAndIgnoresUnknownFields() {
        val cards = """
            const UNITS = [{code: 'U1', title: 'Unit', extra: 1}];
            const CARDS = [
              {id: 1, q: 'Q', a: 'A', topic: 'T', unit: 'U1', outcomes: ['1'], hint: 'H', references: [], newField: {x: 1}},
              {id: 2, a: 'no question', topic: 'T', unit: 'U1'},
              {id: '3', q: 'Q3', a: 'A3', topic: 'T', unit: 'U1'},
            ];
            const COVERAGE = {U1: {outcomes: [{id: '1', title: 'One'}], source: 'https://x'}};
        """.trimIndent()
        val quiz = """const QUESTIONS = [
            {id: 'q1', cardId: 1, unit: 'U1', topic: 'T', q: 'Q?', options: ['a','b','c','d'], answer: 2, explanation: 'E', outcomes: ['1']},
            {id: 'q2', cardId: 1, unit: 'U1', topic: 'T', q: 'Bad', options: ['a','b'], answer: 5},
        ];"""
        val vlsm = "const vlsmExercises = []; const exerciseHints = [];"
        val d = DataParser.parse(cards, quiz, vlsm)
        assertEquals(listOf(1, 3), d.cards.map { it.id })
        assertEquals(listOf("q1"), d.questions.map { it.id })
        assertEquals("", d.cards[1].hint)
        assertEquals(emptyList<String>(), d.cards[1].outcomes)
        assertEquals("One", d.coverage["U1"]?.outcomes?.single()?.title)
    }

    @Test fun scriptUrlDiscovery() {
        val html = """<script src="cards.js?v=5"></script><script src="quiz.js?v=2"></script><script src="editor.js"></script><script src="app.js?v=10"></script>"""
        val urls = DataRepository.scriptUrls(html, DataRepository.SITE_URL)
        assertEquals(DataRepository.SITE_URL + "cards.js?v=5", urls["cards.js"])
        assertEquals(DataRepository.SITE_URL + "quiz.js?v=2", urls["quiz.js"])
        assertEquals(DataRepository.SITE_URL + "vlsm.js", urls["vlsm.js"]) // falls back to the bare name
    }

    @Test fun loadsBundledThenCachesSuccessfulRefresh() {
        val dir = tmp.newFolder()
        val html = """<script src="cards.js?v=7"></script><script src="quiz.js?v=3"></script><script src="vlsm.js?v=2"></script>"""
        val requested = mutableListOf<String>()
        var clock = 1_000L
        val repo = DataRepository(dir, { TestData.files }, fetch = { url ->
            requested += url
            when {
                url == DataRepository.SITE_URL -> html
                else -> TestData.files.getValue(url.substringAfterLast('/').substringBefore('?'))
            }
        }, clock = { clock })
        assertEquals(DataSource.BuiltIn, repo.loadInitial().source)
        val refreshed = repo.refresh()
        assertNotNull(refreshed)
        assertEquals(DataSource.Live(1_000L), refreshed!!.source)
        assertTrue(requested.contains(DataRepository.SITE_URL + "cards.js?v=7"))
        clock = 2_000L
        val again = DataRepository(dir, { error("bundled not needed") }, fetch = { throw IOException() })
        val loaded = again.loadInitial()
        assertEquals(DataSource.Saved(1_000L), loaded.source)
        assertEquals(182, loaded.data.cards.size)
        assertNull(again.refresh())
    }

    @Test fun refreshRejectsUnusableData() {
        val dir = tmp.newFolder()
        val repo = DataRepository(dir, { TestData.files }, fetch = { url ->
            if (url.endsWith("quiz.js")) "const QUESTIONS = [];" else if (url == DataRepository.SITE_URL) "" else TestData.files.getValue(url.substringAfterLast('/'))
        })
        assertNull(repo.refresh())
        assertEquals(DataSource.BuiltIn, repo.loadInitial().source)
    }
}
