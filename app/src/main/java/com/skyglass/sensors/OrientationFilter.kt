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
 * @param timeConstantSeconds time to cover ~63% of a step change; larger is smoother but laggier
 */
class OrientationFilter(private val timeConstantSeconds: Double = DEFAULT_TIME_CONSTANT_SECONDS) {

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
    }
}
