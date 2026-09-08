package com.daylight.window

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.util.Calendar

/**
 * How the app tells you it is time to go out or come back in.
 */
enum class AlertStyle(val label: String, val explanation: String) {
    /**
     * A real alarm, built the way Google's own Clock app builds one: a foreground
     * service loops the ringtone on the alarm audio stream, a wake lock keeps it
     * going, and a full-screen alert appears over the lock screen and over other apps
     * until it is dismissed.
     *
     * Kept inside this app rather than written into the phone's clock app because an
     * app can create alarms there but never delete or move them — the clock app's
     * database is closed to other apps. Since these times shift a minute or two daily,
     * that route would leave a new alarm behind every day with no way to clear it.
     */
    ALARM(
        "Alarm",
        "Rings until you dismiss it and appears over your lock screen, like a wake-up " +
            "alarm. It moves itself each day as the sun shifts."
    ),

    /**
     * Writes the times into the phone's clock app. One-off, because those alarms
     * cannot be updated or removed afterwards by this app.
     */
    CLOCK_APP(
        "Alarms in my clock app",
        "Puts the times in your clock app so you can see them there. They cannot update " +
            "themselves — an app can add alarms to your clock app but never change or " +
            "remove them — so you would resend them each day and tidy up the old ones."
    ),

    /** A notification: quieter, easy to miss. */
    NOTIFICATION(
        "Quiet notification",
        "A silent notification when it is time. Easy to miss if your phone is away."
    ),

    /** Nothing: the plan is there when you open the app. */
    IN_APP_ONLY(
        "Nothing — I will check the app",
        "No alerts at all. Open the app when you want to know."
    );

    companion object {
        val DEFAULT = ALARM

        fun fromName(name: String): AlertStyle =
            entries.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("Unknown alert style: $name")
    }
}

/** What an alert is telling you to do. */
enum class AlertKind { GO_OUT, COME_IN }

/**
 * Works out which alerts today's plan needs.
 *
 * Alarms cannot pile up because the app owns four fixed alarm slots and overwrites
 * them: go out, come in, go out again, and a final come in for days that end before
 * sunset. Re-planning replaces what is in each slot rather than adding to it, so a
 * year of use still leaves four alarms, shifted a minute or two as the sun moves.
 *
 * A "come back in" alert is set whenever a session ends while the sun is still up. It
 * is skipped only when the session runs to sunset, because from there ultraviolet only
 * falls and there is nothing to come inside from. That distinction matters for anyone
 * who wants two shorter sessions rather than staying out until dark.
 */
object Alerts {

    /** A fixed slot the app owns. The number is the alarm's identity. */
    data class Slot(val id: Int, val kind: AlertKind, val minuteOfDay: Int, val message: String)

    /** Slot identifiers. Fixed for the life of the app so alarms overwrite cleanly. */
    const val SLOT_FIRST_OUT = 2001
    const val SLOT_FIRST_IN = 2002
    const val SLOT_SECOND_OUT = 2003
    const val SLOT_LAST_IN = 2004

    val ALL_SLOTS = listOf(SLOT_FIRST_OUT, SLOT_FIRST_IN, SLOT_SECOND_OUT, SLOT_LAST_IN)

    /**
     * Ultraviolet is treated as finished for the day this close to sunset. Sunset is
     * the moment the sun's upper edge meets the horizon; the last few minutes before
     * it carry almost no ultraviolet, so an alert to come inside then would be noise.
     */
    private const val SUNSET_GRACE_MINUTES = 10

    fun slotsFor(plan: DayPlan.Plan): List<Slot> {
        if (plan.sessions.isEmpty()) return emptyList()

        if (plan.wholeDayIsSafe) {
            val session = plan.sessions.first()
            return listOf(
                Slot(
                    SLOT_FIRST_OUT, AlertKind.GO_OUT, session.startMinute,
                    "The sun is gentle all day today. Go out whenever suits you."
                )
            )
        }

        val slots = ArrayList<Slot>()
        plan.sessions.forEachIndexed { index, session ->
            val outId = if (index == 0) SLOT_FIRST_OUT else SLOT_SECOND_OUT
            slots.add(
                Slot(
                    outId, AlertKind.GO_OUT, session.startMinute,
                    "Time to go outside. You have ${Format.duration(session.lengthMinutes)}."
                )
            )
            // Tell them to come in whenever the session ends with the sun still
            // meaningfully up — not merely because another session follows.
            val endsAtSunset =
                session.endMinute >= plan.sunsetMinute - SUNSET_GRACE_MINUTES
            if (!endsAtSunset) {
                val moreToCome = index != plan.sessions.lastIndex
                slots.add(
                    Slot(
                        if (moreToCome) SLOT_FIRST_IN else SLOT_LAST_IN,
                        AlertKind.COME_IN,
                        session.endMinute,
                        if (moreToCome) "Head back inside. The sun gets stronger from here."
                        else "That is your outdoor time for today. Head back in."
                    )
                )
            }
        }
        return slots
    }

    /** Every day of the week: makes each alarm a permanent repeating one. */
    private val EVERY_DAY = arrayListOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    )

    /**
     * Writes the plan into the phone's own clock app, so the times sit alongside every
     * other alarm and ring the way a wake-up alarm rings.
     *
     * Each alarm is set to repeat on all seven days and carries a fixed label. That is
     * what stops them accumulating: a repeating alarm is one permanent entry, so
     * tomorrow's re-plan moves the existing alarm to the new time instead of adding a
     * second one. A one-off alarm would leave a spent entry behind every day.
     *
     * Requires the SET_ALARM permission and a <queries> entry for the clock app; both
     * are declared in the manifest. Without either, the request is silently refused.
     */
    fun exportToClockApp(context: Context, slots: List<Slot>) {
        for (slot in slots) {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, slot.minuteOfDay / 60)
                putExtra(AlarmClock.EXTRA_MINUTES, slot.minuteOfDay % 60)
                putExtra(AlarmClock.EXTRA_MESSAGE, labelFor(slot))
                putExtra(AlarmClock.EXTRA_DAYS, EVERY_DAY)
                putExtra(AlarmClock.EXTRA_VIBRATE, true)
                // Ask the clock app to set it without opening its own screen. Some
                // clock apps show their screen anyway; the alarm is still set.
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * The alarm's name in the clock app. Fixed per slot so the same entry is updated
     * each day rather than a new one created, and recognisable at a glance in a list
     * of alarms.
     */
    private fun labelFor(slot: Slot): String = when (slot.id) {
        SLOT_FIRST_OUT -> "Daylight · go outside"
        SLOT_FIRST_IN -> "Daylight · head back in"
        SLOT_SECOND_OUT -> "Daylight · second time outside"
        SLOT_LAST_IN -> "Daylight · that is today's sun"
        else -> "Daylight"
    }

    /** Opens the clock app's alarm list. */
    fun openClockApp(context: Context) {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun clockAppAvailable(context: Context): Boolean =
        Intent(AlarmClock.ACTION_SET_ALARM).resolveActivity(context.packageManager) != null
}
