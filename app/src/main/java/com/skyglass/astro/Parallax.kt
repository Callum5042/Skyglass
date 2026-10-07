package com.skyglass.astro

import com.skyglass.math.Vector3
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Diurnal parallax: the shift in a nearby body's direction between the Earth's centre and the
 * observer's place on its surface. It reaches 0.95° for the Moon and is below 0.003° for any
 * planet, so only the Moon needs it.
 *
 * Works with vectors in the equatorial frame of date rather than Meeus's chapter 40 angle
 * formulae (which the tests use as an independent cross-check).
 */
object Parallax {

    const val EARTH_EQUATORIAL_RADIUS_KM = 6378.14

    /** Polar radius over equatorial radius of the reference ellipsoid (Meeus, chapter 11). */
    private const val POLAR_TO_EQUATORIAL = 0.99664719
    private const val EARTH_EQUATORIAL_RADIUS_M = 6_378_140.0

    /**
     * The observer's geocentric position in the equatorial frame of date, in Earth equatorial radii.
     * The observer's right ascension is the local sidereal time [lstRad]; [heightM] is above sea level.
     */
    fun observerPosition(latitudeRad: Double, lstRad: Double, heightM: Double = 0.0): Vector3 {
        val u = atan(POLAR_TO_EQUATORIAL * tan(latitudeRad))
        val rhoSinLat = POLAR_TO_EQUATORIAL * sin(u) + heightM / EARTH_EQUATORIAL_RADIUS_M * sin(latitudeRad)
        val rhoCosLat = cos(u) + heightM / EARTH_EQUATORIAL_RADIUS_M * cos(latitudeRad)
        return Vector3(rhoCosLat * cos(lstRad), rhoCosLat * sin(lstRad), rhoSinLat)
    }

    /**
     * Direction to a body as seen from [observer] (see [observerPosition]) rather than from the
     * Earth's centre.
     *
     * @param geocentricDirection unit vector from the Earth's centre to the body
     * @param distanceKm distance from the Earth's centre
     */
    fun topocentricDirection(geocentricDirection: Vector3, distanceKm: Double, observer: Vector3): Vector3 =
        (geocentricDirection * (distanceKm / EARTH_EQUATORIAL_RADIUS_KM) - observer).normalized()
}
