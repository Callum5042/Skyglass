package com.skyglass.catalog

import com.skyglass.astro.BodyPosition

/**
 * Packs the Sun, Moon and planets into an interleaved vertex buffer. Per body: a unit vector in
 * the equatorial frame of date, point size in dp, premultiplied colour, the sprite kind (see
 * [BodyAppearance]) and a unit vector toward the Sun. The Sun direction is not a GL attribute: the
 * renderer reads it from the Moon's entry on the CPU to work out the Moon's lit side each frame.
 */
object BodyVertices {

    const val FLOATS_PER_BODY = 11
    const val POSITION_OFFSET = 0
    const val SIZE_OFFSET = 3
    const val COLOR_OFFSET = 4
    const val KIND_OFFSET = 7
    const val SUN_DIRECTION_OFFSET = 8

    fun build(bodies: List<BodyPosition>): FloatArray {
        val out = FloatArray(bodies.size * FLOATS_PER_BODY)
        bodies.forEachIndexed { i, position ->
            val color = BodyAppearance.color(position.body, position.vmag)
            val base = i * FLOATS_PER_BODY
            out[base + POSITION_OFFSET] = position.direction.x.toFloat()
            out[base + POSITION_OFFSET + 1] = position.direction.y.toFloat()
            out[base + POSITION_OFFSET + 2] = position.direction.z.toFloat()
            out[base + SIZE_OFFSET] = BodyAppearance.spriteSizeDp(position)
            out[base + COLOR_OFFSET] = color.r
            out[base + COLOR_OFFSET + 1] = color.g
            out[base + COLOR_OFFSET + 2] = color.b
            out[base + KIND_OFFSET] = BodyAppearance.kind(position.body)
            out[base + SUN_DIRECTION_OFFSET] = position.sunDirection.x.toFloat()
            out[base + SUN_DIRECTION_OFFSET + 1] = position.sunDirection.y.toFloat()
            out[base + SUN_DIRECTION_OFFSET + 2] = position.sunDirection.z.toFloat()
        }
        return out
    }
}
