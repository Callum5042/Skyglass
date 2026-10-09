package com.skyglass.render

import com.skyglass.astro.BodyPosition
import com.skyglass.catalog.BodyAppearance
import com.skyglass.catalog.DeepSkyAppearance
import com.skyglass.catalog.DeepSkyGeometry
import com.skyglass.catalog.DrawnSemiAxes
import com.skyglass.catalog.DeepSkyObject
import com.skyglass.catalog.StarVertices
import com.skyglass.catalog.StarVisibility
import com.skyglass.math.Matrix4
import com.skyglass.math.Vector3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A star near the finger: its index in the vertex buffer and its distance from the tap. [onTarget] when
 * the tap is on the star as drawn (see [SkyHitTest.ON_TARGET_SLACK_DP]), not merely within a fingertip.
 */
data class StarHit(val index: Int, val distancePx: Double, val onTarget: Boolean = false)

/** A Sun, Moon or planet near the finger: its index in the body list and its distance from the tap; [onTarget] on its drawn disc. */
data class BodyHit(val index: Int, val distancePx: Double, val onTarget: Boolean = false)

/** A deep-sky object near the finger: its index in the object list and its distance from the tap; [onTarget] inside its drawn ellipse. */
data class DeepSkyHit(val index: Int, val distancePx: Double, val onTarget: Boolean = false)

/** Maps a screen tap to the star the user most plausibly meant. */
object SkyHitTest {

    /** Smallest tap radius; roughly a fingertip. */
    const val MIN_TAP_RADIUS_DP = 24.0

    /**
     * How far beyond a star's drawn sprite (a body's drawn disc) a tap still counts as *on* it rather
     * than merely near it. Much smaller than the fingertip, so that a faint star 1.5 dp across does not
     * claim a tap on a constellation's name written over it; see [TapResolver].
     */
    const val ON_TARGET_SLACK_DP = 4.0

    /**
     * Finds the star nearest to ([tapX], [tapY]) among those in [vertices] (the layout of
     * [StarVertices]) that lie within reach of the tap. A star's reach is the larger of
     * [MIN_TAP_RADIUS_DP] and the radius of its drawn sprite, so a big bright star can be hit
     * anywhere on its glow. Stars behind the viewer are ignored, as are stars the "faintest stars"
     * [limitingMagnitude] excludes (fainter than it; see [StarVisibility]).
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
        limitingMagnitude: Double = StarVisibility.DEFAULT_LIMIT,
    ): StarHit? = starsAt(mvp, vertices, widthPx, heightPx, tapX, tapY, density, limitingMagnitude).firstOrNull()

    /** Every star [nearestStar] would consider, nearest first. On target within its sprite plus [ON_TARGET_SLACK_DP]. */
    fun starsAt(
        mvp: Matrix4,
        vertices: FloatArray,
        widthPx: Int,
        heightPx: Int,
        tapX: Double,
        tapY: Double,
        density: Float,
        limitingMagnitude: Double = StarVisibility.DEFAULT_LIMIT,
    ): List<StarHit> {
        val minRadiusPx = MIN_TAP_RADIUS_DP * density
        val slackPx = ON_TARGET_SLACK_DP * density
        val hits = ArrayList<StarHit>()
        for (i in 0 until vertices.size / StarVertices.FLOATS_PER_STAR) {
            val base = i * StarVertices.FLOATS_PER_STAR
            if (!StarVisibility.isTappable(vertices[base + StarVertices.VMAG_OFFSET].toDouble(), limitingMagnitude)) continue
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
            hits += StarHit(i, distance, onTarget = distance <= spriteRadiusPx + slackPx)
        }
        hits.sortBy { it.distancePx }
        return hits
    }

