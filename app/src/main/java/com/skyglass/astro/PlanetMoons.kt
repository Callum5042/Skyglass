package com.skyglass.astro

import com.skyglass.math.Matrix3
import com.skyglass.math.Vector3
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The major moons of Jupiter and Saturn, which separate from their planet when the view is zoomed in.
 *
 * @property planet the planet it orbits
 * @property absoluteMagnitude V(1,0), the magnitude at 1 AU from both the Sun and the Earth (phase
 *   ignored), for the apparent magnitude `V(1,0) + 5 log10(r Δ)`
 */
enum class PlanetMoon(val planet: SkyBody, val absoluteMagnitude: Double) {
    IO(SkyBody.JUPITER, -1.68),
    EUROPA(SkyBody.JUPITER, -1.41),
    GANYMEDE(SkyBody.JUPITER, -2.09),
    CALLISTO(SkyBody.JUPITER, -1.05),
    MIMAS(SkyBody.SATURN, 3.3),
    ENCELADUS(SkyBody.SATURN, 2.1),
    TETHYS(SkyBody.SATURN, 0.6),
    DIONE(SkyBody.SATURN, 0.8),
    RHEA(SkyBody.SATURN, 0.1),
    TITAN(SkyBody.SATURN, -1.28),
    HYPERION(SkyBody.SATURN, 4.6),
    IAPETUS(SkyBody.SATURN, 1.5),
}

/**
 * Where a moon is relative to its planet, as Meeus gives it: in units of the planet's equatorial
 * radius, [x] along the planet's apparent equator, positive to the west, [y] toward the planet's
 * north pole as projected on the sky; [behind] when it is farther from the Earth than the planet.
 */
data class PlanetMoonOffset(val moon: PlanetMoon, val x: Double, val y: Double, val behind: Boolean)

/**
 * A moon as seen by one observer at one instant.
 *
 * @property direction unit vector in the equatorial frame of date, like the planet's
 * @property hidden behind the planet's disc, so not drawn
 * @property separationTan the tangent of the moon's angular distance from the planet's centre
 */
data class PlanetMoonPosition(
    val moon: PlanetMoon,
    val direction: Vector3,
    val vmag: Double,
    val offset: PlanetMoonOffset,
    val hidden: Boolean,
    val separationTan: Double,
)

/** A compass direction on the sky (celestial north and east, not the ground's). */
enum class SkySide { NORTH, EAST, SOUTH, WEST }

/**
 * Positions of the major moons of Jupiter and Saturn (Meeus, *Astronomical Algorithms*, ch. 44 and 46),
 * placed on the sky around the planets' positions from [SolarSystem]. The chapter 46 code follows the
 * layout of, and was checked against, Sonia Keys' `meeus` Go library (MIT licence, copyright 2013 Sonia Keys).
 */
object PlanetMoons {

    private const val DEG = Math.PI / 180

    /** Polar radius over equatorial radius, for whether a moon behind the planet is behind its disc. */
    const val JUPITER_POLAR_RATIO = 66_854.0 / 71_492.0
    const val SATURN_POLAR_RATIO = 54_364.0 / 60_268.0

    /** B1950.0 (JDE 2433282.4235), the equinox Meeus' theory of Saturn's moons is referred to. */
    private const val B1950_CENTURIES = (2433282.4235 - 2451545.0) / 36525.0

    /**
     * Every moon's position at [julianDateUtc], from the planets' positions in [bodies] (which must hold
     * the Sun, Jupiter and Saturn, as [SolarSystem.positions] gives them); moons of a missing planet are
     * left out.
     */
    fun positions(julianDateUtc: Double, bodies: List<BodyPosition>): List<PlanetMoonPosition> {
        val jde = AstroTime.julianEphemerisDay(julianDateUtc)
        val sun = bodies.firstOrNull { it.body == SkyBody.SUN } ?: return emptyList()
        val centuries = AstroTime.julianCenturiesSinceJ2000(jde)
        val out = ArrayList<PlanetMoonPosition>(PlanetMoon.entries.size)
        bodies.firstOrNull { it.body == SkyBody.JUPITER }?.let { jupiter ->
            val pole = Precession.matrixFromJ2000(centuries) * jupiterPoleJ2000(centuries)
            for (offset in jupiterOffsets(jde)) out += place(offset, jupiter, pole, sun, JUPITER_POLAR_RATIO)
        }
        bodies.firstOrNull { it.body == SkyBody.SATURN }?.let { saturn ->
            val pole = Precession.matrixFromJ2000(centuries) * fromB1950Ecliptic(SATURN_POLE_B1950_ECLIPTIC)
            for (offset in saturnOffsets(jde, saturn.direction, saturn.distanceAu)) out += place(offset, saturn, pole, sun, SATURN_POLAR_RATIO)
        }
        return out
    }

