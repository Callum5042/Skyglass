package com.skyglass.render

import com.skyglass.math.Matrix4
import com.skyglass.math.Vector3
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Space, in pixels, that guidance keeps clear at each edge of the sky view: system bars, the
 * toggles, the details card, the bottom panel and room for the arrow itself.
 */
data class NavInsets(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    companion object {
        val NONE = NavInsets(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * An arrow at the edge of the usable view pointing toward the target.
 *
 * @property angleDeg direction in screen space, clockwise from pointing right (the way a Compose
 *   `rotate` turns): 0 right, 90 down, ±180 left, −90 up
 */
data class EdgeArrow(val position: ScreenPoint, val angleDeg: Double)

/**
 * Where the target is relative to the view.
 *
 * @property angularDistanceDeg angle between the centre of the view and the target, 0..180
 * @property onScreen where the target is drawn, if it lies within the viewport (it may still be
 *   under a card, so [arrow] can be set too)
 * @property arrow set unless the target is inside the usable area, i.e. the user still has to turn
 */
data class Guidance(val angularDistanceDeg: Double, val onScreen: ScreenPoint?, val arrow: EdgeArrow?)

/**
 * "Which way do I turn the phone to bring this into view": CPU-side, from the same MVP as the
 * renderer, so it agrees with where things are drawn in both views and every display rotation.
 */
object SkyNavigation {

    /**
     * @param mvp the latest frame's matrix (`SkyRenderInputs.lastMvp`)
     * @param direction unit vector toward the target, equatorial of date, like the star and body vertices
     * @param insets space the target's arrow must stay clear of; a target inside the remaining
     *   rectangle needs no arrow
     */
    fun guide(mvp: Matrix4, direction: Vector3, widthPx: Int, heightPx: Int, insets: NavInsets = NavInsets.NONE): Guidance {
        val x = direction.x
        val y = direction.y
        val z = direction.z
        val clipX = mvp[0, 0] * x + mvp[0, 1] * y + mvp[0, 2] * z + mvp[0, 3]
        val clipY = mvp[1, 0] * x + mvp[1, 1] * y + mvp[1, 2] * z + mvp[1, 3]
        val clipW = mvp[3, 0] * x + mvp[3, 1] * y + mvp[3, 2] * z + mvp[3, 3]

        // For a rotation followed by a perspective projection, clip w is the depth along the view
        // axis, so for a unit vector it is the cosine of the angle off the centre of the view.
        val length = direction.length()
        val angular = Math.toDegrees(acos((clipW / length).coerceIn(-1.0, 1.0)))

        val point = SkyProjection.project(mvp, direction, widthPx, heightPx)
        val onScreen = point?.takeIf { SkyProjection.isOnScreen(it, widthPx, heightPx) }

        val centreX = widthPx / 2.0
        val centreY = heightPx / 2.0
        val left = insets.left.coerceIn(0.0, centreX)
        val right = (widthPx - insets.right).coerceIn(centreX, widthPx.toDouble())
        val top = insets.top.coerceIn(0.0, centreY)
        val bottom = (heightPx - insets.bottom).coerceIn(centreY, heightPx.toDouble())

        if (point != null && point.x in left..right && point.y in top..bottom) {
            return Guidance(angular, onScreen, arrow = null)
        }

        // Pixel offset from the centre of the view, up to the (positive) factor 1/w that the divide
        // would apply. Unlike the projected point it keeps its meaning behind the viewer, where
        // that divide mirrors the target through the centre: a target behind and to the right is
        // reached by turning right, which is the sign of clip x there too.
        val dx = clipX * widthPx / 2.0
        val dy = -clipY * heightPx / 2.0 // clip y is up, screen y is down
        val norm = hypot(dx, dy)
        // Dead ahead is on screen; dead behind has no better turn than either way, so say right.
        val unitX = if (norm < 1e-12) 1.0 else dx / norm
        val unitY = if (norm < 1e-12) 0.0 else dy / norm

        val tX = when {
            unitX > 1e-12 -> (right - centreX) / unitX
            unitX < -1e-12 -> (left - centreX) / unitX
            else -> Double.POSITIVE_INFINITY
        }
        val tY = when {
            unitY > 1e-12 -> (bottom - centreY) / unitY
            unitY < -1e-12 -> (top - centreY) / unitY
            else -> Double.POSITIVE_INFINITY
        }
        val t = min(tX, tY)
        return Guidance(
            angularDistanceDeg = angular,
            onScreen = onScreen,
            arrow = EdgeArrow(
                position = ScreenPoint(centreX + unitX * t, centreY + unitY * t),
                angleDeg = Math.toDegrees(atan2(unitY, unitX)),
            ),
        )
    }
}
