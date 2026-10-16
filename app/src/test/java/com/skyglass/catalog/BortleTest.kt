package com.skyglass.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Class boundaries from Bortle (2001) as tabulated on Wikipedia's "Bortle scale" page. */
class BortleTest {

    @Test
    fun classesFollowTheTabulatedNakedEyeLimits() {
        assertEquals(1, Bortle.classForLimitingMagnitude(7.8))
        assertEquals(2, Bortle.classForLimitingMagnitude(7.3))
        assertEquals(3, Bortle.classForLimitingMagnitude(6.8))
        assertEquals(4, Bortle.classForLimitingMagnitude(6.3))
        assertEquals(5, Bortle.classForLimitingMagnitude(5.8))
        assertEquals(6, Bortle.classForLimitingMagnitude(5.3))
        assertEquals(7, Bortle.classForLimitingMagnitude(4.8))
        assertEquals(8, Bortle.classForLimitingMagnitude(4.3))
        assertEquals(9, Bortle.classForLimitingMagnitude(4.0))
    }

    @Test
    fun classBoundariesBelongToTheDarkerClass() {
        assertEquals(4, Bortle.classForLimitingMagnitude(6.1))
        assertEquals(5, Bortle.classForLimitingMagnitude(6.0))
        assertEquals(8, Bortle.classForLimitingMagnitude(4.1))
        assertEquals(9, Bortle.classForLimitingMagnitude(4.09))
    }

    @Test
    fun theSliderRange_spansBortle3To9() {
        assertEquals(3, Bortle.classForLimitingMagnitude(StarVisibility.MAX_LIMIT))
        assertEquals(9, Bortle.classForLimitingMagnitude(StarVisibility.MIN_LIMIT))
    }

    /** The middle of each class's range in Wikipedia's sky brightness (SQM) column. */
    @Test
    fun skyBrightnessClassesFollowTheTabulatedSqmRanges() {
        assertEquals(1, Bortle.classForSkyBrightness(21.88))
        assertEquals(2, Bortle.classForSkyBrightness(21.68))
        assertEquals(3, Bortle.classForSkyBrightness(21.45))
        assertEquals(4, Bortle.classForSkyBrightness(21.05))
        assertEquals(4, Bortle.classForSkyBrightness(20.55)) // 4.5 counts as 4, as for limiting magnitudes
        assertEquals(5, Bortle.classForSkyBrightness(19.8))
        assertEquals(6, Bortle.classForSkyBrightness(18.9))
        assertEquals(7, Bortle.classForSkyBrightness(18.25))
        assertEquals(8, Bortle.classForSkyBrightness(17.8))
        assertEquals(9, Bortle.classForSkyBrightness(16.5))
        assertEquals(1, Bortle.classForSkyBrightness(23.0))
    }

    @Test
    fun skyBrightnessBoundariesBelongToTheDarkerClass() {
        assertEquals(1, Bortle.classForSkyBrightness(21.76))
        assertEquals(2, Bortle.classForSkyBrightness(21.75))
        assertEquals(7, Bortle.classForSkyBrightness(18.0))
        assertEquals(8, Bortle.classForSkyBrightness(17.99))
    }

    @Test
    fun limitingMagnitude_isAtTheSamePlaceWithinTheClass() {
        assertEquals(8.0, Bortle.limitingMagnitudeForSkyBrightness(22.0), 1e-9)
        assertEquals(8.0, Bortle.limitingMagnitudeForSkyBrightness(22.5), 1e-9)
        // Halfway through class 6 in brightness (18.5-19.25) is halfway through it in NELM (5.1-5.6).
        assertEquals(5.35, Bortle.limitingMagnitudeForSkyBrightness(18.875), 1e-9)
        assertEquals(4.6, Bortle.limitingMagnitudeForSkyBrightness(18.0), 1e-9)
    }

    /**
     * Below 18.0, Schaefer's NELM = 7.93 - 5 log10(10^(4.316 - SQM/5) + 1) moved up to meet 4.6 at 18.0:
     * 3.9681 at 18.0 and 3.1015 at 17.0, worked by hand.
     */
    @Test
    fun limitingMagnitude_followsSchaeferBelowTheTable() {
        assertEquals(4.6 + 3.1015 - 3.9681, Bortle.limitingMagnitudeForSkyBrightness(17.0), 1e-3)
        assertEquals(4.6, Bortle.limitingMagnitudeForSkyBrightness(17.9999), 1e-3)
    }

    @Test
    fun limitingMagnitude_neverRisesAsTheSkyBrightens() {
        var previous = Double.POSITIVE_INFINITY
        var sqm = 23.0
        while (sqm > 14.0) {
            val nelm = Bortle.limitingMagnitudeForSkyBrightness(sqm)
            assertTrue("NELM rose at SQM $sqm", nelm <= previous + 1e-12)
            previous = nelm
            sqm -= 0.01
        }
    }
}
