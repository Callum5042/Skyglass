package com.skyglass.render

import com.skyglass.astro.CompassPoint
import com.skyglass.astro.Coordinates
import com.skyglass.astro.Horizontal
import com.skyglass.math.Matrix4
import com.skyglass.math.Vector3

/**
 * The horizon line, the altitude/azimuth reference grid and the compass points, all fixed to the
 * observer's horizon. Everything here is in **true ENU** (+X east, +Y north, +Z up; azimuth from
 * true north through east), the frame [SkyViewMath.horizonViewMatrix] takes to the screen. Because
 * the observer's horizon does not move relative to ENU, the geometry is built once and never
 * touched again; it is the view matrix that follows the phone (and the declination that puts true
 * north in the right place), not the vertices that follow the sky's rotation.
 */
object HorizonGuides {

    const val FLOATS_PER_VERTEX = 3

    /** Chord length of the sampled circles. A chord of a unit circle projects to a straight line, so this only has to be fine enough to look round. */
    private const val SAMPLE_STEP_DEG = 2.0

    /** Altitude circles of the grid, in degrees above the horizon. */
    val ALTITUDE_CIRCLES_DEG: List<Double> = listOf(30.0, 60.0)

    /** The vertical lines of the grid are one per [CompassPoint]: every 45° of azimuth, N, NE, E, ... */
    const val MERIDIAN_STEP_DEG = 45.0

    /**
     * How high the vertical lines run. Short of the zenith, where all eight would meet in a knot;
     * the zenith itself is marked by a small cross instead.
     */
    const val MERIDIAN_TOP_DEG = 80.0

    /** The zenith cross reaches this far down from the zenith along N-S and E-W. */
    const val ZENITH_CROSS_RADIUS_DEG = 3.0

    /**
     * GPU-ready `GL_LINES` positions (unit vectors, [FLOATS_PER_VERTEX] floats each, two vertices
     * per segment, true ENU). The two sets are kept apart because they are drawn in different colours.
     *
     * @property horizon the great circle at altitude 0°
     * @property grid altitude circles, vertical lines and the zenith cross
     */
    class Geometry(val horizon: FloatArray, val grid: FloatArray)

    /** Builds the geometry. Cheap (a few hundred segments), pure and deterministic. */
    fun build(): Geometry {
        val horizon = ArrayList<Float>()
        circleAtAltitude(0.0, horizon)

        val grid = ArrayList<Float>()
        for (altitude in ALTITUDE_CIRCLES_DEG) circleAtAltitude(altitude, grid)
        var azimuth = 0.0
        while (azimuth < 360.0 - 1e-9) {
            verticalLine(azimuth, grid)
            azimuth += MERIDIAN_STEP_DEG
        }
        zenithCross(grid)
        return Geometry(horizon.toFloatArray(), grid.toFloatArray())
    }

    /** Unit vector in true ENU of the direction at [altitudeDeg] and [azimuthDeg] (from north through east). */
    fun direction(altitudeDeg: Double, azimuthDeg: Double): Vector3 =
        Coordinates.horizontalToEnu(Horizontal(Math.toRadians(altitudeDeg), Math.toRadians(azimuthDeg)))

    private fun circleAtAltitude(altitudeDeg: Double, out: MutableList<Float>) {
        val steps = Math.round(360.0 / SAMPLE_STEP_DEG).toInt()
        for (i in 0 until steps) {
            segment(direction(altitudeDeg, i * 360.0 / steps), direction(altitudeDeg, (i + 1) * 360.0 / steps), out)
        }
    }

    private fun verticalLine(azimuthDeg: Double, out: MutableList<Float>) {
        val steps = Math.round(MERIDIAN_TOP_DEG / SAMPLE_STEP_DEG).toInt()
        for (i in 0 until steps) {
            segment(
                direction(i * MERIDIAN_TOP_DEG / steps, azimuthDeg),
                direction((i + 1) * MERIDIAN_TOP_DEG / steps, azimuthDeg),
                out,
            )
        }
    }

    /** Two chords through the zenith, one along the meridian and one along the prime vertical. */
    private fun zenithCross(out: MutableList<Float>) {
        val altitude = 90.0 - ZENITH_CROSS_RADIUS_DEG
        segment(direction(altitude, 0.0), direction(altitude, 180.0), out)
        segment(direction(altitude, 90.0), direction(altitude, 270.0), out)
    }

    private fun segment(a: Vector3, b: Vector3, out: MutableList<Float>) {
        out += a.x.toFloat(); out += a.y.toFloat(); out += a.z.toFloat()
        out += b.x.toFloat(); out += b.y.toFloat(); out += b.z.toFloat()
    }
}

/** A compass point placed on the screen; [position] is where the centre of its text goes. */
data class PlacedCompassPoint(val point: CompassPoint, val position: ScreenPoint)

/**
 * Where the compass letters go. Each sits on the horizon at its azimuth, raised by
 * [ANCHOR_ALTITUDE_DEG] so the text rests just above the line instead of being struck through by it.
 * (Raising it by degrees of sky rather than pixels keeps "above" meaning "toward the zenith" when
 * the phone is rolled, in any display rotation.)
 */
object CompassLabels {

    /** About 13 dp at the default 60° field of view on a tall phone. */
    const val ANCHOR_ALTITUDE_DEG = 1.0

    /** The unit vector in true ENU of [point] on the horizon itself: N is +Y, E is +X, S is −Y, W is −X. */
    fun horizonDirection(point: CompassPoint): Vector3 = HorizonGuides.direction(0.0, point.azimuthDeg)

    /** Where the text for [point] is anchored, in true ENU. */
    fun anchorDirection(point: CompassPoint): Vector3 = HorizonGuides.direction(ANCHOR_ALTITUDE_DEG, point.azimuthDeg)

    /**
     * The compass points to draw for a view described by [horizonMvp] (true ENU → clip, see
     * [SkyViewMath.horizonModelViewProjection]) on a [widthPx] × [heightPx] screen: those in front of
     * the viewer (clip w > 0) and within [marginPx] of the screen, so a letter does not pop out the
     * instant its centre crosses the edge. A point behind the viewer is never returned, however
     * close its mirrored position would land to the screen.
     */
    fun place(horizonMvp: Matrix4, widthPx: Int, heightPx: Int, marginPx: Double = 0.0): List<PlacedCompassPoint> =
        CompassPoint.entries.mapNotNull { point ->
            val screen = SkyProjection.project(horizonMvp, anchorDirection(point), widthPx, heightPx) ?: return@mapNotNull null
            if (SkyProjection.isOnScreen(screen, widthPx, heightPx, marginPx)) PlacedCompassPoint(point, screen) else null
        }
}
