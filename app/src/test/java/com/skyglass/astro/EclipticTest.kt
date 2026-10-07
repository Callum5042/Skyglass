package com.skyglass.astro

import com.skyglass.assertAngleDegEquals
import com.skyglass.assertVecEquals
import com.skyglass.dmsToDeg
import com.skyglass.hmsToDeg
import org.junit.Assert.assertEquals
import org.junit.Test

class EclipticTest {

    @Test
    fun meeusExample22a_meanObliquity() {
        // 1987 April 10, 0h TD: ε0 = 23°26′27.407″.
        val t = (2446895.5 - AstroTime.JD_J2000) / 36525.0
        val expected = dmsToDeg(+1, 23, 26, 27.407)
        assertEquals(expected, Math.toDegrees(Coordinates.meanObliquityRad(t)), 1e-6)
    }

    @Test
    fun meanObliquityAtJ2000_is23d26m21s() {
        assertEquals(dmsToDeg(+1, 23, 26, 21.448), Math.toDegrees(Coordinates.meanObliquityRad(0.0)), 1e-9)
    }

    @Test
    fun meeusExample13a_polluxEclipticToEquatorial() {
        // Pollux: λ = 113.215630°, β = +6.684170°, ε = 23.4392911° → α = 7h45m18.946s, δ = +28°01′34.26″.
        val v = Coordinates.eclipticToEquatorial(
            Math.toRadians(113.215630), Math.toRadians(6.684170), Math.toRadians(23.4392911),
        )
        val eq = Coordinates.unitToRaDec(v)
        assertAngleDegEquals(hmsToDeg(7, 45, 18.946), Math.toDegrees(eq.raRad), 1e-5)
        assertEquals(dmsToDeg(+1, 28, 1, 34.26), Math.toDegrees(eq.decRad), 1e-5)
    }

    @Test
    fun eclipticToEquatorial_keepsTheEquinoxAndTiltsThePole() {
        val eps = Math.toRadians(23.44)
        assertVecEquals(
            com.skyglass.math.Vector3(1.0, 0.0, 0.0),
            Coordinates.eclipticToEquatorial(0.0, 0.0, eps), 1e-12,
        )
        // The northern ecliptic pole lies 23.44° from the celestial pole, toward RA 18h.
        val pole = Coordinates.unitToRaDec(Coordinates.eclipticToEquatorial(0.0, Math.PI / 2, eps))
        assertEquals(90.0 - 23.44, Math.toDegrees(pole.decRad), 1e-9)
        assertAngleDegEquals(270.0, Math.toDegrees(pole.raRad), 1e-9)
        // The solstice point (λ = 90°) is at RA 6h, declination +ε.
        val solstice = Coordinates.unitToRaDec(Coordinates.eclipticToEquatorial(Math.PI / 2, 0.0, eps))
        assertEquals(23.44, Math.toDegrees(solstice.decRad), 1e-9)
        assertAngleDegEquals(90.0, Math.toDegrees(solstice.raRad), 1e-9)
    }

    @Test
    fun julianEphemerisDay_isUtcPlusSixtyNineSeconds() {
        val jd = 2461320.25
        assertEquals(69.184 / 86400.0, AstroTime.julianEphemerisDay(jd) - jd, 1e-8) // double precision at JD 2.4e6
    }
}
