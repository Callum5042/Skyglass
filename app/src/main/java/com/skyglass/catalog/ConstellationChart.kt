package com.skyglass.catalog

import com.skyglass.math.Vector3

/**
 * A star on a [ConstellationChart]; [x] and [y] are in the chart's units (see [ConstellationChart]).
 *
 * @property inFigure part of the constellation's stick figure; the others are neighbours that give it context
 */
data class ChartStar(val x: Double, val y: Double, val vmag: Double, val inFigure: Boolean)

/** A line of the stick figure, in chart units. */
data class ChartSegment(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

/**
 * A picture of a constellation: its stick figure and its stars, drawn from the same catalogue
 * positions as the sky, not from artwork, so it shows exactly the stick figure that is lit up.
 *
 * The chart is laid out the way the constellation looks in the sky: north up and east to the left, as
 * on a printed star map. Coordinates are in chart units with **y pointing down** (the screen's
 * convention), scaled so the longer side of the picture is 1: x runs over `0..width` and y over
 * `0..height`, where `max(width, height) == 1`. A caller fits that into its canvas.
 */
class ConstellationChart(
    val width: Double,
    val height: Double,
    val stars: List<ChartStar>,
    val segments: List<ChartSegment>,
) {
    /** width / height. */
    val aspect: Double get() = width / height
}

object ConstellationCharts {

    /** Fainter stars than this are left out of the background; the chart is a sketch, not a map of the region. */
    const val CONTEXT_MAGNITUDE_LIMIT = 5.5

    /** The space left round the figure, as a fraction of its longer side. */
    const val MARGIN_FRACTION = 0.12

    /** Stars further than this from the chart's centre (cosine of the angle) are not projected: the projection blows up toward the antipode. */
    private const val MIN_COSINE = -0.5

    /**
     * Draws the constellation made of [figures] (all the figures that share one abbreviation, so both
     * halves of Serpens) with [stars] in the equatorial frame of date at [positions], which are in the
     * same order. The projection is stereographic about the mean direction of the figure's stars:
     * unlike a plain gnomonic projection it does not stretch the ends of the long figures (Hydra is
     * 100 degrees long), and it keeps shapes. Returns null if none of the figure's stars is in [stars].
     */
    fun build(figures: List<Constellation>, stars: List<Star>, positions: List<Vector3>): ConstellationChart? {
        require(stars.size == positions.size) { "one position per star" }
        val indexByHr = HashMap<Int, Int>(stars.size * 2)
        stars.forEachIndexed { i, star -> indexByHr[star.hr] = i }
        val figureHrs = figures.flatMap { it.starHrs }.filter { it in indexByHr }.toSet()
        if (figureHrs.isEmpty()) return null

        fun positionOf(hr: Int): Vector3 = positions[indexByHr.getValue(hr)]

        val sum = figureHrs.fold(Vector3(0.0, 0.0, 0.0)) { acc, hr -> acc + positionOf(hr) }
        val centre = if (sum.length() < 1e-6) positionOf(figureHrs.first()) else sum.normalized()
        val (east, north) = tangentBasis(centre)

        // x to the right is west and y down is south, so east is on the left and north at the top.
        fun project(v: Vector3): Pair<Double, Double>? {
            val cosine = v dot centre
            if (cosine <= MIN_COSINE) return null
            val k = 2.0 / (1.0 + cosine)
            return Pair(-(v dot east) * k, -(v dot north) * k)
        }

        val figurePoints = HashMap<Int, Pair<Double, Double>>()
        for (hr in figureHrs) project(positionOf(hr))?.let { figurePoints[hr] = it }
        if (figurePoints.isEmpty()) return null

        val minX = figurePoints.values.minOf { it.first }
        val maxX = figurePoints.values.maxOf { it.first }
        val minY = figurePoints.values.minOf { it.second }
        val maxY = figurePoints.values.maxOf { it.second }
        val margin = maxOf(maxOf(maxX - minX, maxY - minY) * MARGIN_FRACTION, 1e-4)
        val boxMinX = minX - margin
        val boxMinY = minY - margin
        val boxWidth = maxX - minX + 2 * margin
        val boxHeight = maxY - minY + 2 * margin
        val scale = 1.0 / maxOf(boxWidth, boxHeight)

        val chartStars = ArrayList<ChartStar>()
        for ((i, star) in stars.withIndex()) {
            val inFigure = star.hr in figurePoints
            if (!inFigure && star.vmag > CONTEXT_MAGNITUDE_LIMIT) continue
            val p = (if (inFigure) figurePoints[star.hr] else project(positions[i])) ?: continue
            val outside = p.first < boxMinX || p.first > boxMinX + boxWidth || p.second < boxMinY || p.second > boxMinY + boxHeight
            if (!inFigure && outside) continue
            chartStars += ChartStar((p.first - boxMinX) * scale, (p.second - boxMinY) * scale, star.vmag, inFigure)
        }

        val segments = ArrayList<ChartSegment>()
        for (figure in figures) {
            for (polyline in figure.polylines) {
                for (i in 0 until polyline.size - 1) {
                    val a = figurePoints[polyline[i]] ?: continue
                    val b = figurePoints[polyline[i + 1]] ?: continue
                    segments += ChartSegment(
                        (a.first - boxMinX) * scale, (a.second - boxMinY) * scale,
                        (b.first - boxMinX) * scale, (b.second - boxMinY) * scale,
                    )
                }
            }
        }
        return ConstellationChart(boxWidth * scale, boxHeight * scale, chartStars, segments)
    }

    /**
     * East and north unit vectors at [centre] (equatorial frame): east is z x centre, the direction of
     * increasing right ascension, and north is centre x east. At a celestial pole east is not defined,
     * so the y axis stands in for it.
     */
    private fun tangentBasis(centre: Vector3): Pair<Vector3, Vector3> {
        val raw = Vector3(-centre.y, centre.x, 0.0)
        val east = if (raw.length() < 1e-9) Vector3(0.0, 1.0, 0.0) else raw.normalized()
        return Pair(east, centre cross east)
    }
}
