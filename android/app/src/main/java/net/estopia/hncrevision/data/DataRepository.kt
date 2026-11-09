package net.estopia.hncrevision.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Where the data currently on screen came from. */
sealed interface DataSource {
    /** Downloaded from the website during this run, at [at] (epoch millis). */
    data class Live(val at: Long) : DataSource
    /** A previously downloaded copy saved at [at] (epoch millis); the site hasn't been reached yet. */
    data class Saved(val at: Long) : DataSource
    /** The copy shipped inside the app. */
    data object BuiltIn : DataSource
}

data class LoadedData(val data: RevisionData, val source: DataSource)

/** The three raw scripts, keyed by file name. */
typealias RawFiles = Map<String, String>

/**
 * Loads the site's data: instantly from the saved live copy (or the bundled one), and refreshes
 * from the website on request. Plain Kotlin so it can be unit-tested with a fake [fetch].
 *
 * @param cacheDir directory holding the last successful live copy.
 * @param bundled reads the copy shipped as assets.
 * @param fetch downloads a URL as text, throwing on any failure.
 */
class DataRepository(
    private val cacheDir: File,
    private val bundled: () -> RawFiles,
    private val fetch: (String) -> String = ::httpGet,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Saved live copy if it exists and parses, else the bundled copy. Never touches the network. */
    fun loadInitial(): LoadedData {
        readCache()?.let { (files, savedAt) ->
            parse(files)?.let { return LoadedData(it, DataSource.Saved(savedAt)) }
        }
        val data = parse(bundled()) ?: throw IllegalStateException("Bundled data failed to parse")
        return LoadedData(data, DataSource.BuiltIn)
    }

    /**
     * Fetches the current scripts from the site. Returns the new data (and saves the raw files as
     * the cached copy) only if all three download and parse into usable data; otherwise null.
     */
    fun refresh(): LoadedData? = try {
        val page = fetch(SITE_URL)
        val urls = scriptUrls(page, SITE_URL)
        val files = FILES.associateWith { fetch(urls.getValue(it)) }
        val data = parse(files)
        if (data == null) null else {
            val now = clock()
            writeCache(files, now)
            LoadedData(data, DataSource.Live(now))
        }
    } catch (_: Exception) {
        null
    }

    private fun parse(files: RawFiles): RevisionData? = try {
        DataParser.parse(
            files[CARDS] ?: return null,
            files[QUIZ] ?: return null,
            files[VLSM] ?: return null,
        ).takeIf { it.isUsable }
    } catch (_: Exception) {
        null
    }

    private fun readCache(): Pair<RawFiles, Long>? {
        val savedAt = File(cacheDir, META).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: return null
        val files = FILES.associateWith { name ->
            File(cacheDir, name).takeIf { it.isFile }?.readText() ?: return null
        }
        return files to savedAt
    }

    private fun writeCache(files: RawFiles, savedAt: Long) {
        try {
            cacheDir.mkdirs()
            // Write everything to temp files first so a half-written copy is never picked up.
            File(cacheDir, META).delete()
            for ((name, text) in files) {
                val tmp = File(cacheDir, "$name.tmp")
                tmp.writeText(text)
                val dest = File(cacheDir, name)
                if (!tmp.renameTo(dest)) {
                    dest.delete()
                    if (!tmp.renameTo(dest)) throw IOException("Couldn't save $name")
                }
            }
            File(cacheDir, META).writeText(savedAt.toString())
        } catch (_: Exception) {
            // Keep using the in-memory data; next launch falls back to an older copy.
        }
    }

    companion object {
        const val SITE_URL = "https://data-security-revision-cards.exquisitemeadow.chatgpt.site/"
        const val CARDS = "cards.js"
        const val QUIZ = "quiz.js"
        const val VLSM = "vlsm.js"
        val FILES = listOf(CARDS, QUIZ, VLSM)
        private const val META = "saved-at.txt"
        private const val TIMEOUT_MS = 15_000

        private val SCRIPT_SRC = Regex("""src="((cards|quiz|vlsm)\.js[^"]*)"""")

        /**
         * Finds the current script URLs (with their `?v=N` cache-busters) in the site's HTML,
         * resolved against [base]. Files not referenced fall back to their bare names.
         */
        fun scriptUrls(html: String, base: String): Map<String, String> {
            val found = SCRIPT_SRC.findAll(html).associate { m -> "${m.groupValues[2]}.js" to m.groupValues[1] }
            val baseUri = URI(base)
            return FILES.associateWith { name ->
                val src = (found[name] ?: name).replace("&amp;", "&")
                baseUri.resolve(src).toString()
            }
        }

        fun httpGet(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.useCaches = false
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Pragma", "no-cache")
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code for $url")
                return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                connection.disconnect()
            }
        }
    }
}