    /**
     * Puts a moon on the sky: [PlanetMoonOffset.x] along the planet's equator (west) and [PlanetMoonOffset.y] toward
     * its pole, in units of the planet's angular equatorial radius, in the tangent plane at the planet.
     *
     * @param pole the planet's north pole, unit vector, equatorial of date
     */
    private fun place(offset: PlanetMoonOffset, planet: BodyPosition, pole: Vector3, sun: BodyPosition, polarRatio: Double): PlanetMoonPosition {
        val d = planet.direction
        val north = (pole - d * (pole dot d)).normalized()
        val west = d cross north // north × d is east, as celestial north × d is (+Z × d)
        val radiusTan = BodySize.radiusKm(planet.body) / (planet.distanceAu * SolarSystem.KM_PER_AU)
        val tangent = (west * offset.x + north * offset.y) * radiusTan
        // The planet's distance from the Sun, from the triangle Sun-Earth-planet.
        val sunDistance = sun.distanceAu
        val heliocentric = sqrt(
            max(sunDistance * sunDistance + planet.distanceAu * planet.distanceAu -
                2 * sunDistance * planet.distanceAu * cos(Math.toRadians(planet.elongationDeg)), 1e-12),
        )
        return PlanetMoonPosition(
            moon = offset.moon,
            direction = (d + tangent).normalized(),
            vmag = offset.moon.absoluteMagnitude + 5 * log10(heliocentric * planet.distanceAu),
            offset = offset,
            hidden = hiddenBehindDisc(offset, polarRatio),
            separationTan = tangent.length(),
        )
    }

    /** Which way [moon] lies from [planet] on the sky: the larger of its northward and eastward components. */
    fun sideOf(moon: Vector3, planet: Vector3): SkySide {
        val celestialNorth = Vector3(0.0, 0.0, 1.0)
        val north = (celestialNorth - planet * planet.z).normalized()
        val east = (celestialNorth cross planet).normalized()
        val shift = moon - planet
        val northward = shift dot north
        val eastward = shift dot east
        return if (abs(northward) > abs(eastward)) {
            if (northward > 0) SkySide.NORTH else SkySide.SOUTH
        } else {
            if (eastward > 0) SkySide.EAST else SkySide.WEST
        }
    }

    /**
     * Whether a moon is behind its planet's disc (an ellipse, its polar radius [polarRatio] of the
     * equatorial; Saturn's rings are not counted). One in front, crossing the disc, stays visible.
     */
    fun hiddenBehindDisc(offset: PlanetMoonOffset, polarRatio: Double): Boolean =
        offset.behind && offset.x * offset.x + (offset.y / polarRatio) * (offset.y / polarRatio) < 1.0

    /** Jupiter's north pole (IAU WGCCRE: α₀ = 268.056595° − 0.006499°T, δ₀ = 64.495303° + 0.002413°T), J2000. */
    private fun jupiterPoleJ2000(centuries: Double): Vector3 = Coordinates.raDecToUnit(
        Equatorial(Math.toRadians(268.056595 - 0.006499 * centuries), Math.toRadians(64.495303 + 0.002413 * centuries)),
    )

    /**
     * Saturn's equator as Meeus' theory takes it (inclination 28.0817° to the B1950 ecliptic, node
     * 168.8112°), so the moons' "north" agrees with the theory's own: the pole is at ecliptic longitude
     * node − 90°, latitude 90° − inclination.
     */
    private val SATURN_POLE_B1950_ECLIPTIC: Vector3 = Vector3(
        sin(168.8112 * DEG) * sin(28.0817 * DEG),
        -cos(168.8112 * DEG) * sin(28.0817 * DEG),
        cos(28.0817 * DEG),
    )

