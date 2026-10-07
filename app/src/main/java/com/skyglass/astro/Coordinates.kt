package com.skyglass.astro

import com.skyglass.math.Matrix3
import com.skyglass.math.Vector3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Right ascension / declination, radians. */
data class Equatorial(val raRad: Double, val decRad: Double)

/** Altitude above the horizon / azimuth measured from true north through east, radians. */
data class Horizontal(val altRad: Double, val azRad: Double)

/**
 * Frames used by the sky view:
 * - Equatorial: +X toward the vernal equinox, +Z toward the north celestial pole.
 * - ENU (horizontal): +X east, +Y north, +Z up. Matches the Android rotation vector's world
 *   frame, except that Android's "north" is magnetic; see [magneticToTrueEnu].
 */
object Coordinates {

    private const val TWO_PI = 2 * Math.PI

    fun raDecToUnit(position: Equatorial): Vector3 {
        val cosDec = cos(position.decRad)
        return Vector3(cosDec * cos(position.raRad), cosDec * sin(position.raRad), sin(position.decRad))
    }

    fun unitToRaDec(v: Vector3): Equatorial =
        Equatorial(normalizeRadians(atan2(v.y, v.x)), asin(v.z.coerceIn(-1.0, 1.0)))

    fun horizontalToEnu(h: Horizontal): Vector3 {
        val cosAlt = cos(h.altRad)
        return Vector3(cosAlt * sin(h.azRad), cosAlt * cos(h.azRad), sin(h.altRad))
    }

    fun enuToHorizontal(v: Vector3): Horizontal =
        Horizontal(asin(v.z.coerceIn(-1.0, 1.0)), normalizeRadians(atan2(v.x, v.y)))

    /**
     * Rotation taking equatorial-of-date unit vectors to ENU for an observer at latitude
     * [latitudeRad] when the local sidereal time is [lstRad].
     */
    fun equatorialToEnuMatrix(lstRad: Double, latitudeRad: Double): Matrix3 {
        val sinLat = sin(latitudeRad)
        val cosLat = cos(latitudeRad)
        // After rotating by −LST: +X points at the meridian on the celestial equator,
        // +Y at hour angle −6h (due east), +Z at the celestial pole.
        val localToEnu = Matrix3.fromRows(
            Vector3(0.0, 1.0, 0.0),
            Vector3(-sinLat, 0.0, cosLat),
            Vector3(cosLat, 0.0, sinLat),
        )
        return localToEnu * Matrix3.rotationZ(-lstRad)
    }

    /**
     * Direct trigonometric conversion (Meeus eq. 13.5/13.6). Mathematically equivalent to
     * [equatorialToEnuMatrix] followed by [enuToHorizontal]; kept as an independent cross-check.
     */
    fun equatorialToHorizontal(position: Equatorial, lstRad: Double, latitudeRad: Double): Horizontal {
        val hourAngle = lstRad - position.raRad
        val azFromSouth = atan2(
            sin(hourAngle),
            cos(hourAngle) * sin(latitudeRad) - tan(position.decRad) * cos(latitudeRad),
        )
        val alt = asin(
            sin(latitudeRad) * sin(position.decRad) +
                cos(latitudeRad) * cos(position.decRad) * cos(hourAngle)
        )
        return Horizontal(alt, normalizeRadians(azFromSouth + Math.PI))
    }

    /**
     * Rotation from the magnetic ENU frame reported by the rotation vector sensor to true ENU.
     * [declinationRad] is positive when magnetic north lies east of true north, as returned by
     * `GeomagneticField.getDeclination()`.
     */
    fun magneticToTrueEnu(declinationRad: Double): Matrix3 = Matrix3.rotationZ(-declinationRad)

    fun normalizeRadians(rad: Double): Double {
        val r = rad % TWO_PI
        return if (r < 0) r + TWO_PI else r
    }
}
