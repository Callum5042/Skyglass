package com.skyglass.sensors

import android.hardware.GeomagneticField

object MagneticDeclination {

    /**
     * Angle between magnetic and true north from the World Magnetic Model, in degrees,
     * positive when magnetic north is east of true north.
     */
    fun degrees(latitudeDeg: Double, longitudeDeg: Double, altitudeMeters: Double, timeMillis: Long): Double =
        GeomagneticField(latitudeDeg.toFloat(), longitudeDeg.toFloat(), altitudeMeters.toFloat(), timeMillis)
            .declination
            .toDouble()
}
