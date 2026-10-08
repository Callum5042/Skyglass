package com.skyglass.catalog

import kotlin.math.round

/**
 * The "faintest stars" setting: a limiting magnitude L, the faintest star the user wants to see.
 *
 * The fade is centred on L: a star of magnitude L - 0.5 or brighter is drawn at full strength, a
 * star of magnitude L at half strength, and a star of L + 0.5 or fainter not at all, along a
 * smoothstep so there is no pop as the slider moves. Because half strength is the midpoint,
 * "visible" means exactly `vmag <= L` ([isTappable]), which is what the label and the Bortle
 * class beside it claim. `StarShaders` evaluates the same expression,
 * `1 - smoothstep(L - FADE_WIDTH/2, L + FADE_WIDTH/2, vmag)`.
 *
 * Only stars are affected. The Sun, Moon and planets are far brighter than any limit on the
 * slider and the user wants to be able to find them to orient themselves, and the constellation
 * lines and names are navigation aids, not stars.
 */
object StarVisibility {

    /** Brightest limit offered: only the very brightest stars (Sirius, Canopus, Vega, ...) stay at full strength. */
    const val MIN_LIMIT = 1.0

    /**
     * Dimmest limit, and the default. The catalogue ends at magnitude 6.5 ([StarAppearance.MAGNITUDE_LIMIT]),
     * so half a magnitude beyond that every star is at full strength and the slider reads "all".
     * The range also reaches the dark-sky Bortle classes 1 to 3 (limiting magnitude 6.6 and up).
     */
    const val MAX_LIMIT = 7.0
    const val DEFAULT_LIMIT = MAX_LIMIT

    /** The slider moves in steps of this many magnitudes, so what the label shows is what is applied. */
    const val STEP = 0.1

    /** Magnitudes over which a star fades from full brightness to invisible, centred on the limit. */
    const val FADE_WIDTH = 1.0

    /** Slack for comparing a star's float magnitude with a limit that was a double. */
    private const val TOLERANCE = 1e-4

    /** [limit] kept within the slider's range; anything that is not a number gives the default. */
    fun clampLimit(limit: Double): Double = if (limit.isNaN()) DEFAULT_LIMIT else limit.coerceIn(MIN_LIMIT, MAX_LIMIT)

    /** [limit] clamped to the slider's range and rounded to a multiple of [STEP]. */
    fun snapLimit(limit: Double): Double = clampLimit(round(clampLimit(limit) / STEP) * STEP)

    /** True if [limit] is the "show everything" end of the slider. */
    fun isUnlimited(limit: Double): Boolean = limit >= MAX_LIMIT - TOLERANCE

    /** How much of its normal brightness a star of magnitude [vmag] keeps under [limit], from 0 to 1. */
    fun visibility(vmag: Double, limit: Double): Float {
        val t = ((vmag - (limit - FADE_WIDTH / 2)) / FADE_WIDTH).coerceIn(0.0, 1.0)
        val smooth = t * t * (3 - 2 * t)
        return (1 - smooth).toFloat()
    }

    /** True if a star of magnitude [vmag] is at least as bright as [limit], and so can be selected by a tap. */
    fun isTappable(vmag: Double, limit: Double): Boolean = vmag <= limit + TOLERANCE

    /** Whether a selected star (null if none) stays selected once the limit is [limit]. */
    fun keepsSelection(selectedVmag: Double?, limit: Double): Boolean = selectedVmag == null || isTappable(selectedVmag, limit)
}
