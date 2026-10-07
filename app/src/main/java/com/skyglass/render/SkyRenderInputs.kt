package com.skyglass.render

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

    /**
     * GL thread -> main thread: the MVP used for the most recent frame, so labels and taps can be
     * placed with exactly the view the user is looking at. Null until the first frame is drawn.
     */
    @Volatile var lastMvp: Matrix4? = null
}
