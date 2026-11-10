package net.estopia.hncrevision.system

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import net.estopia.hncrevision.MainActivity
import net.estopia.hncrevision.R
import net.estopia.hncrevision.logic.ReminderText
import java.time.ZonedDateTime
import kotlin.concurrent.thread

/**
 * The daily reminder: an inexact alarm (a 10-minute window, so no exact-alarm permission) whose
 * receiver builds the notification from saved state, then arms the next day's alarm. Re-armed on
 * app start, after boot, after an app update and when the clock or time zone changes.
 */
object Reminders {
    const val CHANNEL_ID = "daily-reminder"
    private const val NOTIFICATION_ID = 1
    private const val REQUEST_ALARM = 10
    private const val REQUEST_OPEN = 11
    private const val WINDOW_MS = 10 * 60 * 1000L

    /** Arms or cancels the alarm to match the saved setting. */
    fun sync(context: Context) {
        val settings = AppStorage.progressStore(context).loadReminder()
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context)
        if (!settings.enabled) {
            alarms.cancel(pending)
            return
        }
        val at = ReminderText.nextTrigger(ZonedDateTime.now(), settings.hour, settings.minute)
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), WINDOW_MS, pending)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_ALARM,
        Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun createChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Daily reminder")
                .setDescription("A daily nudge with your due cards and streak")
                .build(),
        )
    }

    /** True when the app may post notifications (permission on Android 13+, and not blocked). */
    fun canNotify(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Posts today's reminder with the current due count and streak. Runs off the main thread. */
    fun post(context: Context) {
        val snapshot = AppStorage.recomputeWidgetSnapshot(context)
        HomeWidgets.push(context, snapshot)
        if (!AppStorage.progressStore(context).loadReminder().enabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(context)
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DUE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = ReminderText.body(snapshot.dueCount, snapshot.streak)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.icon_background))
            .setContentTitle(ReminderText.TITLE)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and posting.
        }
    }
}

/** Fires once a day at the reminder time. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "hnc-reminder") {
            try {
                Reminders.post(app)
            } finally {
                Reminders.sync(app) // arm tomorrow's
                pending.finish()
            }
        }
    }
}

/** Re-arms the reminder and refreshes the widget after boot, app updates and clock changes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "hnc-boot") {
            try {
                Reminders.sync(app)
                HomeWidgets.armMidnight(app)
                HomeWidgets.refresh(app)
            } finally {
                pending.finish()
            }
        }
    }
}
