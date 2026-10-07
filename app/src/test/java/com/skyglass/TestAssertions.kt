package com.skyglass

import com.skyglass.math.Matrix3
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.Instant
import kotlin.math.abs

/** Asserts two angles are equal modulo 360°, so 359.9° and 0.1° differ by 0.2°. */
fun assertAngleDegEquals(expected: Double, actual: Double, toleranceDeg: Double, message: String = "") {
    val diff = ((actual - expected) % 360.0 + 540.0) % 360.0 - 180.0
    assertTrue("$message expected $expected° but was $actual° (off by $diff°)", abs(diff) <= toleranceDeg)
}

fun assertVecEquals(expected: Vector3, actual: Vector3, tolerance: Double, message: String = "") {
    assertEquals("$message x", expected.x, actual.x, tolerance)
    assertEquals("$message y", expected.y, actual.y, tolerance)
    assertEquals("$message z", expected.z, actual.z, tolerance)
}

fun assertMatrixEquals(expected: Matrix3, actual: Matrix3, tolerance: Double) {
    for (i in 0..2) for (j in 0..2) assertEquals("[$i,$j]", expected[i, j], actual[i, j], tolerance)
}

/** Hours, minutes, seconds of time → degrees. */
fun hmsToDeg(h: Int, m: Int, s: Double): Double = 15.0 * (h + m / 60.0 + s / 3600.0)

/** Degrees, arcminutes, arcseconds → degrees. Pass the sign separately so −0° works. */
fun dmsToDeg(sign: Int, d: Int, m: Int, s: Double): Double = sign * (d + m / 60.0 + s / 3600.0)

fun epochMillis(isoUtc: String): Long = Instant.parse(isoUtc).toEpochMilli()
