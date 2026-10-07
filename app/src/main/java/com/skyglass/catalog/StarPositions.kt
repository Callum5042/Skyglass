package com.skyglass.catalog

import com.skyglass.astro.AstroTime
import com.skyglass.astro.Coordinates
import com.skyglass.astro.Precession
import com.skyglass.math.Vector3

/** Star directions in the equatorial frame of date, shared by the star and constellation buffers. */
object StarPositions {

    /** Unit vectors, in input order, with proper motion and precession applied for [julianDate]. */
    fun ofDate(stars: List<Star>, julianDate: Double): List<Vector3> {
        val centuries = AstroTime.julianCenturiesSinceJ2000(julianDate)
        val years = centuries * 100.0
        val precession = Precession.matrixFromJ2000(centuries)
        return stars.map { star ->
            val moved = Precession.applyProperMotion(
                star.j2000, star.pmRaArcsecPerYear, star.pmDecArcsecPerYear, years,
            )
            precession * Coordinates.raDecToUnit(moved)
        }
    }
}
