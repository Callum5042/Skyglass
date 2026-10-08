package com.skyglass.catalog

/**
 * The Bortle dark-sky scale, used only to tell the user roughly what their "faintest stars"
 * setting corresponds to, so they can match it to a Bortle class they know for their site.
 *
 * Source: J. E. Bortle, "The Bortle Dark-Sky Scale", Sky & Telescope, February 2001, as
 * tabulated by Wikipedia ("Bortle scale"): the naked-eye limiting magnitudes (NELM) of the
 * classes are 1: 7.6-8.0, 2: 7.1-7.5, 3: 6.6-7.0, 4: 6.1-6.5 (Wikipedia splits this into 4,
 * 6.3-6.5, and 4.5, 6.1-6.3), 5: 5.6-6.0, 6: 5.1-5.5, 7: 4.6-5.0, 8: 4.1-4.5, 9: 4.0 or fainter.
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

    /** The Bortle class (1-9) whose naked-eye limiting magnitude range contains [nelm]. */
    fun classForLimitingMagnitude(nelm: Double): Int =
        LOWER_NELM.firstOrNull { nelm >= it.second }?.first ?: 9
}
