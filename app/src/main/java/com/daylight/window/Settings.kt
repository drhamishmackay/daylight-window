package com.daylight.window

import android.content.Context

/**
 * The few choices the app remembers. Kept deliberately small: the point of the app
 * is that you stop making decisions, not that you tune it.
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

    /**
     * The UV the app will not send you out above. Defaults to 1, which for type I skin
     * buys roughly two and a half hours before pinking — the setting that yields the
     * most time outdoors per unit of accumulated exposure.
     */
    var uvLimit: Double
        get() = prefs.getFloat(KEY_LIMIT, DEFAULT_UV_LIMIT).toDouble()
        set(value) {
            require(value > 0) { "UV limit must be above zero, got $value" }
            prefs.edit().putFloat(KEY_LIMIT, value.toFloat()).apply()
        }

    var remindersOn: Boolean
        get() = prefs.getBoolean(KEY_REMIND, false)
        set(value) = prefs.edit().putBoolean(KEY_REMIND, value).apply()

    /** Last known position, so the app can show something before the fix arrives. */
    var lastLatitude: Double
        get() = prefs.getFloat(KEY_LAT, Float.NaN).toDouble()
        set(value) = prefs.edit().putFloat(KEY_LAT, value.toFloat()).apply()

    var lastLongitude: Double
        get() = prefs.getFloat(KEY_LON, Float.NaN).toDouble()
        set(value) = prefs.edit().putFloat(KEY_LON, value.toFloat()).apply()

    fun hasStoredLocation(): Boolean = !lastLatitude.isNaN() && !lastLongitude.isNaN()

    companion object {
        const val DEFAULT_SKIN_TYPE = 1
        const val DEFAULT_UV_LIMIT = 1.0f

        private const val KEY_SKIN = "skin_type"
        private const val KEY_LIMIT = "uv_limit"
        private const val KEY_REMIND = "reminders_on"
        private const val KEY_LAT = "last_latitude"
        private const val KEY_LON = "last_longitude"
    }
}
