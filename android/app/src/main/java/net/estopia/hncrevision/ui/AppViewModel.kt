package net.estopia.hncrevision.ui

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.estopia.hncrevision.data.DataRepository
import net.estopia.hncrevision.data.DataSource
import net.estopia.hncrevision.logic.KeyValueStore
import net.estopia.hncrevision.logic.ProgressStore
import net.estopia.hncrevision.logic.RevisionEngine
import net.estopia.hncrevision.logic.Snapshot
import java.io.File
import java.util.Locale

enum class RefreshOutcome { UPDATED, FAILED }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("hnc-revision", Context.MODE_PRIVATE)
    private val progressStore = ProgressStore(object : KeyValueStore {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String?) = prefs.edit { if (value == null) remove(key) else putString(key, value) }
    })

    private val repository = DataRepository(
        cacheDir = File(app.filesDir, "live-data"),
        bundled = {
            DataRepository.FILES.associateWith { name ->
                app.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        },
    )

    private val initial = repository.loadInitial()
    private val engine = RevisionEngine(initial.data, progressStore)

    var snapshot: Snapshot by mutableStateOf(engine.snapshot())
        private set
    var source: DataSource by mutableStateOf(initial.source)
        private set
    var refreshing by mutableStateOf(false)
        private set
    /** Result of the last check the user asked for (pull-to-refresh / Check for updates). */
    var manualRefreshOutcome by mutableStateOf<RefreshOutcome?>(null)
        private set
    private var lastCheck = 0L

    var readAloud by mutableStateOf(progressStore.getFlag(ProgressStore.SPEECH))
        private set
    var dyslexiaReading by mutableStateOf(progressStore.getFlag(ProgressStore.DYSLEXIA))
        private set

    val vlsmWorking = mutableStateMapOf<Int, String>().apply { putAll(progressStore.loadVlsmWorking()) }

    /** Runs an engine action and publishes a fresh snapshot for the UI. */
    fun act(action: RevisionEngine.() -> Unit) {
        engine.action()
        snapshot = engine.snapshot()
    }

    // region Data refresh

    /** Called on every resume: refreshes when the last check was over 30 minutes ago. */
    fun onResume() {
        if (System.currentTimeMillis() - lastCheck > REFRESH_INTERVAL_MS) refresh(manual = false)
    }

    fun refresh(manual: Boolean) {
        if (refreshing) return
        refreshing = true
        if (manual) manualRefreshOutcome = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.refresh() }
            lastCheck = System.currentTimeMillis()
            if (result != null) {
                engine.replaceData(result.data)
                snapshot = engine.snapshot()
                source = result.source
            }
            if (manual) manualRefreshOutcome = if (result != null) RefreshOutcome.UPDATED else RefreshOutcome.FAILED
            refreshing = false
        }
    }

    // endregion

    // region Settings

    fun updateReadAloud(on: Boolean) {
        readAloud = on
        progressStore.setFlag(ProgressStore.SPEECH, on)
        if (on) speaker.prepare() else speaker.stop()
        speechPaused = false
    }

    fun updateDyslexiaReading(on: Boolean) {
        dyslexiaReading = on
        progressStore.setFlag(ProgressStore.DYSLEXIA, on)
    }

    fun setVlsmWorking(index: Int, text: String) {
        vlsmWorking[index] = text
        progressStore.saveVlsmWorking(vlsmWorking.toMap())
    }

    fun resetProgress() = act { resetProgress() }

    // endregion

    // region Speech

    private val speaker = Speaker(app)
    /** "Stop reading" pauses automatic reading until "Read aloud" is pressed again. */
    var speechPaused by mutableStateOf(false)
        private set

    init {
        if (readAloud) speaker.prepare()
    }

    /** Automatic reading (question on show, answer on reveal, hint when shown). */
    fun autoRead(text: String) {
        if (readAloud && !speechPaused && text.isNotBlank()) speaker.speak(text)
    }

    /** "Read aloud" buttons: always read, and resume automatic reading. */
    fun readNow(text: String) {
        speechPaused = false
        speaker.speak(text)
    }

    fun stopReading(pause: Boolean = true) {
        if (pause) speechPaused = true
        speaker.stop()
    }

    override fun onCleared() {
        speaker.shutdown()
    }

    // endregion

    companion object {
        private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
    }
}

/** Thin wrapper over TextToSpeech that queues the first utterance until the engine is ready. */
private class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun prepare() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val engine = tts ?: return@TextToSpeech
                val result = engine.setLanguage(Locale.UK)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.ENGLISH)
                }
                pending?.let { speak(it) }
            }
            pending = null
        }
    }

    fun speak(text: String) {
        prepare()
        val engine = tts
        if (!ready || engine == null) {
            pending = text
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hnc-${text.hashCode()}")
    }

    fun stop() {
        pending = null
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
