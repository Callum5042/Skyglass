package com.skyglass.astro

import com.skyglass.math.Matrix3
import com.skyglass.math.Vector3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keplerian elements and their rates per Julian century, referred to the J2000 ecliptic and
 * equinox (Standish, "Keplerian Elements for Approximate Positions of the Major Planets",
 * JPL, table 1, valid 1800–2050). Angles in degrees, [a] in AU.
 *
 * @property meanLongitude L, i.e. the mean anomaly plus the longitude of perihelion
 * @property periLongitude ϖ, the longitude of perihelion
 * @property nodeLongitude Ω, the longitude of the ascending node
 */
class OrbitalElements(
    private val a: Double, private val aRate: Double,
    private val e: Double, private val eRate: Double,
    private val i: Double, private val iRate: Double,
    private val meanLongitude: Double, private val meanLongitudeRate: Double,
    private val periLongitude: Double, private val periLongitudeRate: Double,
    private val nodeLongitude: Double, private val nodeLongitudeRate: Double,
) {

    /**
     * Heliocentric position in AU, J2000 ecliptic rectangular coordinates, [t] Julian centuries
     * after J2000 (dynamical time).
     */
    fun heliocentricPosition(t: Double): Vector3 {
        val semiMajorAxis = a + aRate * t
        val eccentricity = e + eRate * t
        val inclination = Math.toRadians(i + iRate * t)
        val periLon = periLongitude + periLongitudeRate * t
        val node = Math.toRadians(nodeLongitude + nodeLongitudeRate * t)
        val argumentOfPerihelion = Math.toRadians(periLon) - node
        // Normalised to (−180°, 180°] so Newton's method starts close to the root.
        val meanAnomaly = Math.toRadians(
            AstroTime.normalizeDegrees(meanLongitude + meanLongitudeRate * t - periLon + 180.0) - 180.0,
        )

        val eccentricAnomaly = solveKepler(meanAnomaly, eccentricity)
        val xOrbit = semiMajorAxis * (cos(eccentricAnomaly) - eccentricity)
        val yOrbit = semiMajorAxis * sqrt(1 - eccentricity * eccentricity) * sin(eccentricAnomaly)

        // Orbital plane → ecliptic: R3(−Ω) · R1(−I) · R3(−ω), written as active rotations.
        return Matrix3.rotationZ(node) * Matrix3.rotationX(inclination) * Matrix3.rotationZ(argumentOfPerihelion) *
            Vector3(xOrbit, yOrbit, 0.0)
    }

    private fun solveKepler(meanAnomaly: Double, eccentricity: Double): Double {
        var eccentricAnomaly = meanAnomaly + eccentricity * sin(meanAnomaly)
        repeat(MAX_KEPLER_ITERATIONS) {
            val delta = (eccentricAnomaly - eccentricity * sin(eccentricAnomaly) - meanAnomaly) /
                (1 - eccentricity * cos(eccentricAnomaly))
            eccentricAnomaly -= delta
            if (abs(delta) < 1e-12) return eccentricAnomaly
        }
        return eccentricAnomaly
    }

    private companion object {
        const val MAX_KEPLER_ITERATIONS = 20
    }
}

enum class Planet(val elements: OrbitalElements) {
    MERCURY(
        OrbitalElements(
            0.38709927, 0.00000037, 0.20563593, 0.00001906, 7.00497902, -0.00594749,
            252.25032350, 149472.67411175, 77.45779628, 0.16047689, 48.33076593, -0.12534081,
        ),
    ),
    VENUS(
        OrbitalElements(
            0.72333566, 0.00000390, 0.00677672, -0.00004107, 3.39467605, -0.00078890,
            181.97909950, 58517.81538729, 131.60246718, 0.00268329, 76.67984255, -0.27769418,
        ),
    ),
    MARS(
        OrbitalElements(
            1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84969142, -0.00813131,
            -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343,
        ),
    ),
    JUPITER(
        OrbitalElements(
            5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30439695, -0.00183714,
            34.39644051, 3034.74612775, 14.72847983, 0.21252668, 100.47390909, 0.20469106,
        ),
    ),
    SATURN(
        OrbitalElements(
            9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48599187, 0.00193609,
            49.95424423, 1222.49362201, 92.59887831, -0.41897216, 113.66242448, -0.28867794,
        ),
    ),
    URANUS(
        OrbitalElements(
            19.18916464, -0.00196176, 0.04725744, -0.00004397, 0.77263783, -0.00242939,
            313.23810451, 428.48202785, 170.95427630, 0.40805281, 74.01692503, 0.04240589,
        ),
    ),
    NEPTUNE(
        OrbitalElements(
            30.06992276, 0.00026291, 0.00859048, 0.00005105, 1.77004347, 0.00035372,
            -55.12002969, 218.45945325, 44.96476227, -0.32241464, 131.78422574, -0.00508664,
        ),
    ),
}

