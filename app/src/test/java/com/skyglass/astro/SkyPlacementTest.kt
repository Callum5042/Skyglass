package com.skyglass.astro

import com.skyglass.assertAngleDegEquals
import com.skyglass.dmsToDeg
import com.skyglass.epochMillis
import com.skyglass.hmsToDeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyPlacementTest {

    private fun rad(deg: Double) = Math.toRadians(deg)

    @Test
    fun meeusExample13b_venusFromWashington() {
        // Same worked example as CoordinatesTest, through the unit-vector entry point the cards use:
        // h = 15.1249°, azimuth 68.0337° from south = 248.0337° from north.
        val raDeg = hmsToDeg(23, 9, 16.641)
        val decDeg = dmsToDeg(-1, 6, 43, 11.61)
        val direction = Coordinates.raDecToUnit(Equatorial(rad(raDeg), rad(decDeg)))
        val jd = AstroTime.julianDate(epochMillis("1987-04-10T19:21:00Z"))

        val placement = SkyPlacement.of(direction, jd, dmsToDeg(+1, 38, 55, 17.0), dmsToDeg(-1, 77, 3, 56.0))

        assertEquals(15.1249, placement.altitudeDeg, 0.003)
        assertAngleDegEquals(248.0337, placement.azimuthDeg, 0.003)
        assertAngleDegEquals(raDeg, placement.raDeg, 1e-9)
        assertEquals(decDeg, placement.decDeg, 1e-9)
    }

    @Test
    fun northPoleStar_isDueNorthAtAnAltitudeEqualToTheLatitude() {
        val pole = Coordinates.raDecToUnit(Equatorial(0.0, rad(90.0)))
        val placement = SkyPlacement.of(pole, AstroTime.julianDate(epochMillis("2026-10-08T22:00:00Z")), 51.5, -0.1)
        assertEquals(51.5, placement.altitudeDeg, 1e-9)
        assertTrue("azimuth ${placement.azimuthDeg}", placement.azimuthDeg < 1e-6 || placement.azimuthDeg > 360 - 1e-6)
        assertEquals(90.0, placement.decDeg, 1e-9)
    }

    @Test
    fun aStarOnTheMeridianSouthOfZenith_isDueSouth_andOneToTheEastHasNotTransitedYet() {
        // Local sidereal time equals the star's RA when it transits; the equator at latitude 40°N is 50° up in the south.
        val jd = AstroTime.julianDate(epochMillis("2026-03-01T03:00:00Z"))
        val lst = AstroTime.localSiderealTimeDeg(jd, 10.0)
        val onMeridian = SkyPlacement.of(Coordinates.raDecToUnit(Equatorial(rad(lst), 0.0)), jd, 40.0, 10.0)
        assertEquals(50.0, onMeridian.altitudeDeg, 1e-6)
        assertAngleDegEquals(180.0, onMeridian.azimuthDeg, 1e-6)

        // An hour of right ascension east of the meridian has not transited yet: it is rising, so east of south.
        val east = SkyPlacement.of(Coordinates.raDecToUnit(Equatorial(rad(lst + 15.0), 0.0)), jd, 40.0, 10.0)
        assertTrue("azimuth ${east.azimuthDeg}", east.azimuthDeg in 90.0..180.0)
    }

    @Test
    fun compassPoint_isCentredOnTheEightPoints_andWrapsAtNorth() {
        assertEquals(CompassPoint.N, CompassPoint.of(0.0))
        assertEquals(CompassPoint.N, CompassPoint.of(22.4))
        assertEquals(CompassPoint.NE, CompassPoint.of(22.6))
        assertEquals(CompassPoint.E, CompassPoint.of(90.0))
        assertEquals(CompassPoint.SE, CompassPoint.of(135.0))
        assertEquals(CompassPoint.S, CompassPoint.of(180.0))
        assertEquals(CompassPoint.SW, CompassPoint.of(225.0))
        assertEquals(CompassPoint.W, CompassPoint.of(270.0))
        assertEquals(CompassPoint.NW, CompassPoint.of(315.0))
        assertEquals(CompassPoint.N, CompassPoint.of(337.6))
        assertEquals(CompassPoint.NW, CompassPoint.of(337.4))
        assertEquals(CompassPoint.N, CompassPoint.of(359.9))
        assertEquals(CompassPoint.E, CompassPoint.of(-270.0))
    }

    @Test
    fun sexagesimal_splitsAndCarries() {
        // Meeus 13.b Venus: δ = −6°43′11.61″ → whole seconds, sign kept separately.
        val dec = Sexagesimal.of(dmsToDeg(-1, 6, 43, 11.61))
        assertEquals(Sexagesimal(negative = true, whole = 6, minutes = 43, seconds = 12), dec)

        // 59.6 s must carry into the minute, not print 60.
        val carried = Sexagesimal.of(dmsToDeg(+1, 10, 29, 59.6))
        assertEquals(Sexagesimal(negative = false, whole = 10, minutes = 30, seconds = 0), carried)

        // The sign of a value that rounds to zero is dropped, so "-0° 00′ 00″" cannot appear.
        assertFalse(Sexagesimal.of(-0.00001).negative)

        // Right ascension 23h59m59.9s is 0h0m0s, not 24h.
        assertEquals(Sexagesimal(false, 0, 0, 0), Sexagesimal.ofHours(23.0 + 59 / 60.0 + 59.9 / 3600.0))
        assertEquals(Sexagesimal(false, 23, 9, 17), Sexagesimal.ofHours(hmsToDeg(23, 9, 16.641) / 15.0))
    }
}
