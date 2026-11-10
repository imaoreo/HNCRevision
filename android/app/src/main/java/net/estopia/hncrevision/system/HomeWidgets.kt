package net.estopia.hncrevision.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.net.toUri
import net.estopia.hncrevision.MainActivity
import net.estopia.hncrevision.R
import net.estopia.hncrevision.logic.ReminderText
import net.estopia.hncrevision.logic.WidgetSnapshot
import net.estopia.hncrevision.logic.streakLabel
import java.time.ZonedDateTime
import kotlin.concurrent.thread

/**
 * Home screen widget: "N due" and the streak (small), or the card of the day (medium, from
 * about 4 × 2 cells). The app pushes a new snapshot whenever its state changes; a daily alarm
 * just after midnight and `updatePeriodMillis` recompute it from saved state.
 */
class HncWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        HomeWidgets.armMidnight(context)
        recomputeAsync(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // Before Android 12 the layout is picked from the size, so redraw on resize.
        val snapshot = AppStorage.progressStore(context).loadWidgetSnapshot() ?: return recomputeAsync(context)
        manager.updateAppWidget(id, HomeWidgets.views(context, snapshot, options))
    }

    override fun onEnabled(context: Context) = HomeWidgets.armMidnight(context)

    override fun onDisabled(context: Context) = HomeWidgets.cancelMidnight(context)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == HomeWidgets.ACTION_MIDNIGHT) {
            HomeWidgets.armMidnight(context)
            recomputeAsync(context)
            return
        }
        super.onReceive(context, intent)
    }

    private fun recomputeAsync(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "hnc-widget") {
            try {
                HomeWidgets.refresh(app)
            } finally {
                pending.finish()
            }
        }
    }
}

object HomeWidgets {
    const val ACTION_MIDNIGHT = "net.estopia.hncrevision.action.WIDGET_MIDNIGHT"
    private const val REQUEST_MIDNIGHT = 20
    private const val REQUEST_OPEN = 21
    private const val REQUEST_CARD = 22
    private const val MEDIUM_MIN_WIDTH_DP = 250
    private const val MIDNIGHT_WINDOW_MS = 30 * 60 * 1000L

    private fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, HncWidgetProvider::class.java))

    /** Recomputes from saved state and redraws every widget. Does disk work: call off the main thread. */
    fun refresh(context: Context) {
        if (ids(context).isEmpty()) return
        push(context, AppStorage.recomputeWidgetSnapshot(context))
    }

    /** Redraws every widget with [snapshot]. */
    fun push(context: Context, snapshot: WidgetSnapshot) {
        val manager = AppWidgetManager.getInstance(context)
        for (id in ids(context)) {
            manager.updateAppWidget(id, views(context, snapshot, manager.getAppWidgetOptions(id)))
        }
    }

    fun views(context: Context, snapshot: WidgetSnapshot, options: Bundle?): RemoteViews {
        val small = small(context, snapshot)
        val medium = medium(context, snapshot)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RemoteViews(mapOf(SizeF(40f, 40f) to small, SizeF(MEDIUM_MIN_WIDTH_DP.toFloat(), 100f) to medium))
        } else {
            val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            if (width >= MEDIUM_MIN_WIDTH_DP) medium else small
        }
    }

    private fun dueText(n: Int) = "$n due"

    private fun small(context: Context, s: WidgetSnapshot) = RemoteViews(context.packageName, R.layout.widget_small).apply {
        setTextViewText(R.id.widget_due, dueText(s.dueCount))
        setTextViewText(R.id.widget_streak, streakLabel(s.streak) ?: "Start a streak today")
        setContentDescription(
            R.id.widget_root,
            "HNC Revision. ${s.dueCount} cards due. " + (if (s.streak >= 1) "${s.streak}-day streak. " else "") + "Opens the app",
        )
        setOnClickPendingIntent(R.id.widget_root, openApp(context))
    }

    private fun medium(context: Context, s: WidgetSnapshot) = RemoteViews(context.packageName, R.layout.widget_medium).apply {
        val card = s.cardOfDay
        setTextViewText(R.id.widget_due, dueText(s.dueCount))
        setTextViewText(R.id.widget_meta, card?.let { "${it.unit} · ${it.topic}" } ?: "")
        setTextViewText(R.id.widget_question, card?.q ?: "Open HNC Revision to load today’s card.")
        setTextViewText(R.id.widget_streak, streakLabel(s.streak) ?: "")
        setContentDescription(
            R.id.widget_root,
            "Card of the day" + (card?.let { ": ${it.q}. ${it.unit}, ${it.topic}" } ?: "") + ". ${s.dueCount} cards due. Opens this card",
        )
        setOnClickPendingIntent(R.id.widget_root, card?.let { openCard(context, it.id) } ?: openApp(context))
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Opens `hncrevision://card/<id>` in the app, which shows it as a one-card deck. */
    private fun openCard(context: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_CARD,
        Intent(Intent.ACTION_VIEW, "${MainActivity.DEEP_LINK_SCHEME}://card/$id".toUri(), context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CARD_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun midnightIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        REQUEST_MIDNIGHT,
        Intent(context, HncWidgetProvider::class.java).setAction(ACTION_MIDNIGHT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** An inexact alarm just after local midnight while any widget is placed. */
    fun armMidnight(context: Context) {
        if (ids(context).isEmpty()) return
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = ReminderText.nextMidnight(ZonedDateTime.now()).toInstant().toEpochMilli()
        alarms.setWindow(AlarmManager.RTC, at, MIDNIGHT_WINDOW_MS, midnightIntent(context))
    }

    fun cancelMidnight(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(midnightIntent(context))
    }
}
