package com.skyglass.astro

import com.skyglass.assertAngleDegEquals
import com.skyglass.dmsToDeg
import com.skyglass.hmsToDeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanetEphemerisTest {

    @Test
    fun meeusExample33a_venusOn1992Dec20() {
        // 1992 December 20, 0h TD (JDE 2448976.5). Meeus, with VSOP87: r = 0.724603 AU, Δ = 0.910845 AU,
        // apparent α = 21h04m41.454s, δ = −18°53′16.84″, and the Earth's R = 0.983824 AU.
        // Ours is a Keplerian model without nutation or stellar aberration (each ≤ 0.006° here),
        // good to a few arcseconds for Venus; 0.005° leaves room for those and nothing else.
        val jde = 2448976.5
        val venus = PlanetEphemeris.geocentric(Planet.VENUS, jde)

        assertEquals(0.724603, venus.sunDistanceAu, 3e-4)
        assertEquals(0.910845, venus.distanceAu, 5e-4)

        val precession = Precession.matrixFromJ2000(AstroTime.julianCenturiesSinceJ2000(jde))
        val eq = Coordinates.unitToRaDec(precession * venus.directionJ2000)
        assertAngleDegEquals(hmsToDeg(21, 4, 41.454), Math.toDegrees(eq.raRad), 0.005)
        assertEquals(dmsToDeg(-1, 18, 53, 16.84), Math.toDegrees(eq.decRad), 0.005)
    }

    @Test
    fun meeusExample33a_venusPhaseAngleFollowsFromTheThreeDistances() {
        // The Sun–Venus–Earth triangle of the same example: r = 0.724603, Δ = 0.910845, R = 0.983824.
        val r = 0.724603
        val delta = 0.910845
        val earthSun = 0.983824
        val expected = Math.toDegrees(kotlin.math.acos((r * r + delta * delta - earthSun * earthSun) / (2 * r * delta)))

        val venus = PlanetEphemeris.geocentric(Planet.VENUS, 2448976.5)
        assertEquals(expected, venus.phaseAngleDeg, 0.3)
        assertTrue("magnitude ${venus.magnitude}", venus.magnitude in -4.5..-3.7)
    }

    @Test
    fun solverHandlesTheWholeOrbit_unitDirectionAndSaneDistances() {
        var jde = 2451545.0 - 20_000 // ~1945
        repeat(120) {
            for (planet in Planet.entries) {
                val g = PlanetEphemeris.geocentric(planet, jde)
                assertEquals(1.0, g.directionJ2000.length(), 1e-12)
                assertTrue("${planet.name} Δ=${g.distanceAu}", g.distanceAu > 0.2)
                assertTrue("${planet.name} phase=${g.phaseAngleDeg}", g.phaseAngleDeg in 0.0..180.0)
            }
            jde += 400.0
        }
    }

    @Test
    fun heliocentricDistances_matchTheSemiMajorAxes() {
        // Averaged over a full orbit the radius is a(1 + e²/2); a loose bound catches a wrong element.
        val expected = mapOf(
            Planet.MERCURY to 0.387, Planet.VENUS to 0.723, Planet.MARS to 1.524, Planet.JUPITER to 5.203,
            Planet.SATURN to 9.537, Planet.URANUS to 19.19, Planet.NEPTUNE to 30.07,
        )
        for ((planet, a) in expected) {
            val r = PlanetEphemeris.geocentric(planet, 2461041.5).sunDistanceAu
            assertTrue("${planet.name} r=$r", r in a * 0.78..a * 1.23)
        }
    }

    @Test
    fun venusGreatestEasternElongation_january2025() {
        // Venus reached its greatest eastern elongation, 47.2°, on 2025 January 10.
        val elongation = elongationFromSunDeg(Planet.VENUS, "2025-01-10T12:00:00Z")
        assertEquals(47.2, elongation, 0.4)
    }

    @Test
    fun mercuryNeverStraysFarFromTheSun() {
        // Mercury's greatest elongation is between 17.9° and 27.8°.
        var maxSeen = 0.0
        var jd = AstroTime.julianDate(com.skyglass.epochMillis("2026-01-01T00:00:00Z"))
        repeat(366) {
            maxSeen = maxOf(maxSeen, elongationFromSunDeg(Planet.MERCURY, jd))
            jd += 1.0
        }
        assertTrue("max elongation $maxSeen", maxSeen in 17.0..28.5)
    }

    @Test
    fun outerPlanets_areOppositeTheSunAtOpposition() {
        // Dates of the 2024-25 oppositions; the planet's ecliptic longitude is then the Sun's + 180°.
        // The date (noon UTC) can be off by half a day, i.e. ≤ 0.7° of relative motion.
        val cases = mapOf(
            Planet.JUPITER to "2024-12-07T12:00:00Z",
            Planet.MARS to "2025-01-16T12:00:00Z",
            Planet.SATURN to "2025-09-21T12:00:00Z",
            Planet.NEPTUNE to "2025-09-23T12:00:00Z",
            Planet.URANUS to "2025-11-21T12:00:00Z",
        )
        for ((planet, isoTime) in cases) {
            val jde = AstroTime.julianEphemerisDay(AstroTime.julianDate(com.skyglass.epochMillis(isoTime)))
            val planetLongitude = eclipticLongitudeDeg(planet, jde)
            val sunLongitude = SunEphemeris.position(jde).trueLongitudeDeg
            assertAngleDegEquals(sunLongitude + 180.0, planetLongitude, 1.0, planet.name)
        }
    }

    @Test
    fun magnitudes_atOppositionMatchTheAlmanac() {
        fun magnitudeAt(planet: Planet, iso: String): Double {
            val jde = AstroTime.julianEphemerisDay(AstroTime.julianDate(com.skyglass.epochMillis(iso)))
            return PlanetEphemeris.geocentric(planet, jde).magnitude
        }
        assertEquals(-2.7, magnitudeAt(Planet.JUPITER, "2024-12-07T12:00:00Z"), 0.2)
        assertEquals(-1.4, magnitudeAt(Planet.MARS, "2025-01-16T12:00:00Z"), 0.3)
        assertEquals(0.5, magnitudeAt(Planet.SATURN, "2025-09-21T12:00:00Z"), 0.4)
        assertEquals(5.7, magnitudeAt(Planet.URANUS, "2025-11-21T12:00:00Z"), 0.2)
        assertEquals(7.8, magnitudeAt(Planet.NEPTUNE, "2025-09-23T12:00:00Z"), 0.2)
    }

    @Test
    fun saturnRingsOpenedUp_makeItBrighter() {
        val edgeOn = PlanetEphemeris.apparentMagnitude(Planet.SATURN, 9.5, 8.5, 2.0, ringSinB = 0.0)
        val open = PlanetEphemeris.apparentMagnitude(Planet.SATURN, 9.5, 8.5, 2.0, ringSinB = 0.44)
        assertTrue("edge-on $edgeOn vs open $open", open < edgeOn - 0.8)
    }

    /** Ecliptic longitude of date, degrees, of the planet seen from the Earth's centre. */
    private fun eclipticLongitudeDeg(planet: Planet, jde: Double): Double {
        val t = AstroTime.julianCenturiesSinceJ2000(jde)
        val direction = Precession.matrixFromJ2000(t) * PlanetEphemeris.geocentric(planet, jde).directionJ2000
        val ecliptic = com.skyglass.math.Matrix3.rotationX(-Coordinates.meanObliquityRad(t)) * direction
        return AstroTime.normalizeDegrees(Math.toDegrees(kotlin.math.atan2(ecliptic.y, ecliptic.x)))
    }

    private fun elongationFromSunDeg(planet: Planet, iso: String): Double =
        elongationFromSunDeg(planet, AstroTime.julianDate(com.skyglass.epochMillis(iso)))

    private fun elongationFromSunDeg(planet: Planet, julianDateUtc: Double): Double {
        val jde = AstroTime.julianEphemerisDay(julianDateUtc)
        val t = AstroTime.julianCenturiesSinceJ2000(jde)
        val planetDirection = Precession.matrixFromJ2000(t) * PlanetEphemeris.geocentric(planet, jde).directionJ2000
        val sunDirection = Coordinates.eclipticToEquatorial(
            Math.toRadians(SunEphemeris.position(jde).trueLongitudeDeg), 0.0, Coordinates.meanObliquityRad(t),
        )
        return Math.toDegrees(kotlin.math.acos((planetDirection dot sunDirection).coerceIn(-1.0, 1.0)))
    }
}
