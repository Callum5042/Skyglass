package com.skyglass.render

import com.skyglass.catalog.ConstellationSky
import com.skyglass.math.Matrix4
import kotlin.math.abs
import kotlin.math.hypot

/** The constellation under the finger: its abbreviation, the figure's name and how far the tap was from it. */
data class ConstellationHit(val abbreviation: String, val name: String, val distancePx: Double)

/**
 * Maps a screen tap to the constellation whose drawn figure it is on or beside. This only decides
 * between constellations; whether a constellation beats a star, a planet or a deep-sky object is the
 * caller's business (a constellation is the last resort: see `SkyViewModel.onSkyTapped`).
 */
object ConstellationHitTest {

    /**
     * How far from a drawn line a tap still counts. Smaller than the fingertip radius used for stars
     * (`SkyHitTest.MIN_TAP_RADIUS_DP`): lines cross the whole sky, and a reach as large as that would
     * leave hardly any empty sky to tap to dismiss a card.
     */
    const val LINE_REACH_DP = 16.0

    /** Half the size of the box round a constellation's name in which a tap counts as on it. The text's real width is the overlay's. */
    const val LABEL_HALF_WIDTH_DP = 44.0
    const val LABEL_HALF_HEIGHT_DP = 14.0

    /** Clip-space w below which a point is treated as at the viewer; the segment is cut there. */
    private const val NEAR_W = 1e-4

    /**
     * Finds the constellation of [sky] nearest to ([tapX], [tapY]): the distance to its nearest line
     * segment as drawn on a [widthPx] by [heightPx] screen, or 0 for a tap inside the box round its
     * name. A segment that runs behind the viewer is cut where it reaches the viewer's plane, so only
     * the part that is drawn counts. Null if nothing is within [LINE_REACH_DP].
     *
     * @param density pixels per dp
     */
    fun nearest(
        mvp: Matrix4,
        sky: ConstellationSky,
        widthPx: Int,
        heightPx: Int,
        tapX: Double,
        tapY: Double,
        density: Float,
    ): ConstellationHit? {
        val reachPx = LINE_REACH_DP * density
        var best: ConstellationHit? = null
        fun offer(abbreviation: String, name: String, distance: Double) {
            if (distance > reachPx) return
            if (best == null || distance < best!!.distancePx) best = ConstellationHit(abbreviation, name, distance)
        }

        val lines = sky.lineVertices
        val stride = ConstellationSky.FLOATS_PER_VERTEX
        for (span in sky.spans) {
            var vertex = span.firstVertex
            val end = span.firstVertex + span.vertexCount
            while (vertex + 1 < end) {
                val a = vertex * stride
                val b = (vertex + 1) * stride
                val distance = distanceToSegment(
                    mvp, lines[a].toDouble(), lines[a + 1].toDouble(), lines[a + 2].toDouble(),
                    lines[b].toDouble(), lines[b + 1].toDouble(), lines[b + 2].toDouble(),
                    widthPx, heightPx, tapX, tapY,
                )
                if (distance != null) offer(span.abbreviation, span.name, distance)
                vertex += 2
            }
        }

        val halfWidth = LABEL_HALF_WIDTH_DP * density
        val halfHeight = LABEL_HALF_HEIGHT_DP * density
        for (label in sky.labels) {
            val p = SkyProjection.project(mvp, label.direction, widthPx, heightPx) ?: continue
            if (abs(tapX - p.x) <= halfWidth && abs(tapY - p.y) <= halfHeight) {
                offer(label.abbreviation, label.name, 0.0)
            }
        }
        return best
    }

    /**
     * Distance in pixels from the tap to the segment between the two directions, as the GPU draws it
     * (a straight line in clip space, cut at the near plane), or null if all of it is behind the viewer.
     */
    private fun distanceToSegment(
        mvp: Matrix4,
        ax: Double, ay: Double, az: Double,
        bx: Double, by: Double, bz: Double,
        widthPx: Int, heightPx: Int,
        tapX: Double, tapY: Double,
    ): Double? {
        var xa = mvp[0, 0] * ax + mvp[0, 1] * ay + mvp[0, 2] * az + mvp[0, 3]
        var ya = mvp[1, 0] * ax + mvp[1, 1] * ay + mvp[1, 2] * az + mvp[1, 3]
        var wa = mvp[3, 0] * ax + mvp[3, 1] * ay + mvp[3, 2] * az + mvp[3, 3]
        var xb = mvp[0, 0] * bx + mvp[0, 1] * by + mvp[0, 2] * bz + mvp[0, 3]
        var yb = mvp[1, 0] * bx + mvp[1, 1] * by + mvp[1, 2] * bz + mvp[1, 3]
        var wb = mvp[3, 0] * bx + mvp[3, 1] * by + mvp[3, 2] * bz + mvp[3, 3]
        if (wa <= NEAR_W && wb <= NEAR_W) return null
        if (wa <= NEAR_W) {
            val t = (NEAR_W - wa) / (wb - wa)
            xa += (xb - xa) * t
            ya += (yb - ya) * t
            wa = NEAR_W
        } else if (wb <= NEAR_W) {
            val t = (NEAR_W - wb) / (wa - wb)
            xb += (xa - xb) * t
            yb += (ya - yb) * t
            wb = NEAR_W
        }
        val pax = (xa / wa + 1.0) * 0.5 * widthPx
        val pay = (1.0 - ya / wa) * 0.5 * heightPx
        val pbx = (xb / wb + 1.0) * 0.5 * widthPx
        val pby = (1.0 - yb / wb) * 0.5 * heightPx

        val dx = pbx - pax
        val dy = pby - pay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared < 1e-12) 0.0 else (((tapX - pax) * dx + (tapY - pay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(tapX - (pax + t * dx), tapY - (pay + t * dy))
    }
}
