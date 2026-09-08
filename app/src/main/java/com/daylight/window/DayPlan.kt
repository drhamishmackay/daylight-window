package com.daylight.window

import kotlin.math.roundToInt

/**
 * Turns today's forecast into a plan: when to go out, when to come back in, and what
 * the whole day costs you.
 *
 * The aim is the most MINUTES the budget can buy, not the most sun. Those pull in
 * opposite directions: an hour at dawn and ten minutes at noon cost the same, so the
 * planner always spends the budget on the gentlest daylight available and works
 * inwards from the edges of the day.
 */
object DayPlan {

    /** How the day's outdoor time is arranged. */
    enum class Shape { TWO_SESSIONS, ONE_SESSION }

    /** One period outdoors: go out at [startMinute], come back in at [endMinute]. */
    data class Session(
        val startMinute: Int,
        val endMinute: Int,
        val dose: Double,
        val peakUv: Double
    ) {
        val lengthMinutes: Int get() = endMinute - startMinute
    }

    data class Plan(
        val sessions: List<Session>,
        /** Total dose the plan spends, in standard doses of sunlight. */
        val dose: Double,
        /** The budget it was planned against. */
        val budget: Double,
        val skinType: Int,
        /** True when the whole of daylight fits inside the budget. */
        val wholeDayIsSafe: Boolean,
        val sunriseMinute: Int,
        val sunsetMinute: Int,
        /** Sun already collected today outside the plan, in standard doses. */
        val alreadySpent: Double = 0.0
    ) {
        val totalMinutes: Int get() = sessions.sumOf { it.lengthMinutes }

        /** Everything today costs: the plan plus anything already taken. */
        val totalDose: Double get() = dose + alreadySpent

        /** True when unplanned time outside has used up the whole allowance. */
        val allowanceUsedUp: Boolean get() = sessions.isEmpty() && alreadySpent > 0

        /** Share of the day's budget used in total, as a percentage. */
        val budgetUsedPercent: Double get() = totalDose / budget * 100.0

        /**
         * Share of the dose that would just turn this skin pink. Well under 100 means
         * no burn; the budget keeps it far below that.
         */
        val reddeningPercent: Double
            get() = totalDose / SunModel.reddeningDoseFor(skinType) * 100.0
    }

    /**
     * Builds the plan.
     *
     * With two sessions the budget is halved and each half is spent separately —
     * one from the morning edge of daylight, one ending at the evening edge — so the
     * two together never exceed the day's ceiling.
     */
    /**
     * @param alreadySpent sun already collected today outside the plan, in standard
     *   doses. Recorded when someone tells the app they went out. It is subtracted
     *   from the day's allowance, so an unplanned hour at lunchtime shortens the
     *   evening session rather than being quietly ignored.
     */
    fun build(
        forecast: DayForecast,
        skinType: Int,
        budget: Double,
        shape: Shape,
        alreadySpent: Double = 0.0
    ): Plan {
        require(budget > 0) { "Daily budget must be above zero, got $budget" }
        require(alreadySpent >= 0) { "Sun already spent cannot be negative, got $alreadySpent" }

        // What is left to allocate. Never negative: going over the limit means there
        // is nothing left, not that the app owes you time indoors.
        val remainingBudget = (budget - alreadySpent).coerceAtLeast(0.0)
        if (remainingBudget == 0.0) {
            return Plan(
                sessions = emptyList(),
                dose = 0.0,
                budget = budget,
                skinType = skinType,
                wholeDayIsSafe = false,
                sunriseMinute = forecast.sunriseMinute,
                sunsetMinute = forecast.sunsetMinute,
                alreadySpent = alreadySpent
            )
        }

        val samples = SunModel.interpolate(forecast.hourlyUv)
        val sunrise = forecast.sunriseMinute
        val sunset = forecast.sunsetMinute

        val wholeDayDose = doseBetween(samples, sunrise, sunset)
        if (wholeDayDose <= remainingBudget) {
            // Nothing to ration: the entire day is within the ceiling.
            return Plan(
                sessions = listOf(
                    Session(sunrise, sunset, wholeDayDose, peakBetween(samples, sunrise, sunset))
                ),
                dose = wholeDayDose,
                budget = budget,
                skinType = skinType,
                wholeDayIsSafe = true,
                sunriseMinute = sunrise,
                sunsetMinute = sunset,
                alreadySpent = alreadySpent
            )
        }

        val sessions = when (shape) {
            Shape.ONE_SESSION ->
                listOfNotNull(morningSession(samples, sunrise, sunset, remainingBudget))
            Shape.TWO_SESSIONS -> {
                val half = remainingBudget / 2.0
                listOfNotNull(
                    morningSession(samples, sunrise, sunset, half),
                    eveningSession(samples, sunrise, sunset, half)
                )
            }
        }

        return Plan(
            sessions = sessions,
            dose = sessions.sumOf { it.dose },
            budget = budget,
            skinType = skinType,
            wholeDayIsSafe = false,
            sunriseMinute = sunrise,
            sunsetMinute = sunset,
            alreadySpent = alreadySpent
        )
    }

