package com.skyglass.catalog

import kotlin.math.pow

data class Rgb(val r: Float, val g: Float, val b: Float)

/** Maps catalogue photometry to how a star is drawn. All tuning constants live here. */
object StarAppearance {

    const val MAGNITUDE_LIMIT = 6.5

    /** Sirius, the brightest star in the catalogue. */
    private const val BRIGHTEST_MAGNITUDE = -1.5

    private const val FAINTEST_SIZE_DP = 1.5f
    private const val BRIGHTEST_SIZE_DP = 12f

    private const val MIN_INTENSITY = 0.3
    private const val FULL_INTENSITY_MAGNITUDE = 1.0

    /** Fraction of the blackbody tint kept; the naked eye sees most stars as near-white. */
    private const val COLOR_SATURATION = 0.6f

    /** Point-sprite diameter in dp, growing geometrically as stars get brighter. */
    fun pointSizeDp(vmag: Double): Float {
        val fraction = ((MAGNITUDE_LIMIT - vmag) / (MAGNITUDE_LIMIT - BRIGHTEST_MAGNITUDE)).coerceIn(0.0, 1.0)
        return FAINTEST_SIZE_DP * (BRIGHTEST_SIZE_DP / FAINTEST_SIZE_DP).toDouble().pow(fraction).toFloat()
    }

    /** Peak brightness of the sprite in [MIN_INTENSITY, 1]. */
    fun intensity(vmag: Double): Float {
        val fraction = (MAGNITUDE_LIMIT - vmag) / (MAGNITUDE_LIMIT - FULL_INTENSITY_MAGNITUDE)
        return (MIN_INTENSITY + (1 - MIN_INTENSITY) * fraction).coerceIn(MIN_INTENSITY, 1.0).toFloat()
    }

    /** Approximate perceived colour from the B−V index (blackbody colours, partly desaturated). */
    fun color(bv: Double?): Rgb {
        if (bv == null) return WHITE
        val clamped = bv.coerceIn(BV_TABLE.first().first, BV_TABLE.last().first)
        val upper = BV_TABLE.indexOfFirst { it.first >= clamped }.coerceAtLeast(1)
        val (bv0, c0) = BV_TABLE[upper - 1]
        val (bv1, c1) = BV_TABLE[upper]
        val t = ((clamped - bv0) / (bv1 - bv0)).toFloat()
        return Rgb(
            desaturate(c0.r + (c1.r - c0.r) * t),
            desaturate(c0.g + (c1.g - c0.g) * t),
            desaturate(c0.b + (c1.b - c0.b) * t),
        )
    }

    private fun desaturate(channel: Float): Float = 1f - COLOR_SATURATION * (1f - channel)

    private val WHITE = Rgb(1f, 1f, 1f)

    private val BV_TABLE = listOf(
        -0.4 to Rgb(0.61f, 0.69f, 1.00f),
        0.0 to Rgb(0.79f, 0.84f, 1.00f),
        0.4 to Rgb(1.00f, 0.96f, 0.93f),
        0.8 to Rgb(1.00f, 0.90f, 0.79f),
        1.2 to Rgb(1.00f, 0.82f, 0.63f),
        1.6 to Rgb(1.00f, 0.73f, 0.47f),
        2.0 to Rgb(1.00f, 0.65f, 0.35f),
    )
}
