package com.skyglass.render

import kotlin.math.atan
import kotlin.math.max
import kotlin.math.tan

/**
 * The part of a camera sensor that records the scene, and the lens in front of it. Sizes are of
 * the *active* pixel array in the sensor's native orientation (width is the long side).
 */
data class CameraSensor(val focalLengthMm: Double, val activeWidthMm: Double, val activeHeightMm: Double) {

    companion object {
        /**
         * Camera2 reports the physical size of the whole pixel array, while the image is made from
         * the (usually slightly smaller) active array; the two share one pixel pitch.
         *
         * @return null if any value is not positive
         */
        fun fromCharacteristics(
            focalLengthMm: Double,
            physicalWidthMm: Double,
            physicalHeightMm: Double,
            pixelArrayWidthPx: Int,
            pixelArrayHeightPx: Int,
            activeWidthPx: Int,
            activeHeightPx: Int,
        ): CameraSensor? {
            if (focalLengthMm <= 0.0 || physicalWidthMm <= 0.0 || physicalHeightMm <= 0.0) return null
            if (pixelArrayWidthPx <= 0 || pixelArrayHeightPx <= 0 || activeWidthPx <= 0 || activeHeightPx <= 0) return null
            return CameraSensor(
                focalLengthMm,
                physicalWidthMm * activeWidthPx / pixelArrayWidthPx,
                physicalHeightMm * activeHeightPx / pixelArrayHeightPx,
            )
        }
    }
}

/** Angles subtended by the whole view, across its vertical and horizontal extent as drawn. */
data class CameraFieldOfView(val verticalDeg: Double, val horizontalDeg: Double)

/**
 * Works out how wide a view the camera preview shows, so that the sky projection can use the
 * same field of view and a star drawn over the camera image lands on the star in the image
 * (and a tap on the image picks the star under the finger).
 *
 * Model: a pinhole camera whose principal point is the centre of the image. Real lenses add a
 * little distortion and an off-centre principal point; both are ignored (a degree or two at the
 * very edge of a wide lens, invisible near the centre of the screen).
 */
object CameraFov {

    /**
     * A typical phone main camera (1/2.55" sensor, 4.8 mm lens, about 67° along the long side),
     * used when the real characteristics cannot be read.
     */
    val TYPICAL_PHONE = CameraSensor(focalLengthMm = 4.8, activeWidthMm = 6.4, activeHeightMm = 4.8)

    /**
     * The field of view of a [viewWidthPx] × [viewHeightPx] view showing a camera preview that
     * fills it, centred, cropping whatever overflows (`PreviewView.ScaleType.FILL_CENTER`).
     *
     * Two crops are involved, and both only discard the edges, so what matters is the focal
     * length in pixels:
     *  1. The camera delivers a [streamWidthPx] × [streamHeightPx] stream (sensor orientation,
     *     width is the long side). If its aspect ratio is wider than the sensor's, the sensor's
     *     full width is used and the top and bottom are dropped; if narrower, the full height is
     *     used and the sides are dropped. This fixes how many millimetres of sensor the stream spans.
     *  2. The stream is rotated by [rotationDegrees] to upright, then scaled uniformly until it
     *     covers the view; the overflow is cropped.
     *
     * @param rotationDegrees clockwise rotation that makes the stream upright on screen (90 or
     *   270 for a phone held in portrait, whose sensor is mounted sideways)
     * @param cropWidthPx width of the part of the stream that is shown, if the preview is cropped
     *   (CameraX's `ResolutionInfo.cropRect`); in the stream's own, unrotated orientation. The
     *   whole stream by default. A crop changes how much is scaled to fill the view, not the lens.
     * @param cropHeightPx likewise, the height of the shown part
     * @return null if any size is not positive
     */
    fun forView(
        sensor: CameraSensor,
        streamWidthPx: Int,
        streamHeightPx: Int,
        rotationDegrees: Int,
        viewWidthPx: Int,
        viewHeightPx: Int,
        cropWidthPx: Int = streamWidthPx,
        cropHeightPx: Int = streamHeightPx,
    ): CameraFieldOfView? {
        if (streamWidthPx <= 0 || streamHeightPx <= 0 || viewWidthPx <= 0 || viewHeightPx <= 0) return null
        if (cropWidthPx <= 0 || cropHeightPx <= 0) return null
        if (sensor.focalLengthMm <= 0.0 || sensor.activeWidthMm <= 0.0 || sensor.activeHeightMm <= 0.0) return null

        val sensorAspect = sensor.activeWidthMm / sensor.activeHeightMm
        val streamAspect = streamWidthPx.toDouble() / streamHeightPx
        val streamWidthMm = if (streamAspect >= sensorAspect) sensor.activeWidthMm else sensor.activeHeightMm * streamAspect
        val streamFocalPx = sensor.focalLengthMm * streamWidthPx / streamWidthMm

        val quarterTurn = Math.floorMod(Math.round(rotationDegrees / 90.0).toInt(), 2) == 1
        val shownWidthPx = (if (quarterTurn) cropHeightPx else cropWidthPx).coerceAtMost(if (quarterTurn) streamHeightPx else streamWidthPx)
        val shownHeightPx = (if (quarterTurn) cropWidthPx else cropHeightPx).coerceAtMost(if (quarterTurn) streamWidthPx else streamHeightPx)
        val scale = max(viewWidthPx.toDouble() / shownWidthPx, viewHeightPx.toDouble() / shownHeightPx)
        val focalPx = streamFocalPx * scale

        return CameraFieldOfView(
            verticalDeg = Math.toDegrees(2.0 * atan(viewHeightPx / (2.0 * focalPx))),
            horizontalDeg = Math.toDegrees(2.0 * atan(viewWidthPx / (2.0 * focalPx))),
        )
    }

    /**
     * The field of view at a camera zoom ratio, from the one at 1×. Camera2 defines the ratio
     * (`CONTROL_ZOOM_RATIO`, which CameraX's `setZoomRatio` sets) by the field of view: at 2× the view
     * is cut to half the width and height of the 1× view, i.e. the focal length doubles, whichever
     * physical lens a multi-camera switches to. A ratio below 1 (an ultra-wide lens) widens it.
     */
    fun zoomed(fov: CameraFieldOfView, zoomRatio: Double): CameraFieldOfView {
        if (!(zoomRatio > 0.0)) return fov
        fun narrow(deg: Double) = Math.toDegrees(2.0 * atan(tan(Math.toRadians(deg / 2)) / zoomRatio))
        return CameraFieldOfView(verticalDeg = narrow(fov.verticalDeg), horizontalDeg = narrow(fov.horizontalDeg))
    }
}
