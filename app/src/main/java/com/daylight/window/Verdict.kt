package com.daylight.window

/**
 * Decides what the app tells you right now. Kept apart from the screen so the wording
 * and the reasoning can be tested without an Android device.
 */
object Verdict {

    enum class Mood { GO, WAIT, FINISHED, NONE }

    data class Result(
        val mood: Mood,
        val status: String,
        val headline: String,
        val detail: String,
        val windows: List<SunModel.Window>,
        val peakUv: Double,
        val totalMinutesOutside: Int,
        val totalDosePercent: Double
    )

    fun decide(
        forecast: DayForecast,
        skinType: Int,
        uvLimit: Double,
        nowMinute: Int
    ): Result {
        val samples = SunModel.interpolate(forecast.hourlyUv)
        val windows = SunModel.findWindows(
            samples, forecast.sunriseMinute, forecast.sunsetMinute, uvLimit, skinType
        )
        val peak = samples.maxOf { it.uv }
        val uvNow = SunModel.uvAt(samples, nowMinute)
        val totalMinutes = windows.sumOf { it.lengthMinutes }
        val totalDose = windows.sumOf { it.dosePercent }

        val open = windows.firstOrNull { nowMinute in it.startMinute..it.endMinute }
        val next = windows.firstOrNull { it.startMinute > nowMinute }

        val uvNowText = String.format("%.1f", uvNow)

        return when {
            open != null -> {
                val remaining = open.endMinute - nowMinute
                val pinkAt = SunModel.minutesUntilPinking(open.peakUv, skinType)
                Result(
                    mood = Mood.GO,
                    status = "Window open",
                    headline = if (remaining > 90) "Go outside. No rush." else "Go outside now.",
                    detail = buildString {
                        append("UV is $uvNowText. The window runs ")
                        append(Format.duration(remaining))
                        append(" more, until ${Format.clock(open.endMinute)}. ")
                        if (pinkAt == null) {
                            append("At this level you could stay out all of it without pinking.")
                        } else {
                            append("Your skin would take about ${Format.duration(pinkAt)} ")
                            append("to start pinking, so stay inside that.")
                        }
                    },
                    windows = windows, peakUv = peak,
                    totalMinutesOutside = totalMinutes, totalDosePercent = totalDose
                )
            }

            next != null -> Result(
                mood = Mood.WAIT,
                status = "Opens ${Format.clock(next.startMinute)}",
                headline = "Wait until ${Format.clock(next.startMinute)}.",
                detail = "UV is $uvNowText, over your limit of ${trim(uvLimit)}. " +
                    "It settles back at ${Format.clock(next.startMinute)} and stays gentle " +
                    "for ${Format.duration(next.lengthMinutes)}.",
                windows = windows, peakUv = peak,
                totalMinutesOutside = totalMinutes, totalDosePercent = totalDose
            )

            windows.isNotEmpty() -> Result(
                mood = Mood.FINISHED,
                status = "Done for today",
                headline = "That was today's daylight.",
                detail = "The last gentle window closed at " +
                    "${Format.clock(windows.last().endMinute)}. " +
                    "Tomorrow's plan is here in the morning.",
                windows = windows, peakUv = peak,
                totalMinutesOutside = totalMinutes, totalDosePercent = totalDose
            )

            else -> Result(
                mood = Mood.NONE,
                status = "Nothing under your limit",
                headline = "No gentle window today.",
                detail = "UV stays over ${trim(uvLimit)} for all of daylight, peaking at " +
                    String.format("%.1f", peak) + ". Going out means covering up.",
                windows = windows, peakUv = peak,
                totalMinutesOutside = totalMinutes, totalDosePercent = totalDose
            )
        }
    }

    /** Shows 1 rather than 1.0 for whole-number limits. */
    private fun trim(value: Double): String =
        if (value == Math.floor(value)) value.toInt().toString() else value.toString()
}
