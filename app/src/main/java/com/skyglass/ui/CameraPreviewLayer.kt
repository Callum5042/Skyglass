package com.skyglass.ui

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.skyglass.render.CameraFov
import com.skyglass.render.CameraSensor

/**
 * The live rear-camera image, filling its bounds (centre-cropped, as the sky is projected about
 * the centre of the same bounds). The camera runs only while the host is resumed: it is bound
 * when the screen resumes and released when it pauses or this leaves the composition.
 *
 * Whenever the preview's stream or size changes, [onFieldOfView] gets the vertical field of view
 * that the image has *as laid out*, computed by [CameraFov] from the camera's focal length and
 * sensor size, so the caller can project the sky with the same field of view.
 *
 * The preview must be a `SurfaceView` (`PERFORMANCE` mode): the translucent GL surface sits above
 * it (`setZOrderMediaOverlay`), and both are below the window. CameraX falls back to a
 * `TextureView` on some legacy devices; that lives in the window, where the GL `SurfaceView`
 * punches its hole, so the camera image would be wiped and only stars on black would remain. That
 * fallback is detected once the stream starts and reported through [onUnsupported]. (Drawing the
 * camera inside GL as an external texture would remove the limitation, at the cost of owning the
 * whole preview pipeline.)
 *
 * @param onProblem the camera will not open: none on the device, CameraX failed to bind, or it
 *   reported a `CRITICAL` error (disabled by policy, fatal error, do-not-disturb mode, stream
 *   configuration). A camera in use by another app is *not* this: that error is `RECOVERABLE`,
 *   CameraX keeps retrying, and it is reported through [onWaiting] instead.
 * @param onWaiting true while CameraX is waiting to open a busy camera; false once it has opened
 *   (or this leaves the composition or pauses). The caller stays in camera mode throughout.
 * @param onUnsupported the preview fell back to a `TextureView`
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreviewLayer(
    onFieldOfView: (fovYDeg: Double) -> Unit,
    onProblem: () -> Unit,
    onWaiting: (waiting: Boolean) -> Unit,
    onUnsupported: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val currentOnFieldOfView by rememberUpdatedState(onFieldOfView)
    val currentOnProblem by rememberUpdatedState(onProblem)
    val currentOnWaiting by rememberUpdatedState(onWaiting)
    val currentOnUnsupported by rememberUpdatedState(onUnsupported)

    LifecycleResumeEffect(previewView) {
        // 4:3 is the whole sensor on nearly every phone, i.e. the widest view the camera offers.
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
        var provider: ProcessCameraProvider? = null
        var cameraState: LiveData<CameraState>? = null
        var sensor = CameraFov.TYPICAL_PHONE
        var released = false

        fun updateFieldOfView() {
            if (released) return
            val info = preview.resolutionInfo ?: return
            val crop = info.cropRect
            val fov = CameraFov.forView(
                sensor, info.resolution.width, info.resolution.height, info.rotationDegrees,
                previewView.width, previewView.height,
                cropWidthPx = crop.width(), cropHeightPx = crop.height(),
            ) ?: return
            currentOnFieldOfView(fov.verticalDeg)
        }

        val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) updateFieldOfView()
        }
        val streamObserver = Observer<PreviewView.StreamState> { state ->
            if (state != PreviewView.StreamState.STREAMING || released) return@Observer
            if (previewView.containsTextureView()) {
                currentOnUnsupported()
            } else {
                updateFieldOfView()
            }
        }
        // A camera that cannot be opened does not make bindToLifecycle throw: CameraX reports it here.
        val tracker = CameraStateTracker()
        val stateObserver = Observer<CameraState> { state ->
            if (released) return@Observer
            val verdict = tracker.onState(state.type.toPhase(), state.error?.type.toKind())
            currentOnWaiting(verdict == CameraVerdict.WAITING)
            if (verdict == CameraVerdict.FATAL) currentOnProblem()
        }
        previewView.addOnLayoutChangeListener(layoutListener)
        previewView.previewStreamState.observeForever(streamObserver)

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (released) return@addListener
            try {
                val cameraProvider = providerFuture.get()
                if (!cameraProvider.hasCamera(selector)) {
                    currentOnProblem()
                    return@addListener
                }
                preview.surfaceProvider = previewView.surfaceProvider
                val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview)
                provider = cameraProvider
                cameraState = camera.cameraInfo.cameraState.also {
                    it.observeForever(stateObserver) // delivers stale values synchronously; the tracker ignores them
                    tracker.attached()
                }
                sensor = readSensor(camera.cameraInfo) ?: CameraFov.TYPICAL_PHONE
                updateFieldOfView()
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the camera", e)
                currentOnProblem()
            }
        }, ContextCompat.getMainExecutor(context))

        onPauseOrDispose {
            released = true
            previewView.removeOnLayoutChangeListener(layoutListener)
            previewView.previewStreamState.removeObserver(streamObserver)
            cameraState?.removeObserver(stateObserver)
            currentOnWaiting(false)
            provider?.unbind(preview)
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private const val TAG = "CameraPreviewLayer"

private fun CameraState.Type.toPhase(): CameraPhase = when (this) {
    CameraState.Type.PENDING_OPEN -> CameraPhase.PENDING_OPEN
    CameraState.Type.OPENING -> CameraPhase.OPENING
    CameraState.Type.OPEN -> CameraPhase.OPEN
    CameraState.Type.CLOSING -> CameraPhase.CLOSING
    CameraState.Type.CLOSED -> CameraPhase.CLOSED
}

private fun CameraState.ErrorType?.toKind(): CameraErrorKind = when (this) {
    null -> CameraErrorKind.NONE
    CameraState.ErrorType.RECOVERABLE -> CameraErrorKind.RECOVERABLE
    CameraState.ErrorType.CRITICAL -> CameraErrorKind.CRITICAL
}

private fun View.containsTextureView(): Boolean =
    this is TextureView || (this is ViewGroup && (0 until childCount).any { getChildAt(it).containsTextureView() })

/**
 * The lens and active sensor area of the bound camera, or null if it does not report them.
 *
 * The focal length is the first of `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`. A fixed-lens camera lists
 * one; a logical multi-camera or optical-zoom camera may list several, and then this is the main
 * lens only by convention, so a phone that uses another at zoom 1.0 would be off by the ratio of
 * the two (not verified on such hardware). `LENS_INTRINSIC_CALIBRATION` or the focal length of a
 * capture result would be exact, but are missing or unreliable on many devices.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun readSensor(cameraInfo: CameraInfo): CameraSensor? {
    val camera2 = Camera2CameraInfo.from(cameraInfo)
    val focalLengthMm = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.firstOrNull() ?: return null
    val physical = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    val pixelArray = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
    val active = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
    return CameraSensor.fromCharacteristics(
        focalLengthMm = focalLengthMm.toDouble(),
        physicalWidthMm = physical.width.toDouble(),
        physicalHeightMm = physical.height.toDouble(),
        pixelArrayWidthPx = pixelArray.width,
        pixelArrayHeightPx = pixelArray.height,
        activeWidthPx = active.width(),
        activeHeightPx = active.height(),
    )
}