    /** B1950 ecliptic → J2000 equatorial. */
    private fun fromB1950Ecliptic(v: Vector3): Vector3 =
        Precession.matrixFromJ2000(B1950_CENTURIES).transpose() *
            (Matrix3.rotationX(Coordinates.meanObliquityRad(B1950_CENTURIES)) * v)

    // --- Jupiter: Meeus ch. 44, the low-accuracy method --------------------------------------

    /** The four Galilean moons at [jde] (TT), Meeus ch. 44's low-accuracy method: a few hundredths of Jupiter's radius. */
    fun jupiterOffsets(jde: Double): List<PlanetMoonOffset> {
        val d = jde - 2451545.0
        val v = (172.74 + 0.00111588 * d) * DEG
        val m = (357.529 + 0.9856003 * d) * DEG
        val n = (20.020 + 0.0830853 * d + 0.329 * sin(v)) * DEG
        val j = (66.115 + 0.9025179 * d - 0.329 * sin(v)) * DEG
        val a = (1.915 * sin(m) + 0.020 * sin(2 * m)) * DEG
        val b = (5.555 * sin(n) + 0.168 * sin(2 * n)) * DEG
        val k = j + a - b
        val sunDistance = 1.00014 - 0.01671 * cos(m) - 0.00014 * cos(2 * m)
        val r = 5.20872 - 0.25208 * cos(n) - 0.00611 * cos(2 * n)
        val delta = sqrt(r * r + sunDistance * sunDistance - 2 * r * sunDistance * cos(k))
        val psi = asin(sunDistance / delta * sin(k))
        val lambda = (34.35 + 0.083091 * d + 0.329 * sin(v)) * DEG + b
        val ds = 3.12 * DEG * sin(lambda + 42.8 * DEG)
        val de = ds - 2.22 * DEG * sin(psi) * cos(lambda + 22.0 * DEG) - 1.30 * DEG * (r - delta) / delta * sin(lambda - 100.5 * DEG)
        // Light time: the moons are seen as they were Δ/173 days before (173 AU a day).
        val dd = d - delta / 173.0
        val u1 = (163.8069 + 203.4058646 * dd) * DEG + psi - b
        val u2 = (358.4140 + 101.2916335 * dd) * DEG + psi - b
        val u3 = (5.7176 + 50.2345180 * dd) * DEG + psi - b
        val u4 = (224.8092 + 21.4879800 * dd) * DEG + psi - b
        val g = (331.18 + 50.310482 * dd) * DEG
        val h = (87.45 + 21.569231 * dd) * DEG
        val s = sin(de)
        // u is 0 at inferior conjunction (the moon between Jupiter and the Earth), so cos u < 0 is the far side.
        fun at(moon: PlanetMoon, u: Double, radius: Double) =
            PlanetMoonOffset(moon, x = radius * sin(u), y = -radius * cos(u) * s, behind = cos(u) < 0)
        return listOf(
            at(PlanetMoon.IO, u1 + 0.473 * DEG * sin(2 * (u1 - u2)), 5.9057 - 0.0244 * cos(2 * (u1 - u2))),
            at(PlanetMoon.EUROPA, u2 + 1.065 * DEG * sin(2 * (u2 - u3)), 9.3966 - 0.0882 * cos(2 * (u2 - u3))),
            at(PlanetMoon.GANYMEDE, u3 + 0.165 * DEG * sin(g), 14.9883 - 0.0216 * cos(g)),
            at(PlanetMoon.CALLISTO, u4 + 0.843 * DEG * sin(h), 26.3627 - 0.1939 * cos(h)),
        )
    }

    // --- Saturn: Meeus ch. 46 -------------------------------------------------------------------

    /** Saturn's moons in Meeus' order, with the constant K of the light-time correction to X. */
    private val SATURN_MOONS = listOf(
        PlanetMoon.MIMAS to 20947.0,
        PlanetMoon.ENCELADUS to 23715.0,
        PlanetMoon.TETHYS to 26382.0,
        PlanetMoon.DIONE to 29876.0,
        PlanetMoon.RHEA to 35313.0,
        PlanetMoon.TITAN to 53800.0,
        PlanetMoon.HYPERION to 59222.0,
        PlanetMoon.IAPETUS to 91820.0,
    )

    /** Light time for one AU, in days. */
    private const val LIGHT_DAYS_PER_AU = 0.0057755183

