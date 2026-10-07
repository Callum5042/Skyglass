package com.skyglass.math

import kotlin.math.tan

/** Row-major 4×4 matrix; converted to OpenGL's column-major layout only at upload time. */
class Matrix4(private val m: DoubleArray) {

    init {
        require(m.size == 16)
    }

    operator fun get(row: Int, col: Int): Double = m[row * 4 + col]

    operator fun times(o: Matrix4): Matrix4 {
        val r = DoubleArray(16)
        for (i in 0..3) for (j in 0..3) {
            var sum = 0.0
            for (k in 0..3) sum += this[i, k] * o[k, j]
            r[i * 4 + j] = sum
        }
        return Matrix4(r)
    }

    /** Transforms the homogeneous point (x, y, z, w) and returns the result as [x, y, z, w]. */
    fun transform(x: Double, y: Double, z: Double, w: Double = 1.0): DoubleArray =
        DoubleArray(4) { i -> m[i * 4] * x + m[i * 4 + 1] * y + m[i * 4 + 2] * z + m[i * 4 + 3] * w }

    fun toColumnMajorFloatArray(out: FloatArray = FloatArray(16)): FloatArray {
        for (row in 0..3) for (col in 0..3) out[col * 4 + row] = this[row, col].toFloat()
        return out
    }

    companion object {
        fun fromMatrix3(r: Matrix3) = Matrix4(
            doubleArrayOf(
                r[0, 0], r[0, 1], r[0, 2], 0.0,
                r[1, 0], r[1, 1], r[1, 2], 0.0,
                r[2, 0], r[2, 1], r[2, 2], 0.0,
                0.0, 0.0, 0.0, 1.0,
            )
        )

        /** Standard OpenGL perspective projection; the eye looks down −Z with +Y up. */
        fun perspective(fovYDeg: Double, aspect: Double, near: Double, far: Double): Matrix4 {
            val f = 1.0 / tan(Math.toRadians(fovYDeg) / 2.0)
            return Matrix4(
                doubleArrayOf(
                    f / aspect, 0.0, 0.0, 0.0,
                    0.0, f, 0.0, 0.0,
                    0.0, 0.0, (far + near) / (near - far), 2.0 * far * near / (near - far),
                    0.0, 0.0, -1.0, 0.0,
                )
            )
        }
    }
}