    /**
     * Spends an allowance forward from first light. Because UV climbs through the
     * morning, starting at the earliest daylight buys the most minutes per unit of
     * exposure.
     */
    private fun morningSession(
        samples: List<SunModel.Sample>,
        sunrise: Int,
        sunset: Int,
        allowance: Double
    ): Session? {
        var spent = 0.0
        var end = sunrise
        for (s in samples) {
            if (s.minuteOfDay < sunrise || s.minuteOfDay >= sunset) continue
            val step = s.uv * SunModel.DOSE_PER_UV_PER_MINUTE * SunModel.SAMPLE_MINUTES
            if (spent + step > allowance) break
            spent += step
            end = s.minuteOfDay + SunModel.SAMPLE_MINUTES
        }
        end = minOf(end, sunset)
        if (end - sunrise < SunModel.SHORTEST_USEFUL_WINDOW_MINUTES) return null
        return Session(sunrise, end, spent, peakBetween(samples, sunrise, end))
    }

    /** The mirror image: spends an allowance backwards from last light. */
    private fun eveningSession(
        samples: List<SunModel.Sample>,
        sunrise: Int,
        sunset: Int,
        allowance: Double
    ): Session? {
        var spent = 0.0
        var start = sunset
        for (s in samples.reversed()) {
            if (s.minuteOfDay >= sunset || s.minuteOfDay < sunrise) continue
            val step = s.uv * SunModel.DOSE_PER_UV_PER_MINUTE * SunModel.SAMPLE_MINUTES
            if (spent + step > allowance) break
            spent += step
            start = s.minuteOfDay
        }
        start = maxOf(start, sunrise)
        if (sunset - start < SunModel.SHORTEST_USEFUL_WINDOW_MINUTES) return null
        return Session(start, sunset, spent, peakBetween(samples, start, sunset))
    }

    /**
     * For a trip outside that is not in the plan: how long you could stay, starting
     * now, before the day's remaining budget runs out.
     *
     * This is what makes an unplanned midday walk answerable rather than a guess.
     */
    fun minutesRemainingFrom(
        forecast: DayForecast,
        startMinute: Int,
        remainingBudget: Double
    ): Int {
        if (remainingBudget <= 0) return 0
        val samples = SunModel.interpolate(forecast.hourlyUv)
        var spent = 0.0
        var minutes = 0
        for (s in samples) {
            if (s.minuteOfDay < startMinute) continue
            if (s.minuteOfDay > forecast.sunsetMinute) break
            val perMinute = s.uv * SunModel.DOSE_PER_UV_PER_MINUTE
            val block = perMinute * SunModel.SAMPLE_MINUTES

            if (spent + block <= remainingBudget) {
                spent += block
                minutes += SunModel.SAMPLE_MINUTES
                continue
            }

            // The budget runs out part-way through this block. Count the minutes that
            // do fit rather than discarding them — in strong sun a whole block can cost
            // more than the day's entire allowance, and "0 minutes" would be a less
            // truthful answer than "about 5".
            if (perMinute > 0) {
                minutes += ((remainingBudget - spent) / perMinute).toInt()
            }
            break
        }
        return minutes
    }

    /** Dose collected outdoors between two times, in standard doses. */
    fun doseBetween(samples: List<SunModel.Sample>, fromMinute: Int, toMinute: Int): Double {
        var dose = 0.0
        for (s in samples) {
            if (s.minuteOfDay in fromMinute until toMinute) {
                dose += s.uv * SunModel.DOSE_PER_UV_PER_MINUTE * SunModel.SAMPLE_MINUTES
            }
        }
        return dose
    }

    private fun peakBetween(samples: List<SunModel.Sample>, from: Int, to: Int): Double =
        samples.filter { it.minuteOfDay in from..to }.maxOfOrNull { it.uv } ?: 0.0
}
