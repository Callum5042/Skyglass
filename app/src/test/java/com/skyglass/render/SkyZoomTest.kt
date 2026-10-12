package com.skyglass.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.tan

class SkyZoomTest {

    private fun rad(deg: Double) = Math.toRadians(deg)

    @Test
    fun theDefaultFieldOfView_isOneTimes() {
        assertEquals(1.0, SkyZoom.magnification(SkyRenderInputs.DEFAULT_FOV_Y_DEG), 1e-12)
    }

    @Test
    fun magnification_isTheRatioOfFocalLengths() {
        // tan 30° / tan 0.5° = 0.5773503 / 0.0087269 = 66.157
        assertEquals(66.157, SkyZoom.magnification(1.0), 0.001)
        // tan 30° / tan 45° = 0.5773503
        assertEquals(0.5773503, SkyZoom.magnification(90.0), 1e-7)
    }

    @Test
    fun fovForMagnification_undoesMagnification() {
        for (fov in listOf(1.0, 5.0, 30.0, 60.0, 90.0)) {
            assertEquals(fov, SkyZoom.fovForMagnification(SkyZoom.magnification(fov)), 1e-9)
        }
    }

    @Test
    fun spreadingTheFingersTwiceAsFar_doublesTheFocalLength() {
        // 2 atan(tan 30° / 2) = 2 atan(0.2886751) = 32.2042°
        assertEquals(32.2042, SkyZoom.pinched(60.0, 2.0), 1e-4)
        assertEquals(tan(rad(30.0)) / 2, tan(rad(SkyZoom.pinched(60.0, 2.0) / 2)), 1e-12)
    }

    @Test
    fun pinchesCompose_soAPinchInManySmallStepsEndsWhereOneBigStepWould() {
        var fov = 60.0
        repeat(30) { fov = SkyZoom.pinched(fov, 1.1) }
        assertEquals(SkyZoom.pinched(60.0, Math.pow(1.1, 30.0)), fov, 1e-9)
        repeat(30) { fov = SkyZoom.pinched(fov, 1 / 1.1) }
        assertEquals(60.0, fov, 1e-9)
    }

    @Test
    fun pinching_staysWithinTheLimits() {
        assertEquals(SkyZoom.MIN_FOV_Y_DEG, SkyZoom.pinched(60.0, 1000.0), 0.0)
        assertEquals(SkyZoom.MAX_FOV_Y_DEG, SkyZoom.pinched(60.0, 0.001), 0.0)
        assertEquals(SkyZoom.MIN_FOV_Y_DEG, SkyZoom.pinched(1.0, 1.5), 0.0)
    }

    @Test
    fun aMeaninglessScale_changesNothing() {
        for (scale in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals("$scale", 20.0, SkyZoom.pinched(20.0, scale), 0.0)
        }
    }

    @Test
    fun smoothing_isTheUsualAtOrBeyondTheDefaultView_andGrowsWhenZoomedIn() {
        val base = 0.1
        assertEquals(base, SkyZoom.smoothingSeconds(60.0, base), 1e-12)
        assertEquals(base, SkyZoom.smoothingSeconds(90.0, base), 1e-12)
        val at30 = SkyZoom.smoothingSeconds(30.0, base)
        val at10 = SkyZoom.smoothingSeconds(10.0, base)
        assertTrue("$at30", at30 > base)
        assertTrue("$at10 > $at30", at10 > at30)
    }

    @Test
    fun smoothing_neverExceedsItsCap() {
        assertEquals(SkyZoom.MAX_SMOOTHING_SECONDS, SkyZoom.smoothingSeconds(SkyZoom.MIN_FOV_Y_DEG, 0.1), 1e-12)
        // A base already above the cap is left alone rather than shortened.
        assertEquals(0.5, SkyZoom.smoothingSeconds(1.0, 0.5), 1e-12)
    }

    @Test
    fun aZoomWithinTheToleranceOfOne_doesNotCountAsZoomed() {
        assertFalse(SkyZoom.isZoomed(1.0))
        assertFalse(SkyZoom.isZoomed(1.02))
        assertTrue(SkyZoom.isZoomed(1.1))
        assertTrue(SkyZoom.isZoomed(0.6))
        // The limits are well outside it.
        assertTrue(SkyZoom.isZoomed(SkyZoom.magnification(SkyZoom.MAX_FOV_Y_DEG)))
        assertEquals(2 * Math.toDegrees(atan(tan(rad(30.0)) / 66.0)), SkyZoom.fovForMagnification(66.0), 1e-12)
    }
}
