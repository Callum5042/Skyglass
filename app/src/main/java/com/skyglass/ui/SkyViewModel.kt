package com.skyglass.ui

import android.annotation.SuppressLint
import android.app.Application
import android.hardware.SensorManager
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skyglass.astro.AstroTime
import com.skyglass.astro.BodyPosition
import com.skyglass.astro.SkyBody
import com.skyglass.astro.SolarSystem
import com.skyglass.catalog.BodyVertices
import com.skyglass.catalog.ConstellationGeometry
import com.skyglass.catalog.ConstellationLabel
import com.skyglass.catalog.ConstellationSky
import com.skyglass.catalog.Star
import com.skyglass.catalog.StarCatalog
import com.skyglass.catalog.StarDescription
import com.skyglass.catalog.StarNamer
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The star the user tapped, with where it is in the sky so the overlay can ring it. */
data class SelectedStar(val description: StarDescription, val direction: Vector3)

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
)

private class LoadedSky(
    val stars: List<Star>,
    val vertices: FloatArray,
    val constellations: ConstellationSky,
    val namer: StarNamer,
)

class SkyViewModel(application: Application) : AndroidViewModel(application) {

    val orientationSensor = OrientationSensor(application)
    val renderInputs = SkyRenderInputs()

    // Stars in the same order as the vertex buffer, and what the tap overlay needs to describe them.
    private var stars: List<Star> = emptyList()
    private var starNamer: StarNamer? = null

    private val locationSource = LocationSource(application)
    private var locationJob: Job? = null
    private var bodyJob: Job? = null

    private val _uiState = MutableStateFlow(SkyUiState(hasRotationSensor = orientationSensor.isAvailable))
    val uiState: StateFlow<SkyUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val sky = withContext(Dispatchers.IO) {
                val assets = application.assets
                val loadedStars = StarCatalog.loadNakedEyeStars(assets)
                val constellations = StarCatalog.loadConstellations(assets)
                val julianDate = AstroTime.julianDate(System.currentTimeMillis())
                LoadedSky(
                    stars = loadedStars,
                    vertices = StarVertices.build(loadedStars, julianDate),
                    constellations = ConstellationGeometry.build(constellations, loadedStars, julianDate),
                    namer = StarNamer(StarCatalog.loadProperNames(assets), constellations),
                )
            }
            stars = sky.stars
            starNamer = sky.namer
            renderInputs.constellationLines = sky.constellations.lineVertices
            renderInputs.starVertices = sky.vertices
            _uiState.update {
                it.copy(
                    starCount = sky.stars.size,
                    constellationLabels = sky.constellations.labels,
                )
            }
        }
        viewModelScope.launch {
            orientationSensor.accuracy.collect { accuracy -> _uiState.update { it.copy(compassAccuracy = accuracy) } }
        }
    }

    /**
     * Selects the Sun, Moon, planet or star nearest the tap (a body wins a tie, and a star only
     * if it is closer), or clears the selection when none is within reach.
     * Coordinates are in pixels of the [widthPx] × [heightPx] sky view.
     */
    fun onSkyTapped(x: Float, y: Float, widthPx: Int, heightPx: Int, density: Float) {
        val mvp = renderInputs.lastMvp
        val vertices = renderInputs.starVertices
        val namer = starNamer
        var selectedStar: SelectedStar? = null
        var selectedBody: SkyBody? = null
        if (mvp != null) {
            val bodies = _uiState.value.bodies
            val bodyHit = SkyHitTest.nearestBody(
                mvp, bodies, widthPx, heightPx, x.toDouble(), y.toDouble(), density, renderInputs.maxPointSizePx,
            )
            val starHit = if (vertices != null && namer != null) {
                SkyHitTest.nearestStar(mvp, vertices, widthPx, heightPx, x.toDouble(), y.toDouble(), density)
            } else {
                null
            }
            if (bodyHit != null && (starHit == null || bodyHit.distancePx <= starHit.distancePx)) {
                selectedBody = bodies[bodyHit.index].body
            } else if (starHit != null && vertices != null && namer != null) {
                val base = starHit.index * StarVertices.FLOATS_PER_STAR + StarVertices.POSITION_OFFSET
                selectedStar = SelectedStar(
                    description = namer.describe(stars[starHit.index]),
                    direction = Vector3(
                        vertices[base].toDouble(), vertices[base + 1].toDouble(), vertices[base + 2].toDouble(),
                    ),
                )
            }
        }
        _uiState.update { it.copy(selectedStar = selectedStar, selectedBody = selectedBody) }
    }

    fun hasLocationPermission(): Boolean = locationSource.hasPermission()

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
        val bodies = SolarSystem.positions(
            AstroTime.julianDate(System.currentTimeMillis()), observer.latitudeDeg, observer.longitudeDeg,
        )
        renderInputs.bodyVertices = BodyVertices.build(bodies)
        _uiState.update { it.copy(bodies = bodies) }
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
    }
}
