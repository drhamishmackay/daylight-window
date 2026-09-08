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
 * Wakes the phone when it is time to go out or come back in, and re-plans each morning.
 *
 * Alarms are held in a fixed set of slots (see [Alerts]) and overwritten rather than
 * added, so they shift with the sun instead of accumulating. Everything is re-armed
 * after a reboot.
 */
object Reminders {

    const val CHANNEL_GO_OUT = "daylight_go_out"
    const val CHANNEL_COME_IN = "daylight_come_in"

    private const val REQUEST_DAILY_REPLAN = 1002

    /** The morning re-plan runs before the earliest plausible first session. */
    private const val DAILY_REPLAN_HOUR = 4

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GO_OUT,
                context.getString(R.string.channel_go_out),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.channel_go_out_description) }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_COME_IN,
                context.getString(R.string.channel_come_in),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.channel_come_in_description) }
        )
    }

    /** Arms the morning re-plan, which in turn arms each day's alerts. */
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
            replanIntent(context)
        )
    }

    fun cancelAll(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(replanIntent(context))
        for (slot in Alerts.ALL_SLOTS) {
            alarms.cancel(slotIntent(context, slot, AlertKind.GO_OUT, ""))
        }
    }

    /**
     * Works out today's plan and arms an alarm in each slot it needs. Slots the plan
     * does not need are cleared, so yesterday's alarm never fires on a day that has no
     * matching session.
     */
    fun scheduleToday(context: Context) {
        val settings = Settings(context)
        // Nothing to schedule here for either of these: the clock app owns the alarms
        // in one case, and there are no alerts at all in the other.
        if (settings.alertStyle == AlertStyle.IN_APP_ONLY) return
        if (settings.alertStyle == AlertStyle.CLOCK_APP) return
        if (!settings.hasStoredLocation()) return

        val now = Calendar.getInstance()
        val nowMinuteForFetch = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val forecast = try {
            runBlocking {
                Forecast.fetchCorrected(
                    settings.lastLatitude, settings.lastLongitude, nowMinuteForFetch
                )
            }
        } catch (e: ForecastUnavailable) {
            // Without a forecast there is nothing to schedule. Tomorrow's re-plan tries
            // again; a missed nudge is not worth waking someone for.
            return
        }

        val plan = DayPlan.build(
            forecast = forecast,
            skinType = settings.skinType,
            budget = settings.riskProfile.dailyDose,
            shape = settings.planShape,
            alreadySpent = settings.doseUsedToday
        )
        val slots = Alerts.slotsFor(plan)
        val alarms = context.getSystemService(AlarmManager::class.java)

        val nowMinute = nowMinuteForFetch

        // Clear every slot first, then arm only the ones today actually uses.
        for (slotId in Alerts.ALL_SLOTS) {
            alarms.cancel(slotIntent(context, slotId, AlertKind.GO_OUT, ""))
        }

        for (slot in slots) {
            if (slot.minuteOfDay <= nowMinute) continue
            val fireAt = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, slot.minuteOfDay / 60)
                set(Calendar.MINUTE, slot.minuteOfDay % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val intent = slotIntent(context, slot.id, slot.kind, slot.message)
            val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarms.canScheduleExactAlarms()
            if (canBeExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, intent)
            }
        }
    }

    /** Shows the alert when its moment arrives. */
    fun fire(context: Context, kind: AlertKind, message: String) {
        val settings = Settings(context)
        if (settings.alertStyle == AlertStyle.IN_APP_ONLY) return
        if (settings.alertStyle == AlertStyle.CLOCK_APP) return

        val goingOut = kind == AlertKind.GO_OUT
        val channel = if (goingOut) CHANNEL_GO_OUT else CHANNEL_COME_IN
        val title = context.getString(
            if (goingOut) R.string.alert_go_out_title else R.string.alert_come_in_title
        )

        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)

        // An alarm should behave like an alarm: sound, vibrate, and show over the
        // lock screen rather than sitting quietly in the shade.
        if (settings.alertStyle == AlertStyle.ALARM) {
            builder.setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(tap, true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setOngoing(false)
        }

        try {
            NotificationManagerCompat.from(context)
                .notify(if (goingOut) Alerts.SLOT_FIRST_OUT else Alerts.SLOT_FIRST_IN, builder.build())
        } catch (e: SecurityException) {
            // Notification permission was revoked after alerts were switched on.
            return
        }
    }

    private fun replanIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        REQUEST_DAILY_REPLAN,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_REPLAN),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /**
     * The alarm for one slot. The slot id is the request code, which is what makes a
     * second call for the same slot replace the first rather than add to it.
     */
    private fun slotIntent(
        context: Context,
        slotId: Int,
        kind: AlertKind,
        message: String
    ) = PendingIntent.getBroadcast(
        context,
        slotId,
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_ALERT)
            .putExtra(AlarmReceiver.EXTRA_KIND, kind.name)
            .putExtra(AlarmReceiver.EXTRA_MESSAGE, message),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

/** Receives the alarms, and re-arms everything after a reboot. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Reminders.createChannels(context)
                Reminders.scheduleDailyReplan(context)
                Reminders.scheduleToday(context)
            }
            ACTION_REPLAN -> Reminders.scheduleToday(context)
            ACTION_ALERT -> {
                val kindName = intent.getStringExtra(EXTRA_KIND)
                    ?: throw IllegalStateException("Alert fired with no kind")
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                    ?: throw IllegalStateException("Alert fired with no message")
                Reminders.fire(context, AlertKind.valueOf(kindName), message)
                // Chain the rest of today, in case a later slot was added since.
                Reminders.scheduleToday(context)
            }
        }
    }

    companion object {
        const val ACTION_REPLAN = "com.daylight.window.REPLAN"
        const val ACTION_ALERT = "com.daylight.window.ALERT"
        const val EXTRA_KIND = "kind"
        const val EXTRA_MESSAGE = "message"
    }
}
