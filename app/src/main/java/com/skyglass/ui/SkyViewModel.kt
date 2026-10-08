package com.skyglass.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skyglass.astro.AstroTime
import com.skyglass.astro.BodyPosition
import com.skyglass.astro.RiseSet
import com.skyglass.astro.RiseSetTimes
import com.skyglass.astro.SkyBody
import com.skyglass.astro.SolarSystem
import com.skyglass.catalog.BodyVertices
import com.skyglass.catalog.ConstellationGeometry
import com.skyglass.catalog.ConstellationLabel
import com.skyglass.catalog.ConstellationSky
import com.skyglass.catalog.DeepSkyLabels
import com.skyglass.catalog.DeepSkyObject
import com.skyglass.catalog.DeepSkyVertices
import com.skyglass.catalog.MilkyWayMesh
import com.skyglass.catalog.NavigationTarget
import com.skyglass.catalog.SearchResult
import com.skyglass.catalog.SearchTarget
import com.skyglass.catalog.SkySearchIndex
import com.skyglass.catalog.Star
import com.skyglass.catalog.StarCatalog
import com.skyglass.catalog.StarDescription
import com.skyglass.catalog.StarNamer
import com.skyglass.catalog.StarVisibility
import com.skyglass.catalog.StarVertices
import com.skyglass.math.Vector3
import com.skyglass.render.Observer
import com.skyglass.render.SkyHitTest
import com.skyglass.render.SkyRenderInputs
import com.skyglass.sensors.LocationSource
import com.skyglass.sensors.MagneticDeclination
import com.skyglass.sensors.OrientationSensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.tan

/** What is behind the stars: the plain black sky, or the live rear-camera image. */
enum class ViewMode { ENHANCED, CAMERA }

/** Why the camera view is not showing although the user asked for it; shown in the status overlay. */
enum class CameraProblem {
    PERMISSION_DENIED,

    /** Denied with "don't ask again" (or twice): the system dialog will not appear, only app settings can fix it. */
    PERMISSION_BLOCKED,

    /**
     * The camera will not open: disabled by policy, a fatal error, do-not-disturb mode, or a stream
     * configuration it cannot do. (A camera in use by another app is *not* this: CameraX keeps
     * retrying, so the view stays up with [SkyUiState.cameraWaiting] set.)
     */
    UNAVAILABLE,

    /** The preview would be drawn above the GL surface and hide the sky; see `CameraPreviewLayer`. */
    UNSUPPORTED,
}

/** The star the user tapped, with where it is in the sky so the overlay can ring it. */
data class SelectedStar(val description: StarDescription, val direction: Vector3)

/** The deep-sky object the user tapped, with where it is in the sky so the overlay can ring it. */
data class SelectedDeepSky(val obj: DeepSkyObject, val direction: Vector3)

/** A deep-sky object that gets a name beside it (see [DeepSkyLabels]), with where to put it. */
data class DeepSkyLabel(val text: String, val obj: DeepSkyObject, val direction: Vector3)

data class SkyUiState(
    /** Null while the catalogue is loading. */
    val starCount: Int? = null,
    val observer: Observer? = null,
    val hasRotationSensor: Boolean = true,
    val compassAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val locationEnabled: Boolean = true,
    val constellationLabels: List<ConstellationLabel> = emptyList(),
    val selectedStar: SelectedStar? = null,
    /** Sun, Moon and planets as of the last update; empty until the observer's location is known. */
    val bodies: List<BodyPosition> = emptyList(),
    /** The tapped body; its current position is looked up in [bodies] so the ring follows it. */
    val selectedBody: SkyBody? = null,
    val viewMode: ViewMode = ViewMode.ENHANCED,
    /** Set when the camera view could not be shown; cleared once it is. */
    val cameraProblem: CameraProblem? = null,
    /** Bumped each time a problem is reported, so a timeout started for one message cannot clear a newer one. */
    val cameraProblemSeq: Int = 0,
    /** In the camera view, CameraX is waiting to open a camera that is busy (e.g. in use by another app). */
    val cameraWaiting: Boolean = false,
    /** The "faintest stars" slider: stars fainter than this fade out (see `StarVisibility`). */
    val limitingMagnitude: Double = StarVisibility.DEFAULT_LIMIT,
    /**
     * Julian date (UTC) of the last body update, so at most ten seconds old: the
     * moment the details card works out the tapped object's altitude and azimuth for. Null until the
     * observer's location is known.
     */
    val julianDate: Double? = null,
    /** Rise and set of the tapped star or body; null until calculated (in the background) and when nothing is tapped. */
    val riseSet: RiseSetTimes? = null,
    /** What the user searched for and is being guided to, until they stop; see [SkyViewModel.navigateTo]. */
    val navigation: NavigationTarget? = null,
    /** The horizon line and the compass letters on it (see `HorizonGuides`); on by default. */
    val showHorizon: Boolean = true,
    /** The altitude/azimuth reference grid; on by default. */
    val showGrid: Boolean = true,
    /** The deep-sky objects, their names and tapping them; on by default. */
    val showDeepSky: Boolean = true,
    /** The Milky Way band; on by default. */
    val showMilkyWay: Boolean = true,
    /** The tapped (or searched-for) deep-sky object. */
    val selectedDeepSky: SelectedDeepSky? = null,
    /** The deep-sky objects that are named on screen; empty until the catalogue has loaded. */
    val deepSkyLabels: List<DeepSkyLabel> = emptyList(),
)

