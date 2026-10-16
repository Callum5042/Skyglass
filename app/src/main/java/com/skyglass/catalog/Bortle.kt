package com.skyglass.catalog

import kotlin.math.log10
import kotlin.math.pow

/**
 * The Bortle dark-sky scale, used to tell the user roughly what their "faintest stars" setting
 * corresponds to, and what class the light-pollution map gives their location.
 *
 * Source: J. E. Bortle, "The Bortle Dark-Sky Scale", Sky & Telescope, February 2001, as
 * tabulated by Wikipedia ("Bortle scale"): the naked-eye limiting magnitudes (NELM) of the
 * classes are 1: 7.6-8.0, 2: 7.1-7.5, 3: 6.6-7.0, 4: 6.1-6.5 (Wikipedia splits this into 4,
 * 6.3-6.5, and 4.5, 6.1-6.3), 5: 5.6-6.0, 6: 5.1-5.5, 7: 4.6-5.0, 8: 4.1-4.5, 9: 4.0 or fainter.
 * The same table gives each class's zenith sky brightness (SQM, mag/arcsec², from Dark Skies
 * Awareness): 1: 21.76-22.0, 2: 21.6-21.75, 3: 21.3-21.6, 4: 20.8-21.3, 4.5: 20.3-20.8,
 * 5: 19.25-20.3, 6: 18.5-19.25, 7: 18.0-18.5, 8 and 9: below 18.0.
 * The classes are descriptive and the limits fuzzy (they depend on the observer's eyes), so
 * this is a guide, not a measurement.
 */
object Bortle {

    /** Faintest limiting magnitude at which each class starts, brightest sky (class 9) last. */
    private val LOWER_NELM = listOf(
        1 to 7.6,
        2 to 7.1,
        3 to 6.6,
        4 to 6.1,
        5 to 5.6,
        6 to 5.1,
        7 to 4.6,
        8 to 4.1,
    )

    /**
     * Sky brightness (mag/arcsec²) paired with limiting magnitude at the class boundaries of the two
     * tabulated columns, darkest first: the dark end of class 1, then the start of classes 1 to 7.
     */
    private val SQM_TO_NELM = listOf(
        22.0 to 8.0,
        21.76 to 7.6,
        21.6 to 7.1,
        21.3 to 6.6,
        20.3 to 6.1,
        19.25 to 5.6,
        18.5 to 5.1,
        18.0 to 4.6,
    )

    /** The Bortle class (1-9) whose naked-eye limiting magnitude range contains [nelm]. */
    fun classForLimitingMagnitude(nelm: Double): Int =
        LOWER_NELM.firstOrNull { nelm >= it.second }?.first ?: 9

    /**
     * The limiting magnitude that puts [sqm] (zenith sky brightness, mag/arcsec²) at the same place
     * within its Bortle class as in the table's brightness column: linear between the class
     * boundaries, so [classForSkyBrightness] and the slider's label always agree. The table lumps
     * classes 8 and 9 together below 18.0, so there the curve follows Schaefer's (1990) relation
     * between sky brightness and limiting magnitude, shifted to meet the table at 18.0; class 9 then
     * starts near 17.4. Darker than 22.0 counts as 22.0.
     */
    fun limitingMagnitudeForSkyBrightness(sqm: Double): Double {
        val (darkestSqm, darkestNelm) = SQM_TO_NELM.first()
        if (sqm >= darkestSqm) return darkestNelm
        val (edgeSqm, edgeNelm) = SQM_TO_NELM.last()
        if (sqm < edgeSqm) return edgeNelm + schaefer(sqm) - schaefer(edgeSqm)
        val i = SQM_TO_NELM.indexOfFirst { sqm >= it.first }
        val (sqmA, nelmA) = SQM_TO_NELM[i - 1]
        val (sqmB, nelmB) = SQM_TO_NELM[i]
        return nelmB + (sqm - sqmB) / (sqmA - sqmB) * (nelmA - nelmB)
    }

    /** The Bortle class (1-9) of a zenith sky brightness of [sqm] mag/arcsec². */
    fun classForSkyBrightness(sqm: Double): Int = classForLimitingMagnitude(limitingMagnitudeForSkyBrightness(sqm))

    /** Schaefer (1990): NELM = 7.93 - 5 log10(10^(4.316 - SQM/5) + 1). */
    private fun schaefer(sqm: Double): Double = 7.93 - 5 * log10(10.0.pow(4.316 - sqm / 5) + 1)
}
