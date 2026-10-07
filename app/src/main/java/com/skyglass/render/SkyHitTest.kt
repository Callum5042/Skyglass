package com.skyglass.render

import com.skyglass.catalog.StarVertices
import com.skyglass.math.Matrix4
import kotlin.math.hypot
import kotlin.math.max

/** A star under the finger: its index in the vertex buffer and its distance from the tap. */
data class StarHit(val index: Int, val distancePx: Double)

/** Maps a screen tap to the star the user most plausibly meant. */
object SkyHitTest {

    /** Smallest tap radius; roughly a fingertip. */
    const val MIN_TAP_RADIUS_DP = 24.0

    /**
     * Finds the star nearest to ([tapX], [tapY]) among those in [vertices] (the layout of
     * [StarVertices]) that lie within reach of the tap. A star's reach is the larger of
     * [MIN_TAP_RADIUS_DP] and the radius of its drawn sprite, so a big bright star can be hit
     * anywhere on its glow. Stars behind the viewer are ignored.
     *
     * @param density pixels per dp
     */
    fun nearestStar(
        mvp: Matrix4,
        vertices: FloatArray,
        widthPx: Int,
        heightPx: Int,
        tapX: Double,
        tapY: Double,
        density: Float,
    ): StarHit? {
        val minRadiusPx = MIN_TAP_RADIUS_DP * density
        var best: StarHit? = null
        for (i in 0 until vertices.size / StarVertices.FLOATS_PER_STAR) {
            val base = i * StarVertices.FLOATS_PER_STAR
            val p = SkyProjection.project(
                mvp,
                vertices[base + StarVertices.POSITION_OFFSET].toDouble(),
                vertices[base + StarVertices.POSITION_OFFSET + 1].toDouble(),
                vertices[base + StarVertices.POSITION_OFFSET + 2].toDouble(),
                widthPx, heightPx,
            ) ?: continue
            val distance = hypot(p.x - tapX, p.y - tapY)
            val spriteRadiusPx = vertices[base + StarVertices.SIZE_OFFSET] * density / 2.0
            if (distance > max(minRadiusPx, spriteRadiusPx)) continue
            if (best == null || distance < best.distancePx) best = StarHit(i, distance)
        }
        return best
    }
}
