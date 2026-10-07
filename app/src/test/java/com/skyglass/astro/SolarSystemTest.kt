package com.skyglass.astro

import com.skyglass.assertAngleDegEquals
import com.skyglass.epochMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos

class SolarSystemTest {

    private fun at(iso: String, latitudeDeg: Double = 51.5, longitudeDeg: Double = -0.1): List<BodyPosition> =
        SolarSystem.positions(AstroTime.julianDate(epochMillis(iso)), latitudeDeg, longitudeDeg)

    private fun List<BodyPosition>.of(body: SkyBody) = first { it.body == body }

    private fun separationDeg(a: BodyPosition, b: BodyPosition) =
        Math.toDegrees(acos((a.direction dot b.direction).coerceIn(-1.0, 1.0)))

    @Test
    fun returnsEveryBodyOnce_inDrawingOrder_asUnitVectors() {
        val bodies = at("2026-10-07T21:00:00Z")
        assertEquals(SkyBody.entries, bodies.map { it.body })
        assertEquals("the Moon must be drawn last", SkyBody.MOON, bodies.last().body)
        for (b in bodies) {
            assertEquals(b.body.name, 1.0, b.direction.length(), 1e-12)
            assertEquals(b.body.name, 1.0, b.sunDirection.length(), 1e-12)
        }
    }

    @Test
    fun theSun_onTheDateOfWriting_isWhereAnAlmanacPutsIt() {
        // Early October: Sun at ecliptic longitude ≈ 194°, i.e. RA ≈ 12h52m (192.9°), Dec ≈ −5.5°.
        val sun = at("2026-10-07T12:00:00Z").of(SkyBody.SUN)
        val eq = Coordinates.unitToRaDec(sun.direction)
        assertAngleDegEquals(192.9, Math.toDegrees(eq.raRad), 0.3)
        assertEquals(-5.5, Math.toDegrees(eq.decRad), 0.2)
        assertEquals(-26.7, sun.vmag, 0.1)
        assertEquals(1.0, sun.illuminatedFraction, 0.0)
        assertEquals(0.0, sun.elongationDeg, 1e-9)
    }

    @Test
    fun totalSolarEclipse2026_moonCoversTheSunFromBurgosButNotFromTheEarthsCentre() {
        // 12 August 2026: totality crosses northern Spain, about 18:27 UTC at Burgos. From there
        // the topocentric Moon and Sun centres nearly coincide, while the geocentric ones are
        // ~0.8° apart (the shadow axis passes 0.9 Earth radii from the Earth's centre). This
        // checks the Moon series, the sidereal time and the sign of the parallax together.
        val burgos = at("2026-08-12T18:27:00Z", 42.34, -3.70)
        val topocentric = separationDeg(burgos.of(SkyBody.MOON), burgos.of(SkyBody.SUN))
        assertTrue("topocentric separation $topocentric°", topocentric < 0.25)
        assertEquals("geocentric elongation", 0.8, burgos.of(SkyBody.MOON).elongationDeg, 0.3)
        assertTrue(burgos.of(SkyBody.MOON).illuminatedFraction < 0.001)
    }

    @Test
    fun parallaxMovesTheMoonByLessThanAboutADegree_andOnlyTheMoon() {
        val iso = "2026-10-07T21:00:00Z"
        val london = at(iso, 51.5, -0.1)
        val sydney = at(iso, -33.9, 151.2)
        for (body in SkyBody.entries) {
            val shift = separationDeg(london.of(body), sydney.of(body))
            if (body == SkyBody.MOON) {
                assertTrue("Moon shift $shift°", shift in 0.2..2.0)
            } else {
                assertTrue("${body.name} shift $shift°", shift < 0.005)
            }
        }
    }

    @Test
    fun theMoon_atTheOctoberFullMoon_isOppositeTheSunAndFullyLit() {
        // Full Moon 2026-10-26 ~04:12 UTC.
        val moon = at("2026-10-26T04:12:00Z").of(SkyBody.MOON)
        assertAngleDegEquals(180.0, moon.lunarPhaseDeg!!, 3.0)
        assertTrue("illuminated ${moon.illuminatedFraction}", moon.illuminatedFraction > 0.99)
        assertEquals(-12.7, moon.vmag, 0.4)
        assertEquals(MoonPhase.FULL, MoonPhase.of(moon.lunarPhaseDeg!!))
    }

