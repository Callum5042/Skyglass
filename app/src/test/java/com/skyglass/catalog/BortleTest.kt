package com.skyglass.catalog

import org.junit.Assert.assertEquals
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
}
