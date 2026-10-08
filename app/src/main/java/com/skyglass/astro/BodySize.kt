package com.skyglass.astro

import kotlin.math.asin

/** Physical sizes of the Sun, Moon and planets, for how big they look. */
object BodySize {

    /** IAU 2015 nominal solar radius and the mean lunar radius; planets' are the equatorial (1 bar) radii. */
    fun radiusKm(body: SkyBody): Double = when (body) {
        SkyBody.SUN -> 695_700.0
        SkyBody.MOON -> 1_737.4
        SkyBody.MERCURY -> 2_439.7
        SkyBody.VENUS -> 6_051.8
        SkyBody.MARS -> 3_396.2
        SkyBody.JUPITER -> 71_492.0
        SkyBody.SATURN -> 60_268.0
        SkyBody.URANUS -> 25_559.0
        SkyBody.NEPTUNE -> 24_764.0
    }

    /** Apparent angular radius, in degrees, of [body] at [distanceAu] from the observer. */
    fun angularRadiusDeg(body: SkyBody, distanceAu: Double): Double =
        Math.toDegrees(asin((radiusKm(body) / (distanceAu * SolarSystem.KM_PER_AU)).coerceIn(0.0, 1.0)))

    /** Apparent disc diameter in arcseconds (a planet's rings, if any, are not included). */
    fun angularDiameterArcsec(body: SkyBody, distanceAu: Double): Double =
        2 * angularRadiusDeg(body, distanceAu) * 3600.0
}
