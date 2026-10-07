package com.skyglass.render

import com.skyglass.math.Matrix4
import com.skyglass.math.Quaternion
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class SkyProjectionTest {

    private val width = 1080
    private val height = 2160
    private val fovYDeg = 60.0
    private val aspect = width.toDouble() / height

    /** Eye at the origin looking down -Z with +Y up; no rotation. */
    private val lookingDownMinusZ = Matrix4.perspective(fovYDeg, aspect, SkyViewMath.NEAR, SkyViewMath.FAR)

    @Test
    fun straightAhead_isScreenCentre() {
        val p = checkNotNull(SkyProjection.project(lookingDownMinusZ, 0.0, 0.0, -1.0, width, height))
        assertEquals(width / 2.0, p.x, 1e-9)
        assertEquals(height / 2.0, p.y, 1e-9)
    }

    @Test
    fun topOfFieldOfView_isTopOfScreen() {
        // Half the vertical field of view above the axis lands on the top edge (pixel y = 0).
        val half = Math.toRadians(fovYDeg / 2)
        val p = checkNotNull(SkyProjection.project(lookingDownMinusZ, 0.0, kotlin.math.sin(half), -kotlin.math.cos(half), width, height))
        assertEquals(width / 2.0, p.x, 1e-6)
        assertEquals(0.0, p.y, 1e-6)
    }

    @Test
    fun right_isLargerX_andUp_isSmallerY() {
        val centre = checkNotNull(SkyProjection.project(lookingDownMinusZ, 0.0, 0.0, -1.0, width, height))
        val right = checkNotNull(SkyProjection.project(lookingDownMinusZ, 0.1, 0.0, -1.0, width, height))
        val up = checkNotNull(SkyProjection.project(lookingDownMinusZ, 0.0, 0.1, -1.0, width, height))
        assertTrue(right.x > centre.x)
        assertEquals(centre.y, right.y, 1e-9)
        assertTrue(up.y < centre.y)
        assertEquals(centre.x, up.x, 1e-9)
    }

    @Test
    fun pixelOffsetMatchesPerspectiveGeometry() {
        // 0.1 right at depth 1: tan(angle) = 0.1; the horizontal focal length is
        // (height / 2) / tan(fovY / 2) pixels because pixels are square.
        val focalPx = (height / 2.0) / tan(Math.toRadians(fovYDeg / 2))
        val p = checkNotNull(SkyProjection.project(lookingDownMinusZ, 0.1, 0.0, -1.0, width, height))
        assertEquals(width / 2.0 + 0.1 * focalPx, p.x, 1e-6)
    }

    @Test
    fun behindTheViewer_isNull() {
        assertNull(SkyProjection.project(lookingDownMinusZ, 0.0, 0.0, 1.0, width, height))
        assertNull(SkyProjection.project(lookingDownMinusZ, 0.3, 0.2, 0.001, width, height))
    }

    @Test
    fun isOnScreen_respectsViewportAndMargin() {
        assertTrue(SkyProjection.isOnScreen(ScreenPoint(0.0, 0.0), width, height))
        assertTrue(SkyProjection.isOnScreen(ScreenPoint(width.toDouble(), height.toDouble()), width, height))
        assertFalse(SkyProjection.isOnScreen(ScreenPoint(-1.0, 100.0), width, height))
        assertFalse(SkyProjection.isOnScreen(ScreenPoint(100.0, height + 1.0), width, height))
        assertTrue(SkyProjection.isOnScreen(ScreenPoint(-10.0, 100.0), width, height, marginPx = 20.0))
    }

    @Test
    fun withFullViewMath_nadirIsCentreAndNorthPoleIsAbove() {
        // Identity rotation, equator, LST 0: device axes = ENU, so the phone's back looks at the
        // nadir; ENU "up" is equatorial +X, so the nadir is equatorial -X (see SkyViewMathTest).
        val mvp = SkyViewMath.modelViewProjection(Quaternion.IDENTITY, 0.0, 0.0, 0.0, 0, fovYDeg, aspect)

        val nadir = checkNotNull(SkyProjection.project(mvp, Vector3(-1.0, 0.0, 0.0), width, height))
        assertEquals(width / 2.0, nadir.x, 1e-6)
        assertEquals(height / 2.0, nadir.y, 1e-6)

        // The north celestial pole is due north on the horizon; with the top of the phone toward
        // north it sits above the nadir on screen, i.e. at a smaller pixel y.
        val towardPole = Vector3(-1.0, 0.0, 0.2).normalized()
        val p = checkNotNull(SkyProjection.project(mvp, towardPole, width, height))
        assertEquals(width / 2.0, p.x, 1e-6)
        assertTrue("y = ${p.y}", p.y < height / 2.0)
        assertNotNull(p)

        // The zenith (equatorial +X) is behind a phone that looks at the nadir.
        assertNull(SkyProjection.project(mvp, Vector3(1.0, 0.0, 0.0), width, height))
    }
}
