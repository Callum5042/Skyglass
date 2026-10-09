package com.skyglass.sensors

import com.skyglass.assertAngleDegEquals
import com.skyglass.assertMatrixEquals
import com.skyglass.assertVecEquals
import com.skyglass.math.Matrix3
import com.skyglass.math.Quaternion
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp

class HeadingFusionTest {

    private val tau = 2.0

    /** 20 ms, SENSOR_DELAY_GAME. */
    private val stepNanos = 20_000_000L

    private fun yaw(deg: Double) = Matrix3.rotationZ(Math.toRadians(deg))

    /** A phone pointed up at the sky and rolled a little, turned to [headingDeg] about the vertical. */
    private fun pose(headingDeg: Double): Quaternion =
        Quaternion.fromMatrix3(yaw(headingDeg) * Matrix3.rotationX(Math.toRadians(70.0)) * Matrix3.rotationY(Math.toRadians(15.0)))

    /** The same device orientation expressed in a world frame whose heading differs by [offsetDeg]. */
    private fun inFrame(offsetDeg: Double, q: Quaternion) = Quaternion.fromMatrix3(yaw(offsetDeg) * q.toMatrix3())

    private fun angleBetweenDeg(a: Quaternion, b: Quaternion) =
        Math.toDegrees(2 * acos(abs(a dot b).coerceAtMost(1.0)))

    private fun offsetDeg(fusion: HeadingFusion) = Math.toDegrees(fusion.yawOffsetRadians!!)

    @Test
    fun yawOffset_recoversTheHeadingBetweenFrames() {
        val game = pose(37.0)
        for (offset in listOf(-170.0, -45.0, 0.0, 12.5, 90.0, 179.0)) {
            assertAngleDegEquals(offset, Math.toDegrees(HeadingFusion.yawOffset(inFrame(offset, game), game)), 1e-9)
        }
    }

    @Test
    fun beforeAnyGameReading_theRotationVectorPassesThrough() {
        val fusion = HeadingFusion(tau)
        val rv = pose(20.0)
        assertEquals(rv, fusion.onRotationVector(rv, 1_000))
        assertNull(fusion.yawOffsetRadians)
    }

    @Test
    fun noReadingYet_givesNothing() {
        assertNull(HeadingFusion(tau).onGameRotation(pose(0.0), 1_000))
    }

    @Test
    fun firstPairing_snapsToTheMagneticHeading() {
        val fusion = HeadingFusion(tau)
        val game = pose(10.0)
        fusion.onGameRotation(game, 0)
        val out = fusion.onRotationVector(inFrame(25.0, game), 0)
        assertNotNull(out)
        assertMatrixEquals(inFrame(25.0, game).toMatrix3(), out!!.toMatrix3(), 1e-9)
    }

    @Test
    fun gyroMotion_isFollowedAtOnce() {
        val fusion = HeadingFusion(tau)
        fusion.onGameRotation(pose(0.0), 0)
        fusion.onRotationVector(inFrame(25.0, pose(0.0)), 0)
        // A fast turn seen only by the game sensor: the output turns by exactly as much, with no lag.
        val out = fusion.onGameRotation(pose(40.0), stepNanos)!!
        assertMatrixEquals(inFrame(25.0, pose(40.0)).toMatrix3(), out.toMatrix3(), 1e-9)
    }

    @Test
    fun magnetometerJump_becomesAGlide() {
        val fusion = HeadingFusion(tau)
        val game = pose(30.0)
        var t = 0L
        fusion.onGameRotation(game, t)
        fusion.onRotationVector(inFrame(0.0, game), t)

        // The magnetometer recalibrates and its heading jumps by 40°; the phone has not moved.
        t += stepNanos
        fusion.onGameRotation(game, t)
        val justAfter = fusion.onRotationVector(inFrame(40.0, game), t)!!
        val expectedFirstStep = 40.0 * (1 - exp(-0.02 / tau))
        assertEquals(expectedFirstStep, angleBetweenDeg(game, justAfter), 1e-6)
        assertEquals("under a degree, not 40°", true, expectedFirstStep < 1.0)

        // One time constant later, 63 % of the way, and it settles on the new heading.
        repeat(99) {
            t += stepNanos
            fusion.onGameRotation(game, t)
            fusion.onRotationVector(inFrame(40.0, game), t)
        }
        assertEquals(40.0 * (1 - exp(-1.0)), offsetDeg(fusion), 1e-6)
        repeat(1_000) {
            t += stepNanos
            fusion.onGameRotation(game, t)
            fusion.onRotationVector(inFrame(40.0, game), t)
        }
        assertEquals(40.0, offsetDeg(fusion), 1e-3)
    }

