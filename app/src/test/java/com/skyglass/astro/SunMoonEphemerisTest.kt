package com.skyglass.astro

import com.skyglass.assertAngleDegEquals
import com.skyglass.dmsToDeg
import com.skyglass.epochMillis
import com.skyglass.hmsToDeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SunMoonEphemerisTest {

    private fun centuries(jde: Double) = AstroTime.julianCenturiesSinceJ2000(jde)

    /** Equatorial position (degrees) from ecliptic coordinates of date and the mean obliquity. */
    private fun equatorialDeg(lonDeg: Double, latDeg: Double, jde: Double): Pair<Double, Double> {
        val eps = Coordinates.meanObliquityRad(centuries(jde))
        val eq = Coordinates.unitToRaDec(
            Coordinates.eclipticToEquatorial(Math.toRadians(lonDeg), Math.toRadians(latDeg), eps),
        )
        return Math.toDegrees(eq.raRad) to Math.toDegrees(eq.decRad)
    }

    @Test
    fun meeusExample25a_sunOn1992Oct13() {
        // 1992 October 13, 0h TD: geometric longitude 199.90988°, R = 0.99766 AU.
        val jde = 2448908.5
        val sun = SunEphemeris.position(jde)
        assertEquals(199.90988, sun.trueLongitudeDeg, 1e-4)
        assertEquals(0.99766, sun.distanceAu, 1e-5)

        // Meeus's apparent λ = 199.90895° is the geometric longitude less 0.00569° of annual
        // aberration and plus 0.00476° of nutation. We use the geometric one, as for the stars.
        assertEquals(199.90895, sun.trueLongitudeDeg, 0.002)
    }

    @Test
    fun meeusExample25a_sunEquatorialPositionMatchesToTheNutation() {
        // Apparent α = 13h13m31.4s, δ = −7°47′06″. Ours is the mean geometric place, so aberration
        // and nutation (together under 0.001° in longitude here) are the only expected differences.
        val jde = 2448908.5
        val sun = SunEphemeris.position(jde)
        val (ra, dec) = equatorialDeg(sun.trueLongitudeDeg, 0.0, jde)
        assertAngleDegEquals(hmsToDeg(13, 13, 31.4), ra, 0.01)
        assertEquals(dmsToDeg(-1, 7, 47, 6.0), dec, 0.01)
    }

    @Test
    fun marchEquinox2026_sunCrossesLongitudeZero() {
        // The 2026 March equinox was at 14:46 UTC; the geometric longitude is then about +0.005°
        // (apparent = geometric − 0.0057° aberration + 0.005° nutation = 0).
        val jde = AstroTime.julianEphemerisDay(AstroTime.julianDate(epochMillis("2026-03-20T14:46:00Z")))
        assertAngleDegEquals(0.0, SunEphemeris.position(jde).trueLongitudeDeg, 0.02)
    }

    @Test
    fun sunDistance_staysBetweenPerihelionAndAphelion() {
        var jde = 2461041.5 // 2026 Jan 1
        repeat(366) {
            val r = SunEphemeris.position(jde).distanceAu
            assertTrue("R = $r", r in 0.9832..1.0168)
            jde += 1.0
        }
    }

    @Test
    fun meeusExample47a_moonOn1992Apr12() {
        // 1992 April 12, 0h TD.
        val moon = MoonEphemeris.position(2448724.5)
        assertEquals(133.162655, moon.longitudeDeg, 2e-5)
        assertEquals(-3.229126, moon.latitudeDeg, 2e-5)
        assertEquals(368409.7, moon.distanceKm, 0.1)
    }

    @Test
    fun meeusExample47a_moonEquatorialPositionMatchesToTheNutation() {
        // Apparent α = 134.688470°, δ = +13.768368°; Δψ = +0.004610° is the only difference expected.
        val jde = 2448724.5
        val moon = MoonEphemeris.position(jde)
        val (ra, dec) = equatorialDeg(moon.longitudeDeg, moon.latitudeDeg, jde)
        assertAngleDegEquals(134.688470, ra, 0.01)
        assertEquals(13.768368, dec, 0.01)
    }

    @Test
    fun moon_staysWithinItsKnownRanges() {
        var jde = 2461041.5
        repeat(400) {
            val moon = MoonEphemeris.position(jde)
            assertTrue("distance ${moon.distanceKm}", moon.distanceKm in 356_000.0..407_000.0)
            assertTrue("latitude ${moon.latitudeDeg}", moon.latitudeDeg in -5.4..5.4)
            assertTrue("longitude ${moon.longitudeDeg}", moon.longitudeDeg in 0.0..360.0)
            jde += 0.9
        }
    }

    @Test
    fun moon_movesAboutThirteenDegreesADay() {
        val jde = 2461320.5
        val a = MoonEphemeris.position(jde).longitudeDeg
        val b = MoonEphemeris.position(jde + 1.0).longitudeDeg
        val motion = AstroTime.normalizeDegrees(b - a)
        assertTrue("motion $motion", motion in 11.5..15.5)
    }
}
