package com.skyglass.sensors

import com.skyglass.math.Quaternion
import kotlin.math.exp

/**
 * Exponential low-pass filter on orientation, done with slerp so it behaves correctly across
 * the 0°/360° wrap and near the poles, where filtering Euler angles or matrix elements fails.
 *
 * The blend factor is derived from elapsed time, so the response is the same regardless of the
 * display's frame rate.
 *
 * @property timeConstantSeconds time to cover ~63% of a step change; larger is smoother but laggier.
 *   It may be changed at any time, e.g. to follow the sensor closely while a camera image is behind the sky.
 */
class OrientationFilter(var timeConstantSeconds: Double = DEFAULT_TIME_CONSTANT_SECONDS) {

    private var current: Quaternion? = null

    fun update(target: Quaternion, dtSeconds: Double): Quaternion {
        val previous = current
        val next = if (previous == null) {
            target
        } else {
            val alpha = 1.0 - exp(-dtSeconds.coerceAtLeast(0.0) / timeConstantSeconds)
            Quaternion.slerp(previous, target, alpha)
        }
        current = next
        return next
    }

    fun reset() {
        current = null
    }

    companion object {
        const val DEFAULT_TIME_CONSTANT_SECONDS = 0.1

        /**
         * Over a live camera image, filter lag shows as the sky trailing the stars while panning
         * (an exponential filter lags a steady turn by exactly its time constant). But the preview
         * itself is typically tens of milliseconds behind the world, so no lag at all would make
         * the sky lead it, and less smoothing shows more sensor jitter; this is a compromise between
         * the two. Unlike the enhanced view's, it does not grow with the zoom: the camera image is
         * not smoothed, and the sky must stay on it.
         */
        const val CAMERA_TIME_CONSTANT_SECONDS = 0.05
    }
}
