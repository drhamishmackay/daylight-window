package com.daylight.window

/** Turns minutes into the short, plain phrasings the screen and notifications use. */
object Format {

    /** 615 becomes "10:15am"; 600 becomes "10am". */
    fun clock(minuteOfDay: Int): String {
        val hour24 = (minuteOfDay / 60) % 24
        val minute = minuteOfDay % 60
        val suffix = if (hour24 < 12) "am" else "pm"
        val hour12 = when (hour24 % 12) { 0 -> 12; else -> hour24 % 12 }
        return if (minute == 0) "$hour12$suffix"
        else "$hour12:${minute.toString().padStart(2, '0')}$suffix"
    }

    /** 95 becomes "1h 35m"; 60 becomes "1 hour"; 40 becomes "40 min". */
    fun duration(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours > 0 && rest > 0 -> "${hours}h ${rest}m"
            hours == 1 -> "1 hour"
            hours > 1 -> "$hours hours"
            else -> "$rest min"
        }
    }

    /** Small shares keep one decimal so a gentle day does not round away to zero. */
    fun percent(value: Double): String =
        if (value < 1.0) String.format("%.1f%%", value) else "${Math.round(value)}%"
}
