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
 * Publishes the latest device orientation. It is read by the GL thread each frame, so it lives in
 * an [AtomicReference] instead of a Flow; smoothing is done by the renderer with [OrientationFilter].
 *
 * The sky needs an absolute heading, so TYPE_ROTATION_VECTOR (which includes the magnetometer) is
 * required. On its own its heading jumps whenever the magnetometer recalibrates or is disturbed,
 * which happens exactly when the phone is swung around the sky, so where the phone also has
 * TYPE_GAME_ROTATION_VECTOR (a gyroscope) the two are combined by [HeadingFusion]: the gyroscope
 * moves the sky and the magnetometer only steers north, slowly.
 *
 * Callbacks arrive on the main thread (no handler is given), which is the only thread that
 * touches [fusion].
 */
class OrientationSensor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val fusion = HeadingFusion()

    private val latest = AtomicReference<Quaternion?>(null)

    private val _accuracy = MutableStateFlow(SensorManager.SENSOR_STATUS_UNRELIABLE)

    /** One of the `SensorManager.SENSOR_STATUS_*` values; low means the compass needs calibrating. */
    val accuracy: StateFlow<Int> = _accuracy.asStateFlow()

    val isAvailable: Boolean get() = sensor != null

    /** Device → magnetic-ENU rotation, or null before the first reading. Thread-safe. */
    fun latestOrientation(): Quaternion? = latest.get()

    fun start() {
        // The game frame's heading is arbitrary and may restart with each registration.
        fusion.reset()
        sensor?.let { rotationVector ->
            sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME)
            gameSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // event.values is reused by the framework, so copy it out immediately.
        val q = Quaternion.fromRotationVector(event.values)
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                _accuracy.value = event.accuracy
                if (gameSensor == null) latest.set(q) else fusion.onRotationVector(q, event.timestamp)?.let(latest::set)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> fusion.onGameRotation(q, event.timestamp)?.let(latest::set)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Only the magnetometer's calibration is worth telling the user about.
        if (sensor.type == Sensor.TYPE_ROTATION_VECTOR) _accuracy.value = accuracy
    }
}
