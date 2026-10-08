package com.skyglass.catalog

import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * How far a star is, from the catalogue's trigonometric parallax.
 *
 * @property lightYears distance, unrounded; round it for display with [displayLightYears]
 * @property rough true when the parallax is so small that its uncertainty is a large fraction of it
 */
data class StarDistance(val lightYears: Double, val rough: Boolean) {

    /** Two significant figures, which is as much as the parallax supports (a tenth of a light year under 10). */
    fun displayLightYears(): Double = when {
        lightYears < 10 -> (lightYears * 10).roundToInt() / 10.0
        else -> {
            val step = Math.pow(10.0, (log10(lightYears).toInt() - 1).toDouble())
            (lightYears / step).roundToInt() * step
        }
    }

    companion object {
        const val LIGHT_YEARS_PER_PARSEC = 3.261563777

        /**
         * The Bright Star Catalogue (1991) predates Hipparcos, so its parallaxes are ground-based, with
         * typical errors of 0.005–0.01″. At or above [GOOD_PARALLAX_ARCSEC] that is 10–20 % of the
         * value or better; between [ROUGH_PARALLAX_ARCSEC] and that it is a rough guide (up to 50 %);
         * below, the star is too far for this catalogue to say (Betelgeuse's 0.005″ is nothing but an
         * upper limit), so there is no distance rather than a wrong one.
         */
        const val GOOD_PARALLAX_ARCSEC = 0.05
        const val ROUGH_PARALLAX_ARCSEC = 0.02

        /**
         * Null when there is no parallax or it is too small to give a useful distance. A [dynamical]
         * parallax (worked out from a binary's orbit and assumed masses, flagged "D" in the catalogue)
         * is always rough, however large.
         */
        fun fromParallax(parallaxArcsec: Double?, dynamical: Boolean = false): StarDistance? {
            if (parallaxArcsec == null || parallaxArcsec < ROUGH_PARALLAX_ARCSEC) return null
            return StarDistance(
                lightYears = LIGHT_YEARS_PER_PARSEC / parallaxArcsec,
                rough = dynamical || parallaxArcsec < GOOD_PARALLAX_ARCSEC,
            )
        }
    }
}

/** The colour a star appears to the eye, from blue-white hot stars to red cool ones. */
enum class StarColorName { BLUE, BLUE_WHITE, WHITE, YELLOW_WHITE, YELLOW, ORANGE, RED }

/** Reads the Harvard spectral class and a colour name out of the catalogue's spectral type and B−V. */
object SpectralType {

    private val ORDER = "OBAFGKM"

    /**
     * The Harvard class letter (O, B, A, F, G, K or M) of a type such as "A1Vm", "gG9" or "kA2hA5mA7",
     * or null for types without one (white dwarfs "DA", carbon stars "C", S and N types) or none at all.
     * Lower-case prefixes (giant "g", dwarf "d", subdwarf "sd", the "k"/"h"/"m" line markers) are skipped.
     */
    fun classLetter(spectralType: String?): Char? {
        val first = spectralType?.firstOrNull { it.isUpperCase() } ?: return null
        return first.takeIf { it in ORDER }
    }

    /** From the class letter if there is one, else from [bv]; null when neither is known. */
    fun colorName(spectralType: String?, bv: Double?): StarColorName? {
        classLetter(spectralType)?.let { return byClass(it) }
        return bv?.let(::byColorIndex)
    }

    private fun byClass(letter: Char): StarColorName = when (letter) {
        'O' -> StarColorName.BLUE
        'B' -> StarColorName.BLUE_WHITE
        'A' -> StarColorName.WHITE
        'F' -> StarColorName.YELLOW_WHITE
        'G' -> StarColorName.YELLOW
        'K' -> StarColorName.ORANGE
        else -> StarColorName.RED
    }

    /** Typical B−V at the class boundaries (O/B −0.2, B/A 0.0, A/F 0.3, F/G 0.58, G/K 0.81, K/M 1.4). */
    private fun byColorIndex(bv: Double): StarColorName = when {
        bv < -0.2 -> StarColorName.BLUE
        bv < 0.0 -> StarColorName.BLUE_WHITE
        bv < 0.3 -> StarColorName.WHITE
        bv < 0.58 -> StarColorName.YELLOW_WHITE
        bv < 0.81 -> StarColorName.YELLOW
        bv < 1.4 -> StarColorName.ORANGE
        else -> StarColorName.RED
    }
}
