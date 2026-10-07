package com.skyglass.catalog

import com.skyglass.astro.Equatorial

/**
 * A Yale Bright Star Catalogue entry.
 *
 * @property j2000 mean position, equinox and epoch J2000
 * @property pmRaArcsecPerYear projected proper motion in RA, cos(δ)·dα/dt
 * @property bv B−V colour index, or null when the catalogue has none
 */
data class Star(
    val hr: Int,
    val j2000: Equatorial,
    val vmag: Double,
    val bv: Double?,
    val pmRaArcsecPerYear: Double,
    val pmDecArcsecPerYear: Double,
)
