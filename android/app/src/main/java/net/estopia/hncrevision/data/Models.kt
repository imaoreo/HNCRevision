package net.estopia.hncrevision.data

data class StudyUnit(val code: String, val title: String)

data class Reference(val label: String, val url: String)

data class Card(
    val id: Int,
    val q: String,
    val a: String,
    val topic: String,
    val unit: String,
    val outcomes: List<String>,
    val hint: String,
    val references: List<Reference>,
    val legalSource: String? = null,
    val source: String? = null,
)

data class CoverageOutcome(val id: String, val title: String, val practice: String, val note: String)

data class Coverage(
    val version: String,
    val pages: String,
    val gaps: String,
    val outcomes: List<CoverageOutcome>,
    val source: String?,
    val checked: String,
)

data class Question(
    val id: String,
    val cardId: Int,
    val unit: String,
    val topic: String,
    val q: String,
    val options: List<String>,
    val answer: Int,
    val explanation: String,
    val outcomes: List<String>,
    val legalSource: String? = null,
)

/** One row of a worked VLSM answer. */
data class VlsmRow(
    val lan: String,
    val hosts: Int,
    val network: String,
    val mask: String,
    val firstUsable: String,
    val lastUsable: String,
    val broadcast: String,
    val usableCapacity: Int,
) {
    val cells: List<String>
        get() = listOf(lan, hosts.toString(), network, mask, firstUsable, lastUsable, broadcast, usableCapacity.toString())
}

data class VlsmRequest(val lan: String, val hosts: Int)

data class VlsmExercise(
    val title: String,
    val parent: String,
    val requests: List<VlsmRequest>,
    val rows: List<VlsmRow>,
    val hints: List<String>,
)

data class RevisionData(
    val units: List<StudyUnit>,
    val cards: List<Card>,
    val coverage: Map<String, Coverage>,
    val questions: List<Question>,
    val vlsm: List<VlsmExercise>,
) {
    private val cardsById: Map<Int, Card> by lazy { cards.associateBy { it.id } }
    fun card(id: Int): Card? = cardsById[id]
    fun unit(code: String): StudyUnit? = units.firstOrNull { it.code == code }

    /** Usable when it has at least one unit, card and question. */
    val isUsable: Boolean get() = units.isNotEmpty() && cards.isNotEmpty() && questions.isNotEmpty()
}
