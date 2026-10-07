package com.skyglass.catalog

import com.skyglass.math.Vector3

/** Where to write a constellation's name in the sky: [direction] is a unit vector, equatorial of date. */
data class ConstellationLabel(val name: String, val direction: Vector3)

/**
 * GPU-ready constellation figures.
 *
 * @property lineVertices `GL_LINES` positions, [FLOATS_PER_VERTEX] floats each, two vertices per segment
 */
class ConstellationSky(val lineVertices: FloatArray, val labels: List<ConstellationLabel>) {
    val lineVertexCount: Int get() = lineVertices.size / FLOATS_PER_VERTEX

    companion object {
        const val FLOATS_PER_VERTEX = 3
    }
}

object ConstellationGeometry {

    /**
     * Builds line segments and label anchors for [constellations], using the same positions
     * (proper motion and precession to [julianDate]) as the star buffer so the lines meet the
     * stars. Figure stars absent from [stars] are skipped along with the segments touching them.
     * A label sits at the normalised mean direction of its figure's stars.
     */
    fun build(constellations: List<Constellation>, stars: List<Star>, julianDate: Double): ConstellationSky {
        val positions = StarPositions.ofDate(stars, julianDate)
        val byHr = HashMap<Int, Vector3>(stars.size * 2)
        stars.forEachIndexed { i, star -> byHr[star.hr] = positions[i] }

        val segments = ArrayList<Float>()
        val labels = ArrayList<ConstellationLabel>()
        for (constellation in constellations) {
            for (polyline in constellation.polylines) {
                for (i in 0 until polyline.size - 1) {
                    val a = byHr[polyline[i]] ?: continue
                    val b = byHr[polyline[i + 1]] ?: continue
                    segments += listOf(a.x, a.y, a.z, b.x, b.y, b.z).map(Double::toFloat)
                }
            }
            labelFor(constellation, byHr)?.let(labels::add)
        }
        return ConstellationSky(segments.toFloatArray(), labels)
    }

    private fun labelFor(constellation: Constellation, byHr: Map<Int, Vector3>): ConstellationLabel? {
        val vectors = constellation.starHrs.mapNotNull(byHr::get)
        if (vectors.isEmpty()) return null
        val sum = vectors.fold(Vector3(0.0, 0.0, 0.0)) { acc, v -> acc + v }
        // A figure spread over opposite sides of the sky would average to nothing.
        if (sum.length() < MIN_MEAN_LENGTH * vectors.size) return null
        return ConstellationLabel(constellation.name, sum.normalized())
    }

    private const val MIN_MEAN_LENGTH = 0.05
}
