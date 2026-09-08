package com.daylight.window

/**
 * Turns a plan and the time of day into something a person can read at a glance.
 *
 * No percentages, no doses, no units. The screen answers three questions: should I go
 * out now, how long for, and am I safe.
 */
object Advice {

    enum class State {
        /** A planned session is running. */
        OUT_NOW,
        /** A planned session is coming later today. */
        LATER,
        /** Every planned session has passed. */
        DONE,
        /** Outside the plan, with budget left — the app can still answer "how long". */
        UNPLANNED,
        /** Nothing left today. */
        SPENT
    }

    data class Now(
        val state: State,
        val headline: String,
        val detail: String,
        val safetyLine: String,
        val minutesRemaining: Int
    )

    /**
     * What today's plan costs, in plain words.
     *
     * This sits beside the daily-limit setting on the screen, not beside the live
     * advice, because it describes the setting rather than the moment. The setting's
     * own description already explains what the limit is, so this says only what
     * today's plan spends against it — never repeating the explanation.
     */
    fun safetyOf(plan: DayPlan.Plan, profile: RiskProfile): String {
        val burnShare = plan.reddeningPercent
        val withinGuideline = plan.dose <= RiskProfile.OCCUPATIONAL_LIMIT.dailyDose

        return when {
            plan.totalMinutes == 0 ->
                "There is no time outside today that would keep you under this limit."

            withinGuideline && burnShare < 50 ->
                "Today's plan uses less than half of what it would take to turn your " +
                    "skin pink. No burn, and nothing meaningful added to your lifetime risk."

            withinGuideline ->
                "Today's plan stays under this limit. No burn risk, and nothing " +
                    "meaningful added to your lifetime risk."

            profile == RiskProfile.OUTDOOR_WORKER ->
                "Today's plan goes past the workplace limit, which is what this setting " +
                    "allows. Still no burn, but more than the guideline advises."

            else ->
                "Today's plan runs a little past the limit, but stays well short of a burn."
        }
    }

    fun describe(
        plan: DayPlan.Plan,
        profile: RiskProfile,
        nowMinute: Int,
        minutesAlreadySpentOutside: Int,
        forecast: DayForecast
    ): Now {
        val safety = safetyOf(plan, profile)

        if (plan.wholeDayIsSafe) {
            val leftToday = maxOf(0, plan.sunsetMinute - maxOf(nowMinute, plan.sunriseMinute))
            return Now(
                state = if (nowMinute in plan.sunriseMinute..plan.sunsetMinute)
                    State.OUT_NOW else State.LATER,
                headline = "Go outside whenever you like.",
                detail = "The sun is gentle enough today that the whole of daylight fits " +
                    "inside your limit. You do not need to watch the clock.",
                safetyLine = safety,
                minutesRemaining = leftToday
            )
        }

        val running = plan.sessions.firstOrNull { nowMinute in it.startMinute until it.endMinute }
        val next = plan.sessions.firstOrNull { it.startMinute > nowMinute }

        if (running != null) {
            val left = running.endMinute - nowMinute
            return Now(
                state = State.OUT_NOW,
                headline = "Go outside now.",
                detail = "You have until ${Format.clock(running.endMinute)} — " +
                    "${Format.duration(left)} from now. Come back in then and you stay " +
                    "inside your limit for the day.",
                safetyLine = safety,
                minutesRemaining = left
            )
        }

        if (next != null) {
            val until = next.startMinute - nowMinute
            return Now(
                state = State.LATER,
                headline = "Next time out: ${Format.clock(next.startMinute)}.",
                detail = "The sun is too strong right now. In ${Format.duration(until)} it " +
                    "settles, and you will have ${Format.duration(next.lengthMinutes)} outside.",
                safetyLine = safety,
                minutesRemaining = 0
            )
        }

        // Past every planned session. Work out whether anything is left.
        val spentDose = plan.sessions
            .filter { it.endMinute <= nowMinute }
            .sumOf { it.dose }
        val remaining = plan.budget - spentDose
        val couldStay = DayPlan.minutesRemainingFrom(forecast, nowMinute, remaining)

        return if (couldStay >= SunModel.SHORTEST_USEFUL_WINDOW_MINUTES) {
            Now(
                state = State.UNPLANNED,
                headline = "Your planned time is done.",
                detail = "If you do go out now, you have about " +
                    "${Format.duration(couldStay)} before you reach your limit for today.",
                safetyLine = safety,
                minutesRemaining = couldStay
            )
        } else {
            Now(
                state = State.SPENT,
                headline = "That is today's sun.",
                detail = "You have had your outdoor time for the day. Tomorrow's plan is " +
                    "ready in the morning.",
                safetyLine = safety,
                minutesRemaining = 0
            )
        }
    }

    /**
     * For an unplanned trip: how long, starting now, before the day's limit is reached.
     * Deliberately blunt about midday sun, since that is when the answer is shortest
     * and the temptation to ignore it is greatest.
     */
    fun unplannedTripAdvice(minutes: Int, uvNow: Double): String = when {
        minutes <= 0 ->
            "Nothing left for today. Going out now adds to your lifetime total."
        minutes < 15 ->
            "You have about ${Format.duration(minutes)}. The sun is strong right now."
        minutes < 60 ->
            "You have about ${Format.duration(minutes)}."
        else ->
            "You have about ${Format.duration(minutes)} — plenty of room."
    }
}
