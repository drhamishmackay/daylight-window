package com.daylight.window

import android.content.Context
import java.util.Calendar

/**
 * The choices the app remembers, and what you have actually spent today.
 *
 * Deliberately small: the point of the app is that you stop making decisions, not
 * that you tune it.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("daylight", Context.MODE_PRIVATE)

    /**
     * Fitzpatrick skin type. Defaults to type I — the type that burns fastest — so an
     * unconfigured app is cautious rather than permissive.
     */
    var skinType: Int
        get() = prefs.getInt(KEY_SKIN, DEFAULT_SKIN_TYPE)
        set(value) {
            require(value in 1..6) { "Fitzpatrick type must be 1-6, got $value" }
            prefs.edit().putInt(KEY_SKIN, value).apply()
        }

    /** How much sun you are willing to collect in a day. */
    var riskProfile: RiskProfile
        get() {
            val stored = prefs.getString(KEY_PROFILE, null) ?: return RiskProfile.DEFAULT
            return RiskProfile.fromName(stored)
        }
        set(value) = prefs.edit().putString(KEY_PROFILE, value.name).apply()

    /** One longer session, or two shorter ones. */
    var planShape: DayPlan.Shape
        get() {
            val stored = prefs.getString(KEY_SHAPE, null) ?: return DayPlan.Shape.TWO_SESSIONS
            return DayPlan.Shape.valueOf(stored)
        }
        set(value) = prefs.edit().putString(KEY_SHAPE, value.name).apply()

    /**
     * The earliest the app may send you outside, in minutes past midnight.
     *
     * There is no default: the app asks rather than choosing, because the cost of
     * guessing is either being woken at dawn or losing most of the morning session.
     * Null means it has not been set yet.
     */
    var earliestMinute: Int?
        get() {
            val stored = prefs.getInt(KEY_EARLIEST, -1)
            return if (stored < 0) null else stored
        }
        set(value) {
            if (value != null) {
                require(value in 0 until 24 * 60) {
                    "Wake time must be a minute of the day, got $value"
                }
            }
            prefs.edit().putInt(KEY_EARLIEST, value ?: -1).apply()
        }

    /** Alarm, notification, or nothing. */
    var alertStyle: AlertStyle
        get() {
            val stored = prefs.getString(KEY_ALERTS, null) ?: return AlertStyle.DEFAULT
            return AlertStyle.fromName(stored)
        }
        set(value) = prefs.edit().putString(KEY_ALERTS, value.name).apply()

    /** Last known position, so the app can show something before a fix arrives. */
    var lastLatitude: Double
        get() = prefs.getFloat(KEY_LAT, Float.NaN).toDouble()
        set(value) = prefs.edit().putFloat(KEY_LAT, value.toFloat()).apply()

    var lastLongitude: Double
        get() = prefs.getFloat(KEY_LON, Float.NaN).toDouble()
        set(value) = prefs.edit().putFloat(KEY_LON, value.toFloat()).apply()

    fun hasStoredLocation(): Boolean = !lastLatitude.isNaN() && !lastLongitude.isNaN()

    // ---- What you have actually spent today -------------------------------------
    //
    // Only meaningful if you tell the app when you go out. Untracked days simply
    // follow the plan; a tracked day knows the real total.

    /**
     * Sun collected today, in standard doses. Resets on its own when the date
     * changes, so a forgotten session never carries into tomorrow.
     */
    var doseUsedToday: Double
        get() {
            rollOverIfNewDay()
            return prefs.getFloat(KEY_USED, 0f).toDouble()
        }
        private set(value) = prefs.edit().putFloat(KEY_USED, value.toFloat()).apply()

    fun addDoseUsed(dose: Double) {
        require(dose >= 0) { "Dose added must not be negative, got $dose" }
        rollOverIfNewDay()
        doseUsedToday = doseUsedToday + dose
    }

    /** Minute of the day a manually started trip began, or null if none is running. */
    var tripStartedAtMinute: Int?
        get() {
            rollOverIfNewDay()
            val stored = prefs.getInt(KEY_TRIP_START, -1)
            return if (stored < 0) null else stored
        }
        set(value) = prefs.edit().putInt(KEY_TRIP_START, value ?: -1).apply()

    /**
     * Clears today's tally when the calendar date has moved on. Called before every
     * read so a stale figure can never be returned.
     */
    private fun rollOverIfNewDay() {
        val today = Calendar.getInstance().let {
            it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR)
        }
        if (prefs.getInt(KEY_DAY_STAMP, -1) != today) {
            prefs.edit()
                .putInt(KEY_DAY_STAMP, today)
                .putFloat(KEY_USED, 0f)
                .putInt(KEY_TRIP_START, -1)
                .apply()
        }
    }

    companion object {
        const val DEFAULT_SKIN_TYPE = 1

        private const val KEY_SKIN = "skin_type"
        private const val KEY_PROFILE = "risk_profile"
        private const val KEY_SHAPE = "plan_shape"
        private const val KEY_ALERTS = "alert_style"
        private const val KEY_LAT = "last_latitude"
        private const val KEY_LON = "last_longitude"
        private const val KEY_USED = "dose_used_today"
        private const val KEY_TRIP_START = "trip_started_at"
        private const val KEY_DAY_STAMP = "day_stamp"
        private const val KEY_EARLIEST = "earliest_minute"
    }
}