    /** Saturn's distance from the Earth in its own equatorial radii per AU, for the perspective factor W. */
    private const val SATURN_RADII_PER_AU = 2475.0

    /**
     * Saturn's eight major moons at [jde] (TT), Meeus ch. 46.
     *
     * @param saturnDirection unit vector toward Saturn, light time allowed for, equatorial of date
     * @param distanceAu Saturn's distance from the Earth
     */
    fun saturnOffsets(jde: Double, saturnDirection: Vector3, distanceAu: Double): List<PlanetMoonOffset> {
        // The theory wants Saturn's geocentric ecliptic place referred to B1950.
        val toJ2000 = Precession.matrixFromJ2000(AstroTime.julianCenturiesSinceJ2000(jde)).transpose()
        val b1950Equatorial = Precession.matrixFromJ2000(B1950_CENTURIES) * (toJ2000 * saturnDirection)
        val ecliptic = Matrix3.rotationX(-Coordinates.meanObliquityRad(B1950_CENTURIES)) * b1950Equatorial
        val lambda0 = atan2(ecliptic.y, ecliptic.x)
        val beta0 = asin(ecliptic.z.coerceIn(-1.0, 1.0))

        val q = SaturnTheory(jde - distanceAu * LIGHT_DAYS_PER_AU)
        val orbits = listOf(q.mimas(), q.enceladus(), q.tethys(), q.dione(), q.rhea(), q.titan(), q.hyperion(), q.iapetus())

        // Rectangular coordinates in Saturn's equatorial frame; index 0 is the pole, which fixes the
        // rotation D that puts Y toward the pole as seen.
        val count = orbits.size + 1
        val xs = DoubleArray(count)
        val ys = DoubleArray(count)
        val zs = DoubleArray(count)
        zs[0] = 1.0
        for ((index, orbit) in orbits.withIndex()) {
            val u = orbit.lambda - orbit.node
            val w = orbit.node - 168.8112 * DEG
            xs[index + 1] = orbit.radius * (cos(u) * cos(w) - sin(u) * cos(orbit.gamma) * sin(w))
            ys[index + 1] = orbit.radius * (sin(u) * cos(w) * cos(orbit.gamma) + cos(u) * sin(w))
            zs[index + 1] = orbit.radius * sin(u) * sin(orbit.gamma)
        }
        val bigA = DoubleArray(count)
        val bigB = DoubleArray(count)
        val bigC = DoubleArray(count)
        for (index in 0 until count) {
            var a = xs[index]
            var b = q.c1 * ys[index] - q.s1 * zs[index]
            val c = q.s1 * ys[index] + q.c1 * zs[index]
            val a2 = q.c2 * a - q.s2 * b
            b = q.s2 * a + q.c2 * b
            a = a2
            bigA[index] = a * sin(lambda0) - b * cos(lambda0)
            b = a * cos(lambda0) + b * sin(lambda0)
            bigB[index] = b * cos(beta0) + c * sin(beta0)
            bigC[index] = c * cos(beta0) - b * sin(beta0)
        }
        val rotation = atan2(bigA[0], bigC[0])
        return SATURN_MOONS.mapIndexed { i, (moon, k) ->
            val index = i + 1
            var x = bigA[index] * cos(rotation) - bigC[index] * sin(rotation)
            val y = bigA[index] * sin(rotation) + bigC[index] * cos(rotation)
            val z = bigB[index]
            val ratio = x / orbits[i].radius
            x += abs(z) / k * sqrt(max(1 - ratio * ratio, 0.0))
            val perspective = distanceAu / (distanceAu + z / SATURN_RADII_PER_AU)
            PlanetMoonOffset(moon, x = x * perspective, y = y * perspective, behind = z > 0)
        }
    }

    /** One moon's orbit at the moment: true longitude, radius vector (Saturn radii), inclination and node in Meeus' sense. */
    private class Orbit(val lambda: Double, val radius: Double, val gamma: Double, val node: Double)

