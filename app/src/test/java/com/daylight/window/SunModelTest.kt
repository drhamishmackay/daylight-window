package com.daylight.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunModelTest {

    /** A real Melbourne day: 8 September, UV peaking at 5.6 early afternoon. */
    private val melbourneSpring = listOf(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.05,
        0.65, 1.90, 3.35, 4.60, 5.40, 5.60, 5.15, 3.75,
        2.45, 1.10, 0.25, 0.0, 0.0, 0.0, 0.0, 0.0
    )

    /** A Melbourne winter day, where UV never climbs out of the gentle range. */
    private val melbourneWinter = listOf(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.1, 0.4, 0.9, 1.4, 1.7, 1.6, 1.2, 0.7,
        0.3, 0.05, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
    )

    // The reddening thresholds come from measured medians (Young et al. 2018,
    // PMC6158343, Table 1). This checks the arithmetic reproduces them: type I is
    // 2.1 standard erythema doses, so at UV 8 — which delivers 0.12 of a dose per
    // minute — the threshold should be reached in about eighteen minutes.
    @Test
    fun `pinking time reproduces the measured threshold for the fairest skin`() {
        assertEquals(18, SunModel.minutesUntilPinking(8.0, skinType = 1))
        assertEquals(70, SunModel.minutesUntilPinking(2.0, skinType = 1))
        assertEquals(140, SunModel.minutesUntilPinking(1.0, skinType = 1))
    }

    // Every measured threshold, checked against the arithmetic rather than trusting
    // one type. At UV 4 a minute delivers 0.06 of a dose, so the expected time is
    // simply the type's threshold divided by that.
    @Test
    fun `every skin type reproduces its measured threshold`() {
        val measuredSed = mapOf(1 to 2.1, 2 to 2.6, 3 to 3.2, 4 to 5.6, 5 to 7.5, 6 to 15.2)
        val dosePerMinuteAtUv4 = 4 * 0.015
        for ((type, sed) in measuredSed) {
            val expected = Math.round(sed / dosePerMinuteAtUv4).toInt()
            assertEquals(
                "Type $type should reach its measured threshold at the expected time",
                expected,
                SunModel.minutesUntilPinking(4.0, skinType = type)
            )
        }
    }

    @Test
    fun `darker skin tolerates proportionally more`() {
        val fairest = SunModel.minutesUntilPinking(3.0, skinType = 1)!!
        val darkest = SunModel.minutesUntilPinking(3.0, skinType = 6)!!
        assertTrue("Type VI should tolerate longer than type I", darkest > fairest)
        // The measured medians put type VI at 15.2 doses against type I's 2.1.
        assertEquals(15.2 / 2.1, darkest.toDouble() / fairest, 0.1)
    }

    @Test
    fun `thresholds rise monotonically across the skin types`() {
        val times = (1..6).map { SunModel.minutesUntilPinking(3.0, skinType = it)!! }
        for (i in 1 until times.size) {
            assertTrue(
                "Type ${i + 1} should tolerate at least as long as type $i",
                times[i] > times[i - 1]
            )
        }
    }

    @Test
    fun `negligible ultraviolet has no meaningful pinking time`() {
        assertNull(SunModel.minutesUntilPinking(0.0, skinType = 1))
    }

    @Test
    fun `a gentle limit finds morning and afternoon windows either side of the peak`() {
        val samples = SunModel.interpolate(melbourneSpring)
        val windows = SunModel.findWindows(
            samples = samples,
            sunriseMinute = 6 * 60 + 31,
            sunsetMinute = 18 * 60 + 4,
            uvLimit = 1.0,
            skinType = 1
        )
        assertEquals("Should split the day around the midday peak", 2, windows.size)
        assertTrue("First window is in the morning", windows[0].endMinute < 12 * 60)
        assertTrue("Second window is in the afternoon", windows[1].startMinute > 12 * 60)
        windows.forEach { assertTrue("Never exceeds the limit", it.peakUv <= 1.0) }
    }

    @Test
    fun `raising the limit buys more time outside but costs more exposure`() {
        val samples = SunModel.interpolate(melbourneSpring)
        fun day(limit: Double) = SunModel.findWindows(
            samples, 6 * 60 + 31, 18 * 60 + 4, limit, skinType = 1
        )

        val gentle = day(1.0)
        val permissive = day(3.0)

        val gentleMinutes = gentle.sumOf { it.lengthMinutes }
        val permissiveMinutes = permissive.sumOf { it.lengthMinutes }
        val gentleDose = gentle.sumOf { it.dosePercent }
        val permissiveDose = permissive.sumOf { it.dosePercent }

        assertTrue("More daylight at a higher limit", permissiveMinutes > gentleMinutes)
        assertTrue("But more exposure too", permissiveDose > gentleDose)
        assertTrue("A UV 1 day stays well under a burn", gentleDose < 100.0)
        assertTrue("A UV 3 day would exceed it", permissiveDose > 100.0)
    }

    @Test
    fun `a winter day is gentle all day long`() {
        val samples = SunModel.interpolate(melbourneWinter)
        val windows = SunModel.findWindows(
            samples, 7 * 60 + 32, 17 * 60 + 8, uvLimit = 2.0, skinType = 1
        )
        assertEquals("Winter needs no splitting", 1, windows.size)
        assertTrue(
            "The whole of daylight should qualify",
            windows[0].lengthMinutes > 8 * 60
        )
    }

    @Test
    fun `windows shorter than the useful minimum are dropped`() {
        // A day that only dips under the limit for a single sample.
        val spike = List(24) { hour -> if (hour == 12) 0.5 else 9.0 }
        val samples = SunModel.interpolate(spike)
        val windows = SunModel.findWindows(samples, 0, 23 * 60, uvLimit = 1.0, skinType = 1)
        windows.forEach {
            assertTrue(
                "No window shorter than the minimum survives",
                it.lengthMinutes >= SunModel.SHORTEST_USEFUL_WINDOW_MINUTES
            )
        }
    }

    @Test
    fun `a fully overcast dark day yields one long window`() {
        val samples = SunModel.interpolate(List(24) { 0.0 })
        val windows = SunModel.findWindows(samples, 8 * 60, 17 * 60, 1.0, skinType = 1)
        assertEquals(1, windows.size)
        assertEquals(0.0, windows[0].dosePercent, 0.001)
    }

    @Test
    fun `an empty forecast is rejected rather than silently accepted`() {
        try {
            SunModel.interpolate(emptyList())
            throw AssertionError("Should have refused an empty forecast")
        } catch (expected: IllegalArgumentException) {
            // This is what we want: a loud failure, not a blank day.
        }
    }

    @Test
    fun `an unknown skin type is rejected`() {
        try {
            SunModel.reddeningDoseFor(0)
            throw AssertionError("Should have refused an out-of-range skin type")
        } catch (expected: IllegalArgumentException) {
            // Correct.
        }
    }
}
