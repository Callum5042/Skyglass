package com.skyglass.math

import kotlin.math.sqrt

data class Vector3(val x: Double, val y: Double, val z: Double) {

    operator fun plus(o: Vector3) = Vector3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: Vector3) = Vector3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Double) = Vector3(x * s, y * s, z * s)

    operator fun unaryMinus() = Vector3(-x, -y, -z)

    infix fun dot(o: Vector3): Double = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vector3) = Vector3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    fun length(): Double = sqrt(this dot this)

    fun normalized(): Vector3 = this * (1.0 / length())
}