    @Test
    fun theMoon_atTheOctoberNewMoon_isNearlyDark() {
        // New Moon 2026-10-10 ~15:50 UTC.
        val moon = at("2026-10-10T15:50:00Z").of(SkyBody.MOON)
        assertAngleDegEquals(0.0, moon.lunarPhaseDeg!!, 3.0)
        assertTrue("illuminated ${moon.illuminatedFraction}", moon.illuminatedFraction < 0.01)
        assertEquals(MoonPhase.NEW, MoonPhase.of(moon.lunarPhaseDeg!!))
    }

    @Test
    fun theMoon_isWaxingAfterNewMoon_andWaningBeforeIt() {
        val waxing = at("2026-10-14T12:00:00Z").of(SkyBody.MOON)
        val waning = at("2026-10-06T12:00:00Z").of(SkyBody.MOON)
        assertTrue("waxing phase ${waxing.lunarPhaseDeg}", waxing.lunarPhaseDeg!! in 30.0..90.0)
        assertTrue("waning phase ${waning.lunarPhaseDeg}", waning.lunarPhaseDeg!! in 270.0..340.0)
        // A waxing Moon is east of the Sun (greater right ascension), a waning one west of it; the
        // sign of z in sun × moon says which, since both are within 180° of each other in RA.
        assertTrue((waxing.sunDirection cross waxing.direction).z > 0)
        assertTrue((waning.sunDirection cross waning.direction).z < 0)
    }

    @Test
    fun illuminatedFraction_matchesTheElongationRoughly() {
        val moon = at("2026-10-14T12:00:00Z").of(SkyBody.MOON)
        val expected = (1 - Math.cos(Math.toRadians(moon.elongationDeg))) / 2
        assertEquals(expected, moon.illuminatedFraction, 0.01)
    }

    @Test
    fun elongations_areSane() {
        val bodies = at("2026-10-07T21:00:00Z")
        for (b in bodies) {
            assertTrue("${b.body.name} ${b.elongationDeg}", b.elongationDeg in 0.0..180.0)
        }
        assertTrue(bodies.of(SkyBody.MERCURY).elongationDeg < 28.5)
        assertTrue(bodies.of(SkyBody.VENUS).elongationDeg < 47.5)
    }

    @Test
    fun planetMagnitudes_areInTheirUsualRanges() {
        val bodies = at("2026-10-07T21:00:00Z")
        assertTrue(bodies.of(SkyBody.VENUS).vmag in -4.95..-3.3)
        assertTrue(bodies.of(SkyBody.JUPITER).vmag in -3.0..-1.5)
        assertTrue(bodies.of(SkyBody.MARS).vmag in -3.0..2.0)
        assertTrue(bodies.of(SkyBody.SATURN).vmag in -0.7..1.6)
        assertTrue(bodies.of(SkyBody.URANUS).vmag in 5.3..6.2)
        assertTrue(bodies.of(SkyBody.NEPTUNE).vmag in 7.5..8.1)
    }

    @Test
    fun planetsHaveNoLunarPhase_andMoonHasOne() {
        val bodies = at("2026-10-07T21:00:00Z")
        for (b in bodies) {
            if (b.body == SkyBody.MOON) assertNotNull(b.lunarPhaseDeg) else assertNull(b.body.name, b.lunarPhaseDeg)
        }
    }

    @Test
    fun moonPhaseNames_coverTheWholeCycle() {
        val expected = listOf(
            0.0 to MoonPhase.NEW, 45.0 to MoonPhase.WAXING_CRESCENT, 90.0 to MoonPhase.FIRST_QUARTER,
            135.0 to MoonPhase.WAXING_GIBBOUS, 180.0 to MoonPhase.FULL, 225.0 to MoonPhase.WANING_GIBBOUS,
            270.0 to MoonPhase.LAST_QUARTER, 315.0 to MoonPhase.WANING_CRESCENT,
            359.0 to MoonPhase.NEW, 360.0 to MoonPhase.NEW, -45.0 to MoonPhase.WANING_CRESCENT,
        )
        for ((degrees, phase) in expected) assertEquals("$degrees°", phase, MoonPhase.of(degrees))
    }
}
