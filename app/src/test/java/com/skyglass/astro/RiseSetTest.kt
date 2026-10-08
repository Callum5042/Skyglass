package com.skyglass.astro

import com.skyglass.epochMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class RiseSetTest {

    private fun jd(iso: String) = AstroTime.julianDate(epochMillis(iso))

    private fun hoursSince(eventJd: Double, midnightIso: String) = (eventJd - jd(midnightIso)) * 24.0

    @Test
    fun find_locatesTheZeroCrossingsOfAKnownCurve() {
        // Altitude (relative to the horizon) of +10·sin(2π(t − 0.25)): below at t = 0, rises at 0.25, sets at 0.75.
        val start = 2461000.5
        val times = RiseSet.find(start) { jd -> 10 * sin(2 * Math.PI * (jd - start - 0.25)) }
        assertEquals(start + 0.25, times.riseJd!!, 1e-5)
        assertEquals(start + 0.75, times.setJd!!, 1e-5)
        assertFalse(times.alwaysAbove)
    }

    @Test
    fun find_reportsAnObjectThatNeverCrosses() {
        val up = RiseSet.find(2461000.5) { 5.0 }
        assertNull(up.riseJd)
        assertNull(up.setJd)
        assertTrue(up.alwaysAbove)

        val down = RiseSet.find(2461000.5) { -5.0 }
        assertNull(down.riseJd)
        assertNull(down.setJd)
        assertFalse(down.alwaysAbove)
    }

    @Test
    fun find_startingAboveGivesTheSetFirst_andTheNextRiseWithinTheWindow() {
        val start = 2461000.5
        // Up for the first quarter day, then down for half a day, then up again.
        val times = RiseSet.find(start) { jd -> 10 * sin(2 * Math.PI * (jd - start + 0.25)) }
        assertEquals(start + 0.25, times.setJd!!, 1e-5)
        assertEquals(start + 0.75, times.riseJd!!, 1e-5)
    }

    @Test
    fun star_riseAndSetAreWhereTheAltitudeCrossesMinus34Arcminutes() {
        // Arcturus-like: RA 14h15m, Dec +19.2°, from London.
        val direction = Coordinates.raDecToUnit(Equatorial(Math.toRadians(213.9), Math.toRadians(19.2)))
        val start = jd("2026-10-08T12:00:00Z")
        val times = RiseSet.forStar(direction, start, 51.5, -0.1)

        for (event in listOf(times.riseJd!!, times.setJd!!)) {
            val altitude = SkyPlacement.of(direction, event, 51.5, -0.1).altitudeDeg
            assertEquals(-RiseSet.STANDARD_REFRACTION_DEG, altitude, 0.005)
        }
        val before = SkyPlacement.of(direction, times.riseJd!! - 5.0 / 1440, 51.5, -0.1).altitudeDeg
        val after = SkyPlacement.of(direction, times.riseJd!! + 5.0 / 1440, 51.5, -0.1).altitudeDeg
        assertTrue("rising: $before then $after", before < after)
    }

    @Test
    fun star_onTheEquatorSeenFromTheEquator_isUpForAboutTwelveHoursAndTwoMinutes() {
        // Refraction adds 34′ either side: H = 90.567° each way, 12.0756 sidereal hours, 12.0426 solar hours.
        val direction = Coordinates.raDecToUnit(Equatorial(1.0, 0.0))
        val times = RiseSet.forStar(direction, jd("2026-10-08T00:00:00Z"), 0.0, 30.0)
        val upDays = (times.setJd!! - times.riseJd!!).let { if (it < 0) it + 0.99726958 else it }
        assertEquals(12.0426, upDays * 24, 0.01)
    }

    @Test
    fun star_circumpolarAndNeverRising() {
        val start = jd("2026-10-08T00:00:00Z")
        val northPole = Coordinates.raDecToUnit(Equatorial(0.0, Math.toRadians(89.0)))
        assertTrue(RiseSet.forStar(northPole, start, 51.5, -0.1).alwaysAbove)

        val southPole = Coordinates.raDecToUnit(Equatorial(0.0, Math.toRadians(-89.0)))
        val never = RiseSet.forStar(southPole, start, 51.5, -0.1)
        assertNull(never.riseJd)
        assertNull(never.setJd)
        assertFalse(never.alwaysAbove)
    }

    @Test
    fun meeusExample15a_venusFromBoston() {
        // Meeus example 15.a: Venus on 1988 March 20 at 42.3333°N, 71.0833°W. The setting
        // m2 = 0.12130 and rising m1 = 0.51766 are fractions of that UT day, i.e. 02:54.7 and 12:25.4.
        val start = "1988-03-20T00:00:00Z"
        val times = RiseSet.forBody(SkyBody.VENUS, jd(start), 42.3333, -71.0833)
        assertEquals(0.12130 * 24, hoursSince(times.setJd!!, start), 1.0 / 60)
        assertEquals(0.51766 * 24, hoursSince(times.riseJd!!, start), 1.0 / 60)
    }

    /**
     * Rise and set times in UTC from the US Naval Observatory's Astronomical Applications API
     * (https://aa.usno.navy.mil/api/rstt/oneday?date=...&coords=lat,lon&tz=0), fetched on 2026-10-08.
     * They are given to the minute, so the tolerance is 2 minutes (our ephemerides, the 34′ refraction
     * convention and the topocentric Moon's upper limb all have to agree with theirs for this to hold).
     */
    private fun assertEventsMatchUsno(
        body: SkyBody,
        date: String,
        lat: Double,
        lon: Double,
        rise: String?,
        set: String?,
    ) {
        val midnight = "${date}T00:00:00Z"
        val times = RiseSet.forBody(body, jd(midnight), lat, lon)
        fun hours(hhmm: String) = hhmm.substringBefore(':').toInt() + hhmm.substringAfter(':').toInt() / 60.0
        if (rise != null) assertEquals("$body rise on $date", hours(rise), hoursSince(times.riseJd!!, midnight), 2.0 / 60)
        if (set != null) assertEquals("$body set on $date", hours(set), hoursSince(times.setJd!!, midnight), 2.0 / 60)
    }

    @Test
    fun usno_london_2026_06_21() {
        // Sun: rise 03:43, set 20:22. Moon: rise 11:41, set 23:46.
        assertEventsMatchUsno(SkyBody.SUN, "2026-06-21", 51.5074, -0.1278, rise = "03:43", set = "20:22")
        assertEventsMatchUsno(SkyBody.MOON, "2026-06-21", 51.5074, -0.1278, rise = "11:41", set = "23:46")
    }

    @Test
    fun usno_london_2026_10_08_thinCrescentMoon() {
        // Sun: rise 06:13, set 17:23. Moon (5 % lit): rise 03:27, set 16:24.
        assertEventsMatchUsno(SkyBody.SUN, "2026-10-08", 51.5074, -0.1278, rise = "06:13", set = "17:23")
        assertEventsMatchUsno(SkyBody.MOON, "2026-10-08", 51.5074, -0.1278, rise = "03:27", set = "16:24")
    }

    @Test
    fun usno_madrid_2026_10_22_waxingGibbousMoon() {
        // Sun: rise 06:33, set 17:25. Moon (84 % lit): set 02:19, rise 15:28 (the set comes first that day).
        assertEventsMatchUsno(SkyBody.SUN, "2026-10-22", 40.4168, -3.7038, rise = "06:33", set = "17:25")
        assertEventsMatchUsno(SkyBody.MOON, "2026-10-22", 40.4168, -3.7038, rise = "15:28", set = "02:19")
    }

    @Test
    fun briefRiseAndSet_closerTogetherThanTheScanStep_isNotMissed() {
        // Altitude peaks 0.5° above the horizon and is above it for only ±3 minutes. Peak 5 minutes
        // after a 10-minute scan sample, so the neighbouring samples are both 0.89° below it.
        val start = 2461000.5
        val peak = start + 145.0 / 1440 // between two 10-minute scan samples
        val minutes = { jd: Double -> (jd - peak) * 1440 }
        val times = RiseSet.find(start) { jd -> 0.5 - 0.5 / 9 * minutes(jd) * minutes(jd) }
        assertEquals(-3.0, minutes(times.riseJd!!), 0.01)
        assertEquals(3.0, minutes(times.setJd!!), 0.01)
        assertFalse(times.alwaysAbove)
    }

    @Test
    fun briefDip_belowTheHorizonWhileUp_isNotMissed() {
        // The mirror image: up all day except for a 6-minute dip, whose neighbouring samples are above.
        val start = 2461000.5
        val trough = start + 145.0 / 1440 // between two 10-minute scan samples
        val minutes = { jd: Double -> (jd - trough) * 1440 }
        val times = RiseSet.find(start) { jd -> -0.5 + 0.5 / 9 * minutes(jd) * minutes(jd) }
        assertEquals(-3.0, minutes(times.setJd!!), 0.01)
        assertEquals(3.0, minutes(times.riseJd!!), 0.01)
        assertFalse(times.alwaysAbove)
    }

    @Test
    fun aNearMiss_thatNeverReachesTheHorizon_isStillNeverUp() {
        val start = 2461000.5
        val peak = start + 145.0 / 1440 // between two 10-minute scan samples
        val times = RiseSet.find(start) { jd -> -0.2 - 0.5 * ((jd - peak) * 1440 / 5) * ((jd - peak) * 1440 / 5) }
        assertNull(times.riseJd)
        assertNull(times.setJd)
        assertFalse(times.alwaysAbove)
    }

    @Test
    fun checkActiveHook_canAbandonTheCalculation() {
        class Abandoned : RuntimeException()
        var calls = 0
        try {
            RiseSet.forStar(Coordinates.raDecToUnit(Equatorial(1.0, 0.3)), jd("2026-10-08T00:00:00Z"), 51.5, -0.1) {
                if (++calls == 5) throw Abandoned()
            }
            org.junit.Assert.fail("the hook's exception should have propagated")
        } catch (expected: Abandoned) {
            assertEquals("stopped at once, not after the full scan", 5, calls)
        }
    }

    @Test
    fun sunInTromso_isUpAllDayAtMidsummerAndDownAllDayAtMidwinter() {
        val summer = RiseSet.forBody(SkyBody.SUN, jd("2026-06-21T00:00:00Z"), 69.65, 18.96)
        assertNull(summer.riseJd)
        assertNull(summer.setJd)
        assertTrue(summer.alwaysAbove)

        val winter = RiseSet.forBody(SkyBody.SUN, jd("2026-12-21T00:00:00Z"), 69.65, 18.96)
        assertNull(winter.riseJd)
        assertNull(winter.setJd)
        assertFalse(winter.alwaysAbove)
    }

    @Test
    fun moon_alwaysShowsARiseOrASetWithinTheWindow_atTheHorizonOfItsUpperLimb() {
        // The Moon rises up to ~25 h apart, so a 26 h window can miss one of the two, never both.
        val lat = 51.5
        val lon = -0.1
        for (day in 0..28) {
            val start = jd("2026-10-01T00:00:00Z") + day
            val times = RiseSet.forBody(SkyBody.MOON, start, lat, lon)
            val rise = times.riseJd
            val set = times.setJd
            assertTrue("day $day: rise or set", rise != null || set != null)
            for (event in listOfNotNull(rise, set)) {
                val moon = SolarSystem.position(SkyBody.MOON, event, lat, lon)
                val altitude = SkyPlacement.of(moon.direction, event, lat, lon).altitudeDeg
                // Upper limb: 34′ of refraction plus the semi-diameter (~0.25°) below the centre.
                assertEquals("day $day", -0.82, altitude, 0.08)
            }
        }
    }

    @Test
    fun moonRiseTime_accountsForTheMoonMovingDuringTheDay() {
        // A rise time from the Moon's position at the start of the day would be off by tens of
        // minutes: its own motion is worth over a degree of altitude by the time it rises.
        val lat = 40.0
        val lon = -3.7
        val start = jd("2026-10-08T00:00:00Z")
        val rise = RiseSet.forBody(SkyBody.MOON, start, lat, lon).riseJd
        assertNotNull(rise)
        val atStart = SolarSystem.position(SkyBody.MOON, start, lat, lon)
        val frozen = SkyPlacement.of(atStart.direction, rise!!, lat, lon).altitudeDeg
        val actual = SkyPlacement.of(SolarSystem.position(SkyBody.MOON, rise, lat, lon).direction, rise, lat, lon).altitudeDeg
        assertTrue("frozen $frozen vs actual $actual", abs(frozen - actual) > 0.5)
        assertEquals(-0.82, actual, 0.08)
    }

    @Test
    fun horizonDeg_perBody() {
        assertEquals(-0.8333, RiseSet.horizonDeg(SkyBody.SUN, 1.0), 1e-9)
        assertEquals(-0.5667, RiseSet.horizonDeg(SkyBody.JUPITER, 5.0), 1e-9)
        val moon = RiseSet.horizonDeg(SkyBody.MOON, 384_400.0 / SolarSystem.KM_PER_AU)
        assertEquals(-0.5667 - 0.2589, moon, 0.001)
    }
}
