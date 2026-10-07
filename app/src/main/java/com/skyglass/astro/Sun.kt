package com.skyglass.astro

import kotlin.math.cos
import kotlin.math.sin

/**
 * The Sun's geocentric ecliptic position in the mean equinox of date.
 *
 * @property trueLongitudeDeg geometric longitude, [0, 360). Also where the Sun is against the
 *   stars in the astrometric sense used for the planets and stars here (light time included,
 *   since the Sun is at rest in the heliocentric frame; no aberration, no nutation). The
 *   apparent place differs by 0.0057° of annual aberration and up to 0.005° of nutation.
 * @property distanceAu Earth–Sun distance
 */
data class SunPosition(
    val trueLongitudeDeg: Double,
    val distanceAu: Double,
)

/**
 * Low-accuracy solar coordinates, Meeus, *Astronomical Algorithms*, chapter 25 (about 0.01°).
 * The Sun's ecliptic latitude never exceeds 1.2″, so it is taken as zero.
 */
object SunEphemeris {

    /** @param jde Julian Ephemeris Day (dynamical time), see [AstroTime.julianEphemerisDay] */
    fun position(jde: Double): SunPosition {
        val t = AstroTime.julianCenturiesSinceJ2000(jde)
        val meanLongitudeDeg = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val meanAnomalyDeg = 357.52911 + 35999.05029 * t - 0.0001537 * t * t
        val eccentricity = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t

        val m = Math.toRadians(meanAnomalyDeg)
        val centreDeg = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) +
            (0.019993 - 0.000101 * t) * sin(2 * m) +
            0.000289 * sin(3 * m)

        val trueAnomaly = m + Math.toRadians(centreDeg)
        val distanceAu = 1.000001018 * (1 - eccentricity * eccentricity) / (1 + eccentricity * cos(trueAnomaly))

        return SunPosition(
            trueLongitudeDeg = AstroTime.normalizeDegrees(meanLongitudeDeg + centreDeg),
            distanceAu = distanceAu,
        )
    }
}
