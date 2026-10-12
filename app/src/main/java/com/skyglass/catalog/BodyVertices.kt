package com.skyglass.catalog

import com.skyglass.astro.BodyPosition
import com.skyglass.astro.PlanetMoonPosition
import com.skyglass.astro.SkyBody

/**
 * Packs the Sun, Moon and planets, and the planets' moons, into a vertex buffer of two triangles per
 * body (six vertices, drawn with `GL_TRIANGLES`). The vertex shader spreads each quad on the screen
 * around the body, to the size `BodyAppearance.drawn` works out for the frame's field of view, so a
 * body is drawn whole at the edge of the screen and at any size (a point sprite would vanish once its
 * centre left the screen and could not grow past the GPU's largest point size).
 *
 * Per vertex: a unit vector toward the body in the equatorial frame of date, the quad corner
 * (±1, ±1) with x right and y up on screen, the nominal sprite diameter in dp, the tangent of the
 * true angular radius, the premultiplied colour, the sprite kind (see [BodyAppearance]), a unit
 * vector toward the Sun, and, for a planet's moon, the tangent of its distance from its planet, from
 * which the shader fades it in as the view zooms ([NO_SEPARATION] for the others). The Sun direction is
 * not a GL attribute: the renderer reads it from the Moon's entry on the CPU to work out the Moon's
 * lit side each frame.
 */
object BodyVertices {

    const val FLOATS_PER_VERTEX = 15
    const val VERTICES_PER_BODY = 6
    const val FLOATS_PER_BODY = FLOATS_PER_VERTEX * VERTICES_PER_BODY
    const val POSITION_OFFSET = 0
    const val CORNER_OFFSET = 3
    const val SIZE_OFFSET = 5
    const val TRUE_RADIUS_OFFSET = 6
    const val COLOR_OFFSET = 7
    const val KIND_OFFSET = 10
    const val SUN_DIRECTION_OFFSET = 11
    const val SEPARATION_OFFSET = 14

    /** The separation of a body that is not a planet's moon: the shader never fades it. */
    const val NO_SEPARATION = -1f

    private val CORNERS = arrayOf(
        floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f),
        floatArrayOf(-1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f),
    )

    /**
     * The [bodies] in their order, with the visible [moons] (those not behind their planet) just before
     * the Moon, which stays last so that it covers them as it covers the planets.
     */
    fun build(bodies: List<BodyPosition>, moons: List<PlanetMoonPosition> = emptyList()): FloatArray {
        val visibleMoons = moons.filter { !it.hidden }
        val moonIndex = bodies.indexOfFirst { it.body == SkyBody.MOON }.let { if (it < 0) bodies.size else it }
        val out = FloatArray((bodies.size + visibleMoons.size) * FLOATS_PER_BODY)
        var slot = 0
        fun put(body: BodyPosition) {
            val color = BodyAppearance.color(body.body, body.vmag)
            write(
                out, slot++, body.direction.x, body.direction.y, body.direction.z, BodyAppearance.spriteSizeDp(body),
                BodyAppearance.trueRadiusTan(body).toFloat(), color, BodyAppearance.kind(body.body), body.sunDirection.x,
                body.sunDirection.y, body.sunDirection.z, NO_SEPARATION,
            )
        }
        bodies.take(moonIndex).forEach(::put)
        for (moon in visibleMoons) {
            val sun = bodies.firstOrNull { it.body == moon.moon.planet }?.sunDirection
            write(
                out, slot++, moon.direction.x, moon.direction.y, moon.direction.z, BodyAppearance.satelliteSizeDp(moon.vmag),
                0f, BodyAppearance.satelliteColor(moon.moon, moon.vmag), BodyAppearance.KIND_SATELLITE,
                sun?.x ?: 1.0, sun?.y ?: 0.0, sun?.z ?: 0.0, moon.separationTan.toFloat(),
            )
        }
        bodies.drop(moonIndex).forEach(::put)
        return out
    }

    private fun write(
        out: FloatArray,
        slot: Int,
        x: Double,
        y: Double,
        z: Double,
        sizeDp: Float,
        trueRadius: Float,
        color: Rgb,
        kind: Float,
        sunX: Double,
        sunY: Double,
        sunZ: Double,
        separation: Float,
    ) {
        for (v in 0 until VERTICES_PER_BODY) {
            val base = slot * FLOATS_PER_BODY + v * FLOATS_PER_VERTEX
            out[base + POSITION_OFFSET] = x.toFloat()
            out[base + POSITION_OFFSET + 1] = y.toFloat()
            out[base + POSITION_OFFSET + 2] = z.toFloat()
            out[base + CORNER_OFFSET] = CORNERS[v][0]
            out[base + CORNER_OFFSET + 1] = CORNERS[v][1]
            out[base + SIZE_OFFSET] = sizeDp
            out[base + TRUE_RADIUS_OFFSET] = trueRadius
            out[base + COLOR_OFFSET] = color.r
            out[base + COLOR_OFFSET + 1] = color.g
            out[base + COLOR_OFFSET + 2] = color.b
            out[base + KIND_OFFSET] = kind
            out[base + SUN_DIRECTION_OFFSET] = sunX.toFloat()
            out[base + SUN_DIRECTION_OFFSET + 1] = sunY.toFloat()
            out[base + SUN_DIRECTION_OFFSET + 2] = sunZ.toFloat()
            out[base + SEPARATION_OFFSET] = separation
        }
    }
}
