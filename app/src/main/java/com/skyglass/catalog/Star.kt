package com.skyglass.catalog

import com.skyglass.astro.Equatorial

/**
 * A Yale Bright Star Catalogue entry.
 *
 * @property j2000 mean position, equinox and epoch J2000
 * @property pmRaArcsecPerYear projected proper motion in RA, cos(δ)·dα/dt
 * @property bv B−V colour index, or null when the catalogue has none
 * @property flamsteed Flamsteed number, if the catalogue has one
 * @property bayer Bayer letter as the catalogue abbreviates it ("Alp", "Bet"...), if any
 * @property bayerIndex Bayer superscript digit distinguishing e.g. α¹ and α² Centauri, if any
 * @property constellation IAU abbreviation ("CMa"); present for stars with a Bayer or Flamsteed designation
 */
data class Star(
    val hr: Int,
    val j2000: Equatorial,
    val vmag: Double,
    val bv: Double?,
    val pmRaArcsecPerYear: Double,
    val pmDecArcsecPerYear: Double,
    val flamsteed: Int? = null,
    val bayer: String? = null,
    val bayerIndex: Char? = null,
    val constellation: String? = null,
)
