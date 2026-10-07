package com.skyglass.render

import com.skyglass.assertMatrixEquals
import com.skyglass.assertVecEquals
import com.skyglass.astro.AstroTime
import com.skyglass.astro.Coordinates
import com.skyglass.astro.Horizontal
import com.skyglass.epochMillis
import com.skyglass.math.Matrix3
import com.skyglass.math.Quaternion
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * End-to-end checks of the full chain: rotation-vector quaternion + declination + time + place
 * → clip-space position of a star.
 */
class SkyViewMathTest {

    private val latDeg = 51.4779   // Greenwich
    private val lonDeg = -0.0015
    private val declinationDeg = 10.0
    private val lstDeg = AstroTime.localSiderealTimeDeg(
        AstroTime.julianDate(epochMillis("2026-10-07T21:00:00Z")), lonDeg,
    )
    private val fovYDeg = 60.0
    private val aspect = 9.0 / 19.5

    private fun rad(deg: Double) = Math.toRadians(deg)

    /** Equatorial unit vector of whatever is at (alt, az) for the test observer right now. */
    private fun skyAt(altDeg: Double, azDeg: Double): Vector3 {
        val enu = Coordinates.horizontalToEnu(Horizontal(rad(altDeg), rad(azDeg)))
        return Coordinates.equatorialToEnuMatrix(rad(lstDeg), rad(latDeg)).transpose() * enu
    }

    /** Device→true-ENU for a phone in portrait whose back points at (alt, az), top edge upward. */
    private fun portraitPointingAt(altDeg: Double, azDeg: Double): Matrix3 {
        val forward = Coordinates.horizontalToEnu(Horizontal(rad(altDeg), rad(azDeg)))
        val up = Vector3(0.0, 0.0, 1.0)
        val deviceY = (up - forward * (up dot forward)).normalized()
        val deviceZ = -forward
        val deviceX = deviceY cross deviceZ
        return Matrix3.fromColumns(deviceX, deviceY, deviceZ)
    }

    /** As [portraitPointingAt] but rolled 90° counter-clockwise: the device's +X edge points up. */
    private fun landscapePointingAt(altDeg: Double, azDeg: Double): Matrix3 {
        val forward = Coordinates.horizontalToEnu(Horizontal(rad(altDeg), rad(azDeg)))
        val up = Vector3(0.0, 0.0, 1.0)
        val deviceX = (up - forward * (up dot forward)).normalized()
        val deviceZ = -forward
        val deviceY = deviceZ cross deviceX
        return Matrix3.fromColumns(deviceX, deviceY, deviceZ)
    }

    /** What the rotation vector sensor would report: device → *magnetic* ENU. */
    private fun sensorQuaternion(deviceToTrueEnu: Matrix3, declDeg: Double = declinationDeg): Quaternion =
        Quaternion.fromMatrix3(Coordinates.magneticToTrueEnu(rad(declDeg)).transpose() * deviceToTrueEnu)

    private data class Clip(val x: Double, val y: Double, val w: Double) {
        val ndcX get() = x / w
        val ndcY get() = y / w
    }

    private fun project(
        star: Vector3,
        q: Quaternion,
        declDeg: Double = declinationDeg,
        displayRotation: Int = 0,
    ): Clip {
        val mvp = SkyViewMath.modelViewProjection(q, declDeg, lstDeg, latDeg, displayRotation, fovYDeg, aspect)
        val c = mvp.transform(star.x, star.y, star.z)
        return Clip(c[0], c[1], c[3])
    }

    @Test
    fun starInPointingDirection_isAtScreenCentre() {
        val q = sensorQuaternion(portraitPointingAt(45.0, 120.0))
        val clip = project(skyAt(45.0, 120.0), q)
        assertTrue("in front of camera", clip.w > 0)
        assertEquals(0.0, clip.ndcX, 1e-9)
        assertEquals(0.0, clip.ndcY, 1e-9)
    }

    @Test
    fun starFurtherClockwiseInAzimuth_isRightOfCentre() {
        val q = sensorQuaternion(portraitPointingAt(45.0, 120.0))
        val clip = project(skyAt(45.0, 130.0), q)
        assertTrue(clip.w > 0)
        assertTrue("x = ${clip.ndcX}", clip.ndcX > 0.1)
    }

    @Test
    fun higherStar_isStraightAboveCentre() {
        val q = sensorQuaternion(portraitPointingAt(45.0, 120.0))
        val clip = project(skyAt(55.0, 120.0), q)
        assertTrue(clip.w > 0)
        assertEquals(0.0, clip.ndcX, 1e-9)
        assertTrue("y = ${clip.ndcY}", clip.ndcY > 0.1)
    }

