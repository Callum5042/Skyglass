package com.skyglass.astro

import com.skyglass.assertAngleDegEquals
import com.skyglass.assertVecEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GalacticTest {

    private fun raDeg(e: Equatorial) = Math.toDegrees(e.raRad)
    private fun decDeg(e: Equatorial) = Math.toDegrees(e.decRad)

    private fun galacticOf(raDeg: Double, decDeg: Double) =
        Galactic.fromEquatorial(Equatorial(Math.toRadians(raDeg), Math.toRadians(decDeg)))

    @Test
    fun galacticCentre_isInSagittarius() {
        // The IAU/Hipparcos centre: l = 0, b = 0 is at RA 266.405°, Dec −28.936° (J2000).
        val centre = Galactic.toEquatorial(0.0, 0.0)
        assertAngleDegEquals(266.405, raDeg(centre), 0.002)
        assertEquals(-28.936, decDeg(centre), 0.002)
    }

    @Test
    fun northGalacticPole_isTheDefiningPosition() {
        val pole = Galactic.toEquatorial(123.0, 90.0)
        assertAngleDegEquals(Galactic.NORTH_POLE_RA_DEG, raDeg(pole), 1e-6)
        assertEquals(Galactic.NORTH_POLE_DEC_DEG, decDeg(pole), 1e-6)
    }

    @Test
    fun northCelestialPole_hasTheDefiningGalacticLongitude() {
        val ncp = Galactic.fromEquatorial(Equatorial(0.0, Math.PI / 2))
        assertAngleDegEquals(Galactic.NORTH_CELESTIAL_POLE_LONGITUDE_DEG, ncp.lDeg, 1e-6)
        assertEquals(Galactic.NORTH_POLE_DEC_DEG, ncp.bDeg, 1e-6)
    }

    @Test
    fun longitudeIncreasesTowardCygnus() {
        // Looking from the Sun, l = 90° is the direction of Galactic rotation, toward Cygnus/Cepheus
        // (RA ~21h, Dec ~+48°), and l = 270° the opposite way, toward Vela/Carina.
        val l90 = Galactic.toEquatorial(90.0, 0.0)
        assertAngleDegEquals(318.0, raDeg(l90), 1.0)
        assertEquals(48.3, decDeg(l90), 0.5)
        val l180 = Galactic.toEquatorial(180.0, 0.0)
        assertAngleDegEquals(86.4, raDeg(l180), 0.1) // the anticentre, in Auriga
        assertEquals(28.9, decDeg(l180), 0.1)
    }

    @Test
    fun wellKnownObjects_haveTheirCataloguedGalacticCoordinates() {
        // Positions (ICRS, degrees) and galactic coordinates as SIMBAD gives them.
        val cases = listOf(
            Triple("Vega", doubleArrayOf(279.23473, 38.78369), doubleArrayOf(67.448, 19.237)),
            Triple("Sirius", doubleArrayOf(101.28716, -16.71612), doubleArrayOf(227.230, -8.890)),
            Triple("Deneb", doubleArrayOf(310.35798, 45.28034), doubleArrayOf(84.284, 1.998)),
            Triple("M31", doubleArrayOf(10.68471, 41.26875), doubleArrayOf(121.174, -21.573)),
            Triple("Sgr A*", doubleArrayOf(266.41683, -29.00781), doubleArrayOf(359.944, -0.046)),
        )
        for ((name, equatorial, expected) in cases) {
            val g = galacticOf(equatorial[0], equatorial[1])
            assertAngleDegEquals(expected[0], g.lDeg, 0.02, "$name l")
            assertEquals("$name b", expected[1], g.bDeg, 0.02)
        }
    }

    @Test
    fun conversionRoundTrips() {
        for (l in listOf(0.0, 33.0, 121.17, 200.0, 359.5)) for (b in listOf(-85.0, -21.6, 0.0, 12.0, 80.0)) {
            val back = Galactic.fromEquatorialUnit(Galactic.toEquatorialUnit(l, b))
            assertAngleDegEquals(l, back.lDeg, 1e-9, "l=$l b=$b")
            assertEquals("b for l=$l b=$b", b, back.bDeg, 1e-9)
        }
    }

    @Test
    fun galacticAxes_areOrthonormal_andRightHanded() {
        val x = Galactic.toEquatorialUnit(0.0, 0.0)
        val y = Galactic.toEquatorialUnit(90.0, 0.0)
        val z = Galactic.toEquatorialUnit(0.0, 90.0)
        assertEquals(0.0, x dot y, 1e-12)
        assertEquals(0.0, x dot z, 1e-12)
        assertEquals(0.0, y dot z, 1e-12)
        assertVecEquals(z, x cross y, 1e-12)
    }
}
