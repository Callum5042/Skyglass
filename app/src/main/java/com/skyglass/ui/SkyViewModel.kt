package com.skyglass.ui

import android.annotation.SuppressLint
import android.app.Application
import android.hardware.SensorManager
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skyglass.astro.AstroTime
import com.skyglass.catalog.StarCatalog
import com.skyglass.catalog.StarVertices
import com.skyglass.render.Observer
import com.skyglass.render.SkyRenderInputs
import com.skyglass.sensors.LocationSource
import com.skyglass.sensors.MagneticDeclination
import com.skyglass.sensors.OrientationSensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SkyUiState(
    /** Null while the catalogue is loading. */
    val starCount: Int? = null,
    val observer: Observer? = null,
    val hasRotationSensor: Boolean = true,
    val compassAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val locationEnabled: Boolean = true,
)

class SkyViewModel(application: Application) : AndroidViewModel(application) {

    val orientationSensor = OrientationSensor(application)
    val renderInputs = SkyRenderInputs()

    private val locationSource = LocationSource(application)
    private var locationJob: Job? = null

    private val _uiState = MutableStateFlow(SkyUiState(hasRotationSensor = orientationSensor.isAvailable))
    val uiState: StateFlow<SkyUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val vertices = withContext(Dispatchers.IO) {
                val stars = StarCatalog.loadNakedEyeStars(application.assets)
                StarVertices.build(stars, AstroTime.julianDate(System.currentTimeMillis()))
            }
            renderInputs.starVertices = vertices
            _uiState.update { it.copy(starCount = vertices.size / StarVertices.FLOATS_PER_STAR) }
        }
        viewModelScope.launch {
            orientationSensor.accuracy.collect { accuracy -> _uiState.update { it.copy(compassAccuracy = accuracy) } }
        }
    }

    fun hasLocationPermission(): Boolean = locationSource.hasPermission()

    fun onResume() {
        orientationSensor.start()
        startLocationUpdates()
    }

    fun onPause() {
        orientationSensor.stop()
        locationJob?.cancel()
        locationJob = null
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
    }

    override fun onCleared() {
        orientationSensor.stop()
    }
}
