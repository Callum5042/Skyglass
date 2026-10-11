package com.skyglass.render

import com.skyglass.catalog.ArtImage
import com.skyglass.catalog.ConstellationArtMesh
import com.skyglass.catalog.MilkyWayMesh
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

    /** Draw the horizon line (and, in the overlay, the compass letters); see [HorizonGuides]. */
    @Volatile var showHorizon: Boolean = true

    /** Draw the altitude/azimuth reference grid; see [HorizonGuides]. */
    @Volatile var showGrid: Boolean = true

    /** Deep-sky objects as two triangles each, in the layout of `DeepSkyVertices`; static once loaded. */
    @Volatile var deepSkyVertices: FloatArray? = null

    /** The Milky Way band's mesh (`MilkyWayMesh`); static once loaded. */
    @Volatile var milkyWayMesh: MilkyWayMesh? = null

    /** Draw the deep-sky objects (their labels are the overlay's). Unaffected by the faintest-stars setting. */
    @Volatile var showDeepSky: Boolean = true

    /** Draw the Milky Way band. */
    @Volatile var showMilkyWay: Boolean = true

    /**
     * Draw the constellation lines (their names are the overlay's). A selected constellation is
     * highlighted whatever this says: see [highlightedConstellation].
     */
    @Volatile var showConstellations: Boolean = true

    /**
     * The selected constellation's lines as vertex ranges in [constellationLines] (`ConstellationSky.rangesOf`):
     * `[first0, count0, first1, count1, ...]`, redrawn brighter and wider. Null when none is selected.
     */
    @Volatile var highlightedConstellation: IntArray? = null

    /** The constellation illustrations' mesh (`ConstellationArtMesh`); static once loaded. */
    @Volatile var constellationArtMesh: ConstellationArtMesh? = null

    /**
     * The illustrations' pixels, in the order of [constellationArtMesh]'s images; static once loaded.
     * Kept after upload because a new GL context starts with no textures.
     */
    @Volatile var constellationArtImages: List<ArtImage>? = null

    /** Draw the constellation illustrations. The selected constellation's is drawn whatever this says: see [highlightedArtImage]. */
    @Volatile var showConstellationArt: Boolean = true

    /** The selected constellation's image in [constellationArtMesh], drawn stronger; -1 when none is selected. */
    @Volatile var highlightedArtImage: Int = -1

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

    /** Draw everything in red, and dim the camera image, to keep the eyes dark-adapted; see [NightMode]. */
    @Volatile var nightMode: Boolean = false

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

    /**
     * GL thread -> main thread: the same view as [lastMvp] but taking true-ENU directions (the
     * horizon's own frame, no sidereal time or latitude involved), for placing the compass letters.
     */
    @Volatile var lastHorizonMvp: Matrix4? = null

    companion object {
        const val DEFAULT_FOV_Y_DEG = 60.0
    }
}
