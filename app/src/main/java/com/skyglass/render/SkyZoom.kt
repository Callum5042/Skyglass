package com.skyglass.render

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.tan

/**
 * Pinch-to-zoom in the enhanced view: the vertical field of view runs from [MAX_FOV_Y_DEG] (zoomed out)
 * to [MIN_FOV_Y_DEG] (like a low-power telescope, where Jupiter's moons separate from the planet).
 *
 * Zoom is measured as a magnification against [SkyRenderInputs.DEFAULT_FOV_Y_DEG]: the ratio of focal
 * lengths, `tan(default / 2) / tan(fov / 2)`, which is also how a pinch scales what is under the
 * fingers, and how CameraX defines its zoom ratio, so the two views read alike.
 */
object SkyZoom {

    const val MIN_FOV_Y_DEG = 1.0
    const val MAX_FOV_Y_DEG = 90.0

    /** Below this distance from 1× the view counts as not zoomed (the reset control is hidden). */
    const val UNZOOMED_TOLERANCE = 0.03

    /**
     * The orientation filter's time constant grows as `magnification ^ SMOOTHING_EXPONENT` from the
     * normal one, up to [MAX_SMOOTHING_SECONDS]: hand shake is a fixed angle, so it grows on screen
     * with the magnification, while panning slower when zoomed in makes the extra lag acceptable.
     */
    private const val SMOOTHING_EXPONENT = 0.4
    const val MAX_SMOOTHING_SECONDS = 0.3

    fun magnification(fovYDeg: Double, referenceFovYDeg: Double = SkyRenderInputs.DEFAULT_FOV_Y_DEG): Double =
        tan(Math.toRadians(referenceFovYDeg / 2)) / tan(Math.toRadians(fovYDeg / 2))

    fun fovForMagnification(magnification: Double, referenceFovYDeg: Double = SkyRenderInputs.DEFAULT_FOV_Y_DEG): Double =
        2 * Math.toDegrees(atan(tan(Math.toRadians(referenceFovYDeg / 2)) / magnification))

    /** The field of view after a pinch that spread the fingers by [scale] (above 1 zooms in), within the limits. */
    fun pinched(fovYDeg: Double, scale: Double): Double {
        if (!(scale > 0.0) || scale.isInfinite()) return fovYDeg.coerceIn(MIN_FOV_Y_DEG, MAX_FOV_Y_DEG)
        return fovForMagnification(magnification(fovYDeg) * scale).coerceIn(MIN_FOV_Y_DEG, MAX_FOV_Y_DEG)
    }

    fun isZoomed(magnification: Double): Boolean = abs(magnification - 1.0) > UNZOOMED_TOLERANCE

    /**
     * The orientation filter's time constant at [fovYDeg]: [baseSeconds] at or beyond the default field
     * of view, longer when zoomed in (see [SMOOTHING_EXPONENT]), never more than [MAX_SMOOTHING_SECONDS].
     */
    fun smoothingSeconds(fovYDeg: Double, baseSeconds: Double): Double {
        val m = magnification(fovYDeg).coerceAtLeast(1.0)
        return (baseSeconds * m.pow(SMOOTHING_EXPONENT)).coerceIn(baseSeconds, maxOf(baseSeconds, MAX_SMOOTHING_SECONDS))
    }
}
