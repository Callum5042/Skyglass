package com.skyglass.catalog

/**
 * Packs stars into an interleaved vertex buffer: per star, a unit vector in the equatorial frame
 * of date (proper motion and precession applied), point size in dp, premultiplied colour, and the
 * visual magnitude, which the shader compares with the "faintest stars" limit (see [StarVisibility]).
 */
object StarVertices {

    const val FLOATS_PER_STAR = 8
    const val POSITION_OFFSET = 0
    const val SIZE_OFFSET = 3
    const val COLOR_OFFSET = 4
    const val VMAG_OFFSET = 7

    fun build(stars: List<Star>, julianDate: Double): FloatArray {
        val positions = StarPositions.ofDate(stars, julianDate)

        val out = FloatArray(stars.size * FLOATS_PER_STAR)
        stars.forEachIndexed { i, star ->
            val v = positions[i]
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
            out[base + VMAG_OFFSET] = star.vmag.toFloat()
        }
        return out
    }
}
