package com.skyglass.astro

import com.skyglass.epochMillis
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

class PlanetMoonsTest {

    private fun offsetOf(offsets: List<PlanetMoonOffset>, moon: PlanetMoon) = offsets.first { it.moon == moon }

    @Test
    fun jupitersMoons_matchMeeusExample44a() {
        // Meeus, Astronomical Algorithms, example 44.a: 1992 December 16 at 0h UT (JDE 2448972.50068),
        // low-accuracy method, in Jupiter's equatorial radii, X positive west and Y north.
        val offsets = PlanetMoons.jupiterOffsets(2448972.50068)
        val expected = mapOf(
            PlanetMoon.IO to (-3.44 to 0.21),
            PlanetMoon.EUROPA to (7.44 to 0.25),
            PlanetMoon.GANYMEDE to (1.24 to 0.65),
            PlanetMoon.CALLISTO to (7.08 to 1.10),
        )
        for ((moon, xy) in expected) {
            val o = offsetOf(offsets, moon)
            assertEquals("$moon X", xy.first, o.x, 0.006)
            assertEquals("$moon Y", xy.second, o.y, 0.006)
        }
    }

    @Test
    fun saturnsMoons_matchMeeusExample46a() {
        // Meeus example 46.a, JDE 2451439.50074, in Saturn's equatorial radii. Saturn's geocentric place
        // is JPL Horizons' (fetched 2026-10-11): astrometric ICRF RA 45.148222646°, Dec 14.439732313°,
        // Δ 8.55759967933514 AU, carried to the equator of date as the app's positions are.
        val jde = 2451439.50074
        val j2000 = Coordinates.raDecToUnit(Equatorial(Math.toRadians(45.148222646), Math.toRadians(14.439732313)))
        val ofDate = Precession.matrixFromJ2000(AstroTime.julianCenturiesSinceJ2000(jde)) * j2000
        val offsets = PlanetMoons.saturnOffsets(jde, ofDate, 8.55759967933514)
        val expected = mapOf(
            PlanetMoon.MIMAS to (3.102 to -0.204),
            PlanetMoon.ENCELADUS to (3.823 to 0.318),
            PlanetMoon.TETHYS to (4.027 to -1.061),
            PlanetMoon.DIONE to (-5.365 to -1.148),
            PlanetMoon.RHEA to (-0.972 to -3.136),
            PlanetMoon.TITAN to (14.568 to 4.738),
            PlanetMoon.HYPERION to (-18.001 to -5.328),
            PlanetMoon.IAPETUS to (-48.760 to 4.137),
        )
        for ((moon, xy) in expected) {
            val o = offsetOf(offsets, moon)
            assertEquals("$moon X", xy.first, o.x, 0.003)
            assertEquals("$moon Y", xy.second, o.y, 0.003)
        }
    }

    @Test
    fun aMoonBehindTheDisc_isHidden_butOneInFrontOrBesideItIsNot() {
        val ratio = PlanetMoons.JUPITER_POLAR_RATIO
        assertTrue(PlanetMoons.hiddenBehindDisc(PlanetMoonOffset(PlanetMoon.IO, 0.5, 0.2, behind = true), ratio))
        assertFalse("crossing in front", PlanetMoons.hiddenBehindDisc(PlanetMoonOffset(PlanetMoon.IO, 0.5, 0.2, behind = false), ratio))
        assertFalse("beside the disc", PlanetMoons.hiddenBehindDisc(PlanetMoonOffset(PlanetMoon.IO, 1.2, 0.0, behind = true), ratio))
        // Just inside the equatorial radius but beyond the (smaller) polar one.
        assertFalse("past the pole", PlanetMoons.hiddenBehindDisc(PlanetMoonOffset(PlanetMoon.IO, 0.0, 0.95, behind = true), ratio))
    }

    private val bodies = SolarSystem.positions(AstroTime.julianDate(epochMillis("2026-10-11T22:00:00Z")), 51.5, -0.1)
    private val moons = PlanetMoons.positions(AstroTime.julianDate(epochMillis("2026-10-11T22:00:00Z")), bodies)

