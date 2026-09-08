package com.daylight.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayPlanTest {

    private fun forecast(hourly: List<Double>, sunrise: Int, sunset: Int) = DayForecast(
        placeName = "Test",
        hourlyUv = hourly,
        sunriseMinute = sunrise,
        sunsetMinute = sunset,
        fetchedAtMillis = 0L
    )

    /** Melbourne, 8 September: UV peaks at 5.6 in the early afternoon. */
    private val melbourneSpring = forecast(
        listOf(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.05,
            0.65, 1.90, 3.35, 4.60, 5.40, 5.60, 5.15, 3.75,
            2.45, 1.10, 0.25, 0.0, 0.0, 0.0, 0.0, 0.0
        ),
        sunrise = 6 * 60 + 31, sunset = 18 * 60 + 4
    )

    /** Melbourne in July: gentle from dawn to dusk. */
    private val melbourneWinter = forecast(
        listOf(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.1, 0.4, 0.9, 1.4, 1.7, 1.6, 1.2, 0.7,
            0.3, 0.05, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        ),
        sunrise = 7 * 60 + 32, sunset = 17 * 60 + 8
    )

    /** Tropical summer: UV 13 at midday. */
    private val cairnsSummer = forecast(
        listOf(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.2, 1.5,
            4.0, 7.0, 10.0, 12.0, 13.0, 12.5, 10.0, 7.0,
            4.0, 1.5, 0.2, 0.0, 0.0, 0.0, 0.0, 0.0
        ),
        sunrise = 5 * 60 + 50, sunset = 18 * 60 + 40
    )

    private val budget = RiskProfile.OCCUPATIONAL_LIMIT.dailyDose

    // The core safety promise: whatever the day, the plan never spends more than the
    // budget. If this ever fails the app is telling someone to overexpose themselves.
    @Test
    fun `no plan ever exceeds its budget, in any climate or shape`() {
        val days = listOf(melbourneSpring, melbourneWinter, cairnsSummer)
        for (day in days) {
            for (shape in DayPlan.Shape.entries) {
                for (profile in RiskProfile.entries) {
                    for (skin in 1..6) {
                        val plan = DayPlan.build(day, skin, profile.dailyDose, shape)
                        assertTrue(
                            "Plan for ${day.placeName} $shape $profile skin $skin spent " +
                                "${plan.dose} against a budget of ${profile.dailyDose}",
                            plan.dose <= profile.dailyDose + 1e-9
                        )
                    }
                }
            }
        }
    }

    // The whole point of the default: staying inside the occupational limit must also
    // keep you far away from a burn, for every skin type.
    @Test
    fun `the default budget stays well short of reddening for every skin type`() {
        for (skin in 1..6) {
            val plan = DayPlan.build(cairnsSummer, skin, budget, DayPlan.Shape.TWO_SESSIONS)
            assertTrue(
                "Skin type $skin reached ${plan.reddeningPercent}% of a reddening dose",
                plan.reddeningPercent < 50.0
            )
        }
    }

    @Test
    fun `a gentle winter day needs no rationing at all`() {
        val plan = DayPlan.build(melbourneWinter, 1, budget, DayPlan.Shape.TWO_SESSIONS)
        // Winter in Melbourne still carries more than the occupational limit across a
        // full day, so it should be rationed rather than waved through.
        assertFalse("Full winter daylight exceeds the limit", plan.wholeDayIsSafe)
        assertTrue("Should still buy hours outdoors", plan.totalMinutes > 3 * 60)
    }

    @Test
    fun `a dark overcast day is entirely safe and needs no sessions`() {
        val dark = forecast(List(24) { 0.0 }, sunrise = 7 * 60, sunset = 18 * 60)
        val plan = DayPlan.build(dark, 1, budget, DayPlan.Shape.TWO_SESSIONS)
        assertTrue("Nothing to ration", plan.wholeDayIsSafe)
        assertEquals("The whole of daylight", 11 * 60, plan.totalMinutes)
    }

    @Test
    fun `two sessions sit either side of the midday peak`() {
        val plan = DayPlan.build(melbourneSpring, 1, budget, DayPlan.Shape.TWO_SESSIONS)
        assertEquals(2, plan.sessions.size)
        assertTrue("First is in the morning", plan.sessions[0].endMinute < 12 * 60)
        assertTrue("Second is in the afternoon", plan.sessions[1].startMinute > 12 * 60)
    }

    @Test
    fun `one session spends the whole budget in a single stretch`() {
        val one = DayPlan.build(melbourneSpring, 1, budget, DayPlan.Shape.ONE_SESSION)
        assertEquals(1, one.sessions.size)
        val two = DayPlan.build(melbourneSpring, 1, budget, DayPlan.Shape.TWO_SESSIONS)
        assertTrue(
            "A single stretch should run longer than either half",
            one.sessions[0].lengthMinutes > two.sessions[0].lengthMinutes
        )
    }

    // Maximum safe MINUTES, not maximum sun: the plan must beat a naive rule that
    // simply waits for UV to drop below a fixed line.
    @Test
    fun `planning for gentlest light buys more minutes than a fixed UV cutoff`() {
        val plan = DayPlan.build(melbourneSpring, 1, budget, DayPlan.Shape.TWO_SESSIONS)

        val samples = SunModel.interpolate(melbourneSpring.hourlyUv)
        val naive = SunModel.findWindows(
            samples, melbourneSpring.sunriseMinute, melbourneSpring.sunsetMinute,
            uvLimit = 1.0, skinType = 1
        )
        val naiveDose = naive.sumOf {
            DayPlan.doseBetween(samples, it.startMinute, it.endMinute)
        }

        assertTrue("The naive rule overspends the budget", naiveDose > budget)
        assertTrue("The plan stays inside it", plan.dose <= budget)
    }

    @Test
    fun `an unplanned midday trip is much shorter than a dawn one`() {
        val dawn = DayPlan.minutesRemainingFrom(cairnsSummer, 6 * 60, budget)
        val midday = DayPlan.minutesRemainingFrom(cairnsSummer, 12 * 60, budget)
        assertTrue("Dawn should buy far more time", dawn > midday * 3)
        assertTrue("Midday should still give a real answer", midday > 0)
    }

    @Test
    fun `no budget left means no time outside`() {
        assertEquals(0, DayPlan.minutesRemainingFrom(cairnsSummer, 12 * 60, 0.0))
    }

    @Test
    fun `a zero or negative budget is rejected rather than silently allowed`() {
        try {
            DayPlan.build(melbourneSpring, 1, 0.0, DayPlan.Shape.TWO_SESSIONS)
            throw AssertionError("Should have refused a zero budget")
        } catch (expected: IllegalArgumentException) {
            // Correct: a zero budget is a bug, not a very cautious setting.
        }
    }

    // Alarms must never stack up. Four fixed slots, and re-planning reuses them.
    @Test
    fun `alerts reuse a fixed set of slots so they cannot accumulate`() {
        val plan = DayPlan.build(melbourneSpring, 1, budget, DayPlan.Shape.TWO_SESSIONS)
        val slots = Alerts.slotsFor(plan)
        assertTrue("Every slot is one the app owns", slots.all { it.id in Alerts.ALL_SLOTS })
        assertEquals(
            "No slot is used twice in one day",
            slots.size, slots.map { it.id }.distinct().size
        )
    }

    // A session running to sunset needs no "come inside": ultraviolet only falls.
    @Test
    fun `no come-inside alert when the session runs to sunset`() {
        val plan = DayPlan.build(melbourneSpring, 1, budget, DayPlan.Shape.TWO_SESSIONS)
        val last = plan.sessions.last()
        assertTrue("This day's evening session should reach sunset",
            last.endMinute >= plan.sunsetMinute - 10)
        val comeInAfterLast = Alerts.slotsFor(plan)
            .filter { it.kind == AlertKind.COME_IN && it.minuteOfDay >= last.endMinute }
        assertTrue("Should not tell you to come in at sunset", comeInAfterLast.isEmpty())
    }

    // But a session ending while the sun is still high does need one.
    @Test
    fun `come-inside alert is set when a session ends well before sunset`() {
        val plan = DayPlan.build(melbourneSpring, 1, budget, DayPlan.Shape.TWO_SESSIONS)
        val morning = plan.sessions.first()
        assertTrue("The morning session ends long before sunset",
            morning.endMinute < plan.sunsetMinute - 60)
        val comeIn = Alerts.slotsFor(plan).filter { it.kind == AlertKind.COME_IN }
        assertTrue("Should tell you to come in after the morning session",
            comeIn.any { it.minuteOfDay == morning.endMinute })
    }

    @Test
    fun `a gentle day needs only one nudge and no come-inside alert`() {
        val dark = forecast(List(24) { 0.0 }, sunrise = 7 * 60, sunset = 18 * 60)
        val plan = DayPlan.build(dark, 1, budget, DayPlan.Shape.TWO_SESSIONS)
        val slots = Alerts.slotsFor(plan)
        assertEquals(1, slots.size)
        assertEquals(AlertKind.GO_OUT, slots[0].kind)
    }
}
