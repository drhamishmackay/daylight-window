package com.daylight.window

import kotlin.math.roundToInt

/**
 * Turns an hourly UV forecast into the stretches of daylight worth going outside for,
 * and says how much ultraviolet each one costs.
 *
 * Everything here is pure arithmetic on numbers passed in — no network, no Android —
 * so it can be checked by unit tests on its own.
 */
object SunModel {

    /**
     * The dose that just barely reddens skin, by Fitzpatrick type, in standard
     * erythema doses (the unit UV agencies publish). Type I reddens at the lowest
     * dose; each later type tolerates more before showing any colour.
     */
    private val MINIMAL_REDDENING_DOSE = mapOf(
        1 to 2.0, 2 to 2.5, 3 to 3.0, 4 to 4.5, 5 to 6.0, 6 to 10.0
    )

    /**
     * UV index 1 means 25 milliwatts of sunburn-weighted energy per square metre,
     * and one standard erythema dose is 100 joules per square metre. So a minute
     * spent at UV index 1 delivers 25 * 60 / 100 / 1000 of a dose.
     */
    private const val DOSE_PER_UV_PER_MINUTE = 25.0 * 60.0 / 100.0 / 1000.0

    /** Shorter than this is not worth the trip outside. */
    const val SHORTEST_USEFUL_WINDOW_MINUTES = 20

    /** The hourly forecast is interpolated to this resolution. */
    const val SAMPLE_MINUTES = 10

    /** One sampled instant of the day. */
    data class Sample(val minuteOfDay: Int, val uv: Double)

    /** A stretch of daylight sitting at or under the chosen limit. */
    data class Window(
        val startMinute: Int,
        val endMinute: Int,
        val peakUv: Double,
        /** Share of a just-visible-pink dose this window costs, as a percentage. */
        val dosePercent: Double
    ) {
        val lengthMinutes: Int get() = endMinute - startMinute
    }

    fun reddeningDoseFor(skinType: Int): Double =
        MINIMAL_REDDENING_DOSE[skinType]
            ?: throw IllegalArgumentException("Fitzpatrick type must be 1-6, got $skinType")

    /**
     * Expands 24 hourly UV readings into a finer series by straight-line interpolation
     * between the hours.
     */
    fun interpolate(hourlyUv: List<Double>): List<Sample> {
        require(hourlyUv.isNotEmpty()) { "Hourly UV forecast was empty" }
        val out = ArrayList<Sample>()
        var minute = 0
        while (minute <= 23 * 60) {
            val hour = minute / 60
            if (hour >= hourlyUv.size) break
            val nextHour = minOf(hour + 1, hourlyUv.size - 1)
            val fraction = (minute - hour * 60) / 60.0
            val uv = hourlyUv[hour] + (hourlyUv[nextHour] - hourlyUv[hour]) * fraction
            out.add(Sample(minute, uv))
            minute += SAMPLE_MINUTES
        }
        require(out.isNotEmpty()) { "Interpolation produced no samples" }
        return out
    }

    /**
     * How long skin of this type could stand in steady UV before it would just begin
     * to pink. Returns null when the UV is so low the question stops being meaningful.
     */
    fun minutesUntilPinking(uv: Double, skinType: Int): Int? {
        if (uv <= 0.05) return null
        return (reddeningDoseFor(skinType) / (uv * DOSE_PER_UV_PER_MINUTE)).roundToInt()
    }

    /** Share of a pink-threshold dose collected between two times, as a percentage. */
    fun dosePercentBetween(
        samples: List<Sample>,
        fromMinute: Int,
        toMinute: Int,
        skinType: Int
    ): Double {
        var dose = 0.0
        for (s in samples) {
            if (s.minuteOfDay >= fromMinute && s.minuteOfDay < toMinute) {
                dose += s.uv * DOSE_PER_UV_PER_MINUTE * SAMPLE_MINUTES
            }
        }
        return dose / reddeningDoseFor(skinType) * 100.0
    }

    /**
     * Every stretch of daylight where UV stays at or under [uvLimit], long enough to
     * be worth leaving the house for.
     */
    fun findWindows(
        samples: List<Sample>,
        sunriseMinute: Int,
        sunsetMinute: Int,
        uvLimit: Double,
        skinType: Int
    ): List<Window> {
        val windows = ArrayList<Window>()
        var start: Int? = null
        var peak = 0.0

        for (s in samples) {
            val inDaylight = s.minuteOfDay in sunriseMinute..sunsetMinute
            val gentle = inDaylight && s.uv <= uvLimit
            if (gentle) {
                if (start == null) { start = s.minuteOfDay; peak = s.uv }
                else peak = maxOf(peak, s.uv)
            } else if (start != null) {
                windows.add(makeWindow(samples, start, s.minuteOfDay, peak, skinType))
                start = null
            }
        }
        if (start != null) {
            val end = minOf(sunsetMinute, samples.last().minuteOfDay)
            if (end > start) windows.add(makeWindow(samples, start, end, peak, skinType))
        }

        return windows.filter { it.lengthMinutes >= SHORTEST_USEFUL_WINDOW_MINUTES }
    }

    private fun makeWindow(
        samples: List<Sample>,
        start: Int,
        end: Int,
        peak: Double,
        skinType: Int
    ) = Window(start, end, peak, dosePercentBetween(samples, start, end, skinType))

    /** UV at the sample nearest a given time. */
    fun uvAt(samples: List<Sample>, minuteOfDay: Int): Double =
        samples.minByOrNull { kotlin.math.abs(it.minuteOfDay - minuteOfDay) }?.uv
            ?: throw IllegalStateException("No samples to read UV from")
}
