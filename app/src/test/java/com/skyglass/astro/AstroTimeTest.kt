package com.skyglass.astro

import com.skyglass.assertAngleDegEquals
import com.skyglass.epochMillis
import com.skyglass.hmsToDeg
import org.junit.Assert.assertEquals
import org.junit.Test

class AstroTimeTest {

    @Test
    fun julianDate_atJ2000() {
        assertEquals(2451545.0, AstroTime.julianDate(epochMillis("2000-01-01T12:00:00Z")), 1e-9)
    }

    @Test
    fun julianDate_atUnixEpoch() {
        assertEquals(2440587.5, AstroTime.julianDate(0L), 1e-9)
    }

    @Test
    fun julianDate_meeusExample7a() {
        // 1957 October 4.81 (Sputnik 1 launch) = JD 2436116.31
        assertEquals(2436116.31, AstroTime.julianDate(epochMillis("1957-10-04T19:26:24Z")), 1e-6)
    }

    @Test
    fun julianCenturies_isZeroAtJ2000AndOneAfter36525Days() {
        assertEquals(0.0, AstroTime.julianCenturiesSinceJ2000(2451545.0), 1e-12)
        assertEquals(1.0, AstroTime.julianCenturiesSinceJ2000(2451545.0 + 36525.0), 1e-12)
    }

    @Test
    fun gmst_meeusExample12a_atMidnight() {
        val jd = AstroTime.julianDate(epochMillis("1987-04-10T00:00:00Z"))
        assertAngleDegEquals(hmsToDeg(13, 10, 46.3668), AstroTime.greenwichMeanSiderealTimeDeg(jd), 1e-5)
    }

    @Test
    fun gmst_meeusExample12b_atArbitraryTime() {
        val jd = AstroTime.julianDate(epochMillis("1987-04-10T19:21:00Z"))
        assertAngleDegEquals(hmsToDeg(8, 34, 57.0896), AstroTime.greenwichMeanSiderealTimeDeg(jd), 1e-5)
    }

    @Test
    fun gmst_isAlwaysInRange() {
        for (day in -40_000..40_000 step 997) {
            val gmst = AstroTime.greenwichMeanSiderealTimeDeg(AstroTime.JD_J2000 + day + 0.37)
            assert(gmst >= 0.0 && gmst < 360.0) { "GMST $gmst out of range" }
        }
    }

    @Test
    fun lst_addsEastLongitudeAndWraps() {
        val jd = AstroTime.julianDate(epochMillis("1987-04-10T19:21:00Z"))
        val gmst = hmsToDeg(8, 34, 57.0896) // 128.7379°
        // West longitude (Washington, 77°03′56″ W) subtracts.
        assertAngleDegEquals(gmst - 77.065556, AstroTime.localSiderealTimeDeg(jd, -77.065556), 1e-5)
        // East longitude that pushes past 360° wraps back into range.
        val lst = AstroTime.localSiderealTimeDeg(jd, 250.0)
        assertEquals(gmst + 250.0 - 360.0, lst, 1e-5)
    }

    @Test
    fun normalizeDegrees_handlesNegativeAndLargeValues() {
        assertEquals(330.0, AstroTime.normalizeDegrees(-30.0), 1e-12)
        assertEquals(0.5, AstroTime.normalizeDegrees(720.5), 1e-12)
        assertEquals(0.0, AstroTime.normalizeDegrees(360.0), 1e-12)
    }
}
