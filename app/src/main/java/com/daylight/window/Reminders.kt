package com.daylight.window

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * Wakes the phone when a gentle window opens, and again each morning to work out the
 * day's windows afresh. Alarms are re-armed after a reboot so the habit never breaks.
 */
object Reminders {

    const val CHANNEL_ID = "daylight_windows"
    private const val REQUEST_WINDOW = 1001
    private const val REQUEST_DAILY = 1002

    /** The morning re-plan runs at this hour, before most first windows open. */
    private const val DAILY_REPLAN_HOUR = 5

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_description)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /** Arms the morning re-plan, which in turn arms each day's window alarms. */
    fun scheduleDailyReplan(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, DAILY_REPLAN_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarms.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            next.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent(context, REQUEST_DAILY, AlarmReceiver.ACTION_REPLAN)
        )
    }

    fun cancelAll(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pendingIntent(context, REQUEST_DAILY, AlarmReceiver.ACTION_REPLAN))
        alarms.cancel(pendingIntent(context, REQUEST_WINDOW, AlarmReceiver.ACTION_WINDOW_OPEN))
    }

    /**
     * Looks up today's forecast and sets an alarm for the next window that has not
     * started yet. Called each morning and after every reboot.
     */
    fun scheduleNextWindow(context: Context) {
        val settings = Settings(context)
        if (!settings.remindersOn || !settings.hasStoredLocation()) return

        val forecast = try {
            runBlocking { Forecast.fetch(settings.lastLatitude, settings.lastLongitude) }
        } catch (e: ForecastUnavailable) {
            // Without a forecast there is nothing to schedule. The next morning's
            // re-plan will try again; a missed nudge is not worth waking the user for.
            return
        }

        val samples = SunModel.interpolate(forecast.hourlyUv)
        val windows = SunModel.findWindows(
            samples = samples,
            sunriseMinute = forecast.sunriseMinute,
            sunsetMinute = forecast.sunsetMinute,
            uvLimit = settings.uvLimit,
            skinType = settings.skinType
        )

        val now = Calendar.getInstance()
        val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val next = windows.firstOrNull { it.startMinute > nowMinute } ?: return

        val fireAt = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, next.startMinute / 60)
            set(Calendar.MINUTE, next.startMinute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = pendingIntent(context, REQUEST_WINDOW, AlarmReceiver.ACTION_WINDOW_OPEN)

        // An exact alarm needs permission on Android 12 and later; without it the
        // reminder still arrives, just not to the minute.
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarms.canScheduleExactAlarms()
        if (canBeExact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, intent)
        }
    }

    fun notifyWindowOpen(context: Context) {
        val settings = Settings(context)
        if (!settings.remindersOn || !settings.hasStoredLocation()) return

        val forecast = try {
            runBlocking { Forecast.fetch(settings.lastLatitude, settings.lastLongitude) }
        } catch (e: ForecastUnavailable) {
            return
        }

        val samples = SunModel.interpolate(forecast.hourlyUv)
        val windows = SunModel.findWindows(
            samples, forecast.sunriseMinute, forecast.sunsetMinute,
            settings.uvLimit, settings.skinType
        )

        val now = Calendar.getInstance()
        val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val open = windows.firstOrNull { nowMinute >= it.startMinute && nowMinute <= it.endMinute }
            ?: return

        val pinkAt = SunModel.minutesUntilPinking(open.peakUv, settings.skinType)
        val body = if (pinkAt == null) {
            context.getString(R.string.notify_body_negligible, Format.clock(open.endMinute))
        } else {
            context.getString(
                R.string.notify_body_limited,
                Format.clock(open.endMinute),
                Format.duration(pinkAt)
            )
        }

        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(REQUEST_WINDOW, notification)
        } catch (e: SecurityException) {
            // The user revoked notification permission after enabling reminders.
            return
        }

        // Chain the next window of the same day.
        scheduleNextWindow(context)
    }

    private fun pendingIntent(context: Context, requestCode: Int, action: String) =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}

/** Receives the alarms, and re-arms everything after a reboot. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Reminders.createChannel(context)
                Reminders.scheduleDailyReplan(context)
                Reminders.scheduleNextWindow(context)
            }
            ACTION_REPLAN -> Reminders.scheduleNextWindow(context)
            ACTION_WINDOW_OPEN -> Reminders.notifyWindowOpen(context)
        }
    }

    companion object {
        const val ACTION_REPLAN = "com.daylight.window.REPLAN"
        const val ACTION_WINDOW_OPEN = "com.daylight.window.WINDOW_OPEN"
    }
}
