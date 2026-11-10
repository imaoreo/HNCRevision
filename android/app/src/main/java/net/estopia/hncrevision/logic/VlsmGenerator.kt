package net.estopia.hncrevision.logic

import net.estopia.hncrevision.data.VlsmExercise
import net.estopia.hncrevision.data.VlsmRequest
import net.estopia.hncrevision.data.VlsmRow
import kotlin.random.Random

/** IPv4 helpers on addresses held as unsigned 32-bit values in a Long. */
object Ipv4 {
    fun parse(text: String): Long? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (p in parts) {
            val octet = p.toIntOrNull()?.takeIf { it in 0..255 && p.isNotEmpty() && p.all(Char::isDigit) } ?: return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    fun format(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { ((value shr it) and 0xFF).toString() }

    /** "a.b.c.d/n" → (address, prefix). */
    fun parseCidr(text: String): Pair<Long, Int>? {
        val slash = text.indexOf('/')
        if (slash < 0) return null
        val address = parse(text.substring(0, slash)) ?: return null
        val prefix = text.substring(slash + 1).trim().toIntOrNull()?.takeIf { it in 0..32 } ?: return null
        return address to prefix
    }

    fun mask(prefix: Int): Long = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    fun maskString(prefix: Int): String = format(mask(prefix))

    /** Prefix length of a contiguous mask, or null if the mask isn't valid. */
    fun prefixOf(mask: String): Int? {
        val m = parse(mask) ?: return null
        return (0..32).firstOrNull { mask(it) == m }
    }

    fun blockSize(prefix: Int): Long = 1L shl (32 - prefix)
    fun network(address: Long, prefix: Int): Long = address and mask(prefix)
    fun broadcast(address: Long, prefix: Int): Long = network(address, prefix) + blockSize(prefix) - 1
}

/**
 * The site's VLSM method: largest LAN first (ties alphabetical), each given the smallest
 * power-of-two block holding hosts + 2 (minimum 4), allocated back to back from the parent's
 * network address.
 */
object VlsmSolver {

    /** Smallest power of two ≥ hosts + 2, and at least 4. */
    fun blockSize(hosts: Int): Long {
        var block = 4L
        while (block < hosts + 2L) block = block shl 1
        return block
    }

    fun prefixFor(hosts: Int): Int = 32 - java.lang.Long.numberOfTrailingZeros(blockSize(hosts))

    fun order(requests: List<VlsmRequest>): List<VlsmRequest> =
        requests.sortedWith(compareByDescending<VlsmRequest> { it.hosts }.thenBy { it.lan })

    fun solve(parent: String, requests: List<VlsmRequest>): List<VlsmRow> {
        val (address, prefix) = Ipv4.parseCidr(parent) ?: return emptyList()
        var next = Ipv4.network(address, prefix)
        return order(requests).map { r ->
            val block = blockSize(r.hosts)
            val p = prefixFor(r.hosts)
            val network = next
            val broadcast = network + block - 1
            next = broadcast + 1
            VlsmRow(
                lan = r.lan,
                hosts = r.hosts,
                network = "${Ipv4.format(network)}/$p",
                mask = Ipv4.maskString(p),
                firstUsable = Ipv4.format(network + 1),
                lastUsable = Ipv4.format(broadcast - 1),
                broadcast = Ipv4.format(broadcast),
                usableCapacity = (block - 2).toInt(),
            )
        }
    }

    /** True when every block ends inside the parent network. */
    fun fits(parent: String, rows: List<VlsmRow>): Boolean {
        val (address, prefix) = Ipv4.parseCidr(parent) ?: return false
        val end = Ipv4.broadcast(address, prefix)
        val last = rows.lastOrNull()?.let { Ipv4.parse(it.broadcast) } ?: return true
        return last <= end
    }

    /** "1. LAN B needs 100 hosts. Allow two reserved addresses, then round …" for each row. */
    fun steps(rows: List<VlsmRow>): List<String> = rows.mapIndexed { i, r ->
        "${i + 1}. ${r.lan} needs ${r.hosts} hosts. Allow two reserved addresses, then round ${r.hosts + 2} up to " +
            "${r.usableCapacity + 2} addresses. Allocate ${r.network}. The next block starts immediately after ${r.broadcast}."
    }
}

enum class VlsmDifficulty(val label: String) { EASY("Easy"), MEDIUM("Medium"), HARD("Hard") }

/** Random VLSM questions in the style of the site's exercises. */
object VlsmGenerator {
    private const val MAX_TRIES = 100_000

    fun generate(difficulty: VlsmDifficulty, random: Random): VlsmExercise {
        val prefix = when (difficulty) {
            VlsmDifficulty.EASY, VlsmDifficulty.MEDIUM -> 24
            VlsmDifficulty.HARD -> if (random.nextBoolean()) 23 else 22
        }
        val lans = when (difficulty) {
            VlsmDifficulty.EASY -> 3
            VlsmDifficulty.MEDIUM -> random.nextInt(4, 6)
            VlsmDifficulty.HARD -> random.nextInt(5, 7)
        }
        val parent = randomParent(prefix, random)
        val maxHosts = (Ipv4.blockSize(prefix) / 2 - 2).toInt()
        val names = (0 until lans).map { "LAN ${'A' + it}" }
        var requests: List<VlsmRequest> = emptyList()
        var rows: List<VlsmRow> = emptyList()
        for (attempt in 0 until MAX_TRIES) {
            requests = names.map { VlsmRequest(it, random.nextInt(2, maxHosts + 1)) }
            rows = VlsmSolver.solve(parent, requests)
            if (VlsmSolver.fits(parent, rows)) break
        }
        // Practically unreachable; halve host counts until the allocation fits.
        while (!VlsmSolver.fits(parent, rows)) {
            requests = requests.map { it.copy(hosts = maxOf(2, it.hosts / 2)) }
            rows = VlsmSolver.solve(parent, requests)
        }
        return VlsmExercise(
            title = "${difficulty.label} · $parent",
            parent = parent,
            requests = requests,
            rows = rows,
            hints = hints(parent, rows),
        )
    }

    /** A private block aligned to [prefix]: 192.168.x.0, 172.16–31.x.0 or 10.x.y.0. */
    fun randomParent(prefix: Int, random: Random): String {
        val step = 1 shl (24 - prefix) // third-octet alignment for /22–/24
        val third = random.nextInt(0, 256 / step) * step
        val address = when (random.nextInt(3)) {
            0 -> "192.168.$third.0"
            1 -> "172.${random.nextInt(16, 32)}.$third.0"
            else -> "10.${random.nextInt(0, 256)}.$third.0"
        }
        return "$address/$prefix"
    }

    private fun hints(parent: String, rows: List<VlsmRow>): List<String> {
        val order = rows.joinToString(", ") { "${it.lan} (${it.hosts})" }
        val blocks = rows.joinToString("; ") { r ->
            val size = r.usableCapacity + 2
            "${r.lan}: ${r.hosts} + 2 = ${r.hosts + 2} → $size addresses, /${32 - Integer.numberOfTrailingZeros(size)}"
        }
        val first = rows.firstOrNull()
        val start = Ipv4.parseCidr(parent)?.let { (a, p) -> Ipv4.format(Ipv4.network(a, p)) } ?: parent
        return listOf(
            "Sort the LANs largest first: $order.",
            "Each block is the smallest power of two that holds the hosts plus network and broadcast. $blocks.",
            if (first == null) "Start at $start." else
                "Start at $start. ${first.lan} takes ${first.network}, ending at ${first.broadcast}; the next LAN starts at the following address.",
        )
    }
}

/** Marks typed VLSM answers: Network / prefix, Subnet mask, First usable, Last usable, Broadcast. */
object VlsmChecker {
    val FIELDS = listOf("Network / prefix", "Subnet mask", "First usable", "Last usable", "Broadcast")

    fun expected(row: VlsmRow): List<String> = listOf(row.network, row.mask, row.firstUsable, row.lastUsable, row.broadcast)

    private fun normalise(s: String) = s.filterNot(Char::isWhitespace).lowercase()

    fun check(row: VlsmRow, answers: List<String>): List<Boolean> =
        expected(row).mapIndexed { i, e -> normalise(answers.getOrElse(i) { "" }) == normalise(e) }

    /** Correct field count over all rows. */
    fun score(rows: List<VlsmRow>, answers: List<List<String>>): Pair<Int, Int> {
        val marks = rows.mapIndexed { i, r -> check(r, answers.getOrElse(i) { emptyList() }) }.flatten()
        return marks.count { it } to marks.size
    }
}

enum class DrillKind { MASK_FROM_PREFIX, PREFIX_FROM_MASK, USABLE_HOSTS, NETWORK, BROADCAST }

data class DrillQuestion(val kind: DrillKind, val prompt: String, val answer: String, val working: String)

/** Quick-fire subnetting questions on prefixes /16–/30. */
object VlsmDrills {
    const val ROUND_SIZE = 10

    fun round(random: Random): List<DrillQuestion> = List(ROUND_SIZE) { question(random) }

    fun question(random: Random): DrillQuestion {
        val prefix = random.nextInt(16, 31)
        return when (random.nextInt(4)) {
            0 -> if (random.nextBoolean()) maskFromPrefix(prefix) else prefixFromMask(prefix)
            1 -> usableHosts(prefix)
            2 -> network(randomAddress(random), prefix)
            else -> broadcast(randomAddress(random), prefix)
        }
    }

    fun isCorrect(q: DrillQuestion, input: String): Boolean {
        val given = input.filterNot(Char::isWhitespace)
        return when (q.kind) {
            DrillKind.PREFIX_FROM_MASK -> given.removePrefix("/") == q.answer.removePrefix("/")
            DrillKind.USABLE_HOSTS -> given.replace(",", "") == q.answer
            else -> given == q.answer
        }
    }

    fun randomAddress(random: Random): Long {
        val text = when (random.nextInt(3)) {
            0 -> "10.${random.nextInt(256)}.${random.nextInt(256)}.${random.nextInt(1, 255)}"
            1 -> "172.${random.nextInt(16, 32)}.${random.nextInt(256)}.${random.nextInt(1, 255)}"
            else -> "192.168.${random.nextInt(256)}.${random.nextInt(1, 255)}"
        }
        return Ipv4.parse(text)!!
    }

    /** Bits of the prefix that fall in its last, partly filled octet, and that octet's number (1–4). */
    private fun partialOctet(prefix: Int): Pair<Int, Int> {
        val full = prefix / 8
        return (prefix - full * 8) to (full + 1)
    }

    private fun bitValues(bits: Int) = (0 until bits).map { 128 shr it }

    private fun ordinal(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

    fun maskFromPrefix(prefix: Int): DrillQuestion {
        val mask = Ipv4.maskString(prefix)
        val (bits, _) = partialOctet(prefix)
        val full = prefix / 8
        val working = if (bits == 0) "/$prefix fills $full whole octets with 255, so $mask." else {
            val values = bitValues(bits)
            "/$prefix = ${full * 8} bits in $full octets + $bits more: ${values.joinToString(" + ")} = ${values.sum()}, so $mask."
        }
        return DrillQuestion(DrillKind.MASK_FROM_PREFIX, "/$prefix as a subnet mask?", mask, working)
    }

    fun prefixFromMask(prefix: Int): DrillQuestion {
        val mask = Ipv4.maskString(prefix)
        val (bits, _) = partialOctet(prefix)
        val full = prefix / 8
        val working = if (bits == 0) "$full octets of 255 are ${full * 8} one-bits, so /$prefix." else {
            val value = bitValues(bits).sum()
            "$full octets of 255 are ${full * 8} bits; $value = ${bitValues(bits).joinToString(" + ")} adds $bits, so /$prefix."
        }
        return DrillQuestion(DrillKind.PREFIX_FROM_MASK, "$mask as a prefix?", "/$prefix", working)
    }

    fun usableHosts(prefix: Int): DrillQuestion {
        val block = Ipv4.blockSize(prefix)
        return DrillQuestion(
            DrillKind.USABLE_HOSTS,
            "Usable hosts in a /$prefix?",
            (block - 2).toString(),
            "Block size 2^(32 − $prefix) = $block, minus the network and broadcast addresses = ${block - 2}.",
        )
    }

    /** "Block size 32 in the 4th octet, so …" */
    private fun blockWorking(address: Long, prefix: Int): Triple<Long, Int, Int> {
        // Block size within the octet where the network boundary falls.
        val (bits, octetNo) = if (prefix % 8 == 0) 8 to prefix / 8 else partialOctet(prefix)
        val shift = (4 - octetNo) * 8
        val octet = ((address shr shift) and 0xFF).toInt()
        val size = 1 shl (8 - bits)
        return Triple(size.toLong(), octetNo, octet)
    }

    fun network(address: Long, prefix: Int): DrillQuestion {
        val network = Ipv4.format(Ipv4.network(address, prefix))
        val (size, octetNo, octet) = blockWorking(address, prefix)
        val start = octet / size.toInt() * size.toInt()
        val working = if (prefix % 8 == 0) "/$prefix keeps the first ${prefix / 8} octets and zeros the rest, so $network."
        else "Block size $size in the ${ordinal(octetNo)} octet, so $octet rounds down to $start" +
            (if (octetNo < 4) " and later octets are 0" else "") + ": $network."
        return DrillQuestion(DrillKind.NETWORK, "Network address of ${Ipv4.format(address)}/$prefix?", network, working)
    }

    fun broadcast(address: Long, prefix: Int): DrillQuestion {
        val broadcast = Ipv4.format(Ipv4.broadcast(address, prefix))
        val (size, octetNo, octet) = blockWorking(address, prefix)
        val start = octet / size.toInt() * size.toInt()
        val working = if (prefix % 8 == 0) "/$prefix keeps the first ${prefix / 8} octets and sets the rest to 255, so $broadcast."
        else "Block size $size in the ${ordinal(octetNo)} octet: the block runs $start–${start + size - 1}, so the broadcast is $broadcast."
        return DrillQuestion(DrillKind.BROADCAST, "Broadcast address of ${Ipv4.format(address)}/$prefix?", broadcast, working)
    }
}
