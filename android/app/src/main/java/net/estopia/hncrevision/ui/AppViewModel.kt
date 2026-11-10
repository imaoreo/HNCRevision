package net.estopia.hncrevision.ui

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.estopia.hncrevision.data.DataSource
import net.estopia.hncrevision.data.VlsmExercise
import net.estopia.hncrevision.logic.DrillQuestion
import net.estopia.hncrevision.logic.ProgressStore
import net.estopia.hncrevision.logic.ReminderSettings
import net.estopia.hncrevision.logic.RevisionEngine
import net.estopia.hncrevision.logic.Snapshot
import net.estopia.hncrevision.logic.VlsmChecker
import net.estopia.hncrevision.logic.VlsmDifficulty
import net.estopia.hncrevision.logic.VlsmDrills
import net.estopia.hncrevision.logic.VlsmGenerator
import net.estopia.hncrevision.logic.WidgetSnapshot
import net.estopia.hncrevision.system.AppStorage
import net.estopia.hncrevision.system.HomeWidgets
import net.estopia.hncrevision.system.Reminders
import java.util.Locale
import kotlin.random.Random

enum class RefreshOutcome { UPDATED, FAILED }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val progressStore = AppStorage.progressStore(app)
    private val repository = AppStorage.repository(app)

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
    var reminder by mutableStateOf(progressStore.loadReminder())
        private set

    val vlsmWorking = mutableStateMapOf<Int, String>().apply { putAll(progressStore.loadVlsmWorking()) }

    private var widget: WidgetSnapshot? = progressStore.loadWidgetSnapshot()

    init {
        Reminders.createChannel(app)
        Reminders.sync(app)
        HomeWidgets.armMidnight(app)
        updateWidget()
    }

    /** Runs an engine action and publishes a fresh snapshot for the UI (and the widget). */
    fun act(action: RevisionEngine.() -> Unit) {
        engine.action()
        publish()
    }

    private fun publish() {
        snapshot = engine.snapshot()
        updateWidget()
    }

    /** Saves the widget snapshot and redraws widgets when what they show has changed. */
    private fun updateWidget() {
        val next = engine.widgetSnapshot()
        if (next.sameContent(widget)) return
        widget = next
        progressStore.saveWidgetSnapshot(next)
        HomeWidgets.push(getApplication(), next)
    }

    fun openCard(id: Int) = act { openCard(id) }
    fun openDue() = act { openDue() }

    // region Data refresh

    /** Called on every resume: picks up a new day, and refreshes when the last check was over 30 minutes ago. */
    fun onResume() {
        act { refreshDay() }
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
                publish()
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

    fun updateReminder(settings: ReminderSettings) {
        reminder = settings
        progressStore.saveReminder(settings)
        Reminders.sync(getApplication())
    }

    fun setVlsmWorking(index: Int, text: String) {
        vlsmWorking[index] = text
        progressStore.saveVlsmWorking(vlsmWorking.toMap())
    }

    fun resetProgress(starsAndNotes: Boolean) = act { resetProgress(starsAndNotes) }

    // endregion

    // region VLSM generator and drills (kept in memory; only the best drill score is saved)

    private val random = Random.Default

    var vlsmDifficulty by mutableStateOf(VlsmDifficulty.EASY)
        private set
    var generated by mutableStateOf<VlsmExercise?>(null)
        private set
    /** Typed answers per LAN (solver order) × 5 fields. */
    val generatedAnswers = mutableStateListOf<List<String>>()
    var generatedChecked by mutableStateOf(false)
        private set
    var generatedWorking by mutableStateOf("")

    fun ensureGenerated() {
        if (generated == null) newGenerated(vlsmDifficulty)
    }

    fun newGenerated(difficulty: VlsmDifficulty = vlsmDifficulty) {
        vlsmDifficulty = difficulty
        val e = VlsmGenerator.generate(difficulty, random)
        generated = e
        generatedAnswers.clear()
        generatedAnswers.addAll(e.rows.map { List(VlsmChecker.FIELDS.size) { "" } })
        generatedChecked = false
        generatedWorking = ""
    }

    fun setGeneratedAnswer(row: Int, field: Int, text: String) {
        val current = generatedAnswers.getOrNull(row) ?: return
        generatedAnswers[row] = current.toMutableList().also { it[field] = text }
        generatedChecked = false
    }

    fun checkGenerated() {
        generatedChecked = true
    }

    var drillRound by mutableStateOf(VlsmDrills.round(random))
        private set
    var drillIndex by mutableIntStateOf(0)
        private set
    var drillInput by mutableStateOf("")
    /** Per question: null until checked, then right or wrong. */
    val drillResults = mutableStateListOf<Boolean?>().apply { addAll(List(VlsmDrills.ROUND_SIZE) { null }) }
    var drillBest by mutableIntStateOf(progressStore.getInt(ProgressStore.DRILL_BEST))
        private set

    val drillQuestion: DrillQuestion? get() = drillRound.getOrNull(drillIndex)
    val drillDone get() = drillIndex >= drillRound.size
    val drillScore get() = drillResults.count { it == true }

    fun checkDrill() {
        val q = drillQuestion ?: return
        if (drillResults[drillIndex] != null || drillInput.isBlank()) return
        drillResults[drillIndex] = VlsmDrills.isCorrect(q, drillInput)
    }

    fun nextDrill() {
        if (drillResults.getOrNull(drillIndex) == null) return
        drillIndex++
        drillInput = ""
        if (drillDone && drillScore > drillBest) {
            drillBest = drillScore
            progressStore.setInt(ProgressStore.DRILL_BEST, drillBest)
        }
    }

    fun newDrillRound() {
        drillRound = VlsmDrills.round(random)
        drillIndex = 0
        drillInput = ""
        drillResults.clear()
        drillResults.addAll(List(drillRound.size) { null })
    }

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
