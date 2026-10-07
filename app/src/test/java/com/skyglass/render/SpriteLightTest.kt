package com.skyglass.render

import com.skyglass.math.Matrix3
import com.skyglass.math.Matrix4
import com.skyglass.math.Quaternion
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Moon's lit side in screen terms: x right, y up, z toward the viewer. A sign error here
 * would light a waxing crescent on the side away from the Sun.
 */
class SpriteLightTest {

    private val width = 1080
    private val height = 2160
    private val mvp = Matrix4.perspective(60.0, width.toDouble() / height, SkyViewMath.NEAR, SkyViewMath.FAR)

    /** Straight ahead of a camera at the origin looking down −Z. */
    private val moon = Vector3(0.0, 0.0, -1.0)

    private fun light(body: Vector3, sun: Vector3, matrix: Matrix4 = mvp) =
        checkNotNull(SkyViewMath.spriteLight(matrix, body.normalized(), sun.normalized(), width, height))

    @Test
    fun fullMoon_isLitFromTheFront() {
        val l = light(moon, Vector3(0.0, 0.0, 1.0))
        assertEquals(0.0, l.x, 1e-9)
        assertEquals(0.0, l.y, 1e-9)
        assertEquals(1.0, l.z, 1e-9)
    }

    @Test
    fun newMoon_isLitFromBehind() {
        val l = light(moon, moon)
        assertEquals(0.0, l.x, 1e-9)
        assertEquals(0.0, l.y, 1e-9)
        assertEquals(-1.0, l.z, 1e-9)
    }

    @Test
    fun aSunToTheRight_lightsTheRightLimb() {
        val l = light(moon, Vector3(0.3, 0.0, -1.0))
        assertTrue("x = ${l.x}", l.x > 0.1)
        assertEquals(0.0, l.y, 1e-9)
    }

    @Test
    fun aSunToTheLeft_lightsTheLeftLimb() {
        assertTrue(light(moon, Vector3(-0.3, 0.0, -1.0)).x < -0.1)
    }

    @Test
    fun aSunAbove_lightsTheTopLimb_evenThoughScreenPixelsGrowDownward() {
        val l = light(moon, Vector3(0.0, 0.3, -1.0))
        assertTrue("y = ${l.y}", l.y > 0.1)
        assertEquals(0.0, l.x, 1e-9)
    }

    @Test
    fun aSunBelow_lightsTheBottomLimb() {
        assertTrue(light(moon, Vector3(0.0, -0.3, -1.0)).y < -0.1)
    }

    @Test
    fun aQuarterMoon_isLitEdgeOn() {
        val l = light(moon, Vector3(1.0, 0.0, 0.0)) // 90° from the Moon
        assertEquals(1.0, l.x, 1e-6)
        assertEquals(0.0, l.z, 1e-9)
    }

    @Test
    fun theVectorIsAlwaysUnitLength_andZIsMinusCosOfTheElongation() {
        for (sun in listOf(Vector3(0.1, 0.2, -1.0), Vector3(-0.5, 0.1, -0.3), Vector3(0.4, -0.7, 0.2), Vector3(0.0, 0.5, 1.0))) {
            val l = light(moon, sun)
            assertEquals(1.0, l.length(), 1e-9)
            assertEquals(-(moon dot sun.normalized()), l.z, 1e-9)
        }
    }

    @Test
    fun theDirectionFollowsTheScreen_underEveryDisplayRotation() {
        // A phone held at an arbitrary attitude. With the Moon dead ahead, place the Sun a little
        // to the screen's right, left, top and bottom, in the eye frame (which is screen-aligned
        // for every rotation), and expect the matching limb to be lit.
        val deviceToEnu = Quaternion.fromMatrix3(Matrix3.rotationZ(0.7) * Matrix3.rotationX(1.1))
        for (rotation in 0..3) {
            val view = SkyViewMath.viewMatrix(deviceToEnu, 8.0, 123.0, 51.5, rotation)
            val matrix = Matrix4.perspective(60.0, width.toDouble() / height, SkyViewMath.NEAR, SkyViewMath.FAR) *
                Matrix4.fromMatrix3(view)
            val eyeToSky = view.transpose()
            fun sky(x: Double, y: Double) = eyeToSky * Vector3(x, y, -1.0)

            val moonInSky = sky(0.0, 0.0)
            val right = light(moonInSky, sky(0.2, 0.0), matrix)
            val left = light(moonInSky, sky(-0.2, 0.0), matrix)
            val top = light(moonInSky, sky(0.0, 0.2), matrix)
            val bottom = light(moonInSky, sky(0.0, -0.2), matrix)

            assertTrue("rotation $rotation: sun right -> x = ${right.x}", right.x > 0.1)
            assertTrue("rotation $rotation: sun left -> x = ${left.x}", left.x < -0.1)
            assertTrue("rotation $rotation: sun above -> y = ${top.y}", top.y > 0.1)
            assertTrue("rotation $rotation: sun below -> y = ${bottom.y}", bottom.y < -0.1)
        }
    }

    @Test
    fun matchesTheSunsPositionOnScreen() {
        // The lateral part must point from the Moon's screen position toward the Sun's.
        val sun = Vector3(0.25, -0.15, -1.0).normalized()
        val l = light(moon, sun)
        val moonPx = checkNotNull(SkyProjection.project(mvp, moon, width, height))
        val sunPx = checkNotNull(SkyProjection.project(mvp, sun, width, height))
        val dx = sunPx.x - moonPx.x
        val dy = moonPx.y - sunPx.y
        val norm = Math.hypot(dx, dy)
        val sinPsi = Math.hypot(l.x, l.y)
        assertEquals(dx / norm, l.x / sinPsi, 1e-2)
        assertEquals(dy / norm, l.y / sinPsi, 1e-2)
    }

    @Test
    fun aMoonBehindTheViewer_hasNoLight() {
        assertNull(SkyViewMath.spriteLight(mvp, Vector3(0.0, 0.0, 1.0), Vector3(1.0, 0.0, 0.0), width, height))
        assertNotNull(SkyViewMath.spriteLight(mvp, moon, Vector3(1.0, 0.0, 0.0), width, height))
    }
}
