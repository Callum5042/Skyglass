package com.skyglass.math

import kotlin.math.cos
import kotlin.math.sin

/**
 * Row-major 3×3 matrix. Rotation factories are *active* right-handed rotations:
 * a positive angle turns a vector counter-clockwise when viewed from the tip of the axis.
 */
class Matrix3(private val m: DoubleArray) {

    init {
        require(m.size == 9)
    }

    operator fun get(row: Int, col: Int): Double = m[row * 3 + col]

    operator fun times(o: Matrix3): Matrix3 {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            r[i * 3 + j] = this[i, 0] * o[0, j] + this[i, 1] * o[1, j] + this[i, 2] * o[2, j]
        }
        return Matrix3(r)
    }

    operator fun times(v: Vector3) = Vector3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    fun transpose() = Matrix3(
        doubleArrayOf(
            m[0], m[3], m[6],
            m[1], m[4], m[7],
            m[2], m[5], m[8],
        )
    )

    companion object {
        val IDENTITY = Matrix3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun fromRows(r0: Vector3, r1: Vector3, r2: Vector3) = Matrix3(
            doubleArrayOf(r0.x, r0.y, r0.z, r1.x, r1.y, r1.z, r2.x, r2.y, r2.z)
        )

        fun fromColumns(c0: Vector3, c1: Vector3, c2: Vector3) = fromRows(c0, c1, c2).transpose()

        fun rotationX(angleRad: Double): Matrix3 {
            val c = cos(angleRad)
            val s = sin(angleRad)
            return Matrix3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c))
        }

        fun rotationY(angleRad: Double): Matrix3 {
            val c = cos(angleRad)
            val s = sin(angleRad)
            return Matrix3(doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c))
        }

        fun rotationZ(angleRad: Double): Matrix3 {
            val c = cos(angleRad)
            val s = sin(angleRad)
            return Matrix3(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0))
        }
    }
}
