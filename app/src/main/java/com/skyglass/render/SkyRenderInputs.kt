package com.skyglass.render

import com.skyglass.catalog.StarVisibility
import com.skyglass.math.Matrix4

data class Observer(val latitudeDeg: Double, val longitudeDeg: Double, val declinationDeg: Double)

/**
 * Values exchanged between the main thread and the GL thread. Each field is replaced wholesale
 * with an immutable value (arrays are never mutated after publishing).
 */
class SkyRenderInputs {
    // Main thread -> GL thread.
    @Volatile var starVertices: FloatArray? = null
    @Volatile var constellationLines: FloatArray? = null
    @Volatile var observer: Observer? = null

    /** Sun, Moon and planets in the layout of `BodyVertices`; replaced every few seconds as they move. */
    @Volatile var bodyVertices: FloatArray? = null

    /**
     * The "faintest stars" setting (see `StarVisibility`): stars fainter than this fade out in the
     * shader. Only stars are affected; bodies and constellation lines are drawn regardless.
     */
    @Volatile var limitingMagnitude: Double = StarVisibility.DEFAULT_LIMIT

    /**
     * Vertical field of view of the projection. [DEFAULT_FOV_Y_DEG] for the enhanced view; in the
     * camera view the real camera's (see `CameraFov`), so the sky lines up with the image.
     */
    @Volatile var fovYDeg: Double = DEFAULT_FOV_Y_DEG

    /**
     * True while a camera preview is showing behind the GL surface. The surface is then cleared to
     * transparent and the stars, lines and bodies are drawn with a real alpha, to be composited over
     * the camera image instead of added to black (see `OverlayBlend`).
     */
    @Volatile var cameraMode: Boolean = false

    /**
     * GL thread -> main thread: the largest point sprite the GPU will draw, in pixels, so a tap's
     * reach never exceeds what is actually visible. Unbounded until the GL context exists.
     */
    @Volatile var maxPointSizePx: Float = Float.MAX_VALUE

    /**
     * GL thread -> main thread: the MVP used for the most recent frame, so labels and taps can be
     * placed with exactly the view the user is looking at. Null until the first frame is drawn.
     */
    @Volatile var lastMvp: Matrix4? = null

    companion object {
        const val DEFAULT_FOV_Y_DEG = 60.0
    }
}