    /** The time arguments of Meeus ch. 46 and each moon's orbit, at [jde] less the light time. */
    private class SaturnTheory(jde: Double) {
        val t1 = jde - 2411093.0
        val t2 = t1 / 365.25
        val t3 = (jde - 2433282.423) / 365.25 + 1950.0
        val t4 = jde - 2411368.0
        val t5 = t4 / 365.25
        val t6 = jde - 2415020.0
        val t7 = t6 / 36525.0
        val t8 = t6 / 365.25
        val t9 = (jde - 2442000.5) / 365.25
        val t10 = jde - 2409786.0
        val t11 = t10 / 36525.0
        val w0 = 5.095 * DEG * (t3 - 1866.39)
        val w1 = (74.4 + 32.39 * t2) * DEG
        val w2 = (134.3 + 92.62 * t2) * DEG
        val w3 = (42.0 - 0.5118 * t5) * DEG
        val w4 = (276.59 + 0.5118 * t5) * DEG
        val w5 = (267.2635 + 1222.1136 * t7) * DEG
        val w6 = (175.4762 + 1221.5515 * t7) * DEG
        val w7 = (2.4891 + 0.002435 * t7) * DEG
        val w8 = (113.35 - 0.2597 * t7) * DEG
        val s1 = sin(28.0817 * DEG)
        val c1 = cos(28.0817 * DEG)
        val s2 = sin(168.8112 * DEG)
        val c2 = cos(168.8112 * DEG)
        val e1 = 0.05589 - 0.000346 * t7

        fun mimas(): Orbit {
            val l = (127.64 + 381.994497 * t1 - 43.57 * sin(w0) - 0.720 * sin(3 * w0) - 0.02144 * sin(5 * w0)) * DEG
            val p = (106.1 + 365.549 * t2) * DEG
            val m = l - p
            val c = (2.18287 * sin(m) + 0.025988 * sin(2 * m) + 0.00043 * sin(3 * m)) * DEG
            return Orbit(l + c, 3.06879 / (1 + 0.01905 * cos(m + c)), 1.563 * DEG, (54.5 - 365.072 * t2) * DEG)
        }

        fun enceladus(): Orbit {
            val l = (200.317 + 262.7319002 * t1 + 0.25667 * sin(w1) + 0.20883 * sin(w2)) * DEG
            val p = (309.107 + 123.44121 * t2) * DEG
            val m = l - p
            val c = (0.55577 * sin(m) + 0.00168 * sin(2 * m)) * DEG
            return Orbit(l + c, 3.94118 / (1 + 0.00485 * cos(m + c)), 0.0262 * DEG, (348.0 - 151.95 * t2) * DEG)
        }

        fun tethys(): Orbit {
            val lambda = (285.306 + 190.69791226 * t1 + 2.063 * sin(w0) + 0.03409 * sin(3 * w0) + 0.001015 * sin(5 * w0)) * DEG
            return Orbit(lambda, 4.880998, 1.0976 * DEG, (111.33 - 72.2441 * t2) * DEG)
        }

        fun dione(): Orbit {
            val l = (254.712 + 131.53493193 * t1 - 0.0215 * sin(w1) - 0.01733 * sin(w2)) * DEG
            val p = (174.8 + 30.820 * t2) * DEG
            val m = l - p
            val c = (0.24717 * sin(m) + 0.00033 * sin(2 * m)) * DEG
            return Orbit(l + c, 6.24871 / (1 + 0.002157 * cos(m + c)), 0.0139 * DEG, (232.0 - 30.27 * t2) * DEG)
        }

        fun rhea(): Orbit {
            val pPrime = (342.7 + 10.057 * t2) * DEG
            val a1 = 0.000265 * sin(pPrime) + 0.001 * sin(w4)
            val a2 = 0.000265 * cos(pPrime) + 0.001 * cos(w4)
            val n = (345.0 - 10.057 * t2) * DEG
            val lambdaPrime = (359.244 + 79.69004720 * t1 + 0.086754 * sin(n)) * DEG
            val i = (28.0362 + 0.346898 * cos(n) + 0.01930 * cos(w3)) * DEG
            val node = (168.8034 + 0.736936 * sin(n) + 0.041 * sin(w3)) * DEG
            return orbit(lambdaPrime, atan2(a1, a2), hypot(a1, a2), 8.725924, node, i)
        }