    @Test
    fun everyMoonIsPlaced_asAUnitVector_beside_itsPlanet() {
        assertEquals(PlanetMoon.entries.toSet(), moons.map { it.moon }.toSet())
        for (m in moons) {
            val planet = bodies.first { it.body == m.moon.planet }
            assertEquals(m.moon.name, 1.0, m.direction.length(), 1e-12)
            // Within Iapetus' distance (about 60 Saturn radii, 9′ at Saturn's closest).
            val separationDeg = Math.toDegrees(Math.acos((m.direction dot planet.direction).coerceIn(-1.0, 1.0)))
            assertTrue("${m.moon} $separationDeg", separationDeg < 0.2)
        }
    }

    @Test
    fun theOffsetsAreLaidOutInPlanetRadii_westAlongTheEquator_northAlongTheAxis() {
        for (m in moons) {
            val planet = bodies.first { it.body == m.moon.planet }
            val radiusTan = BodySize.radiusKm(planet.body) / (planet.distanceAu * SolarSystem.KM_PER_AU)
            assertEquals(m.moon.name, hypot(m.offset.x, m.offset.y) * radiusTan, m.separationTan, 1e-9)
            // West is toward smaller right ascension. The planets' axes are within about 25° of celestial
            // north on the sky, so a moon well west along the equator is also west in right ascension.
            if (kotlin.math.abs(m.offset.x) > 3 * kotlin.math.abs(m.offset.y)) {
                val d = planet.direction
                val east = (Vector3(0.0, 0.0, 1.0) cross d).normalized()
                val eastward = (m.direction - d) dot east
                assertTrue("${m.moon}: x ${m.offset.x}, eastward $eastward", eastward * m.offset.x < 0)
            }
        }
    }

    @Test
    fun jupitersAxis_isWhereMeeusPutsItInExample43a() {
        // Meeus example 43.a (1992 December 16 at 0h UT): position angle of Jupiter's north pole P = +24.80°
        // and the Earth's jovicentric declination DE = −2.48°. Our moons' "north" is that pole, so a moon
        // straight "north" of Jupiter (offset 0, 10) sits at position angle P.
        val jd = AstroTime.julianDate(epochMillis("1992-12-16T00:00:00Z"))
        val positions = SolarSystem.positions(jd, 0.0, 0.0)
        val jupiter = positions.first { it.body == SkyBody.JUPITER }
        val io = PlanetMoons.positions(jd, positions).first { it.moon == PlanetMoon.IO }
        // Recover the pole's direction on the sky from Io's offset and the frame it was placed in.
        val d = jupiter.direction
        val north = (Vector3(0.0, 0.0, 1.0) - d * d.z).normalized()
        val east = (Vector3(0.0, 0.0, 1.0) cross d).normalized()
        val shift = io.direction * (1.0 / (io.direction dot d)) - d
        // Io's shift is x·west' + y·north'; rotate it back by its own angle in the (west', north') frame.
        val angleOfShift = Math.toDegrees(atan2(shift dot east, shift dot north)) // position angle, east of north
        val angleInFrame = Math.toDegrees(atan2(-io.offset.x, io.offset.y)) // the same measured from the pole
        val p = angleOfShift - angleInFrame
        assertEquals(24.80, ((p + 540.0) % 360.0) - 180.0, 0.2)
    }

    @Test
    fun ganymedeNearOpposition_isAboutMagnitude4point6() {
        // Jupiter at opposition 2026-01-10: Ganymede is listed at about magnitude 4.6 near opposition.
        val jd = AstroTime.julianDate(epochMillis("2026-01-10T00:00:00Z"))
        val ganymede = PlanetMoons.positions(jd, SolarSystem.positions(jd, 0.0, 0.0)).first { it.moon == PlanetMoon.GANYMEDE }
        assertEquals(4.6, ganymede.vmag, 0.15)
    }

    @Test
    fun sideOf_namesTheSkyDirection_eastBeingTowardLargerRightAscension() {
        val planet = Coordinates.raDecToUnit(Equatorial(Math.toRadians(90.0), Math.toRadians(20.0)))
        fun at(raDeg: Double, decDeg: Double) = Coordinates.raDecToUnit(Equatorial(Math.toRadians(raDeg), Math.toRadians(decDeg)))
        assertEquals(SkySide.EAST, PlanetMoons.sideOf(at(90.01, 20.0), planet))
        assertEquals(SkySide.WEST, PlanetMoons.sideOf(at(89.99, 20.0), planet))
        assertEquals(SkySide.NORTH, PlanetMoons.sideOf(at(90.0, 20.01), planet))
        assertEquals(SkySide.SOUTH, PlanetMoons.sideOf(at(90.0, 19.99), planet))
    }
}
