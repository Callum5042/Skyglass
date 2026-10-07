package com.skyglass.astro

import com.skyglass.math.Matrix3
import kotlin.math.cos
import kotlin.math.max

object Precession {

    private const val ARCSEC_TO_RAD = Math.PI / (180.0 * 3600.0)

    /**
     * IAU 1976 precession matrix taking J2000 mean equatorial unit vectors to the mean equator
     * and equinox of date (Meeus eq. 21.3 with the starting epoch at J2000, so T = 0).
     *
     * @param centuriesSinceJ2000 target epoch in Julian centuries from J2000.0
     */
    fun matrixFromJ2000(centuriesSinceJ2000: Double): Matrix3 {
        val t = centuriesSinceJ2000
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * ARCSEC_TO_RAD
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * ARCSEC_TO_RAD
        val theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * ARCSEC_TO_RAD
        // Frame rotations R3(−z)·R2(θ)·R3(−ζ), written as the equivalent active rotations.
        return Matrix3.rotationZ(z) * Matrix3.rotationY(-theta) * Matrix3.rotationZ(zeta)
    }

    /**
     * Linear proper motion over [years].
     *
     * @param pmRaArcsecPerYear projected motion in RA, i.e. cos(δ)·dα/dt, as given by BSC5
     */
    fun applyProperMotion(
        position: Equatorial,
        pmRaArcsecPerYear: Double,
        pmDecArcsecPerYear: Double,
        years: Double,
    ): Equatorial {
        // Clamp cos δ so the polar stars cannot blow up dα; the on-sky motion stays tiny either way.
        val cosDec = max(cos(position.decRad), 1e-6)
        return Equatorial(
            raRad = position.raRad + pmRaArcsecPerYear / cosDec * years * ARCSEC_TO_RAD,
            decRad = position.decRad + pmDecArcsecPerYear * years * ARCSEC_TO_RAD,
        )
    }
}
