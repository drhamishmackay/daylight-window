package com.daylight.window

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * How the app tells you it is time to go out or come back in.
 */
enum class AlertStyle(val label: String, val explanation: String) {
    /**
     * A ringing, full-screen alert owned by this app. Deliberately not an entry in the
     * phone's clock app: what a clock app does with the same alarm sent again the next
     * day is up to that clock app, and some add a second one rather than replacing it.
     * Keeping the alarm here is what guarantees it never piles up.
     */
    ALARM(
        "Ringing alert (from this app)",
        "Rings and takes over the screen like a wake-up alarm, but it lives in this " +
            "app rather than your clock app — that is what stops it piling up a new " +
            "alarm every day. It will not appear in your list of alarms."
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

    /**
     * Offers the plan to the phone's clock app as ordinary alarms, for people who
     * would rather see them alongside their other alarms.
     *
     * This is a one-off export, not the app's alarm mechanism, because what a clock
     * app does with a repeated request is up to that clock app — some replace an
     * identical alarm, others add a second one. The app's own alarms are the reliable
     * path; this exists because some people simply prefer their clock app.
     */
    fun exportToClockApp(context: Context, slots: List<Slot>) {
        for (slot in slots) {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, slot.minuteOfDay / 60)
                putExtra(AlarmClock.EXTRA_MINUTES, slot.minuteOfDay % 60)
                putExtra(
                    AlarmClock.EXTRA_MESSAGE,
                    if (slot.kind == AlertKind.GO_OUT) "Daylight — go outside"
                    else "Daylight — head back in"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            }
        }
    }

    fun clockAppAvailable(context: Context): Boolean =
        Intent(AlarmClock.ACTION_SET_ALARM).resolveActivity(context.packageManager) != null
}
