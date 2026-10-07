package com.skyglass.math

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit quaternion representing a rotation. Component order and rotation sense match Android's
 * rotation vector sensor: [toMatrix3] gives the same matrix as
 * `SensorManager.getRotationMatrixFromVector`, which maps device coordinates to world coordinates.
 */
data class Quaternion(val x: Double, val y: Double, val z: Double, val w: Double) {

    infix fun dot(o: Quaternion): Double = x * o.x + y * o.y + z * o.z + w * o.w

    operator fun unaryMinus() = Quaternion(-x, -y, -z, -w)

    fun normalized(): Quaternion {
        val n = sqrt(this dot this)
        return Quaternion(x / n, y / n, z / n, w / n)
    }

    fun toMatrix3(): Matrix3 {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val xw = x * w; val yw = y * w; val zw = z * w
        return Matrix3(
            doubleArrayOf(
                1 - 2 * (yy + zz), 2 * (xy - zw), 2 * (xz + yw),
                2 * (xy + zw), 1 - 2 * (xx + zz), 2 * (yz - xw),
                2 * (xz - yw), 2 * (yz + xw), 1 - 2 * (xx + yy),
            )
        )
    }

    companion object {
        val IDENTITY = Quaternion(0.0, 0.0, 0.0, 1.0)

        /**
         * Builds a quaternion from `SensorEvent.values` of a rotation vector sensor: [x, y, z, w, …].
         * Some older devices report only x, y, z, in which case w is reconstructed.
         */
        fun fromRotationVector(values: FloatArray): Quaternion {
            val x = values[0].toDouble()
            val y = values[1].toDouble()
            val z = values[2].toDouble()
            val w = if (values.size >= 4) values[3].toDouble() else sqrt(max(0.0, 1 - x * x - y * y - z * z))
            return Quaternion(x, y, z, w).normalized()
        }

        /** Inverse of [toMatrix3] for a proper rotation matrix (Shepperd's method). */
        fun fromMatrix3(m: Matrix3): Quaternion {
            val trace = m[0, 0] + m[1, 1] + m[2, 2]
            val q = when {
                trace > 0 -> {
                    val s = sqrt(trace + 1.0) * 2
                    Quaternion((m[2, 1] - m[1, 2]) / s, (m[0, 2] - m[2, 0]) / s, (m[1, 0] - m[0, 1]) / s, 0.25 * s)
                }
                m[0, 0] > m[1, 1] && m[0, 0] > m[2, 2] -> {
                    val s = sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
                    Quaternion(0.25 * s, (m[0, 1] + m[1, 0]) / s, (m[0, 2] + m[2, 0]) / s, (m[2, 1] - m[1, 2]) / s)
                }
                m[1, 1] > m[2, 2] -> {
                    val s = sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
                    Quaternion((m[0, 1] + m[1, 0]) / s, 0.25 * s, (m[1, 2] + m[2, 1]) / s, (m[0, 2] - m[2, 0]) / s)
                }
                else -> {
                    val s = sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
                    Quaternion((m[0, 2] + m[2, 0]) / s, (m[1, 2] + m[2, 1]) / s, 0.25 * s, (m[1, 0] - m[0, 1]) / s)
                }
            }
            return q.normalized()
        }

        /**
         * Spherical linear interpolation along the shortest arc. q and −q describe the same
         * rotation, so [b] is flipped when needed to avoid swinging the long way round.
         */
        fun slerp(a: Quaternion, b: Quaternion, t: Double): Quaternion {
            var d = a dot b
            var end = b
            if (d < 0) {
                end = -b
                d = -d
            }
            if (d > 0.9995) {
                // Nearly parallel: linear interpolation is accurate and avoids dividing by ~0.
                return Quaternion(
                    a.x + t * (end.x - a.x),
                    a.y + t * (end.y - a.y),
                    a.z + t * (end.z - a.z),
                    a.w + t * (end.w - a.w),
                ).normalized()
            }
            val theta0 = acos(d)
            val sinTheta0 = sin(theta0)
            val s0 = sin((1 - t) * theta0) / sinTheta0
            val s1 = sin(t * theta0) / sinTheta0
            return Quaternion(
                s0 * a.x + s1 * end.x,
                s0 * a.y + s1 * end.y,
                s0 * a.z + s1 * end.z,
                s0 * a.w + s1 * end.w,
            )
        }
    }
}
