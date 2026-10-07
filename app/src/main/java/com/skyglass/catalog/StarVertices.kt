package com.skyglass.catalog

import com.skyglass.astro.AstroTime
import com.skyglass.astro.Coordinates
import com.skyglass.astro.Precession

/**
 * Packs stars into an interleaved vertex buffer: per star, a unit vector in the equatorial frame
 * of date (proper motion and precession applied), point size in dp, and premultiplied colour.
 */
object StarVertices {

    const val FLOATS_PER_STAR = 7
    const val POSITION_OFFSET = 0
    const val SIZE_OFFSET = 3
    const val COLOR_OFFSET = 4

    fun build(stars: List<Star>, julianDate: Double): FloatArray {
        val centuries = AstroTime.julianCenturiesSinceJ2000(julianDate)
        val years = centuries * 100.0
        val precession = Precession.matrixFromJ2000(centuries)

        val out = FloatArray(stars.size * FLOATS_PER_STAR)
        stars.forEachIndexed { i, star ->
            val moved = Precession.applyProperMotion(
                star.j2000, star.pmRaArcsecPerYear, star.pmDecArcsecPerYear, years,
            )
            val v = precession * Coordinates.raDecToUnit(moved)
            val intensity = StarAppearance.intensity(star.vmag)
            val color = StarAppearance.color(star.bv)

            val base = i * FLOATS_PER_STAR
            out[base + POSITION_OFFSET] = v.x.toFloat()
            out[base + POSITION_OFFSET + 1] = v.y.toFloat()
            out[base + POSITION_OFFSET + 2] = v.z.toFloat()
            out[base + SIZE_OFFSET] = StarAppearance.pointSizeDp(star.vmag)
            out[base + COLOR_OFFSET] = color.r * intensity
            out[base + COLOR_OFFSET + 1] = color.g * intensity
            out[base + COLOR_OFFSET + 2] = color.b * intensity
        }
        return out
    }
}
