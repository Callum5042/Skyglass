package com.skyglass.catalog

import com.skyglass.math.Vector3

/**
 * Where to write a constellation figure's name in the sky: [direction] is a unit vector, equatorial of
 * date. [abbreviation] is the constellation's (Serpens' two figures share one).
 */
data class ConstellationLabel(val name: String, val direction: Vector3, val abbreviation: String = "")

/**
 * The vertices of one figure inside [ConstellationSky.lineVertices]: [vertexCount] vertices from
 * [firstVertex], two per segment, so it can be drawn on its own (`glDrawArrays(GL_LINES, ...)`).
 */
data class ConstellationSpan(val abbreviation: String, val name: String, val firstVertex: Int, val vertexCount: Int)

/**
 * GPU-ready constellation figures.
 *
 * @property lineVertices `GL_LINES` positions, [FLOATS_PER_VERTEX] floats each, two vertices per segment
 * @property spans where each figure's segments are in [lineVertices], in figure order, contiguous and
 *   covering it exactly (a figure whose stars are all missing has an empty span)
 */
class ConstellationSky(
    val lineVertices: FloatArray,
    val labels: List<ConstellationLabel>,
    val spans: List<ConstellationSpan> = emptyList(),
) {
    val lineVertexCount: Int get() = lineVertices.size / FLOATS_PER_VERTEX

    /**
     * `[first0, count0, first1, count1, ...]`: the vertex ranges of every figure of the constellation
     * [abbreviation], for drawing it again highlighted. Empty if it has no segments.
     */
    fun rangesOf(abbreviation: String): IntArray =
        spans.filter { it.abbreviation == abbreviation && it.vertexCount > 0 }
            .flatMap { listOf(it.firstVertex, it.vertexCount) }
            .toIntArray()

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
        val spans = ArrayList<ConstellationSpan>()
        for (constellation in constellations) {
            val firstVertex = segments.size / ConstellationSky.FLOATS_PER_VERTEX
            for (polyline in constellation.polylines) {
                for (i in 0 until polyline.size - 1) {
                    val a = byHr[polyline[i]] ?: continue
                    val b = byHr[polyline[i + 1]] ?: continue
                    segments += listOf(a.x, a.y, a.z, b.x, b.y, b.z).map(Double::toFloat)
                }
            }
            spans += ConstellationSpan(
                constellation.abbreviation, constellation.name, firstVertex,
                segments.size / ConstellationSky.FLOATS_PER_VERTEX - firstVertex,
            )
            labelFor(constellation, byHr)?.let(labels::add)
        }
        return ConstellationSky(segments.toFloatArray(), labels, spans)
    }

    private fun labelFor(constellation: Constellation, byHr: Map<Int, Vector3>): ConstellationLabel? {
        val vectors = constellation.starHrs.mapNotNull(byHr::get)
        if (vectors.isEmpty()) return null
        val sum = vectors.fold(Vector3(0.0, 0.0, 0.0)) { acc, v -> acc + v }
        // A figure spread over opposite sides of the sky would average to nothing.
        if (sum.length() < MIN_MEAN_LENGTH * vectors.size) return null
        return ConstellationLabel(constellation.name, sum.normalized(), constellation.abbreviation)
    }

    private const val MIN_MEAN_LENGTH = 0.05
}
