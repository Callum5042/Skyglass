package com.skyglass.ui

import android.Manifest
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skyglass.R
import com.skyglass.render.SkyRenderer

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** Dim grey keeps the overlay readable without spoiling dark adaptation. */
private val StatusTextColor = Color(0xFF8A8A8A)

@Composable
fun SkyScreen(viewModel: SkyViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var hasLocationPermission by remember { mutableStateOf(viewModel.hasLocationPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasLocationPermission = grants.values.any { it }
        if (hasLocationPermission) viewModel.startLocationUpdates()
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) permissionLauncher.launch(LOCATION_PERMISSIONS)
    }

    LifecycleResumeEffect(viewModel) {
        // Permission may have been changed in system settings while we were paused.
        hasLocationPermission = viewModel.hasLocationPermission()
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SkyGlView(viewModel, Modifier.fillMaxSize())
        StatusOverlay(
            state = state,
            hasLocationPermission = hasLocationPermission,
            onRequestPermission = { permissionLauncher.launch(LOCATION_PERMISSIONS) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(16.dp),
        )
    }
}

@Composable
private fun SkyGlView(viewModel: SkyViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val glView = remember(context) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            val view = this
            setRenderer(
                SkyRenderer(
                    inputs = viewModel.renderInputs,
                    orientation = viewModel.orientationSensor::latestOrientation,
                    displayRotation = { view.display?.rotation ?: Surface.ROTATION_0 },
                    pointScale = resources.displayMetrics.density,
                )
            )
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            keepScreenOn = true
        }
    }

    LifecycleResumeEffect(glView) {
        glView.onResume()
        onPauseOrDispose { glView.onPause() }
    }

    AndroidView(factory = { glView }, modifier = modifier)
}

@Composable
private fun StatusOverlay(
    state: SkyUiState,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when {
        !state.hasRotationSensor -> stringResource(R.string.status_no_rotation_sensor)
        !hasLocationPermission -> stringResource(R.string.status_location_permission)
        state.starCount == null -> stringResource(R.string.status_loading_stars)
        state.observer == null && !state.locationEnabled -> stringResource(R.string.status_location_disabled)
        state.observer == null -> stringResource(R.string.status_waiting_for_location)
        state.compassAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW -> stringResource(R.string.status_calibrate_compass)
        else -> null
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (message != null) {
            Text(
                text = message,
                color = StatusTextColor,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        if (state.hasRotationSensor && !hasLocationPermission) {
            OutlinedButton(onClick = onRequestPermission) {
                Text(stringResource(R.string.action_grant_location), color = StatusTextColor)
            }
        }
        val observer = state.observer
        if (state.starCount != null && observer != null) {
            Text(
                text = stringResource(
                    R.string.status_info,
                    state.starCount,
                    observer.latitudeDeg,
                    observer.longitudeDeg,
                    observer.declinationDeg,
                ),
                color = StatusTextColor,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
