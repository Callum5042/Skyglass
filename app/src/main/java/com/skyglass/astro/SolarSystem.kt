package com.skyglass.astro

import com.skyglass.math.Matrix3
import com.skyglass.math.Vector3
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/** Bodies in the order they are drawn, later ones over earlier ones; the Moon is last so it can cover the Sun in an eclipse. */
enum class SkyBody(val planet: Planet? = null) {
    SUN,
    MERCURY(Planet.MERCURY),
    VENUS(Planet.VENUS),
    MARS(Planet.MARS),
    JUPITER(Planet.JUPITER),
    SATURN(Planet.SATURN),
    URANUS(Planet.URANUS),
    NEPTUNE(Planet.NEPTUNE),
    MOON,
}

/**
 * A Sun, Moon or planet as seen by one observer at one instant.
 *
 * @property direction unit vector in the equatorial frame of date (+X vernal equinox, +Z north
 *   celestial pole), the same frame as the star vertices. Topocentric for the Moon, otherwise
 *   geocentric (the planets' parallax is under 0.003°).
 * @property vmag apparent visual magnitude
 * @property distanceAu distance from the Earth's centre
 * @property elongationDeg angular separation from the Sun, 0–180
 * @property illuminatedFraction fraction of the visible disc that is lit, 0–1
 * @property sunDirection unit vector toward the Sun, same frame as [direction]; it orients the
 *   Moon's lit side
 * @property lunarPhaseDeg Moon only: ecliptic longitude east of the Sun, [0, 360): 0 new,
 *   90 first quarter, 180 full, 270 last quarter
 */
data class BodyPosition(
    val body: SkyBody,
    val direction: Vector3,
    val vmag: Double,
    val distanceAu: Double,
    val elongationDeg: Double,
    val illuminatedFraction: Double,
    val sunDirection: Vector3,
    val lunarPhaseDeg: Double? = null,
)

/** The named phases of the Moon, from [BodyPosition.lunarPhaseDeg]. */
enum class MoonPhase {
    NEW, WAXING_CRESCENT, FIRST_QUARTER, WAXING_GIBBOUS, FULL, WANING_GIBBOUS, LAST_QUARTER, WANING_CRESCENT;

    companion object {
        /** About a day of lunar motion either side of the exact phase. */
        private const val EXACT_PHASE_WINDOW_DEG = 12.0

        fun of(lunarPhaseDeg: Double): MoonPhase {
            val phase = AstroTime.normalizeDegrees(lunarPhaseDeg)
            return when {
                phase < EXACT_PHASE_WINDOW_DEG || phase >= 360 - EXACT_PHASE_WINDOW_DEG -> NEW
                phase < 90 - EXACT_PHASE_WINDOW_DEG -> WAXING_CRESCENT
                phase < 90 + EXACT_PHASE_WINDOW_DEG -> FIRST_QUARTER
                phase < 180 - EXACT_PHASE_WINDOW_DEG -> WAXING_GIBBOUS
                phase < 180 + EXACT_PHASE_WINDOW_DEG -> FULL
                phase < 270 - EXACT_PHASE_WINDOW_DEG -> WANING_GIBBOUS
                phase < 270 + EXACT_PHASE_WINDOW_DEG -> LAST_QUARTER
                else -> WANING_CRESCENT
            }
        }
    }
}

/**
 * Positions of the Sun, Moon and planets in the frame the stars are drawn in (mean equator and
 * equinox of date), so they go through the same view matrix.
 *
 * Accuracy model: geometric positions from Meeus (Sun, Moon) and JPL's approximate planetary
 * elements, with light time applied to the planets, and topocentric parallax applied to the Moon.
 * Nutation and annual aberration are left out because the stars omit them too (both under 20″),
 * so the Sun, the planets and the stars stay in step. Time is taken as UTC plus [AstroTime.TT_MINUS_UTC_SECONDS].
 */
object SolarSystem {

    const val KM_PER_AU = 149_597_870.7

    /** Visual magnitude of the Sun seen from 1 AU (Meeus, chapter 41 and the Astronomical Almanac). */
    private const val SUN_MAGNITUDE_AT_1_AU = -26.74
    private const val MEAN_MOON_DISTANCE_KM = 384_400.0

    /**
     * All nine bodies, in [SkyBody] order.
     *
     * @param longitudeDeg positive east, as in [AstroTime.localSiderealTimeDeg]
     */
    fun positions(julianDateUtc: Double, latitudeDeg: Double, longitudeDeg: Double): List<BodyPosition> {
        val common = Common(julianDateUtc)
        return SkyBody.entries.map { positionIn(common, it, latitudeDeg, longitudeDeg) }
    }