/**
 * Where a planet is, as seen from the Earth's centre.
 *
 * @property directionJ2000 unit vector in the mean equatorial frame of J2000, corrected for light time
 * @property distanceAu Earth–planet distance
 * @property sunDistanceAu Sun–planet distance
 * @property phaseAngleDeg Sun–planet–Earth angle; 0 is fully lit, 180 is new
 * @property magnitude apparent visual magnitude
 */
data class PlanetGeometry(
    val directionJ2000: Vector3,
    val distanceAu: Double,
    val sunDistanceAu: Double,
    val phaseAngleDeg: Double,
    val magnitude: Double,
)

/**
 * Planet positions from mean Keplerian elements. Standish quotes errors over 1800–2050 of about
 * 15–40″ for Mercury to Mars, up to about 400″ (7′) for Jupiter and 600″ (10′) for Saturn. At the
 * current field of view (roughly 35 px per degree) that is under a pixel for the inner planets
 * but 3–6 px for Jupiter and Saturn. Fine for now; **zoom will need a better theory** (VSOP87, or
 * at least the periodic corrections for Jupiter and Saturn). The Earth is represented by the
 * Earth–Moon barycentre, which differs from it by at most 4700 km (about 0.005° for Mars at its
 * closest).
 */
object PlanetEphemeris {

    private const val LIGHT_DAYS_PER_AU = 0.0057755183
    private const val DAYS_PER_CENTURY = 36525.0
    private const val LIGHT_TIME_ITERATIONS = 3

    private val EARTH_MOON_BARYCENTRE = OrbitalElements(
        1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668,
        100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0.0, 0.0,
    )

    /** Rotation from the J2000 ecliptic to the J2000 mean equator. */
    private val ECLIPTIC_TO_EQUATOR_J2000 = Matrix3.rotationX(Coordinates.meanObliquityRad(0.0))

    /** Saturn's north pole in the J2000 equatorial frame (IAU: α = 40.589°, δ = 83.537°). */
    private val SATURN_POLE = Coordinates.raDecToUnit(
        Equatorial(Math.toRadians(40.589), Math.toRadians(83.537)),
    )

    /** @param jde Julian Ephemeris Day (dynamical time), see [AstroTime.julianEphemerisDay] */
    fun geocentric(planet: Planet, jde: Double): PlanetGeometry {
        val t = AstroTime.julianCenturiesSinceJ2000(jde)
        val earth = EARTH_MOON_BARYCENTRE.heliocentricPosition(t)

        // The light we see left the planet some minutes ago, so place it where it was then.
        var position = planet.elements.heliocentricPosition(t)
        var lightDays = LIGHT_DAYS_PER_AU * (position - earth).length()
        repeat(LIGHT_TIME_ITERATIONS) {
            position = planet.elements.heliocentricPosition(t - lightDays / DAYS_PER_CENTURY)
            lightDays = LIGHT_DAYS_PER_AU * (position - earth).length()
        }

        val toPlanet = position - earth
        val distance = toPlanet.length()
        val sunDistance = position.length()
        val direction = (ECLIPTIC_TO_EQUATOR_J2000 * toPlanet).normalized()

        val toSun = -position
        val toEarth = -toPlanet
        val phaseAngleDeg = Math.toDegrees(
            acos(((toSun dot toEarth) / (sunDistance * distance)).coerceIn(-1.0, 1.0)),
        )
        // How far the ring plane is opened to us: sin B, with B the Earth's height above it.
        val ringSinB = abs(SATURN_POLE dot direction)

        return PlanetGeometry(
            directionJ2000 = direction,
            distanceAu = distance,
            sunDistanceAu = sunDistance,
            phaseAngleDeg = phaseAngleDeg,
            magnitude = apparentMagnitude(planet, sunDistance, distance, phaseAngleDeg, ringSinB),
        )
    }

    /**
     * Apparent visual magnitude from Meeus, *Astronomical Algorithms*, chapter 41 (the
     * Astronomical Almanac formulae), good to a few tenths. [ringSinB] is |sin B| of Saturn's
     * ring tilt as seen from the Earth and is only used for Saturn.
     */
    fun apparentMagnitude(
        planet: Planet,
        sunDistanceAu: Double,
        distanceAu: Double,
        phaseAngleDeg: Double,
        ringSinB: Double = 0.0,
    ): Double {
        val i = phaseAngleDeg
        val distanceTerm = 5 * log10(sunDistanceAu * distanceAu)
        return when (planet) {
            Planet.MERCURY -> -0.42 + distanceTerm + 0.0380 * i - 0.000273 * i * i + 0.000002 * i * i * i
            Planet.VENUS -> -4.40 + distanceTerm + 0.0009 * i + 0.000239 * i * i - 0.00000065 * i * i * i
            Planet.MARS -> -1.52 + distanceTerm + 0.016 * i
            Planet.JUPITER -> -9.40 + distanceTerm + 0.005 * i
            Planet.SATURN -> -8.88 + distanceTerm - 2.60 * ringSinB + 1.25 * ringSinB * ringSinB
            Planet.URANUS -> -7.19 + distanceTerm
            Planet.NEPTUNE -> -6.87 + distanceTerm
        }
    }
}
