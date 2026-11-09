package net.estopia.hncrevision

import net.estopia.hncrevision.data.DataParser
import net.estopia.hncrevision.data.RevisionData
import java.io.File

object TestData {
    val dir = File(System.getProperty("hnc.sharedData") ?: "../../shared/data")
    fun raw(name: String) = File(dir, name).readText()
    val files by lazy { listOf("cards.js", "quiz.js", "vlsm.js").associateWith(::raw) }
    val data: RevisionData by lazy { DataParser.parse(raw("cards.js"), raw("quiz.js"), raw("vlsm.js")) }
}
