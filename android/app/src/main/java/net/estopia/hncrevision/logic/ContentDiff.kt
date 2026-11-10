package net.estopia.hncrevision.logic

import net.estopia.hncrevision.data.Card
import net.estopia.hncrevision.data.Question
import net.estopia.hncrevision.data.RevisionData
import java.time.LocalDate

/** Content hashes per card id and question id. */
data class Fingerprints(val cards: Map<Int, String>, val questions: Map<String, String>)

/** What changed between two loads of the data. */
data class ContentDiffResult(val added: Set<Int>, val updated: Set<Int>, val questionsChanged: Int) {
    val isEmpty get() = added.isEmpty() && updated.isEmpty() && questionsChanged == 0
}

/** The last change seen, for Settings: "Last change: 3 added, 2 updated · 12 Nov". */
data class ContentChange(val date: LocalDate, val added: Int, val updated: Int, val questionsChanged: Int)

/** Spots new and edited cards/questions by comparing FNV-1a 64 content hashes with the saved ones. */
object ContentDiff {
    private const val OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME = 0x100000001b3L
    private const val SEP = "\u001f"

    fun fnv1a64(text: String): Long {
        var hash = OFFSET
        for (b in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xFF)
            hash *= PRIME
        }
        return hash
    }

    private fun hex(text: String) = java.lang.Long.toHexString(fnv1a64(text))

    fun cardHash(card: Card) = hex(listOf(card.q, card.a, card.hint).joinToString(SEP))

    fun questionHash(q: Question) =
        hex((listOf(q.q) + q.options + listOf(q.answer.toString(), q.explanation)).joinToString(SEP))

    fun fingerprints(data: RevisionData) = Fingerprints(
        cards = data.cards.associate { it.id to cardHash(it) },
        questions = data.questions.associate { it.id to questionHash(it) },
    )

    fun diff(old: Fingerprints, new: Fingerprints): ContentDiffResult {
        val added = new.cards.keys.filter { it !in old.cards }.toSet()
        val updated = new.cards.filter { (id, h) -> old.cards[id].let { it != null && it != h } }.keys
        val questions = new.questions.count { (id, h) -> old.questions[id] != h }
        return ContentDiffResult(added, updated, questions)
    }
}