    /**
     * Finds the Sun, Moon or planet nearest to the tap, with the same reach rule as
     * [nearestStar] but using each body's drawn sprite, which is large for the Sun and Moon.
     *
     * @param density pixels per dp
     * @param maxSpriteSizePx the largest sprite the GPU draws (`GL_ALIASED_POINT_SIZE_RANGE`); a
     *   sprite drawn smaller than its nominal size is hit only across the smaller size
     */
    fun nearestBody(
        mvp: Matrix4,
        bodies: List<BodyPosition>,
        widthPx: Int,
        heightPx: Int,
        tapX: Double,
        tapY: Double,
        density: Float,
        maxSpriteSizePx: Float = Float.MAX_VALUE,
    ): BodyHit? = bodiesAt(mvp, bodies, widthPx, heightPx, tapX, tapY, density, maxSpriteSizePx).firstOrNull()

    /**
     * Every body [nearestBody] would consider, nearest first. On target within its drawn disc plus
     * [ON_TARGET_SLACK_DP], not its whole sprite: the Sun's glow is far bigger than its disc.
     */
    fun bodiesAt(
        mvp: Matrix4,
        bodies: List<BodyPosition>,
        widthPx: Int,
        heightPx: Int,
        tapX: Double,
        tapY: Double,
        density: Float,
        maxSpriteSizePx: Float = Float.MAX_VALUE,
    ): List<BodyHit> {
        val minRadiusPx = MIN_TAP_RADIUS_DP * density
        val hits = ArrayList<BodyHit>()
        for ((i, body) in bodies.withIndex()) {
            val p = SkyProjection.project(mvp, body.direction, widthPx, heightPx) ?: continue
            val distance = hypot(p.x - tapX, p.y - tapY)
            val spriteRadiusPx = min(BodyAppearance.spriteSizeDp(body) * density, maxSpriteSizePx) / 2.0
            if (distance > max(minRadiusPx, spriteRadiusPx)) continue
            val onTargetPx = (BodyAppearance.discRadiusDp(body) + ON_TARGET_SLACK_DP) * density
            hits += BodyHit(i, distance, onTarget = distance <= onTargetPx)
        }
        hits.sortBy { it.distancePx }
        return hits
    }

    /**
     * Finds the deep-sky object a tap most plausibly means. The hit area is the object's drawn ellipse
     * (its long and short axis and position angle, as the renderer draws it, enlarged to the minimum
     * size), or a fingertip ([MIN_TAP_RADIUS_DP]) round its centre, whichever is larger.
     *
     * The returned distance is what [choose] compares with the distances of stars and bodies. For a
     * small object it is the distance to its centre. Inside a big one it is the tap's normalised
     * radius in the ellipse (0 at the centre, 1 at the edge) times a fingertip, so a tap in the middle
     * of the Large Magellanic Cloud or the Pleiades counts as nearly on the object, and only a star
     * very close to the finger takes it, while a tap near the rim leaves it to a star within a
     * fingertip, as for any other star.
     *
     * @param directions unit vectors toward [objects], equatorial of date, in the same order
     * @param density pixels per dp
     * @param focalPx focal length of the projection in pixels, `(heightPx / 2) / tan(fovY / 2)`
     */
    fun nearestDeepSky(
        mvp: Matrix4,
        objects: List<DeepSkyObject>,
        directions: List<Vector3>,
        widthPx: Int,
        heightPx: Int,
        tapX: Double,
        tapY: Double,
        density: Float,
        focalPx: Double,
    ): DeepSkyHit? = deepSkyAt(mvp, objects, directions, widthPx, heightPx, tapX, tapY, density, focalPx).firstOrNull()

