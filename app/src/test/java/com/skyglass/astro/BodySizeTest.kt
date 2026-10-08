package com.skyglass.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodySizeTest {

    @Test
    fun sun_atOneAu_isAboutThirtyTwoArcminutes() {
        // Meeus gives a semi-diameter of 959.63″ at 1 AU with R = 696,000 km; the IAU nominal
        // 695,700 km used here gives 959.2″.
        assertEquals(2 * 959.2, BodySize.angularDiameterArcsec(SkyBody.SUN, 1.0), 1.0)
    }

    @Test
    fun moon_atMeeusExample47a_isAboutThirtyTwoArcminutes() {
        // Meeus example 47.a: distance 368 409.7 km; 1737.4 / 368 409.7 gives a radius of 0.27019°.
        val au = 368_409.7 / SolarSystem.KM_PER_AU
        assertEquals(0.27019, BodySize.angularRadiusDeg(SkyBody.MOON, au), 1e-4)
        // At the mean distance (384 400 km) the disc is 31.1′ across.
        assertEquals(31.1 * 60, BodySize.angularDiameterArcsec(SkyBody.MOON, 384_400.0 / SolarSystem.KM_PER_AU), 5.0)
    }

    @Test
    fun marsAtTheAugust2003Opposition_is25Arcseconds() {
        // Closest approach 0.3727 AU; published apparent diameter 25.1″.
        assertEquals(25.1, BodySize.angularDiameterArcsec(SkyBody.MARS, 0.3727), 0.1)
    }

    @Test
    fun jupiterAtOpposition_isAbout47Arcseconds() {
        assertEquals(46.9, BodySize.angularDiameterArcsec(SkyBody.JUPITER, 4.2), 0.2)
    }

    @Test
    fun everyBodyHasAPositiveRadius_andShrinksWithDistance() {
        for (body in SkyBody.entries) {
            assertTrue(body.name, BodySize.radiusKm(body) > 0)
            assertTrue(
                body.name,
                BodySize.angularDiameterArcsec(body, 2.0) < BodySize.angularDiameterArcsec(body, 1.0),
            )
        }
    }
}
