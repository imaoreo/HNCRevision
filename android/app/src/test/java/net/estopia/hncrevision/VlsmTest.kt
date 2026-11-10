package net.estopia.hncrevision

import net.estopia.hncrevision.data.VlsmRequest
import net.estopia.hncrevision.logic.DrillKind
import net.estopia.hncrevision.logic.Ipv4
import net.estopia.hncrevision.logic.VlsmChecker
import net.estopia.hncrevision.logic.VlsmDifficulty
import net.estopia.hncrevision.logic.VlsmDrills
import net.estopia.hncrevision.logic.VlsmGenerator
import net.estopia.hncrevision.logic.VlsmSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VlsmTest {

    @Test fun ipv4Helpers() {
        assertEquals(0xC0A81900L, Ipv4.parse("192.168.25.0"))
        assertEquals("192.168.25.0", Ipv4.format(0xC0A81900L))
        assertNull(Ipv4.parse("192.168.1"))
        assertNull(Ipv4.parse("192.168.1.256"))
        assertNull(Ipv4.parse("a.b.c.d"))
        assertEquals("255.255.255.192", Ipv4.maskString(26))
        assertEquals("255.255.240.0", Ipv4.maskString(20))
        assertEquals("0.0.0.0", Ipv4.maskString(0))
        assertEquals(26, Ipv4.prefixOf("255.255.255.192"))
        assertNull(Ipv4.prefixOf("255.0.255.0"))
        assertEquals(0xC0A81980L to 25, Ipv4.parseCidr("192.168.25.128/25"))
    }

    @Test fun solverBlockSizes() {
        assertEquals(4L, VlsmSolver.blockSize(1))
        assertEquals(4L, VlsmSolver.blockSize(2))
        assertEquals(8L, VlsmSolver.blockSize(3))
        assertEquals(64L, VlsmSolver.blockSize(62))
        assertEquals(128L, VlsmSolver.blockSize(63))
        assertEquals(26, VlsmSolver.prefixFor(60))
        assertEquals(30, VlsmSolver.prefixFor(2))
    }

    /** The solver must reproduce the worked answers of all six exercises on the site exactly. */
    @Test fun solverReproducesSiteExercises() {
        val exercises = TestData.data.vlsm
        assertEquals(6, exercises.size)
        for (e in exercises) {
            val solved = VlsmSolver.solve(e.parent, e.requests)
            assertEquals("Exercise ${e.title}", e.rows, solved)
            assertTrue(VlsmSolver.fits(e.parent, solved))
        }
    }

    @Test fun tiesBreakAlphabetically() {
        val rows = VlsmSolver.solve("10.0.0.0/24", listOf(VlsmRequest("LAN C", 10), VlsmRequest("LAN A", 10), VlsmRequest("LAN B", 50)))
        assertEquals(listOf("LAN B", "LAN A", "LAN C"), rows.map { it.lan })
        assertEquals(listOf("10.0.0.0/26", "10.0.0.64/28", "10.0.0.80/28"), rows.map { it.network })
        assertFalse(VlsmSolver.fits("10.0.0.0/30", rows))
    }

    @Test fun generatedExercisesAlwaysFitTheirParent() {
        for (difficulty in VlsmDifficulty.entries) {
            for (seed in 0 until 300) {
                val e = VlsmGenerator.generate(difficulty, Random(seed * 31 + difficulty.ordinal))
                val (address, prefix) = Ipv4.parseCidr(e.parent)!!
                // Aligned private parent of the right size.
                assertEquals(e.parent, address, Ipv4.network(address, prefix))
                assertTrue(e.parent, e.parent.startsWith("192.168.") || e.parent.startsWith("10.") ||
                    (e.parent.startsWith("172.") && e.parent.split('.')[1].toInt() in 16..31))
                val lans = e.requests.size
                when (difficulty) {
                    VlsmDifficulty.EASY -> { assertEquals(24, prefix); assertEquals(3, lans) }
                    VlsmDifficulty.MEDIUM -> { assertEquals(24, prefix); assertTrue(lans in 4..5) }
                    VlsmDifficulty.HARD -> { assertTrue(prefix in 22..23); assertTrue(lans in 5..6) }
                }
                val maxHosts = Ipv4.blockSize(prefix) / 2 - 2
                assertTrue(e.requests.all { it.hosts in 2..maxHosts })
                assertEquals(e.requests.map { it.lan }, (0 until lans).map { "LAN ${'A' + it}" })
                assertEquals(VlsmSolver.solve(e.parent, e.requests), e.rows)
                assertTrue("${e.parent} ${e.requests}", VlsmSolver.fits(e.parent, e.rows))
                // Blocks are back to back, inside the parent, network-aligned.
                var next = address
                for (r in e.rows) {
                    val (n, p) = Ipv4.parseCidr(r.network)!!
                    assertEquals(next, n)
                    assertEquals(n, Ipv4.network(n, p))
                    next = Ipv4.parse(r.broadcast)!! + 1
                }
                assertTrue(next - 1 <= Ipv4.broadcast(address, prefix))
                assertEquals(3, e.hints.size)
            }
        }
    }

    @Test fun checkerTrimsSpacesAndMarksFields() {
        val row = TestData.data.vlsm[0].rows[0]
        val answers = listOf(" 192.168.25.0 / 26 ", "255.255.255.192", "192.168.25.1", "192.168.25.61", "192.168.25.63")
        assertEquals(listOf(true, true, true, false, true), VlsmChecker.check(row, answers))
        // The prefix is required on the network field.
        assertFalse(VlsmChecker.check(row, listOf("192.168.25.0"))[0])
        assertEquals(4 to 10, VlsmChecker.score(TestData.data.vlsm[0].rows.take(2), listOf(answers)))
    }

    @Test fun drillsAreCorrectForEveryKind() {
        val random = Random(11)
        val seen = mutableSetOf<DrillKind>()
        repeat(500) {
            val q = VlsmDrills.question(random)
            seen += q.kind
            assertTrue(q.answer, VlsmDrills.isCorrect(q, " ${q.answer} "))
            assertFalse(VlsmDrills.isCorrect(q, "nope"))
            assertTrue(q.working.isNotBlank())
        }
        assertEquals(DrillKind.entries.toSet(), seen)
        assertEquals(10, VlsmDrills.round(random).size)

        assertEquals("255.255.255.224", VlsmDrills.maskFromPrefix(27).answer)
        assertEquals("/27 = 24 bits in 3 octets + 3 more: 128 + 64 + 32 = 224, so 255.255.255.224.", VlsmDrills.maskFromPrefix(27).working)
        val p = VlsmDrills.prefixFromMask(20)
        assertEquals("255.255.240.0 as a prefix?", p.prompt)
        assertTrue(VlsmDrills.isCorrect(p, "20"))
        assertTrue(VlsmDrills.isCorrect(p, "/20"))
        assertEquals("62", VlsmDrills.usableHosts(26).answer)
        assertTrue(VlsmDrills.isCorrect(VlsmDrills.usableHosts(16), "65,534"))

        val addr = Ipv4.parse("10.1.2.77")!!
        val n = VlsmDrills.network(addr, 27)
        assertEquals("10.1.2.64", n.answer)
        assertEquals("Block size 32 in the 4th octet, so 77 rounds down to 64: 10.1.2.64.", n.working)
        val b = VlsmDrills.broadcast(addr, 27)
        assertEquals("10.1.2.95", b.answer)
        assertEquals("Block size 32 in the 4th octet: the block runs 64–95, so the broadcast is 10.1.2.95.", b.working)
        assertEquals("172.16.48.0", VlsmDrills.network(Ipv4.parse("172.16.55.9")!!, 20).answer)
        assertEquals("172.16.63.255", VlsmDrills.broadcast(Ipv4.parse("172.16.55.9")!!, 20).answer)
        assertEquals("192.168.7.0", VlsmDrills.network(Ipv4.parse("192.168.7.200")!!, 24).answer)
        assertEquals("10.9.255.255", VlsmDrills.broadcast(Ipv4.parse("10.9.3.4")!!, 16).answer)
    }
}