    /** Every object [nearestDeepSky] would consider, nearest (as it reckons distance) first. On target inside its drawn ellipse. */
    fun deepSkyAt(
        mvp: Matrix4,
        objects: List<DeepSkyObject>,
        directions: List<Vector3>,
        widthPx: Int,
        heightPx: Int,
        tapX: Double,
        tapY: Double,
        density: Float,
        focalPx: Double,
    ): List<DeepSkyHit> {
        val fingertipPx = MIN_TAP_RADIUS_DP * density
        val minDrawnRadiusPx = DeepSkyAppearance.MIN_RADIUS_DP * density.toDouble()
        val hits = ArrayList<DeepSkyHit>()
        for (i in objects.indices) {
            val d = directions[i]
            val centre = SkyProjection.project(mvp, d, widthPx, heightPx) ?: continue
            val tapDx = tapX - centre.x
            val tapDy = tapY - centre.y
            val distance = hypot(tapDx, tapDy)
            val axes = DeepSkyAppearance.drawnSemiAxes(objects[i], focalPx, minDrawnRadiusPx)
            val ellipseRadius = ellipseRadius(
                mvp, d, centre, axes, Math.toRadians(objects[i].positionAngleDeg ?: 0.0), tapDx, tapDy, widthPx, heightPx,
                fallbackRadiusPx = axes.majorRad * focalPx,
            )
            val insideEllipse = ellipseRadius <= 1.0
            if (!insideEllipse && distance > fingertipPx) continue
            val effective = if (insideEllipse) min(distance, ellipseRadius * fingertipPx) else distance
            hits += DeepSkyHit(i, effective, onTarget = insideEllipse)
        }
        hits.sortBy { it.distancePx }
        return hits
    }

    /**
     * The tap's radius in the object's ellipse on the screen: 1 on the edge. The ellipse is found by
     * projecting the ends of its semi-axes, and the tap's offset from the centre is written in terms of
     * the two (screen-space) semi-axis vectors. If an end is behind the viewer or the axes are
     * degenerate, the object is treated as a circle of [fallbackRadiusPx].
     */
    private fun ellipseRadius(
        mvp: Matrix4,
        direction: Vector3,
        centre: ScreenPoint,
        axes: DrawnSemiAxes,
        angleRad: Double,
        tapDx: Double,
        tapDy: Double,
        widthPx: Int,
        heightPx: Int,
        fallbackRadiusPx: Double,
    ): Double {
        val circle = hypot(tapDx, tapDy) / fallbackRadiusPx
        val ends = DeepSkyGeometry.semiAxisEnds(direction, axes.majorRad, axes.minorRad, angleRad)
        val major = SkyProjection.project(mvp, ends.first, widthPx, heightPx) ?: return circle
        val minor = SkyProjection.project(mvp, ends.second, widthPx, heightPx) ?: return circle
        val ux = major.x - centre.x
        val uy = major.y - centre.y
        val vx = minor.x - centre.x
        val vy = minor.y - centre.y
        val determinant = ux * vy - uy * vx
        if (abs(determinant) < 1e-9) return circle
        // Solve tap = alpha * u + beta * v.
        val alpha = (tapDx * vy - tapDy * vx) / determinant
        val beta = (ux * tapDy - uy * tapDx) / determinant
        return hypot(alpha, beta)
    }

    /** What a tap selects. */
    enum class Target { NONE, BODY, STAR, DEEP_SKY }

    /**
     * Decides between the hits of one tap. A body wins a tie with a star, and a star only if it is
     * strictly nearer; a deep-sky object is chosen only if strictly nearer than any star or body
     * (distances as returned by the `nearest...` functions; see [nearestDeepSky] for how a big object's
     * distance is reckoned), since stars and bodies are drawn over the symbols and a finger on one of
     * them usually means it.
     */
    fun choose(bodyHit: BodyHit?, starHit: StarHit?, deepSkyHit: DeepSkyHit?): Target {
        val foreground = listOfNotNull(bodyHit?.distancePx, starHit?.distancePx).minOrNull()
        return when {
            deepSkyHit != null && (foreground == null || deepSkyHit.distancePx < foreground) -> Target.DEEP_SKY
            bodyHit != null && (starHit == null || bodyHit.distancePx <= starHit.distancePx) -> Target.BODY
            starHit != null -> Target.STAR
            else -> Target.NONE
        }
    }
}
