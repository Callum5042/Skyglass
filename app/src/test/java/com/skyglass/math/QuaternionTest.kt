package com.skyglass.math

import com.skyglass.assertMatrixEquals
import com.skyglass.assertVecEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class QuaternionTest {

    private fun aboutZ(angleDeg: Double): Quaternion {
        val half = Math.toRadians(angleDeg) / 2
        return Quaternion(0.0, 0.0, sin(half), cos(half))
    }

    private fun randomUnit(random: Random) = Quaternion(
        random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0),
        random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0),
    ).normalized()

    private fun angleBetweenDeg(a: Quaternion, b: Quaternion) =
        Math.toDegrees(2 * acos(abs(a dot b).coerceAtMost(1.0)))

    @Test
    fun identity_givesIdentityMatrix() {
        assertMatrixEquals(Matrix3.IDENTITY, Quaternion.IDENTITY.toMatrix3(), 1e-15)
    }

    @Test
    fun ninetyDegreesAboutZ_mapsXToY() {
        val m = aboutZ(90.0).toMatrix3()
        assertVecEquals(Vector3(0.0, 1.0, 0.0), m * Vector3(1.0, 0.0, 0.0), 1e-12)
        assertMatrixEquals(Matrix3.rotationZ(Math.toRadians(90.0)), m, 1e-12)
    }

    @Test
    fun fromRotationVector_usesSuppliedW_andIgnoresAccuracyField() {
        // Android delivers [x, y, z, w, headingAccuracy].
        val q = Quaternion.fromRotationVector(floatArrayOf(0f, 0f, 0.70710677f, 0.70710677f, 0.1f))
        assertMatrixEquals(aboutZ(90.0).toMatrix3(), q.toMatrix3(), 1e-6)
    }

    @Test
    fun fromRotationVector_reconstructsMissingW() {
        val q = Quaternion.fromRotationVector(floatArrayOf(0f, 0f, 0.5f))
        assertEquals(sqrt(0.75), q.w, 1e-7)
        assertMatrixEquals(aboutZ(60.0).toMatrix3(), q.toMatrix3(), 1e-6)
    }

    @Test
    fun toMatrix3_isAProperRotation() {
        val random = Random(7)
        repeat(100) {
            val m = randomUnit(random).toMatrix3()
            assertMatrixEquals(Matrix3.IDENTITY, m * m.transpose(), 1e-12)
            val det = m[0, 0] * (m[1, 1] * m[2, 2] - m[1, 2] * m[2, 1]) -
                m[0, 1] * (m[1, 0] * m[2, 2] - m[1, 2] * m[2, 0]) +
                m[0, 2] * (m[1, 0] * m[2, 1] - m[1, 1] * m[2, 0])
            assertEquals(1.0, det, 1e-12)
        }
    }

    @Test
    fun fromMatrix3_roundTrips_includingHalfTurns() {
        val random = Random(11)
        val cases = List(100) { randomUnit(random) } + listOf(
            Quaternion(1.0, 0.0, 0.0, 0.0), // 180° about each axis: exercises every branch
            Quaternion(0.0, 1.0, 0.0, 0.0),
            Quaternion(0.0, 0.0, 1.0, 0.0),
        )
        for (q in cases) {
            assertMatrixEquals(q.toMatrix3(), Quaternion.fromMatrix3(q.toMatrix3()).toMatrix3(), 1e-12)
        }
    }

    @Test
    fun slerp_endpointsAndMidpoint() {
        val a = Quaternion.IDENTITY
        val b = aboutZ(90.0)
        assertEquals(0.0, angleBetweenDeg(a, Quaternion.slerp(a, b, 0.0)), 1e-6)
        assertEquals(0.0, angleBetweenDeg(b, Quaternion.slerp(a, b, 1.0)), 1e-6)
        assertEquals(0.0, angleBetweenDeg(aboutZ(45.0), Quaternion.slerp(a, b, 0.5)), 1e-6)
        assertEquals(0.0, angleBetweenDeg(aboutZ(22.5), Quaternion.slerp(a, b, 0.25)), 1e-6)
    }

    @Test
    fun slerp_takesShortestPathWhenTargetHasOppositeSign() {
        val a = Quaternion.IDENTITY
        val b = aboutZ(90.0)
        val viaNegated = Quaternion.slerp(a, -b, 0.5)
        // −b is the same rotation as b; the midpoint must still be 45°, not 45° + 180°.
        assertEquals(0.0, angleBetweenDeg(aboutZ(45.0), viaNegated), 1e-6)
    }

    @Test
    fun slerp_nearlyIdenticalInputs_staysNormalized() {
        val a = aboutZ(10.0)
        val b = aboutZ(10.001)
        val mid = Quaternion.slerp(a, b, 0.5)
        assertEquals(1.0, sqrt(mid dot mid), 1e-12)
        assertEquals(0.0, angleBetweenDeg(aboutZ(10.0005), mid), 1e-6)
    }
}
