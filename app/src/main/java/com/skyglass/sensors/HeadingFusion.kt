package com.skyglass.sensors

import com.skyglass.math.Matrix3
import com.skyglass.math.Quaternion
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp

/**
 * Combines the game rotation vector (gyroscope + accelerometer: smooth, but its heading is
 * arbitrary and drifts slowly) with the rotation vector (which adds the magnetometer: an absolute
 * heading, but one that jumps by tens of degrees when the magnetometer recalibrates itself while
 * the phone is swung around, or meets a magnetic disturbance).
 *
 * The output is the game rotation vector turned about the vertical by a yaw offset, the heading
 * difference between the two sensors' world frames. Each rotation-vector reading measures that
 * offset, and the offset follows the measurements through an exponential low-pass with a time
 * constant of seconds. So motion comes from the gyroscope with no lag, the magnetometer still
 * decides where north is, and a sudden heading jump becomes a glide of a few seconds instead of
 * the sky snapping round. Tilt comes from the game rotation vector alone.
 *
 * The two sensors deliver separately, so each rotation-vector reading is paired with the game
 * orientation interpolated to its own timestamp; pairing it with the nearest game reading would
 * make the offset wobble while panning (at 90°/s a 10 ms skew is 0.9°). A reading newer than the
 * latest game reading waits for the next one.
 *
 * Pure: the timestamps are `SensorEvent.timestamp` values (nanoseconds, one clock for all sensors).
 * Not thread-safe; feed it from the thread the sensor callbacks arrive on.
 *
 * @property timeConstantSeconds how quickly the heading follows the magnetometer
 */
class HeadingFusion(private val timeConstantSeconds: Double = DEFAULT_TIME_CONSTANT_SECONDS) {

    private class Sample(val q: Quaternion, val timestampNanos: Long)

    private var previousGame: Sample? = null
    private var latestGame: Sample? = null
    private var pendingRotationVector: Sample? = null
    private var latestRotationVector: Quaternion? = null

    private var yawOffsetRad: Double? = null
    private var lastOffsetNanos = 0L

    /** The game frame's yaw offset from magnetic ENU, radians counter-clockwise about up; null until measured. */
    val yawOffsetRadians: Double? get() = yawOffsetRad

    /**
     * Takes a game rotation vector reading (device → game world). Returns the best device →
     * magnetic-ENU rotation now known, or null if there is none yet.
     */
    fun onGameRotation(q: Quaternion, timestampNanos: Long): Quaternion? {
        previousGame = latestGame
        latestGame = Sample(q, timestampNanos)
        pendingRotationVector?.let { pending ->
            if (pending.timestampNanos <= timestampNanos) {
                pendingRotationVector = null
                measureOffset(pending)
            }
        }
        return output()
    }

    /**
     * Takes a rotation vector reading (device → magnetic ENU). Returns the best device →
     * magnetic-ENU rotation now known, or null if there is none yet.
     */
    fun onRotationVector(q: Quaternion, timestampNanos: Long): Quaternion? {
        latestRotationVector = q
        val sample = Sample(q, timestampNanos)
        val latest = latestGame
        if (latest == null || timestampNanos > latest.timestampNanos) {
            pendingRotationVector = sample
        } else {
            measureOffset(sample)
        }
        return output()
    }

    fun reset() {
        previousGame = null
        latestGame = null
        pendingRotationVector = null
        latestRotationVector = null
        yawOffsetRad = null
        lastOffsetNanos = 0L
    }

    private fun output(): Quaternion? {
        val offset = yawOffsetRad
        val game = latestGame
        // Until the two have been paired, the rotation vector on its own is the best there is.
        if (offset == null || game == null) return latestRotationVector
        return Quaternion.fromMatrix3(Matrix3.rotationZ(offset) * game.q.toMatrix3())
    }

    private fun measureOffset(rotationVector: Sample) {
        val game = gameAt(rotationVector.timestampNanos) ?: return
        val measured = yawOffset(rotationVector.q, game)
        val current = yawOffsetRad
        if (current == null) {
            yawOffsetRad = measured
        } else {
            val dtSeconds = (rotationVector.timestampNanos - lastOffsetNanos).coerceAtLeast(0L) / 1e9
            val alpha = 1.0 - exp(-dtSeconds / timeConstantSeconds)
            yawOffsetRad = wrap(current + alpha * wrap(measured - current))
        }
        lastOffsetNanos = rotationVector.timestampNanos
    }

    /** The game orientation at [timestampNanos], interpolated between the two latest readings when they bracket it. */
    private fun gameAt(timestampNanos: Long): Quaternion? {
        val latest = latestGame ?: return null
        val previous = previousGame
        if (previous == null || timestampNanos >= latest.timestampNanos) return latest.q
        if (timestampNanos <= previous.timestampNanos) return previous.q
        val t = (timestampNanos - previous.timestampNanos).toDouble() / (latest.timestampNanos - previous.timestampNanos)
        return Quaternion.slerp(previous.q, latest.q, t)
    }

    companion object {
        /**
         * Long enough that a magnetometer jump of 30° shows as a glide rather than a snap, short
         * enough that the game rotation vector's drift (typically well under a degree a minute)
         * and a deliberate recalibration are followed within seconds. Not tuned on a device.
         */
        const val DEFAULT_TIME_CONSTANT_SECONDS = 2.0

        /**
         * The heading by which the game world frame must be turned about up to match magnetic ENU,
         * from the two sensors' device → world rotations. Their tilts agree to within sensor noise,
         * so `R_rv · R_gameᵀ` is nearly a rotation about Z; the angle of its upper-left 2 × 2 block's
         * rotation part is the best-fitting yaw even when the tilts differ slightly.
         */
        fun yawOffset(rotationVector: Quaternion, game: Quaternion): Double {
            val m = rotationVector.toMatrix3() * game.toMatrix3().transpose()
            return atan2(m[1, 0] - m[0, 1], m[0, 0] + m[1, 1])
        }

        /** Wraps an angle into (−π, π]. */
        private fun wrap(rad: Double): Double {
            var r = rad % (2 * PI)
            if (r > PI) r -= 2 * PI
            if (r <= -PI) r += 2 * PI
            return r
        }
    }
}
