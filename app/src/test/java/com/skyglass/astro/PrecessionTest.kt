package com.skyglass.astro

import com.skyglass.assertAngleDegEquals
import com.skyglass.assertMatrixEquals
import com.skyglass.dmsToDeg
import com.skyglass.hmsToDeg
import com.skyglass.math.Matrix3
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos

class PrecessionTest {

    private val oneArcsecDeg = 1.0 / 3600.0

    @Test
    fun matrix_isIdentityAtJ2000() {
        assertMatrixEquals(Matrix3.IDENTITY, Precession.matrixFromJ2000(0.0), 1e-15)
    }

    @Test
    fun matrix_isOrthonormal() {
        val p = Precession.matrixFromJ2000(0.5)
        assertMatrixEquals(Matrix3.IDENTITY, p * p.transpose(), 1e-12)
    }

    @Test
    fun meeusExample21b_thetaPersei() {
        // θ Persei, J2000: α = 2h44m11.986s, δ = +49°13′42.48″,
        // μα = +0.03425 s/yr (unprojected), μδ = −0.0895″/yr. Target: 2028 Nov 13.19 = JD 2462088.69.
        val j2000 = Equatorial(
            Math.toRadians(hmsToDeg(2, 44, 11.986)),
            Math.toRadians(dmsToDeg(+1, 49, 13, 42.48)),
        )
        // BSC-style projected proper motion: cos δ · dα/dt, in arcseconds.
        val pmRaProjected = 0.03425 * 15.0 * cos(j2000.decRad)
        val centuries = AstroTime.julianCenturiesSinceJ2000(2462088.69)

        val moved = Precession.applyProperMotion(j2000, pmRaProjected, -0.0895, centuries * 100.0)
        val ofDate = Coordinates.unitToRaDec(
            Precession.matrixFromJ2000(centuries) * Coordinates.raDecToUnit(moved)
        )

        assertAngleDegEquals(hmsToDeg(2, 46, 11.331), Math.toDegrees(ofDate.raRad), 0.5 * oneArcsecDeg, "RA")
        assertEquals(dmsToDeg(+1, 49, 20, 54.54), Math.toDegrees(ofDate.decRad), 0.5 * oneArcsecDeg)
    }

    @Test
    fun equatorialStar_movesAboutAThirdOfADegreeBetweenJ2000And2026() {
        // General precession is ~50.3″/yr along the ecliptic; at α = δ = 0 that is ~0.36° in 26 years.
        val start = Coordinates.raDecToUnit(Equatorial(0.0, 0.0))
        val end = Precession.matrixFromJ2000(0.26) * start
        val angleDeg = Math.toDegrees(kotlin.math.acos(start dot end))
        assert(angleDeg in 0.33..0.39) { "moved $angleDeg°" }
    }

    @Test
    fun properMotion_inRaIsDividedByCosDec() {
        val atEquator = Precession.applyProperMotion(Equatorial(0.0, 0.0), 1.0, 0.0, 3600.0)
        assertEquals(1.0, Math.toDegrees(atEquator.raRad), 1e-12)

        // Same projected motion at δ = 60° covers twice as much RA.
        val at60 = Precession.applyProperMotion(Equatorial(0.0, Math.toRadians(60.0)), 1.0, 0.0, 3600.0)
        assertEquals(2.0, Math.toDegrees(at60.raRad), 1e-9)
    }

    @Test
    fun properMotion_inDec() {
        val moved = Precession.applyProperMotion(Equatorial(0.0, 0.0), 0.0, -2.0, 1800.0)
        assertEquals(-1.0, Math.toDegrees(moved.decRad), 1e-12)
    }
}
