package com.skyglass.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.provider.Settings
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skyglass.R
import com.skyglass.catalog.Bortle
import com.skyglass.catalog.StarVisibility
import com.skyglass.render.NavInsets
import com.skyglass.render.SkyRenderer
import com.skyglass.render.SkyZoom
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

private const val CAMERA_PERMISSION = Manifest.permission.CAMERA

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** Dim grey keeps the overlay readable without spoiling dark adaptation. */
private val StatusTextColor = Color(0xFF8A8A8A)
private val ActiveTextColor = Color(0xFFCBD2E0)

/** How long a camera message stays up if the user does not touch the switch again. */
private const val CAMERA_PROBLEM_TIMEOUT_MS = 8_000L

/** A minute moves the sky by a quarter of a degree, well within what the list says. */
private const val TONIGHT_REFRESH_MS = 60_000L

/** Panels and info cards sit below the top bar, with a gap so they don't touch its buttons. */
private val TopControlsHeight = 48.dp
private val BelowTopControls = TopControlsHeight + 12.dp

/** Page margin round every panel, and how far the guidance arrow's centre stays from what it must not cover. */
private val ScreenMargin = 16.dp
private val NavigationArrowMargin = 28.dp

@Composable
fun SkyScreen(viewModel: SkyViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val density = LocalDensity.current.density
    val context = LocalContext.current
    var hasLocationPermission by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    val selectedBody = state.selectedBody?.let { id -> state.bodies.firstOrNull { it.body == id } }
    val selectedMoon = state.selectedMoon?.let { id -> state.moons.firstOrNull { it.moon == id } }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasLocationPermission = grants.values.any { it }
        if (hasLocationPermission) viewModel.startLocationUpdates()
    }

    // Whether the display settings card (the "faintest stars" slider and the horizon switches) is
    // showing; the settings themselves live in the view model.
    var starLimitOpen by rememberSaveable { mutableStateOf(false) }

    // Whether the tapped object's card shows all its details; kept across taps, so the choice sticks.
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }

    // The search panel and what is typed in it. Choosing a result closes the panel and empties the box.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = searchOpen) { searchOpen = false }

    // The Tonight panel, in the same place as the search panel; worked out when it opens, and kept
    // current while it is open. Only one of Tonight, Search and Display is open at a time.
    var tonightOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = tonightOpen) { tonightOpen = false }
    val locationKnown = state.observer != null
    val catalogueLoaded = state.starCount != null
    // Only while resumed: the composition outlives a backgrounded activity, and delay() keeps ticking.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(tonightOpen, locationKnown, catalogueLoaded, lifecycleOwner) {
        if (!tonightOpen) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refreshTonight()
                delay(TONIGHT_REFRESH_MS)
            }
        }
    }
    // The search and Tonight panels take the details card's place while open.
    val panelOpen = searchOpen || tonightOpen

    // Where the panels sit, measured, so the guidance arrow stays clear of them: the bottom of the
    // details card, the top of the bottom panel and the height of the screen (all in pixels).
    var infoCardBottomPx by remember { mutableFloatStateOf(0f) }
    var bottomPanelTopPx by remember { mutableFloatStateOf(Float.NaN) }
    var rootHeightPx by remember { mutableFloatStateOf(0f) }
    val localDensity = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeDrawing = WindowInsets.safeDrawing
    val infoCardShowing = !panelOpen &&
        (state.selectedStar != null || selectedBody != null || selectedMoon != null || state.selectedDeepSky != null ||
            state.selectedConstellation != null)
    val navigationInsets = with(localDensity) {
        val margin = NavigationArrowMargin.toPx()
        val controlsBottom = safeDrawing.getTop(localDensity) + (ScreenMargin + TopControlsHeight).toPx()
        NavInsets(
            left = safeDrawing.getLeft(localDensity, layoutDirection) + margin.toDouble(),
            top = max(controlsBottom, if (infoCardShowing) infoCardBottomPx else 0f) + margin.toDouble(),
            right = safeDrawing.getRight(localDensity, layoutDirection) + margin.toDouble(),
            bottom = (if (bottomPanelTopPx.isNaN()) safeDrawing.getBottom(localDensity).toFloat() else rootHeightPx - bottomPanelTopPx) + margin.toDouble(),
        )
    }

    var hasCameraPermission by remember { mutableStateOf(viewModel.hasCameraPermission()) }
    // Read just before each request: see CameraPermissionPolicy.
    // Saved, because the activity can be recreated while the dialog is up.
    var rationaleBeforeRequest by rememberSaveable { mutableStateOf(false) }

    // Asked for only when the user first switches to the camera view.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            viewModel.onCameraPermissionGranted()
            viewModel.setViewMode(ViewMode.CAMERA)
        } else {
            // Once the system stops offering the dialog, asking again returns "denied" at once and
            // only the app's settings page can grant the permission.
            val rationaleAfter = context.findActivity()?.shouldShowRequestPermissionRationale(CAMERA_PERMISSION) ?: true
            val blocked = CameraPermissionPolicy.isBlocked(rationaleBeforeRequest, rationaleAfter, viewModel.cameraPermissionBlocked)
            viewModel.cameraPermissionBlocked = blocked
            viewModel.onCameraProblem(if (blocked) CameraProblem.PERMISSION_BLOCKED else CameraProblem.PERMISSION_DENIED)
        }
    }

    // A message about the camera is not worth keeping on screen for ever, but only its own timeout may clear it.
    LaunchedEffect(state.cameraProblemSeq) {
        if (state.cameraProblem != null) {
            val seq = state.cameraProblemSeq // `state` is read live: capture it before the delay
            delay(CAMERA_PROBLEM_TIMEOUT_MS)
            viewModel.clearCameraProblem(expectedSeq = seq)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) permissionLauncher.launch(LOCATION_PERMISSIONS)
    }

    LifecycleResumeEffect(viewModel) {
        // Permission may have been granted in system settings (which does not restart the app), or
        // a one-time grant may have expired, while we were paused. Revoking one in settings kills
        // the process, so that case never gets here.
        hasLocationPermission = viewModel.hasLocationPermission()
        hasCameraPermission = viewModel.hasCameraPermission()
        if (hasCameraPermission) viewModel.onCameraPermissionGranted()
        if (!hasCameraPermission && viewModel.uiState.value.viewMode == ViewMode.CAMERA) {
            viewModel.onCameraProblem(CameraProblem.PERMISSION_DENIED)
        }
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { rootHeightPx = it.height.toFloat() }) {
        // Behind the GL surface, which is translucent and cleared to transparent in camera mode.
        if (state.viewMode == ViewMode.CAMERA) {
            CameraPreviewLayer(
                zoomRatio = state.cameraZoomRatio,
                onFieldOfView = viewModel::onCameraFieldOfView,
                onZoomRange = viewModel::onCameraZoomRange,
                onProblem = { viewModel.onCameraProblem(CameraProblem.UNAVAILABLE) },
                onWaiting = viewModel::onCameraWaiting,
                onUnsupported = { viewModel.onCameraProblem(CameraProblem.UNSUPPORTED) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        SkyGlView(viewModel, Modifier.fillMaxSize())
        // Everything above the two surfaces, in one layer that night mode turns red (see NightModeUi).
        // Dialogs and menus are windows of their own and read LocalNightMode.
        CompositionLocalProvider(LocalNightMode provides state.nightMode) {
            Box(Modifier.fillMaxSize().nightMode(state.nightMode)) {
                SkyOverlay(
                    renderInputs = viewModel.renderInputs,
                    labels = state.constellationLabels,
                    bodies = state.bodies,
                    selectedStar = state.selectedStar,
                    selectedBody = selectedBody,
                    moons = state.moons,
                    selectedMoon = selectedMoon,
                    deepSkyLabels = state.deepSkyLabels,
                    selectedDeepSky = state.selectedDeepSky,
                    showDeepSky = state.showDeepSky,
                    selectedConstellation = state.selectedConstellation,
                    showConstellations = state.showConstellations,
                    navigation = state.navigation,
                    navigationInsets = navigationInsets,
                    showHorizon = state.showHorizon,
                    onTap = { x, y, width, height, labelSizes -> viewModel.onSkyTapped(x, y, width, height, density, labelSizes) },
                    onPinch = viewModel::onPinch,
                    modifier = Modifier.fillMaxSize(),
                )
                // A popup, so it sits above everything; anchored in this box, which the overlay fills, at the tap.
                state.tapChoices?.let { choices ->
                    TapChoicesMenu(choices = choices, onChoose = viewModel::chooseTapped, onDismiss = viewModel::dismissTapChoices)
                }
                state.selectedStar?.takeIf { !panelOpen }?.let { selected ->
                    StarInfoCard(
                        selected = selected,
                        fact = state.objectFacts.forStar(selected.description.hr),
                        observer = state.observer,
                        julianDate = state.julianDate,
                        riseSet = state.riseSet,
                        expanded = detailsExpanded,
                        onToggleExpanded = { detailsExpanded = !detailsExpanded },
                        onDismiss = viewModel::clearSelection,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(ScreenMargin)
                            .padding(top = BelowTopControls)
                            .onGloballyPositioned { infoCardBottomPx = it.boundsInRoot().bottom },
                    )
                }
                state.selectedDeepSky?.takeIf { !panelOpen }?.let { selected ->
                    DeepSkyInfoCard(
                        selected = selected,
                        image = state.objectImages.forDeepSky(selected.obj.id),
                        fact = state.objectFacts.forDeepSky(selected.obj.id),
                        observer = state.observer,
                        julianDate = state.julianDate,
                        riseSet = state.riseSet,
                        expanded = detailsExpanded,
                        onToggleExpanded = { detailsExpanded = !detailsExpanded },
                        onDismiss = viewModel::clearSelection,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(ScreenMargin)
                            .padding(top = BelowTopControls)
                            .onGloballyPositioned { infoCardBottomPx = it.boundsInRoot().bottom },
                    )
                }
                state.selectedConstellation?.takeIf { !panelOpen }?.let { selected ->
                    ConstellationInfoCard(
                        description = selected,
                        observer = state.observer,
                        julianDate = state.julianDate,
                        expanded = detailsExpanded,
                        onToggleExpanded = { detailsExpanded = !detailsExpanded },
                        onDismiss = viewModel::clearSelection,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(ScreenMargin)
                            .padding(top = BelowTopControls)
                            .onGloballyPositioned { infoCardBottomPx = it.boundsInRoot().bottom },
                    )
                }
                selectedBody?.takeIf { !panelOpen }?.let { body ->
                    BodyInfoCard(
                        position = body,
                        image = state.objectImages.forBody(body.body),
                        moons = state.objectImages.moonsOf(body.body),
                        fact = state.objectFacts.forBody(body.body),
                        observer = state.observer,
                        julianDate = state.julianDate,
                        riseSet = state.riseSet,
                        expanded = detailsExpanded,
                        onToggleExpanded = { detailsExpanded = !detailsExpanded },
                        onDismiss = viewModel::clearSelection,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(ScreenMargin)
                            .padding(top = BelowTopControls)
                            .onGloballyPositioned { infoCardBottomPx = it.boundsInRoot().bottom },
                    )
                }
                selectedMoon?.takeIf { !panelOpen }?.let { moon ->
                    PlanetMoonInfoCard(
                        position = moon,
                        planet = state.bodies.firstOrNull { it.body == moon.moon.planet },
                        image = state.objectImages.forMoon(moon.moon),
                        observer = state.observer,
                        julianDate = state.julianDate,
                        riseSet = state.riseSet,
                        expanded = detailsExpanded,
                        onToggleExpanded = { detailsExpanded = !detailsExpanded },
                        onDismiss = viewModel::clearSelection,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(ScreenMargin)
                            .padding(top = BelowTopControls)
                            .onGloballyPositioned { infoCardBottomPx = it.boundsInRoot().bottom },
                    )
                }
                if (searchOpen) {
                    SearchPanel(
                        search = viewModel::search,
                        catalogueReady = state.starCount != null,
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSelect = { result ->
                            viewModel.navigateTo(result)
                            searchOpen = false
                            searchQuery = ""
                        },
                        onClose = { searchOpen = false },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(ScreenMargin)
                            .padding(top = BelowTopControls),
                    )
                }
                if (tonightOpen) {
                    TonightPanel(
                        plan = state.tonight,
                        locationKnown = locationKnown,
                        onSelect = { target ->
                            viewModel.navigateToTonight(target)
                            tonightOpen = false
                        },
                        onClose = { tonightOpen = false },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(ScreenMargin)
                            .padding(top = BelowTopControls),
                    )
                }
                // Bottom: the guidance banner while searching, then status messages, with the "faintest stars" slider under them.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(ScreenMargin)
                        .onGloballyPositioned { bottomPanelTopPx = it.boundsInRoot().top },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (SkyZoom.isZoomed(state.zoom)) {
                        ZoomChip(zoom = state.zoom, onReset = viewModel::resetZoom)
                    }
                    state.navigation?.let { navigation ->
                        NavigationBanner(
                            navigation = navigation,
                            bodies = state.bodies,
                            moons = state.moons,
                            observer = state.observer,
                            julianDate = state.julianDate,
                            limitingMagnitude = state.limitingMagnitude,
                            showDeepSky = state.showDeepSky,
                            onStop = viewModel::stopNavigation,
                            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                        )
                    }
                    StatusOverlay(
                        state = state,
                        hasLocationPermission = hasLocationPermission,
                        onRequestPermission = { permissionLauncher.launch(LOCATION_PERMISSIONS) },
                        onOpenSettings = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                )
                            } catch (e: ActivityNotFoundException) {
                                // No settings app to open; the message stays up and says where to go.
                            }
                        },
                    )
                    if (starLimitOpen) {
                        StarLimitSlider(
                            limitingMagnitude = state.limitingMagnitude,
                            onChange = viewModel::setLimitingMagnitude,
                            onChangeFinished = viewModel::saveLimitingMagnitude,
                            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                        )
                        DisplaySwitches(
                            showHorizon = state.showHorizon,
                            showGrid = state.showGrid,
                            showDeepSky = state.showDeepSky,
                            showMilkyWay = state.showMilkyWay,
                            showConstellations = state.showConstellations,
                            showConstellationArt = state.showConstellationArt,
                            nightMode = state.nightMode,
                            onShowHorizonChange = viewModel::setShowHorizon,
                            onShowGridChange = viewModel::setShowGrid,
                            onShowDeepSkyChange = viewModel::setShowDeepSky,
                            onShowMilkyWayChange = viewModel::setShowMilkyWay,
                            onShowConstellationsChange = viewModel::setShowConstellations,
                            onShowConstellationArtChange = viewModel::setShowConstellationArt,
                            onNightModeChange = viewModel::setNightMode,
                            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                        )
                    }
                }
                TopBar(
                    tonightOpen = tonightOpen,
                    onTonightClick = {
                        tonightOpen = !tonightOpen
                        if (tonightOpen) {
                            searchOpen = false
                            starLimitOpen = false
                        }
                    },
                    searchOpen = searchOpen,
                    onSearchClick = {
                        searchOpen = !searchOpen
                        if (searchOpen) {
                            tonightOpen = false
                            starLimitOpen = false
                        }
                    },
                    displayOpen = starLimitOpen,
                    onDisplayClick = {
                        starLimitOpen = !starLimitOpen
                        if (starLimitOpen) {
                            searchOpen = false
                            tonightOpen = false
                        }
                    },
                    cameraOn = state.viewMode == ViewMode.CAMERA,
                    onCameraChange = { wantCamera ->
                        viewModel.clearCameraProblem()
                        when {
                            !wantCamera -> viewModel.setViewMode(ViewMode.ENHANCED)
                            hasCameraPermission -> viewModel.setViewMode(ViewMode.CAMERA)
                            else -> {
                                rationaleBeforeRequest = context.findActivity()?.shouldShowRequestPermissionRationale(CAMERA_PERMISSION) ?: false
                                cameraPermissionLauncher.launch(CAMERA_PERMISSION)
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .safeDrawingPadding()
                        .padding(ScreenMargin),
                )
            }
        }
    }
}

@Composable
private fun SkyGlView(viewModel: SkyViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val glView = remember(context) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            // Translucent, so the camera preview below shows through wherever nothing is drawn
            // (the renderer clears to opaque black in the enhanced view). As a media overlay the
            // surface sits above the preview's surface but still below the Compose layer.
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderMediaOverlay(true)
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
    onOpenSettings: () -> Unit,
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

    val cameraMessage = if (state.cameraWaiting) stringResource(R.string.status_camera_waiting) else when (state.cameraProblem) {
        CameraProblem.PERMISSION_DENIED -> stringResource(R.string.status_camera_permission_denied)
        CameraProblem.PERMISSION_BLOCKED -> stringResource(R.string.status_camera_permission_blocked)
        CameraProblem.UNAVAILABLE -> stringResource(R.string.status_camera_unavailable)
        CameraProblem.UNSUPPORTED -> stringResource(R.string.status_camera_unsupported)
        null -> null
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (cameraMessage != null) {
            Text(
                text = cameraMessage,
                color = StatusTextColor,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        if (state.cameraProblem == CameraProblem.PERMISSION_BLOCKED) {
            OutlinedButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.action_open_settings), color = StatusTextColor)
            }
        }
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

/**
 * Shown while the view is zoomed (pinch to zoom; see `SkyZoom`): how far, and a tap to go back to 1×.
 * At least 48 dp tall, like the other controls.
 */
@Composable
private fun ZoomChip(zoom: Double, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val value = if (zoom < 10.0) {
        stringResource(R.string.zoom_value_decimal, zoom)
    } else {
        stringResource(R.string.zoom_value_whole, zoom.roundToInt())
    }
    val resetDescription = stringResource(R.string.zoom_reset_description)
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(CardBackground, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClickLabel = resetDescription, onClick = onReset)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = value, color = StatusTextColor, style = MaterialTheme.typography.labelLarge)
        Text(text = stringResource(R.string.zoom_reset), color = ActiveTextColor, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The display switches: night mode on a row of its own, then two to a row the horizon line with its
 * compass letters, the altitude/azimuth grid, the deep-sky objects and the Milky Way band, then the
 * constellation lines and names and the constellation illustrations on rows of their own (the labels
 * are too long for half the card's width at 360 dp), with the illustrations' credit, which their
 * licence asks for.
 */
@Composable
private fun DisplaySwitches(
    showHorizon: Boolean,
    showGrid: Boolean,
    showDeepSky: Boolean,
    showMilkyWay: Boolean,
    showConstellations: Boolean,
    showConstellationArt: Boolean,
    nightMode: Boolean,
    onShowHorizonChange: (Boolean) -> Unit,
    onShowGridChange: (Boolean) -> Unit,
    onShowDeepSkyChange: (Boolean) -> Unit,
    onShowMilkyWayChange: (Boolean) -> Unit,
    onShowConstellationsChange: (Boolean) -> Unit,
    onShowConstellationArtChange: (Boolean) -> Unit,
    onNightModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(CardBackground, RoundedCornerShape(24.dp)).padding(horizontal = 8.dp)) {
        GuideSwitch(stringResource(R.string.guide_night_mode), nightMode, onNightModeChange, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GuideSwitch(stringResource(R.string.guide_horizon), showHorizon, onShowHorizonChange, Modifier.weight(1f))
            GuideSwitch(stringResource(R.string.guide_grid), showGrid, onShowGridChange, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GuideSwitch(stringResource(R.string.guide_deep_sky), showDeepSky, onShowDeepSkyChange, Modifier.weight(1f))
            GuideSwitch(stringResource(R.string.guide_milky_way), showMilkyWay, onShowMilkyWayChange, Modifier.weight(1f))
        }
        GuideSwitch(stringResource(R.string.guide_constellations), showConstellations, onShowConstellationsChange, Modifier.fillMaxWidth())
        GuideSwitch(stringResource(R.string.guide_constellation_art), showConstellationArt, onShowConstellationArtChange, Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.constellation_art_credit),
            color = StatusTextColor.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
        )
    }
}

/** A labelled switch, at least 48 dp tall (the Switch alone is ~32 dp) with the whole row as its touch target. */
@Composable
private fun GuideSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = StatusTextColor, style = MaterialTheme.typography.labelLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Dims the stars: the slider sets the faintest magnitude drawn, from the very brightest stars only
 * (left) to every star in the catalogue (right, "all"). The Bortle class in the label above the
 * slider is a rough guide for matching it to the sky at hand; see [Bortle]. Values move in steps
 * of 0.1 (the view model snaps them), so the label and the class always describe what is applied.
 */
@Composable
private fun StarLimitSlider(
    limitingMagnitude: Double,
    onChange: (Double) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (StarVisibility.isUnlimited(limitingMagnitude)) {
        stringResource(R.string.star_limit_all)
    } else {
        stringResource(R.string.star_limit_value, limitingMagnitude, Bortle.classForLimitingMagnitude(limitingMagnitude))
    }
    val description = stringResource(R.string.star_limit_description)
    val state = stringResource(R.string.star_limit_state, limitingMagnitude)
    Column(
        modifier = modifier
            .background(CardBackground, RoundedCornerShape(24.dp))
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
    ) {
        Text(text = label, color = StatusTextColor, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = limitingMagnitude.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            onValueChangeFinished = onChangeFinished,
            valueRange = StarVisibility.MIN_LIMIT.toFloat()..StarVisibility.MAX_LIMIT.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = StatusTextColor,
                activeTrackColor = StatusTextColor,
                inactiveTrackColor = StatusTextColor.copy(alpha = 0.3f),
            ),
            // Material's slider is already 48 dp tall, the minimum touch target.
            modifier = Modifier.semantics {
                contentDescription = description
                stateDescription = state
            },
        )
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
