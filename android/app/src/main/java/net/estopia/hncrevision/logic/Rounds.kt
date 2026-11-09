package net.estopia.hncrevision.logic

import kotlin.random.Random

const val ALL = "all"
const val ALL_TOPICS = "All topics"
const val DEFAULT_UNIT = "J0H934"
const val GRADED_UNIT = "J0J734"
const val LEVEL_8_UNIT = "FR2435"
const val ALL_UNITS_SPEC = "https://www.sqa.org.uk/files_ccc/GP1015.pdf"

/**
 * Builds a round: the items not yet in [seen], in data order. When every item has been seen,
 * they are removed from [seen] and returned shuffled as a fresh round.
 */
fun <T, K> nextRound(items: List<T>, seen: MutableSet<K>, random: Random, id: (T) -> K): List<T> {
    val unseen = items.filter { id(it) !in seen }
    if (unseen.isNotEmpty() || items.isEmpty()) return unseen
    items.forEach { seen.remove(id(it)) }
    return items.shuffled(random)
}
