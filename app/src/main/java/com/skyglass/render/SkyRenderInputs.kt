package com.skyglass.render

data class Observer(val latitudeDeg: Double, val longitudeDeg: Double, val declinationDeg: Double)

/**
 * Values written on the main thread and read by the GL thread each frame. Each field is replaced
 * wholesale with an immutable value (the vertex array is never mutated after publishing).
 */
class SkyRenderInputs {
    @Volatile var starVertices: FloatArray? = null
    @Volatile var observer: Observer? = null
}