        fun titan(): Orbit {
            val l = (261.1582 + 22.57697855 * t4 + 0.074025 * sin(w3)) * DEG
            val iPrime = (27.45141 + 0.295999 * cos(w3)) * DEG
            val nodePrime = (168.66925 + 0.628808 * sin(w3)) * DEG
            val a1 = sin(w7) * sin(nodePrime - w8)
            val a2 = cos(w7) * sin(iPrime) - sin(w7) * cos(iPrime) * cos(nodePrime - w8)
            val g0 = 102.8623 * DEG
            val psi = atan2(a1, a2)
            val s = hypot(a1, a2)
            var g = w4 - nodePrime - psi
            var periapsis = 0.0
            repeat(3) {
                periapsis = w4 + 0.37515 * DEG * (sin(2 * g) - sin(2 * g0))
                g = periapsis - nodePrime - psi
            }
            val ePrime = 0.029092 + 0.00019048 * (cos(2 * g) - cos(2 * g0))
            val q = 2 * (w5 - periapsis)
            val b1 = sin(iPrime) * sin(nodePrime - w8)
            val b2 = cos(w7) * sin(iPrime) * cos(nodePrime - w8) - sin(w7) * cos(iPrime)
            val theta = atan2(b1, b2) + w8
            val e = ePrime + 0.002778797 * ePrime * cos(q)
            val p = periapsis + 0.159215 * DEG * sin(q)
            val u = 2 * w5 - 2 * theta + psi
            val h = 0.9375 * ePrime * ePrime * sin(q) + 0.1875 * s * s * sin(2 * (w5 - theta))
            val lambdaPrime = l - 0.254744 * DEG * (e1 * sin(w6) + 0.75 * e1 * e1 * sin(2 * w6) + h)
            val i = iPrime + 0.031843 * DEG * s * cos(u)
            val node = nodePrime + 0.031843 * DEG * s * sin(u) / sin(iPrime)
            return orbit(lambdaPrime, p, e, 20.216193, node, i)
        }

        fun hyperion(): Orbit {
            val eta = (92.39 + 0.5621071 * t6) * DEG
            val zeta = (148.19 - 19.18 * t8) * DEG
            val theta = (184.8 - 35.41 * t9) * DEG
            val thetaPrime = theta - 7.5 * DEG
            val aS = (176.0 + 12.22 * t8) * DEG
            val bS = (8.0 + 24.44 * t8) * DEG
            val cS = bS + 5.0 * DEG
            val periapsis = (69.898 - 18.67088 * t8) * DEG
            val phi = 2 * (periapsis - w5)
            val chi = (94.9 - 2.292 * t8) * DEG
            val a = 24.50601 - 0.08686 * cos(eta) - 0.00166 * cos(zeta + eta) + 0.00175 * cos(zeta - eta)
            val e = 0.103458 - 0.004099 * cos(eta) - 0.000167 * cos(zeta + eta) + 0.000235 * cos(zeta - eta) +
                0.02303 * cos(zeta) - 0.00212 * cos(2 * zeta) + 0.000151 * cos(3 * zeta) + 0.00013 * cos(phi)
            val p = periapsis + (0.15648 * sin(chi) - 0.4457 * sin(eta) - 0.2657 * sin(zeta + eta) - 0.3573 * sin(zeta - eta) -
                12.872 * sin(zeta) + 1.668 * sin(2 * zeta) - 0.2419 * sin(3 * zeta) - 0.07 * sin(phi)) * DEG
            val lambdaPrime = (177.047 + 16.91993829 * t6 + 0.15648 * sin(chi) + 9.142 * sin(eta) + 0.007 * sin(2 * eta) -
                0.014 * sin(3 * eta) + 0.2275 * sin(zeta + eta) + 0.2112 * sin(zeta - eta) - 0.26 * sin(zeta) -
                0.0098 * sin(2 * zeta) - 0.013 * sin(aS) + 0.017 * sin(bS) - 0.0303 * sin(phi)) * DEG
            val i = (27.3347 + 0.6434886 * cos(chi) + 0.315 * cos(w3) + 0.018 * cos(theta) - 0.018 * cos(cS)) * DEG
            val node = (168.6812 + 1.40136 * cos(chi) + 0.68599 * sin(w3) - 0.0392 * sin(cS) + 0.0366 * sin(thetaPrime)) * DEG
            return orbit(lambdaPrime, p, e, a, node, i)
        }

