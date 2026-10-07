package com.skyglass.catalog

import com.skyglass.assertVecEquals
import com.skyglass.astro.AstroTime
import com.skyglass.astro.Coordinates
import com.skyglass.astro.Equatorial
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.acos

class StarVerticesTest {

    private val vega = Star(7001, Equatorial(Math.toRadians(279.2346), Math.toRadians(38.7836)), 0.03, 0.0, 0.201, 0.286)
    private val sirius = Star(2491, Equatorial(Math.toRadians(101.2871), Math.toRadians(-16.7161)), -1.46, 0.0, -0.553, -1.205)

    private fun position(data: FloatArray, index: Int): Vector3 {
        val base = index * StarVertices.FLOATS_PER_STAR + StarVertices.POSITION_OFFSET
        return Vector3(data[base].toDouble(), data[base + 1].toDouble(), data[base + 2].toDouble())
    }

    @Test
    fun packsSevenFloatsPerStar_withUnitPositions() {
        val data = StarVertices.build(listOf(vega, sirius), AstroTime.JD_J2000 + 9000)
        assertEquals(2 * StarVertices.FLOATS_PER_STAR, data.size)
        assertEquals(1.0, position(data, 0).length(), 1e-6)
        assertEquals(1.0, position(data, 1).length(), 1e-6)
        assertEquals(StarAppearance.pointSizeDp(-1.46), data[StarVertices.FLOATS_PER_STAR + StarVertices.SIZE_OFFSET])
    }

    @Test
    fun atJ2000_positionIsTheCataloguePosition() {
        val data = StarVertices.build(listOf(vega), AstroTime.JD_J2000)
        assertVecEquals(Coordinates.raDecToUnit(vega.j2000), position(data, 0), 1e-6)
    }

    @Test
    fun in2026_precessionHasMovedTheStar() {
        val jd2026 = AstroTime.julianDate(java.time.Instant.parse("2026-10-07T00:00:00Z").toEpochMilli())
        val data = StarVertices.build(listOf(sirius), jd2026)
        val movedDeg = Math.toDegrees(acos(Coordinates.raDecToUnit(sirius.j2000) dot position(data, 0)))
        // ~50.3″/yr of precession × 26.8 yr × cos(ecliptic latitude −39.6°) ≈ 0.29°, plus 36″ of proper motion.
        assertEquals(0.29, movedDeg, 0.03)
    }
}