    /** One body, for callers that need it at many instants (rise and set); the same values as [positions]. */
    fun position(body: SkyBody, julianDateUtc: Double, latitudeDeg: Double, longitudeDeg: Double): BodyPosition =
        positionIn(Common(julianDateUtc), body, latitudeDeg, longitudeDeg)

    /** What every body at one instant shares: the time scales, the frame and the Sun. */
    private class Common(val julianDateUtc: Double) {
        val jde = AstroTime.julianEphemerisDay(julianDateUtc)
        val t = AstroTime.julianCenturiesSinceJ2000(jde)
        val obliquity = Coordinates.meanObliquityRad(t)
        val precession = Precession.matrixFromJ2000(t)
        val sun = SunEphemeris.position(jde)
        val sunDirection = Coordinates.eclipticToEquatorial(Math.toRadians(sun.trueLongitudeDeg), 0.0, obliquity)
    }

    private fun positionIn(common: Common, body: SkyBody, latitudeDeg: Double, longitudeDeg: Double): BodyPosition =
        when (body) {
            SkyBody.SUN -> sunPosition(common.sun.distanceAu, common.sunDirection)
            SkyBody.MOON -> moonPosition(
                common.jde, common.julianDateUtc, common.obliquity, common.sun, common.sunDirection,
                latitudeDeg, longitudeDeg,
            )
            else -> planetPosition(body, common.jde, common.precession, common.sunDirection)
        }

    private fun sunPosition(distanceAu: Double, sunDirection: Vector3) = BodyPosition(
        body = SkyBody.SUN,
        direction = sunDirection,
        vmag = SUN_MAGNITUDE_AT_1_AU + 5 * log10(distanceAu),
        distanceAu = distanceAu,
        elongationDeg = 0.0,
        illuminatedFraction = 1.0,
        sunDirection = sunDirection,
    )

    private fun planetPosition(body: SkyBody, jde: Double, precession: Matrix3, sunDirection: Vector3): BodyPosition {
        val geometry = PlanetEphemeris.geocentric(checkNotNull(body.planet), jde)
        val direction = precession * geometry.directionJ2000
        return BodyPosition(
            body = body,
            direction = direction,
            vmag = geometry.magnitude,
            distanceAu = geometry.distanceAu,
            elongationDeg = angleBetweenDeg(direction, sunDirection),
            illuminatedFraction = (1 + cos(Math.toRadians(geometry.phaseAngleDeg))) / 2,
            sunDirection = sunDirection,
        )
    }

    private fun moonPosition(
        jde: Double,
        julianDateUtc: Double,
        obliquity: Double,
        sun: SunPosition,
        sunDirection: Vector3,
        latitudeDeg: Double,
        longitudeDeg: Double,
    ): BodyPosition {
        val moon = MoonEphemeris.position(jde)
        val geocentric = Coordinates.eclipticToEquatorial(
            Math.toRadians(moon.longitudeDeg), Math.toRadians(moon.latitudeDeg), obliquity,
        )
        val lstRad = Math.toRadians(AstroTime.localSiderealTimeDeg(julianDateUtc, longitudeDeg))
        val observer = Parallax.observerPosition(Math.toRadians(latitudeDeg), lstRad)
        val topocentric = Parallax.topocentricDirection(geocentric, moon.distanceKm, observer)

        // Meeus, chapter 48: phase angle i from the Sun–Moon elongation ψ and the two distances.
        val elongation = angleBetweenRad(geocentric, sunDirection)
        val sunDistanceKm = sun.distanceAu * KM_PER_AU
        val phaseAngle = atan2(sunDistanceKm * sin(elongation), moon.distanceKm - sunDistanceKm * cos(elongation))
        val phaseAngleDeg = Math.toDegrees(phaseAngle)

        // Allen's phase law for the magnitude, scaled for the Moon's distance.
        val vmag = -12.74 + 0.026 * phaseAngleDeg + 4.0e-9 * phaseAngleDeg * phaseAngleDeg * phaseAngleDeg * phaseAngleDeg +
            5 * log10(moon.distanceKm / MEAN_MOON_DISTANCE_KM)

        return BodyPosition(
            body = SkyBody.MOON,
            direction = topocentric,
            vmag = vmag,
            distanceAu = moon.distanceKm / KM_PER_AU,
            elongationDeg = Math.toDegrees(elongation),
            illuminatedFraction = (1 + cos(phaseAngle)) / 2,
            sunDirection = sunDirection,
            lunarPhaseDeg = AstroTime.normalizeDegrees(moon.longitudeDeg - sun.trueLongitudeDeg),
        )
    }

    private fun angleBetweenRad(a: Vector3, b: Vector3): Double = acos((a dot b).coerceIn(-1.0, 1.0))

    private fun angleBetweenDeg(a: Vector3, b: Vector3): Double = Math.toDegrees(angleBetweenRad(a, b))
}
