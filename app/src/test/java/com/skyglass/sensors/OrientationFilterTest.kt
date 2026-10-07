package com.skyglass.sensors

import com.skyglass.assertMatrixEquals
import com.skyglass.math.Quaternion
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

class OrientationFilterTest {

    private val tau = 0.1

    private fun aboutZ(angleDeg: Double): Quaternion {
        val half = Math.toRadians(angleDeg) / 2
        return Quaternion(0.0, 0.0, sin(half), cos(half))
    }

    private fun angleBetweenDeg(a: Quaternion, b: Quaternion) =
        Math.toDegrees(2 * acos(abs(a dot b).coerceAtMost(1.0)))

    @Test
    fun firstSample_passesStraightThrough() {
        val filter = OrientationFilter(tau)
        val target = aboutZ(73.0)
        assertEquals(target, filter.update(target, 0.016))
    }

    @Test
    fun afterOneTimeConstant_covers63PercentOfAStep() {
        val filter = OrientationFilter(tau)
        filter.update(Quaternion.IDENTITY, 0.0)
        val out = filter.update(aboutZ(90.0), tau)
        // Slerp is linear in angle, so the output angle follows 1 − e^(−t/τ) exactly.
        assertEquals(90.0 * (1 - exp(-1.0)), angleBetweenDeg(Quaternion.IDENTITY, out), 1e-6)
    }

    @Test
    fun convergesToSteadyTarget() {
        val filter = OrientationFilter(tau)
        filter.update(Quaternion.IDENTITY, 0.0)
        val target = aboutZ(120.0)
        var out = Quaternion.IDENTITY
        repeat(120) { out = filter.update(target, 1.0 / 60.0) } // 2 seconds at 60 fps
        assertEquals(0.0, angleBetweenDeg(target, out), 1e-3)
    }

    @Test
    fun isFrameRateIndependent() {
        val target = aboutZ(80.0)
        val at120Hz = OrientationFilter(tau).apply { update(Quaternion.IDENTITY, 0.0) }
        val at60Hz = OrientationFilter(tau).apply { update(Quaternion.IDENTITY, 0.0) }

        at120Hz.update(target, 0.008)
        val twoSmallSteps = at120Hz.update(target, 0.008)
        val oneBigStep = at60Hz.update(target, 0.016)

        assertEquals(0.0, angleBetweenDeg(twoSmallSteps, oneBigStep), 1e-6)
    }

    @Test
    fun signFlippedSensorQuaternion_doesNotCauseASpin() {
        val filter = OrientationFilter(tau)
        val q = aboutZ(30.0)
        filter.update(q, 0.0)
        // The sensor may report −q for the same physical orientation.
        val out = filter.update(-q, 0.016)
        assertMatrixEquals(q.toMatrix3(), out.toMatrix3(), 1e-9)
    }

    @Test
    fun zeroOrNegativeDt_holdsPreviousValue() {
        val filter = OrientationFilter(tau)
        val start = aboutZ(10.0)
        filter.update(start, 0.0)
        assertEquals(0.0, angleBetweenDeg(start, filter.update(aboutZ(50.0), 0.0)), 1e-9)
        assertEquals(0.0, angleBetweenDeg(start, filter.update(aboutZ(50.0), -0.5)), 1e-9)
    }

    @Test
    fun reset_makesNextSamplePassThrough() {
        val filter = OrientationFilter(tau)
        filter.update(Quaternion.IDENTITY, 0.0)
        filter.reset()
        val target = aboutZ(170.0)
        assertEquals(target, filter.update(target, 0.016))
    }
}
