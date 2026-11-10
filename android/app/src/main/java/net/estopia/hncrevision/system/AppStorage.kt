package net.estopia.hncrevision.system

import android.content.Context
import androidx.core.content.edit
import net.estopia.hncrevision.data.DataRepository
import net.estopia.hncrevision.logic.KeyValueStore
import net.estopia.hncrevision.logic.ProgressStore
import net.estopia.hncrevision.logic.WidgetContent
import net.estopia.hncrevision.logic.WidgetSnapshot
import net.estopia.hncrevision.logic.fromStore
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** The app's SharedPreferences-backed store and data repository, shared by the UI and receivers. */
object AppStorage {
    private const val PREFS = "hnc-revision"

    fun progressStore(context: Context): ProgressStore {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ProgressStore(object : KeyValueStore {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String?) = prefs.edit { if (value == null) remove(key) else putString(key, value) }
        })
    }

    fun repository(context: Context): DataRepository {
        val app = context.applicationContext
        return DataRepository(
            cacheDir = File(app.filesDir, "live-data"),
            bundled = {
                DataRepository.FILES.associateWith { name ->
                    app.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
                }
            },
        )
    }

    /**
     * Recomputes the widget snapshot from saved state and the saved/bundled data, and saves it.
     * Does disk work: call off the main thread.
     */
    fun recomputeWidgetSnapshot(context: Context): WidgetSnapshot {
        val store = progressStore(context)
        val cards = repository(context).loadInitial().data.cards
        val snapshot = WidgetContent.fromStore(cards, store, LocalDate.now(), Instant.now().toString())
        store.saveWidgetSnapshot(snapshot)
        return snapshot
    }
}
