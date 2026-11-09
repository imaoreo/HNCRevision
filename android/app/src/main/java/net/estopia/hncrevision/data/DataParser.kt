package net.estopia.hncrevision.data

/**
 * Turns the site's three scripts into [RevisionData]. Field decoding is tolerant: optional
 * fields may be missing, unknown fields are ignored, and a card/question that lacks a required
 * field is skipped rather than failing the load.
 */
object DataParser {

    fun parse(cardsJs: String, quizJs: String, vlsmJs: String): RevisionData {
        val units = list(JsLiteral.declaration(cardsJs, "UNITS")).mapNotNull(::unit)
        val cards = list(JsLiteral.declaration(cardsJs, "CARDS")).mapNotNull(::card)
        val coverage = (JsLiteral.declaration(cardsJs, "COVERAGE") as? Map<*, *>).orEmpty()
            .mapNotNull { (k, v) -> coverage(v)?.let { k.toString() to it } }
            .toMap()
        val questions = list(JsLiteral.declaration(quizJs, "QUESTIONS")).mapNotNull(::question)
        val hints = list(JsLiteral.declaration(vlsmJs, "exerciseHints")).map { strings(it) }
        val exercises = list(JsLiteral.declaration(vlsmJs, "vlsmExercises"))
            .mapIndexedNotNull { index, raw -> exercise(raw, hints.getOrNull(index).orEmpty()) }
        return RevisionData(units, cards, coverage, questions, exercises)
    }

    private fun unit(raw: Any?): StudyUnit? {
        val m = raw as? Map<*, *> ?: return null
        val code = str(m["code"]) ?: return null
        return StudyUnit(code, str(m["title"]) ?: code)
    }

    private fun card(raw: Any?): Card? {
        val m = raw as? Map<*, *> ?: return null
        return Card(
            id = int(m["id"]) ?: return null,
            q = str(m["q"]) ?: return null,
            a = str(m["a"]) ?: return null,
            topic = str(m["topic"]) ?: return null,
            unit = str(m["unit"]) ?: return null,
            outcomes = strings(m["outcomes"]),
            hint = str(m["hint"]).orEmpty(),
            references = list(m["references"]).mapNotNull(::reference),
            legalSource = str(m["legalSource"])?.takeIf { it.isNotBlank() },
            source = str(m["source"])?.takeIf { it.isNotBlank() },
        )
    }

    private fun reference(raw: Any?): Reference? {
        val m = raw as? Map<*, *> ?: return null
        val url = str(m["url"])?.takeIf { it.isNotBlank() } ?: return null
        return Reference(str(m["label"]) ?: url, url)
    }

    private fun coverage(raw: Any?): Coverage? {
        val m = raw as? Map<*, *> ?: return null
        return Coverage(
            version = str(m["version"]).orEmpty(),
            pages = str(m["pages"]).orEmpty(),
            gaps = str(m["gaps"]).orEmpty(),
            outcomes = list(m["outcomes"]).mapNotNull { o ->
                val om = o as? Map<*, *> ?: return@mapNotNull null
                val id = str(om["id"]) ?: return@mapNotNull null
                CoverageOutcome(id, str(om["title"]) ?: id, str(om["practice"]).orEmpty(), str(om["note"]).orEmpty())
            },
            source = str(m["source"])?.takeIf { it.isNotBlank() },
            checked = str(m["checked"]).orEmpty(),
        )
    }

    private fun question(raw: Any?): Question? {
        val m = raw as? Map<*, *> ?: return null
        val options = strings(m["options"])
        val answer = int(m["answer"]) ?: return null
        if (options.size < 2 || answer !in options.indices) return null
        return Question(
            id = str(m["id"]) ?: return null,
            cardId = int(m["cardId"]) ?: return null,
            unit = str(m["unit"]) ?: return null,
            topic = str(m["topic"]) ?: return null,
            q = str(m["q"]) ?: return null,
            options = options,
            answer = answer,
            explanation = str(m["explanation"]).orEmpty(),
            outcomes = strings(m["outcomes"]),
            legalSource = str(m["legalSource"])?.takeIf { it.isNotBlank() },
        )
    }

    private fun exercise(raw: Any?, hints: List<String>): VlsmExercise? {
        val m = raw as? Map<*, *> ?: return null
        val requests = list(m["requests"]).mapNotNull { r ->
            val l = r as? List<*> ?: return@mapNotNull null
            VlsmRequest(str(l.getOrNull(0)) ?: return@mapNotNull null, int(l.getOrNull(1)) ?: return@mapNotNull null)
        }
        val rows = list(m["rows"]).mapNotNull { r ->
            val l = r as? List<*> ?: return@mapNotNull null
            if (l.size < 8) return@mapNotNull null
            VlsmRow(
                lan = str(l[0]) ?: return@mapNotNull null,
                hosts = int(l[1]) ?: return@mapNotNull null,
                network = str(l[2]).orEmpty(),
                mask = str(l[3]).orEmpty(),
                firstUsable = str(l[4]).orEmpty(),
                lastUsable = str(l[5]).orEmpty(),
                broadcast = str(l[6]).orEmpty(),
                usableCapacity = int(l[7]) ?: return@mapNotNull null,
            )
        }
        return VlsmExercise(
            title = str(m["title"]) ?: return null,
            parent = str(m["parent"]).orEmpty(),
            requests = requests,
            rows = rows,
            hints = hints,
        )
    }

    private fun list(raw: Any?): List<Any?> = raw as? List<*> ?: emptyList()

    private fun strings(raw: Any?): List<String> = list(raw).mapNotNull(::str)

    /** Strings, plus numbers written without quotes (e.g. an outcome id of 1). */
    private fun str(raw: Any?): String? = when (raw) {
        is String -> raw
        is Long -> raw.toString()
        is Double -> if (raw % 1.0 == 0.0) raw.toLong().toString() else raw.toString()
        else -> null
    }

    private fun int(raw: Any?): Int? = when (raw) {
        is Long -> raw.toInt()
        is Double -> if (raw % 1.0 == 0.0) raw.toInt() else null
        is String -> raw.trim().toIntOrNull()
        else -> null
    }
}
