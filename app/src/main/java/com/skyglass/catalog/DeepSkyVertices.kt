package com.skyglass.catalog

import com.skyglass.astro.AstroTime
import com.skyglass.astro.Coordinates
import com.skyglass.astro.Precession
import com.skyglass.math.Vector3

/**
 * Packs deep-sky objects into a vertex buffer of two triangles per object (six vertices, drawn with
 * `GL_TRIANGLES`). The vertex shader builds the quad on the sky from the per-object values repeated in
 * every vertex, so it can enlarge small objects to a minimum size in pixels, which depends on the
 * field of view.
 *
 * Per vertex: a unit vector toward the object in the equatorial frame of date (precession applied;
 * no proper motion), the quad corner (±1, ±1) in the object's own frame (x along the long axis),
 * the angular semi-major and semi-minor axes in radians, the position angle in radians (from north
 * through east), the premultiplied colour and the glyph (see [DeepSkyAppearance]).
 */
object DeepSkyVertices {

    const val FLOATS_PER_VERTEX = 12
    const val VERTICES_PER_OBJECT = 6
    const val POSITION_OFFSET = 0
    const val CORNER_OFFSET = 3
    const val SEMI_MAJOR_OFFSET = 5
    const val SEMI_MINOR_OFFSET = 6
    const val POSITION_ANGLE_OFFSET = 7
    const val COLOR_OFFSET = 8
    const val GLYPH_OFFSET = 11

    private val CORNERS = arrayOf(
        floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f),
        floatArrayOf(-1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f),
    )

    /** Unit vectors toward the objects in the equatorial frame of date, in input order. */
    fun directions(objects: List<DeepSkyObject>, julianDate: Double): List<Vector3> {
        val precession = Precession.matrixFromJ2000(AstroTime.julianCenturiesSinceJ2000(julianDate))
        return objects.map { precession * Coordinates.raDecToUnit(it.j2000) }
    }

    fun build(objects: List<DeepSkyObject>, directions: List<Vector3>): FloatArray {
        require(objects.size == directions.size)
        val out = FloatArray(objects.size * VERTICES_PER_OBJECT * FLOATS_PER_VERTEX)
        objects.forEachIndexed { i, obj ->
            val d = directions[i]
            val color = DeepSkyAppearance.color(obj.type)
            val peak = DeepSkyAppearance.peakIntensity(obj)
            val major = Math.toRadians(obj.majorArcmin / 60.0 / 2.0).toFloat()
            val minor = Math.toRadians(obj.drawnMinorArcmin / 60.0 / 2.0).toFloat()
            val angle = Math.toRadians(obj.positionAngleDeg ?: 0.0).toFloat()
            val glyph = DeepSkyAppearance.glyph(obj.type)
            for (v in 0 until VERTICES_PER_OBJECT) {
                val base = (i * VERTICES_PER_OBJECT + v) * FLOATS_PER_VERTEX
                out[base + POSITION_OFFSET] = d.x.toFloat()
                out[base + POSITION_OFFSET + 1] = d.y.toFloat()
                out[base + POSITION_OFFSET + 2] = d.z.toFloat()
                out[base + CORNER_OFFSET] = CORNERS[v][0]
                out[base + CORNER_OFFSET + 1] = CORNERS[v][1]
                out[base + SEMI_MAJOR_OFFSET] = major
                out[base + SEMI_MINOR_OFFSET] = minor
                out[base + POSITION_ANGLE_OFFSET] = angle
                out[base + COLOR_OFFSET] = color.r * peak
                out[base + COLOR_OFFSET + 1] = color.g * peak
                out[base + COLOR_OFFSET + 2] = color.b * peak
                out[base + GLYPH_OFFSET] = glyph
            }
        }
        return out
    }
}
