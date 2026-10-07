package com.skyglass.render

import com.skyglass.math.Matrix4
import com.skyglass.math.Vector3

/** A position on screen in pixels: origin top-left, +X right, +Y down. */
data class ScreenPoint(val x: Double, val y: Double)

/**
 * CPU-side counterpart of the vertex shader: places equatorial-of-date directions on the screen
 * using the same MVP the GPU gets, so overlays and hit-testing agree with what is drawn.
 */
object SkyProjection {

    /**
     * Projects the direction ([x], [y], [z]) through [mvp] onto a [widthPx] × [heightPx] viewport.
     * Returns null when the direction is behind the viewer; the point may still lie off screen
     * (see [isOnScreen]).
     */
    fun project(mvp: Matrix4, x: Double, y: Double, z: Double, widthPx: Int, heightPx: Int): ScreenPoint? {
        val w = mvp[3, 0] * x + mvp[3, 1] * y + mvp[3, 2] * z + mvp[3, 3]
        if (w <= 0.0) return null
        val ndcX = (mvp[0, 0] * x + mvp[0, 1] * y + mvp[0, 2] * z + mvp[0, 3]) / w
        val ndcY = (mvp[1, 0] * x + mvp[1, 1] * y + mvp[1, 2] * z + mvp[1, 3]) / w
        return ScreenPoint((ndcX + 1.0) * 0.5 * widthPx, (1.0 - ndcY) * 0.5 * heightPx)
    }

    fun project(mvp: Matrix4, direction: Vector3, widthPx: Int, heightPx: Int): ScreenPoint? =
        project(mvp, direction.x, direction.y, direction.z, widthPx, heightPx)

    /** True if [point] lies inside the viewport, allowing [marginPx] outside each edge. */
    fun isOnScreen(point: ScreenPoint, widthPx: Int, heightPx: Int, marginPx: Double = 0.0): Boolean =
        point.x >= -marginPx && point.x <= widthPx + marginPx &&
            point.y >= -marginPx && point.y <= heightPx + marginPx
}