    @Test
    fun starBehindViewer_isClipped() {
        val q = sensorQuaternion(portraitPointingAt(45.0, 120.0))
        assertTrue(project(skyAt(-45.0, 300.0), q).w < 0)
    }

    @Test
    fun ignoringDeclination_wouldMisplaceTheSky() {
        val q = sensorQuaternion(portraitPointingAt(20.0, 200.0))
        val clip = project(skyAt(20.0, 200.0), q, declDeg = 0.0)
        // 10° of uncorrected declination is a large shift with a 60° field of view.
        assertTrue("x = ${clip.ndcX}", abs(clip.ndcX) > 0.3)
    }

    @Test
    fun withZeroDeclination_magneticAndTrueAgree() {
        val q = sensorQuaternion(portraitPointingAt(30.0, 0.0), declDeg = 0.0)
        val clip = project(skyAt(30.0, 0.0), q, declDeg = 0.0)
        assertEquals(0.0, clip.ndcX, 1e-9)
        assertEquals(0.0, clip.ndcY, 1e-9)
    }

    @Test
    fun phoneFlatFaceUp_looksAtNadir_withTopTowardNorth() {
        // Identity rotation vector: device axes = magnetic ENU, so the back of the phone faces down.
        val nadir = project(skyAt(-90.0, 0.0), Quaternion.IDENTITY, declDeg = 0.0)
        assertTrue(nadir.w > 0)
        assertEquals(0.0, nadir.ndcX, 1e-9)
        assertEquals(0.0, nadir.ndcY, 1e-9)

        val belowNorthHorizon = project(skyAt(-70.0, 0.0), Quaternion.IDENTITY, declDeg = 0.0)
        assertTrue(belowNorthHorizon.ndcY > 0)
        assertEquals(0.0, belowNorthHorizon.ndcX, 1e-9)

        assertTrue(project(skyAt(90.0, 0.0), Quaternion.IDENTITY, declDeg = 0.0).w < 0)
    }

    @Test
    fun landscape_withDisplayRotation90_keepsSkyUpright() {
        val q = sensorQuaternion(landscapePointingAt(45.0, 120.0))

        val centre = project(skyAt(45.0, 120.0), q, displayRotation = 1)
        assertEquals(0.0, centre.ndcX, 1e-9)
        assertEquals(0.0, centre.ndcY, 1e-9)

        val higher = project(skyAt(55.0, 120.0), q, displayRotation = 1)
        assertEquals(0.0, higher.ndcX, 1e-9)
        assertTrue("y = ${higher.ndcY}", higher.ndcY > 0.1)

        val clockwise = project(skyAt(45.0, 130.0), q, displayRotation = 1)
        assertTrue("x = ${clockwise.ndcX}", clockwise.ndcX > 0.1)
    }

    @Test
    fun deviceToScreen_mapsDeviceAxesForEachRotation() {
        val x = Vector3(1.0, 0.0, 0.0)
        val y = Vector3(0.0, 1.0, 0.0)
        assertMatrixEquals(Matrix3.IDENTITY, SkyViewMath.deviceToScreen(0), 1e-15)
        // ROTATION_90: device +X edge is now up the screen; device +Y (old top) points left.
        assertVecEquals(Vector3(0.0, 1.0, 0.0), SkyViewMath.deviceToScreen(1) * x, 1e-12)
        assertVecEquals(Vector3(-1.0, 0.0, 0.0), SkyViewMath.deviceToScreen(1) * y, 1e-12)
        assertVecEquals(Vector3(-1.0, 0.0, 0.0), SkyViewMath.deviceToScreen(2) * x, 1e-12)
        assertVecEquals(Vector3(1.0, 0.0, 0.0), SkyViewMath.deviceToScreen(3) * y, 1e-12)
    }

    @Test
    fun columnMajorExport_matchesOpenGlLayout() {
        val mvp = SkyViewMath.modelViewProjection(Quaternion.IDENTITY, 0.0, 0.0, 0.0, 0, fovYDeg, aspect)
        val gl = mvp.toColumnMajorFloatArray()
        // Element (row 3, col 2) of a perspective matrix is −1; column-major index = col * 4 + row.
        assertEquals(mvp[3, 2].toFloat(), gl[2 * 4 + 3])
        assertEquals(mvp[0, 1].toFloat(), gl[1 * 4 + 0])
    }
}