        fun iapetus(): Orbit {
            val l = (261.1582 + 22.57697855 * t4) * DEG
            val periapsisPrime = (91.796 + 0.562 * t7) * DEG
            val psi = (4.367 - 0.195 * t7) * DEG
            val theta = (146.819 - 3.198 * t7) * DEG
            val phi = (60.470 + 1.521 * t7) * DEG
            val bigPhi = (205.055 - 2.091 * t7) * DEG
            val ePrime = 0.028298 + 0.001156 * t11
            val periapsis0 = (352.91 + 11.71 * t11) * DEG
            val mu = (76.3852 + 4.53795125 * t10) * DEG
            val iPrime = (18.4602 + t11 * (-0.9518 + t11 * (-0.072 + t11 * 0.0054))) * DEG
            val nodePrime = (143.198 + t11 * (-3.919 + t11 * (0.116 + t11 * 0.008))) * DEG
            val ll = mu - periapsis0
            val g = periapsis0 - nodePrime - psi
            val g1 = periapsis0 - nodePrime - phi
            val ls = w5 - periapsisPrime
            val gs = periapsisPrime - theta
            val lT = l - w4
            val gT = w4 - bigPhi
            val u1 = 2 * (ll + g - ls - gs)
            val u2 = ll + g1 - lT - gT
            val u3 = ll + 2 * (g - ls - gs)
            val u4 = lT + gT - g1
            val u5 = 2 * (ls + gs)
            val a = 58.935028 + 0.004638 * cos(u1) + 0.058222 * cos(u2)
            val e = ePrime - 0.0014097 * cos(g1 - gT) + 0.0003733 * cos(u5 - 2 * g) + 0.000118 * cos(u3) +
                0.0002408 * cos(ll) + 0.0002849 * cos(ll + u2) + 0.000619 * cos(u4)
            val w = (0.08077 * sin(g1 - gT) + 0.02139 * sin(u5 - 2 * g) - 0.00676 * sin(u3) + 0.0138 * sin(ll) +
                0.01632 * sin(ll + u2) + 0.03547 * sin(u4)) * DEG
            val p = periapsis0 + w / ePrime
            val lambdaPrime = mu + (-0.04299 * sin(u2) - 0.00789 * sin(u1) - 0.06312 * sin(ls) - 0.00295 * sin(2 * ls) -
                0.02231 * sin(u5) + 0.0065 * sin(u5 + psi)) * DEG
            val i = iPrime + (0.04204 * cos(u5 + psi) + 0.00235 * cos(ll + g1 + lT + gT + phi) + 0.0036 * cos(u2 + phi)) * DEG
            val wPrime = (0.04204 * sin(u5 + psi) + 0.00235 * sin(ll + g1 + lT + gT + phi) + 0.00358 * sin(u2 + phi)) * DEG
            val node = nodePrime + wPrime / sin(iPrime)
            return orbit(lambdaPrime, p, e, a, node, i)
        }

        /**
         * Meeus' common step for Rhea, Titan, Hyperion and Iapetus: the equation of the centre and
         * radius vector from mean longitude [lambdaPrime], periapsis [p] and eccentricity [e], then the
         * orbit's inclination and node referred to Saturn's equator (Meeus' γ and w).
         */
        private fun orbit(lambdaPrime: Double, p: Double, e: Double, a: Double, node: Double, i: Double): Orbit {
            val m = lambdaPrime - p
            val e2 = e * e
            val e3 = e2 * e
            val e4 = e2 * e2
            val e5 = e3 * e2
            val c = (2 * e - 0.25 * e3 + 0.0520833333 * e5) * sin(m) +
                (1.25 * e2 - 0.458333333 * e4) * sin(2 * m) +
                (1.083333333 * e3 - 0.671875 * e5) * sin(3 * m) +
                1.072917 * e4 * sin(4 * m) + 1.142708 * e5 * sin(5 * m)
            val radius = a * (1 - e2) / (1 + e * cos(m + c))
            val g = node - 168.8112 * DEG
            val a1 = sin(i) * sin(g)
            val a2 = c1 * sin(i) * cos(g) - s1 * cos(i)
            val gamma = asin(hypot(a1, a2))
            val u = atan2(a1, a2)
            val h = c1 * sin(i) - s1 * cos(i) * cos(g)
            val psi = atan2(s1 * sin(g), h)
            return Orbit(lambdaPrime + c + u - g - psi, radius, gamma, 168.8112 * DEG + u)
        }
    }
}