    @Test
    fun offsetAcross180_takesTheShortWay() {
        val fusion = HeadingFusion(tau)
        val game = pose(0.0)
        fusion.onGameRotation(game, 0)
        fusion.onRotationVector(inFrame(179.0, game), 0)
        fusion.onGameRotation(game, stepNanos * 100)
        fusion.onRotationVector(inFrame(-179.0, game), stepNanos * 100) // 2° further on, not 358° back
        val expected = 179.0 + 2.0 * (1 - exp(-2.0 / tau))
        assertAngleDegEquals(expected, offsetDeg(fusion), 1e-6)
    }

    @Test
    fun whilePanning_aReadingBetweenGameReadingsIsMatchedToItsOwnTime() {
        // Turning at 90°/s; the magnetic frame is 15° from the game frame. The rotation vector
        // readings fall midway between game readings: pairing them with the nearest game reading
        // would measure 14.1° or 15.9°, interpolating gives 15°.
        val fusion = HeadingFusion(tau)
        val rate = 90.0 // degrees per second
        fun headingAt(nanos: Long) = rate * nanos / 1e9
        var t = 0L
        repeat(50) {
            fusion.onGameRotation(pose(headingAt(t)), t)
            val rvTime = t + stepNanos / 2
            fusion.onRotationVector(inFrame(15.0, pose(headingAt(rvTime))), rvTime) // waits for the next game reading
            t += stepNanos
        }
        assertEquals(15.0, offsetDeg(fusion), 1e-6)
    }

    @Test
    fun aReadingNewerThanTheLatestGameReading_waitsForTheNext() {
        val fusion = HeadingFusion(tau)
        fusion.onGameRotation(pose(0.0), 0)
        fusion.onRotationVector(inFrame(10.0, pose(5.0)), stepNanos / 2)
        assertNull("not paired with an older game reading", fusion.yawOffsetRadians)
        fusion.onGameRotation(pose(10.0), stepNanos)
        assertEquals(10.0, offsetDeg(fusion), 1e-6)
    }

    @Test
    fun tilt_comesFromTheGameSensor() {
        val fusion = HeadingFusion(tau)
        val game = pose(30.0)
        // The rotation vector's tilt disagrees by 2° (noise); only its heading is used.
        val rv = Quaternion.fromMatrix3(yaw(20.0) * Matrix3.rotationX(Math.toRadians(2.0)) * game.toMatrix3())
        fusion.onGameRotation(game, 0)
        val out = fusion.onRotationVector(rv, 0)!!
        // "Up" in device coordinates is the third row of device → world.
        val up = Vector3(0.0, 0.0, 1.0)
        assertVecEquals(game.toMatrix3().transpose() * up, out.toMatrix3().transpose() * up, 1e-9)
        assertEquals(20.0, offsetDeg(fusion), 1e-6)
    }

    @Test
    fun reset_forgetsTheOffset() {
        val fusion = HeadingFusion(tau)
        fusion.onGameRotation(pose(0.0), 0)
        fusion.onRotationVector(inFrame(30.0, pose(0.0)), 0)
        fusion.reset()
        assertNull(fusion.yawOffsetRadians)
        // The game sensor may restart in a new frame; the first pairing snaps again.
        fusion.onGameRotation(pose(0.0), 0)
        fusion.onRotationVector(inFrame(-50.0, pose(0.0)), 0)
        assertEquals(-50.0, offsetDeg(fusion), 1e-9)
    }
}
