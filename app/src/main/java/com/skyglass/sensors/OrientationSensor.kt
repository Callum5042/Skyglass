package com.skyglass.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.skyglass.math.Quaternion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes the latest raw device orientation from the rotation vector sensor. It is read by the
 * GL thread each frame, so it lives in an [AtomicReference] instead of a Flow; smoothing is done
 * by the renderer with [OrientationFilter].
 *
 * Uses TYPE_ROTATION_VECTOR (which includes the magnetometer) rather than
 * TYPE_GAME_ROTATION_VECTOR, because the sky needs an absolute heading.
 */
class OrientationSensor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val latest = AtomicReference<Quaternion?>(null)

    private val _accuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_UNRELIABLE)

    /** One of the `SensorManager.SENSOR_STATUS_*` values; low means the compass needs calibrating. */
    val accuracy: StateFlow<Int> = _accuracy.asStateFlow()

    val isAvailable: Boolean get() = sensor != null

    /** Device → magnetic-ENU rotation, or null before the first reading. Thread-safe. */
    fun latestOrientation(): Quaternion? = latest.get()

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // event.values is reused by the framework, so copy it out immediately.
        latest.set(Quaternion.fromRotationVector(event.values))
        _accuracy.value = event.accuracy
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        _accuracy.value = accuracy
    }
}