private class LoadedSky(
    val stars: List<Star>,
    val vertices: FloatArray,
    val constellations: ConstellationSky,
    val namer: StarNamer,
    val searchIndex: SkySearchIndex,
    val deepSky: List<DeepSkyObject>,
    val deepSkyDirections: List<Vector3>,
    val deepSkyVertices: FloatArray,
    val milkyWay: MilkyWayMesh,
)

class SkyViewModel(application: Application) : AndroidViewModel(application) {

    val orientationSensor = OrientationSensor(application)
    val renderInputs = SkyRenderInputs()

    // Stars in the same order as the vertex buffer, and what the tap overlay needs to describe them.
    private var stars: List<Star> = emptyList()
    private var starNamer: StarNamer? = null
    private var starIndexByHr: Map<Int, Int> = emptyMap()
    private var searchIndex: SkySearchIndex? = null
    private var deepSky: List<DeepSkyObject> = emptyList()
    private var deepSkyDirections: List<Vector3> = emptyList()
    private var deepSkyIndexById: Map<String, Int> = emptyMap()

    private val locationSource = LocationSource(application)
    private var locationJob: Job? = null
    private var bodyJob: Job? = null
    private var riseSetJob: Job? = null

    // Display settings the user chooses, kept apart from the camera state below. Safe to back up.
    private val displayPreferences = application.getSharedPreferences(DISPLAY_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val savedLimitingMagnitude = StarVisibility.snapLimit(
        displayPreferences.getFloat(KEY_LIMITING_MAGNITUDE, StarVisibility.DEFAULT_LIMIT.toFloat()).toDouble(),
    )

    private val savedShowHorizon = displayPreferences.getBoolean(KEY_SHOW_HORIZON, true)
    private val savedShowGrid = displayPreferences.getBoolean(KEY_SHOW_GRID, true)
    private val savedShowDeepSky = displayPreferences.getBoolean(KEY_SHOW_DEEP_SKY, true)
    private val savedShowMilkyWay = displayPreferences.getBoolean(KEY_SHOW_MILKY_WAY, true)

    private val _uiState = MutableStateFlow(
        SkyUiState(
            hasRotationSensor = orientationSensor.isAvailable,
            limitingMagnitude = savedLimitingMagnitude,
            showHorizon = savedShowHorizon,
            showGrid = savedShowGrid,
            showDeepSky = savedShowDeepSky,
            showMilkyWay = savedShowMilkyWay,
        ),
    )
    val uiState: StateFlow<SkyUiState> = _uiState.asStateFlow()

    init {
        renderInputs.limitingMagnitude = savedLimitingMagnitude
        renderInputs.showHorizon = savedShowHorizon
        renderInputs.showGrid = savedShowGrid
        renderInputs.showDeepSky = savedShowDeepSky
        renderInputs.showMilkyWay = savedShowMilkyWay
        viewModelScope.launch {
            val sky = withContext(Dispatchers.IO) {
                val assets = application.assets
                val loadedStars = StarCatalog.loadNakedEyeStars(assets)
                val constellations = StarCatalog.loadConstellations(assets)
                val julianDate = AstroTime.julianDate(System.currentTimeMillis())
                val namer = StarNamer(StarCatalog.loadProperNames(assets), constellations)
                val constellationSky = ConstellationGeometry.build(constellations, loadedStars, julianDate)
                val loadedDeepSky = StarCatalog.loadDeepSky(assets)
                val deepSkyDirections = DeepSkyVertices.directions(loadedDeepSky, julianDate)
                LoadedSky(
                    stars = loadedStars,
                    vertices = StarVertices.build(loadedStars, julianDate),
                    constellations = constellationSky,
                    namer = namer,
                    searchIndex = SkySearchIndex.build(
                        loadedStars,
                        namer,
                        constellationSky.labels.map { it.name },
                        SkyBody.entries.associateWith { application.getString(it.nameRes()) },
                        loadedDeepSky,
                    ),
                    deepSky = loadedDeepSky,
                    deepSkyDirections = deepSkyDirections,
                    deepSkyVertices = DeepSkyVertices.build(loadedDeepSky, deepSkyDirections),
                    milkyWay = MilkyWayMesh.build(julianDate),
                )
            }
            stars = sky.stars
            starNamer = sky.namer
            starIndexByHr = sky.stars.withIndex().associate { (i, star) -> star.hr to i }
            searchIndex = sky.searchIndex
            deepSky = sky.deepSky
            deepSkyDirections = sky.deepSkyDirections
            deepSkyIndexById = sky.deepSky.withIndex().associate { (i, obj) -> obj.id to i }
            renderInputs.constellationLines = sky.constellations.lineVertices
            renderInputs.starVertices = sky.vertices
            renderInputs.deepSkyVertices = sky.deepSkyVertices
            renderInputs.milkyWayMesh = sky.milkyWay
            val deepSkyLabels = sky.deepSky.indices.mapNotNull { i ->
                DeepSkyLabels.text(sky.deepSky[i])?.let { DeepSkyLabel(it, sky.deepSky[i], sky.deepSkyDirections[i]) }
            }
            _uiState.update {
                it.copy(
                    starCount = sky.stars.size,
                    constellationLabels = sky.constellations.labels,
                    deepSkyLabels = deepSkyLabels,
                )
            }
        }
        viewModelScope.launch {
            orientationSensor.accuracy.collect { accuracy -> _uiState.update { it.copy(compassAccuracy = accuracy) } }
        }
    }

    /**
     * Selects the Sun, Moon, planet, star or deep-sky object the tap most plausibly means, or clears
     * the selection when none is within reach (the rules are [SkyHitTest.choose]'s). Coordinates are in pixels of the [widthPx] × [heightPx] sky view.
     */
    fun onSkyTapped(x: Float, y: Float, widthPx: Int, heightPx: Int, density: Float) {
        val mvp = renderInputs.lastMvp
        val vertices = renderInputs.starVertices
        val namer = starNamer
        var selectedStar: SelectedStar? = null
        var selectedBody: SkyBody? = null
        var selectedDeepSky: SelectedDeepSky? = null
        if (mvp != null) {
            val bodies = _uiState.value.bodies
            val bodyHit = SkyHitTest.nearestBody(
                mvp, bodies, widthPx, heightPx, x.toDouble(), y.toDouble(), density, renderInputs.maxPointSizePx,
            )
            val starHit = if (vertices != null && namer != null) {
                SkyHitTest.nearestStar(
                    mvp, vertices, widthPx, heightPx, x.toDouble(), y.toDouble(), density, renderInputs.limitingMagnitude,
                )
            } else {
                null
            }
            val deepSkyHit = if (renderInputs.showDeepSky && deepSky.isNotEmpty()) {
                val focalPx = (heightPx / 2.0) / tan(Math.toRadians(renderInputs.fovYDeg / 2))
                SkyHitTest.nearestDeepSky(
                    mvp, deepSky, deepSkyDirections, widthPx, heightPx, x.toDouble(), y.toDouble(), density, focalPx,
                )
            } else {
                null
            }
            when (SkyHitTest.choose(bodyHit, starHit, deepSkyHit)) {
                SkyHitTest.Target.DEEP_SKY -> {
                    val hit = checkNotNull(deepSkyHit)
                    selectedDeepSky = SelectedDeepSky(deepSky[hit.index], deepSkyDirections[hit.index])
                }
                SkyHitTest.Target.BODY -> selectedBody = bodies[checkNotNull(bodyHit).index].body
                SkyHitTest.Target.STAR -> if (vertices != null && namer != null) {
                    val hit = checkNotNull(starHit)
                    selectedStar = SelectedStar(namer.describe(stars[hit.index]), starDirection(vertices, hit.index))
                }
                SkyHitTest.Target.NONE -> Unit
            }
        }
        _uiState.update {
            it.copy(selectedStar = selectedStar, selectedBody = selectedBody, selectedDeepSky = selectedDeepSky, riseSet = null)
        }
        refreshRiseSet()
    }

    private fun starDirection(vertices: FloatArray, index: Int): Vector3 {
        val base = index * StarVertices.FLOATS_PER_STAR + StarVertices.POSITION_OFFSET
        return Vector3(vertices[base].toDouble(), vertices[base + 1].toDouble(), vertices[base + 2].toDouble())
    }

    /** Results for the search box, best first; empty until the catalogue has loaded. See [SkySearchIndex]. */
    fun search(query: String): List<SearchResult> = searchIndex?.search(query).orEmpty()

    /**
     * Starts guiding the user to a search [result] and selects it, so the details card opens (a
     * constellation has none, so any selection is cleared instead). The guidance itself is drawn
     * by `SkyOverlay`: an arrow at the edge of the view while the target is off screen, a marker on it
     * once it is in view. It lasts until [stopNavigation], through taps and the camera switch.
     */
    fun navigateTo(result: SearchResult) {
        var selectedStar: SelectedStar? = null
        var selectedBody: SkyBody? = null
        var selectedDeepSky: SelectedDeepSky? = null
        var fixedDirection: Vector3? = null
        when (val target = result.target) {
            is SearchTarget.StarTarget -> {
                val vertices = renderInputs.starVertices
                val namer = starNamer
                val index = starIndexByHr[target.hr]
                if (vertices == null || namer == null || index == null) return
                val direction = starDirection(vertices, index)
                fixedDirection = direction
                selectedStar = SelectedStar(namer.describe(stars[index]), direction)
            }
            is SearchTarget.BodyTarget -> selectedBody = target.body
            is SearchTarget.DeepSkyTarget -> {
                val index = deepSkyIndexById[target.id] ?: return
                fixedDirection = deepSkyDirections[index]
                selectedDeepSky = SelectedDeepSky(deepSky[index], deepSkyDirections[index])
            }
            is SearchTarget.ConstellationTarget ->
                fixedDirection = _uiState.value.constellationLabels.firstOrNull { it.name == target.name }?.direction ?: return
        }
        _uiState.update {
            it.copy(
                selectedStar = selectedStar,
                selectedBody = selectedBody,
                selectedDeepSky = selectedDeepSky,
                riseSet = null,
                navigation = NavigationTarget(result, fixedDirection),
            )
        }
        refreshRiseSet()
    }

    /** Ends the guidance. The object stays selected, so its card is not taken away. */
    fun stopNavigation() {
        _uiState.update { it.copy(navigation = null) }
    }

    /** Dismisses the details card (the same as tapping empty sky). */
    fun clearSelection() {
        _uiState.update { it.copy(selectedStar = null, selectedBody = null, selectedDeepSky = null, riseSet = null) }
        refreshRiseSet()
    }

    /**
     * Works out when the tapped object next rises and sets, off the main thread: it scans the
     * altitude over the next day (see [RiseSet]), which for a body means a few hundred ephemeris
     * evaluations. Called on a new selection and on every body update, so the times stay "next".
     * A result for something no longer selected is dropped.
     */
    private fun refreshRiseSet() {
        riseSetJob?.cancel()
        riseSetJob = null
        val state = _uiState.value
        val star = state.selectedStar
        val body = state.selectedBody
        val deepSkyObject = state.selectedDeepSky
        val observer = renderInputs.observer
        if ((star == null && body == null && deepSkyObject == null) || observer == null) return
        val startJd = AstroTime.julianDate(System.currentTimeMillis())
        riseSetJob = viewModelScope.launch {
            val times = withContext(Dispatchers.Default) {
                // Stars and deep-sky objects are both fixed points of the equatorial frame of date.
                val fixedDirection = star?.direction ?: deepSkyObject?.direction
                if (fixedDirection != null) {
                    RiseSet.forStar(fixedDirection, startJd, observer.latitudeDeg, observer.longitudeDeg) { ensureActive() }
                } else {
                    RiseSet.forBody(checkNotNull(body), startJd, observer.latitudeDeg, observer.longitudeDeg) { ensureActive() }
                }
            }
            _uiState.update {
                if (it.selectedStar == star && it.selectedBody == body && it.selectedDeepSky == deepSkyObject) it.copy(riseSet = times) else it
            }
        }
    }

    /**
     * Sets how faint a star may be and still be drawn (the "faintest stars" slider). Applies at once
     * to the renderer and to tapping; call [saveLimitingMagnitude] when the drag ends to keep it.
     * A selected star that this dims out of sight is deselected.
     */
    fun setLimitingMagnitude(value: Double) {
        val limit = StarVisibility.snapLimit(value)
        renderInputs.limitingMagnitude = limit
        val selectedBefore = _uiState.value.selectedStar
        _uiState.update { state ->
            val selected = state.selectedStar
            val keep = StarVisibility.keepsSelection(selected?.description?.vmag, limit)
            if (keep) state.copy(limitingMagnitude = limit)
            else state.copy(limitingMagnitude = limit, selectedStar = null, riseSet = null)
        }
        if (selectedBefore != null && _uiState.value.selectedStar == null) refreshRiseSet() // cancels the calculation for the dropped star
    }

    /** Persists the current "faintest stars" setting across restarts. */
    fun saveLimitingMagnitude() {
        displayPreferences.edit().putFloat(KEY_LIMITING_MAGNITUDE, _uiState.value.limitingMagnitude.toFloat()).apply()
    }

    /** Shows or hides the horizon line and compass letters, and keeps the choice across restarts. */
    fun setShowHorizon(show: Boolean) {
        renderInputs.showHorizon = show
        _uiState.update { it.copy(showHorizon = show) }
        displayPreferences.edit().putBoolean(KEY_SHOW_HORIZON, show).apply()
    }

    /** Shows or hides the altitude/azimuth reference grid, and keeps the choice across restarts. */
    fun setShowGrid(show: Boolean) {
        renderInputs.showGrid = show
        _uiState.update { it.copy(showGrid = show) }
        displayPreferences.edit().putBoolean(KEY_SHOW_GRID, show).apply()
    }

    /** Shows or hides the deep-sky objects, and keeps the choice across restarts. Hiding them deselects the tapped one. */
    fun setShowDeepSky(show: Boolean) {
        renderInputs.showDeepSky = show
        val deselect = !show && _uiState.value.selectedDeepSky != null
        _uiState.update {
            if (show || it.selectedDeepSky == null) it.copy(showDeepSky = show)
            else it.copy(showDeepSky = false, selectedDeepSky = null, riseSet = null)
        }
        if (deselect) refreshRiseSet() // cancels the calculation for the dropped object
        displayPreferences.edit().putBoolean(KEY_SHOW_DEEP_SKY, show).apply()
    }

    /** Shows or hides the Milky Way band, and keeps the choice across restarts. */
    fun setShowMilkyWay(show: Boolean) {
        renderInputs.showMilkyWay = show
        _uiState.update { it.copy(showMilkyWay = show) }
        displayPreferences.edit().putBoolean(KEY_SHOW_MILKY_WAY, show).apply()
    }

    fun hasLocationPermission(): Boolean = locationSource.hasPermission()

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Switches between the enhanced sky and the camera view. Taps keep working in both: they are
     * resolved with the MVP of the last frame, which in camera mode uses the camera's field of
     * view (see [onCameraFieldOfView]). Leaving the camera view restores the default field of view.
     */
    fun setViewMode(mode: ViewMode) {
        renderInputs.cameraMode = mode == ViewMode.CAMERA
        if (mode == ViewMode.ENHANCED) renderInputs.fovYDeg = SkyRenderInputs.DEFAULT_FOV_Y_DEG
        _uiState.update {
            it.copy(
                viewMode = mode,
                cameraProblem = if (mode == ViewMode.CAMERA) null else it.cameraProblem,
                cameraWaiting = false,
            )
        }
    }

    /** The camera view could not be shown: return to the enhanced sky and say why. */
    fun onCameraProblem(problem: CameraProblem) {
        setViewMode(ViewMode.ENHANCED)
        _uiState.update { it.copy(cameraProblem = problem, cameraProblemSeq = it.cameraProblemSeq + 1) }
    }

    /**
     * Removes the camera message. With [expectedSeq] (from [SkyUiState.cameraProblemSeq]) only if
     * it is still that message, so a timeout cannot wipe a newer one.
     */
    fun clearCameraProblem(expectedSeq: Int? = null) {
        _uiState.update {
            if (it.cameraProblem == null || (expectedSeq != null && it.cameraProblemSeq != expectedSeq)) it
            else it.copy(cameraProblem = null)
        }
    }

    /** The camera is busy and CameraX is retrying ([waiting]), or it has opened. */
    fun onCameraWaiting(waiting: Boolean) {
        _uiState.update { if (it.cameraWaiting == waiting) it else it.copy(cameraWaiting = waiting) }
    }

    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * True once a camera permission request has been found to be blocked (the system will no
     * longer show the dialog), until the permission is granted. See [CameraPermissionPolicy].
     *
     * Kept in SharedPreferences, not memory: after a process restart `shouldShowRequestPermissionRationale`
     * is false both before and after an instantly refused request, so without this the user could
     * never be sent to the settings page again. Excluded from backup (it describes this device).
     */
    var cameraPermissionBlocked: Boolean
        get() = preferences.getBoolean(KEY_CAMERA_PERMISSION_BLOCKED, false)
        set(value) = preferences.edit().putBoolean(KEY_CAMERA_PERMISSION_BLOCKED, value).apply()

    /** The camera permission is held (granted just now, or found granted on resume): forget any complaint about it. */
    fun onCameraPermissionGranted() {
        cameraPermissionBlocked = false
        _uiState.update {
            if (it.cameraProblem == CameraProblem.PERMISSION_DENIED || it.cameraProblem == CameraProblem.PERMISSION_BLOCKED) {
                it.copy(cameraProblem = null)
            } else {
                it
            }
        }
    }

    /** The vertical field of view of the camera preview as laid out, for the projection to match. */
    fun onCameraFieldOfView(fovYDeg: Double) {
        if (_uiState.value.viewMode == ViewMode.CAMERA) renderInputs.fovYDeg = fovYDeg
    }

    fun onResume() {
        orientationSensor.start()
        startLocationUpdates()
        startBodyUpdates()
    }

    fun onPause() {
        orientationSensor.stop()
        locationJob?.cancel()
        locationJob = null
        bodyJob?.cancel()
        bodyJob = null
    }

    /**
     * Recomputes the Sun, Moon and planets now and then every [BODY_UPDATE_INTERVAL_MS]. They are
     * stored in the equatorial frame, so the Earth's rotation does not move them; what changes is
     * their own motion (the Moon drifts about 0.0015° in 10 s, the planets far less) and the Moon's
     * parallax as the observer is carried round, together well under a pixel per update.
     */
    private fun startBodyUpdates() {
        if (bodyJob != null) return
        bodyJob = viewModelScope.launch {
            while (true) {
                updateBodies()
                delay(BODY_UPDATE_INTERVAL_MS)
            }
        }
    }

    /** No-op until a location is known: the Moon's parallax needs the observer. */
    private fun updateBodies() {
        val observer = renderInputs.observer ?: return
        val julianDate = AstroTime.julianDate(System.currentTimeMillis())
        val bodies = SolarSystem.positions(julianDate, observer.latitudeDeg, observer.longitudeDeg)
        renderInputs.bodyVertices = BodyVertices.build(bodies)
        _uiState.update { it.copy(bodies = bodies, julianDate = julianDate) }
        refreshRiseSet()
    }

    @SuppressLint("MissingPermission") // guarded by hasPermission()
    fun startLocationUpdates() {
        _uiState.update { it.copy(locationEnabled = locationSource.isLocationEnabled()) }
        if (locationJob != null || !locationSource.hasPermission()) return
        locationJob = viewModelScope.launch {
            locationSource.updates().collect(::onLocation)
        }
    }

    private fun onLocation(location: Location) {
        val altitude = if (location.hasAltitude()) location.altitude else 0.0
        val declination = MagneticDeclination.degrees(
            location.latitude, location.longitude, altitude, System.currentTimeMillis(),
        )
        val observer = Observer(location.latitude, location.longitude, declination)
        renderInputs.observer = observer
        _uiState.update { it.copy(observer = observer, locationEnabled = true) }
        updateBodies() // the Moon's parallax depends on where we are
    }

    override fun onCleared() {
        orientationSensor.stop()
    }

    private companion object {
        const val BODY_UPDATE_INTERVAL_MS = 10_000L

        /** Also named in `res/xml/backup_rules.xml` and `data_extraction_rules.xml`. */
        const val PREFERENCES_NAME = "camera_permission_state"
        const val KEY_CAMERA_PERMISSION_BLOCKED = "blocked"

        /** User settings; unlike the camera file this one is deliberately left in backups. */
        const val DISPLAY_PREFERENCES_NAME = "display_settings"
        const val KEY_LIMITING_MAGNITUDE = "limiting_magnitude"
        const val KEY_SHOW_HORIZON = "show_horizon"
        const val KEY_SHOW_GRID = "show_grid"
        const val KEY_SHOW_DEEP_SKY = "show_deep_sky"
        const val KEY_SHOW_MILKY_WAY = "show_milky_way"
    }
}
