package com.skyglass.render

import kotlin.math.max
import kotlin.math.min

/** A premultiplied colour with alpha, each channel in 0..1. */
data class Rgba(val r: Double, val g: Double, val b: Double, val a: Double)

/**
 * How the stars, lines and bodies are blended into the GL surface, for each view mode.
 *
 * Enhanced view: the surface is opaque black (alpha 1) and everything is added to it, with alpha
 * left alone, so overlapping glows brighten.
 *
 * Camera view: the surface is composited over the camera image by the system, which expects
 * *premultiplied* colour (rgb <= alpha). Pixels with rgb > 0 and alpha 0 are only "valid" by
 * accident of how the compositor adds layers; any path that un-premultiplies the layer (colour
 * space conversion, Night Light or other per-layer colour transforms done in client composition)
 * divides by alpha and can lose them. So in camera mode the shaders output a real alpha,
 * [alphaFor] of their colour, and everything is blended "over" with `ONE, ONE_MINUS_SRC_ALPHA`:
 * if source and destination are valid premultiplied colours the result is too (see [blend]).
 *
 * The shaders mirror [alphaFor] in GLSL (`max(max(c.r, c.g), c.b)`), switched by `uCameraMode`.
 */
object OverlayBlend {

    enum class Factor { ZERO, ONE, ONE_MINUS_SRC_ALPHA }

    data class Factors(val srcRgb: Factor, val dstRgb: Factor, val srcAlpha: Factor, val dstAlpha: Factor)

    private val ADD_KEEPING_ALPHA = Factors(Factor.ONE, Factor.ONE, Factor.ZERO, Factor.ONE)
    private val OVER = Factors(Factor.ONE, Factor.ONE_MINUS_SRC_ALPHA, Factor.ONE, Factor.ONE_MINUS_SRC_ALPHA)

    /** Stars and constellation lines. */
    fun stars(cameraMode: Boolean): Factors = if (cameraMode) OVER else ADD_KEEPING_ALPHA

    /**
     * The Sun, Moon and planets: premultiplied "over". In the enhanced view the glows have alpha 0
     * and so still add, while the Moon's disc (alpha 1) hides what is behind it.
     */
    fun bodies(): Factors = OVER

    /**
     * What the surface is cleared to each frame (premultiplied). Enhanced view: opaque black, in
     * either colour mode. Camera view: transparent, or in night mode black at
     * [NightMode.CAMERA_IMAGE_COVER], which dims the camera image the GL surface is composited over.
     */
    fun clearColor(cameraMode: Boolean, nightMode: Boolean): Rgba = when {
        !cameraMode -> Rgba(0.0, 0.0, 0.0, 1.0)
        nightMode -> Rgba(0.0, 0.0, 0.0, NightMode.CAMERA_IMAGE_COVER)
        else -> Rgba(0.0, 0.0, 0.0, 0.0)
    }

    /** The smallest alpha for which a premultiplied colour is valid (rgb <= alpha). */
    fun alphaFor(r: Double, g: Double, b: Double): Double = min(1.0, max(max(r, g), b))

    /** CPU reference of the GL blend equation, with the framebuffer's clamping to 0..1. */
    fun blend(src: Rgba, dst: Rgba, f: Factors): Rgba {
        fun factor(x: Factor, srcAlpha: Double): Double = when (x) {
            Factor.ZERO -> 0.0
            Factor.ONE -> 1.0
            Factor.ONE_MINUS_SRC_ALPHA -> 1.0 - srcAlpha
        }
        fun channel(s: Double, d: Double, sf: Factor, df: Factor) =
            min(1.0, max(0.0, s * factor(sf, src.a) + d * factor(df, src.a)))
        return Rgba(
            channel(src.r, dst.r, f.srcRgb, f.dstRgb),
            channel(src.g, dst.g, f.srcRgb, f.dstRgb),
            channel(src.b, dst.b, f.srcRgb, f.dstRgb),
            channel(src.a, dst.a, f.srcAlpha, f.dstAlpha),
        )
    }
}
