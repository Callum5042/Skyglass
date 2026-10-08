package com.skyglass.render

import com.skyglass.catalog.StarVertices
import com.skyglass.math.Matrix4
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class CameraFovTest {

    /** 6.4 × 4.8 mm sensor behind a 4.8 mm lens: 2·atan(3.2/4.8) = 67.38° across the long side. */
    private val sensor = CameraSensor(focalLengthMm = 4.8, activeWidthMm = 6.4, activeHeightMm = 4.8)

    private fun fov(
        streamW: Int, streamH: Int, rotation: Int, viewW: Int, viewH: Int, s: CameraSensor = sensor,
    ): CameraFieldOfView = checkNotNull(CameraFov.forView(s, streamW, streamH, rotation, viewW, viewH))

    @Test
    fun portraitPhone_fullSensorStream_cropsTheSidesToFillATallView() {
        // 4:3 stream = whole sensor; focal length 4.8/6.4 · 1440 = 1080 px. Upright it is 1080 × 1440,
        // and a 1080 × 2400 view needs it scaled by 2400/1440, so the focal length becomes 1800 px.
        val f = fov(1440, 1080, 90, 1080, 2400)
        assertEquals(Math.toDegrees(2 * atan(1200.0 / 1800)), f.verticalDeg, 1e-9)
        assertEquals(67.380, f.verticalDeg, 0.001)
        assertEquals(Math.toDegrees(2 * atan(540.0 / 1800)), f.horizontalDeg, 1e-9)
        assertEquals(33.398, f.horizontalDeg, 0.001)
    }

    @Test
    fun wideStream_dropsTheTopAndBottomOfTheSensorButKeepsItsFullWidth() {
        // 16:9 on a 4:3 sensor: still the full 6.4 mm width across 1920 px (1440 px focal length),
        // so a 16:9 portrait view shows the sensor's full 67.38° along its vertical side.
        val f = fov(1920, 1080, 90, 1080, 1920)
        assertEquals(67.380, f.verticalDeg, 0.001)
        assertEquals(Math.toDegrees(2 * atan(540.0 / 1440)), f.horizontalDeg, 1e-9)
    }

    @Test
    fun narrowStream_dropsTheSidesOfTheSensorButKeepsItsFullHeight() {
        // 1:1 on a 4:3 sensor uses 4.8 × 4.8 mm: 2·atan(2.4/4.8) = 53.13° each way.
        val f = fov(1080, 1080, 0, 1080, 1080)
        assertEquals(53.130, f.verticalDeg, 0.001)
        assertEquals(53.130, f.horizontalDeg, 0.001)
    }

    @Test
    fun landscapeView_swapsWhichAxisIsCropped() {
        // Same stream, unrotated, in a view wider than it is: the height is the cropped side.
        val f = fov(1440, 1080, 0, 2400, 1080)
        assertEquals(Math.toDegrees(2 * atan(540.0 / 1800)), f.verticalDeg, 1e-9)
        assertEquals(Math.toDegrees(2 * atan(1200.0 / 1800)), f.horizontalDeg, 1e-9)
    }

    @Test
    fun rotationDirectionDoesNotMatter_onlyWhetherItIsAQuarterTurn() {
        val a = fov(1440, 1080, 90, 1080, 2400)
        val b = fov(1440, 1080, 270, 1080, 2400)
        assertEquals(a.verticalDeg, b.verticalDeg, 1e-12)
        val c = fov(1440, 1080, 0, 1080, 2400)
        val d = fov(1440, 1080, 180, 1080, 2400)
        assertEquals(c.verticalDeg, d.verticalDeg, 1e-12)
        // Unrotated, the 1440 × 1080 stream is scaled by 2400/1080 to fill the same view: narrower.
        assertEquals(Math.toDegrees(2 * atan(1200.0 / (1080 * 2400.0 / 1080))), c.verticalDeg, 1e-9)
    }

    @Test
    fun croppedPreview_isScaledUpToFillTheView() {
        // Only the middle half of the stream's height (the screen's width, once rotated) is shown:
        // 540 × 1440 upright, so a 1080 × 2400 view needs a scale of 2 and the focal length is 2160 px.
        val f = fov(1440, 1080, 90, 1080, 2400)
        val cropped = checkNotNull(CameraFov.forView(sensor, 1440, 1080, 90, 1080, 2400, cropWidthPx = 1440, cropHeightPx = 540))
        assertEquals(Math.toDegrees(2 * atan(1200.0 / 2160)), cropped.verticalDeg, 1e-9)
        assertTrue(cropped.verticalDeg < f.verticalDeg)
        // A crop that is the whole stream changes nothing, and a nonsensical one is rejected.
        val whole = checkNotNull(CameraFov.forView(sensor, 1440, 1080, 90, 1080, 2400, 1440, 1080))
        assertEquals(f.verticalDeg, whole.verticalDeg, 1e-12)
        assertNull(CameraFov.forView(sensor, 1440, 1080, 90, 1080, 2400, 0, 1080))
    }

    @Test
    fun longerLens_narrowsTheView() {
        val tele = sensor.copy(focalLengthMm = 9.6)
        val wide = fov(1440, 1080, 90, 1080, 2400)
        val narrow = fov(1440, 1080, 90, 1080, 2400, tele)
        assertEquals(Math.toDegrees(2 * atan(1200.0 / 3600)), narrow.verticalDeg, 1e-9)
        assertTrue(narrow.verticalDeg < wide.verticalDeg)
    }

    @Test
    fun horizontalAndVerticalAgreeWithTheViewAspect() {
        val f = fov(1440, 1080, 90, 1080, 2400)
        val fromVertical = Math.toDegrees(2 * atan(tan(Math.toRadians(f.verticalDeg / 2)) * 1080.0 / 2400))
        assertEquals(fromVertical, f.horizontalDeg, 1e-9)
    }

    @Test
    fun activeArrayIsScaledFromThePhysicalSize() {
        // 4000 × 3000 active inside a 4032 × 3024 array on a 6.4 × 4.8 mm die.
        val s = checkNotNull(CameraSensor.fromCharacteristics(4.8, 6.4, 4.8, 4032, 3024, 4000, 3000))
        assertEquals(6.4 * 4000 / 4032, s.activeWidthMm, 1e-12)
        assertEquals(4.8 * 3000 / 3024, s.activeHeightMm, 1e-12)
        assertEquals(4.8, s.focalLengthMm, 0.0)
    }

    @Test
    fun invalidCharacteristicsOrSizes_giveNull() {
        assertNull(CameraSensor.fromCharacteristics(0.0, 6.4, 4.8, 4032, 3024, 4000, 3000))
        assertNull(CameraSensor.fromCharacteristics(4.8, 6.4, 0.0, 4032, 3024, 4000, 3000))
        assertNull(CameraSensor.fromCharacteristics(4.8, 6.4, 4.8, 0, 3024, 4000, 3000))
        assertNull(CameraSensor.fromCharacteristics(4.8, 6.4, 4.8, 4032, 3024, 4000, 0))
        assertNull(CameraFov.forView(sensor, 0, 1080, 90, 1080, 2400))
        assertNull(CameraFov.forView(sensor, 1440, 1080, 90, 1080, 0))
        assertNull(CameraFov.forView(sensor.copy(focalLengthMm = 0.0), 1440, 1080, 90, 1080, 2400))
    }

    @Test
    fun typicalPhoneFallback_isAboutSixtySevenDegreesAlongTheLongSide() {
        val f = fov(1440, 1080, 90, 1080, 1440, CameraFov.TYPICAL_PHONE)
        assertEquals(67.380, f.verticalDeg, 0.001)
    }

    // --- The point of matching the field of view: a tap on the camera image picks the star there.

    private val viewW = 1080
    private val viewH = 2400
    private val density = 3f

    private fun cameraMvp(): Matrix4 {
        val f = fov(1440, 1080, 90, viewW, viewH)
        return Matrix4.perspective(f.verticalDeg, viewW.toDouble() / viewH, SkyViewMath.NEAR, SkyViewMath.FAR)
    }

    /** Unit eye-frame direction [rightDeg] to the right of and [upDeg] above the optical axis (−Z). */
    private fun offAxis(rightDeg: Double, upDeg: Double): Vector3 {
        val r = Math.toRadians(rightDeg)
        val u = Math.toRadians(upDeg)
        return Vector3(tan(r), tan(u), -1.0).normalized()
    }

    @Test
    fun projection_placesADirectionWhereTheCameraImageWouldShowIt() {
        // The camera's focal length in view pixels is 1800, so 10° off axis is 1800·tan(10°) from the centre.
        val mvp = cameraMvp()
        val offset = 1800.0 * tan(Math.toRadians(10.0))

        val right = checkNotNull(SkyProjection.project(mvp, offAxis(10.0, 0.0), viewW, viewH))
        assertEquals(viewW / 2.0 + offset, right.x, 1e-6)
        assertEquals(viewH / 2.0, right.y, 1e-6)

        val up = checkNotNull(SkyProjection.project(mvp, offAxis(0.0, 10.0), viewW, viewH))
        assertEquals(viewW / 2.0, up.x, 1e-6)
        assertEquals(viewH / 2.0 - offset, up.y, 1e-6)

        // Along the long side, the image edge is half the vertical field of view away.
        val edge = checkNotNull(SkyProjection.project(mvp, Vector3(0.0, sin(Math.toRadians(33.690)), -cos(Math.toRadians(33.690))), viewW, viewH))
        assertEquals(0.0, edge.y, 0.5)
    }

    @Test
    fun tapOnTheStarInTheCameraImage_selectsIt_andATapElsewhereDoesNot() {
        val mvp = cameraMvp()
        val offset = 1800.0 * tan(Math.toRadians(10.0))
        val starDirection = offAxis(10.0, 0.0)
        val other = offAxis(-12.0, 15.0)

        val vertices = FloatArray(2 * StarVertices.FLOATS_PER_STAR)
        listOf(starDirection, other).forEachIndexed { i, d ->
            val base = i * StarVertices.FLOATS_PER_STAR
            vertices[base + StarVertices.POSITION_OFFSET] = d.x.toFloat()
            vertices[base + StarVertices.POSITION_OFFSET + 1] = d.y.toFloat()
            vertices[base + StarVertices.POSITION_OFFSET + 2] = d.z.toFloat()
            vertices[base + StarVertices.SIZE_OFFSET] = 3f
        }

        val tapX = viewW / 2.0 + offset
        val tapY = viewH / 2.0
        val hit = SkyHitTest.nearestStar(mvp, vertices, viewW, viewH, tapX + 10.0, tapY - 5.0, density)
        assertNotNull(hit)
        assertEquals(0, hit!!.index)

        // Well away from both stars (reach is 24 dp = 72 px).
        assertNull(SkyHitTest.nearestStar(mvp, vertices, viewW, viewH, tapX, tapY + 400.0, density))
    }

    @Test
    fun usingTheWrongFieldOfView_wouldMisplaceTheStarByManyPixels() {
        // Documents why the camera FOV is plumbed through: the 60° enhanced-view default puts the
        // same star ~300 px away from where the camera image shows it.
        val enhanced = Matrix4.perspective(60.0, viewW.toDouble() / viewH, SkyViewMath.NEAR, SkyViewMath.FAR)
        val camera = cameraMvp()
        val d = offAxis(25.0, 0.0)
        val a = checkNotNull(SkyProjection.project(enhanced, d, viewW, viewH))
        val b = checkNotNull(SkyProjection.project(camera, d, viewW, viewH))
        assertTrue("enhanced ${a.x} vs camera ${b.x}", abs(a.x - b.x) > 100.0)
    }
}
