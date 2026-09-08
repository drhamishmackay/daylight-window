package com.daylight.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUvTest {

    /** A trimmed copy of the real feed, including a station reporting a fault. */
    private val feed = """
        <?xml version="1.0" encoding="utf-8"?>
        <stations>
          <location id="Melbourne">
            <name>mel</name>
            <index>4.5</index>
            <time>12:11 PM</time>
            <utcdatetime>2026/09/08 02:11</utcdatetime>
            <status>ok</status>
          </location>
          <location id="Sydney">
            <name>syd</name>
            <index>0.0</index>
            <time>12:11 PM</time>
            <utcdatetime>2026/09/08 02:11</utcdatetime>
            <status>fault</status>
          </location>
        </stations>
    """.trimIndent()

    @Test
    fun `reads a working station`() {
        val reading = LiveUv.parseStation(feed, "Melbourne")
        assertEquals(4.5, reading!!.first, 0.001)
        assertEquals("2026/09/08 02:11", reading.second)
    }

    // A broken sensor reporting zero must never be read as "there is no sun" — that
    // would tell someone the whole day is safe when it is not.
    @Test
    fun `a station reporting a fault is ignored rather than believed`() {
        assertNull(LiveUv.parseStation(feed, "Sydney"))
    }

    @Test
    fun `a station that is not in the feed gives nothing`() {
        assertNull(LiveUv.parseStation(feed, "Hobart"))
    }

    @Test
    fun `distance between two known cities is about right`() {
        // Melbourne to Sydney is roughly 700 km in a straight line.
        val km = LiveUv.distanceKm(-37.81, 144.96, -33.87, 151.21)
        assertTrue("Got $km km", km in 690.0..720.0)
    }

    @Test
    fun `standing in the city is essentially at the station`() {
        val km = LiveUv.distanceKm(-37.81, 144.96, -37.81, 144.96)
        assertTrue(km < 1.0)
    }

    // The real case that prompted this: on 8 September the forecast said 5.4 at noon
    // in Melbourne while the station measured 4.5, about 20 per cent high.
    @Test
    fun `a forecast running high is scaled down toward the measurement`() {
        val factor = LiveUv.correctionFactor(forecastUvNow = 5.4, measuredUvNow = 4.5)
        assertEquals(0.833, factor, 0.01)
    }

    @Test
    fun `a forecast running low is scaled up`() {
        val factor = LiveUv.correctionFactor(forecastUvNow = 3.0, measuredUvNow = 4.0)
        assertTrue("Should scale up", factor > 1.0)
    }

    @Test
    fun `an agreeing forecast is left alone`() {
        val factor = LiveUv.correctionFactor(forecastUvNow = 5.0, measuredUvNow = 5.0)
        assertEquals(1.0, factor, 0.001)
    }

    // One station under a passing cloud must not rewrite the whole day.
    @Test
    fun `a wild disagreement is capped rather than trusted`() {
        val tiny = LiveUv.correctionFactor(forecastUvNow = 10.0, measuredUvNow = 0.6)
        assertTrue("Should not scale below half", tiny >= 0.5)

        val huge = LiveUv.correctionFactor(forecastUvNow = 1.0, measuredUvNow = 12.0)
        assertTrue("Should not scale above one and a half", huge <= 1.5)
    }

    // Dividing two near-zero numbers amplifies noise instead of correcting anything.
    @Test
    fun `readings too small to be meaningful leave the forecast alone`() {
        assertEquals(1.0, LiveUv.correctionFactor(0.2, 0.1), 0.001)
        assertEquals(1.0, LiveUv.correctionFactor(5.0, 0.1), 0.001)
        assertEquals(1.0, LiveUv.correctionFactor(0.1, 5.0), 0.001)
    }

    // Correcting the curve must not break the promise that plans stay within budget.
    @Test
    fun `a corrected forecast still produces a plan inside its budget`() {
        val raw = DayForecast(
            placeName = "Test",
            hourlyUv = listOf(
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.05,
                0.65, 1.90, 3.35, 4.60, 5.40, 5.60, 5.15, 3.75,
                2.45, 1.10, 0.25, 0.0, 0.0, 0.0, 0.0, 0.0
            ),
            sunriseMinute = 6 * 60 + 31,
            sunsetMinute = 18 * 60 + 4,
            fetchedAtMillis = 0L
        )

        for (factor in listOf(0.5, 0.83, 1.0, 1.5)) {
            val corrected = raw.copy(hourlyUv = raw.hourlyUv.map { it * factor })
            val budget = RiskProfile.OCCUPATIONAL_LIMIT.dailyDose
            val plan = DayPlan.build(corrected, 1, budget, DayPlan.Shape.TWO_SESSIONS)
            assertTrue(
                "Correction $factor produced a plan spending ${plan.dose}",
                plan.dose <= budget + 1e-9
            )
        }
    }

    // A gentler sky than forecast should buy more time outside, not less.
    @Test
    fun `a cloudier day than forecast earns more time outdoors`() {
        val raw = DayForecast(
            placeName = "Test",
            hourlyUv = listOf(
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.05,
                0.65, 1.90, 3.35, 4.60, 5.40, 5.60, 5.15, 3.75,
                2.45, 1.10, 0.25, 0.0, 0.0, 0.0, 0.0, 0.0
            ),
            sunriseMinute = 6 * 60 + 31,
            sunsetMinute = 18 * 60 + 4,
            fetchedAtMillis = 0L
        )
        val budget = RiskProfile.OCCUPATIONAL_LIMIT.dailyDose
        val asForecast = DayPlan.build(raw, 1, budget, DayPlan.Shape.TWO_SESSIONS)
        val cloudier = DayPlan.build(
            raw.copy(hourlyUv = raw.hourlyUv.map { it * 0.7 }),
            1, budget, DayPlan.Shape.TWO_SESSIONS
        )
        assertTrue(
            "A cloudier sky should buy more minutes",
            cloudier.totalMinutes > asForecast.totalMinutes
        )
    }
}
