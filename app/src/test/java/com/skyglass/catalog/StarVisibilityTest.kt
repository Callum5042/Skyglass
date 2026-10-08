package com.skyglass.catalog

import com.skyglass.render.StarShaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarVisibilityTest {

    @Test
    fun theFadeIsCentredOnTheLimit() {
        assertEquals("half a magnitude brighter: full", 1f, StarVisibility.visibility(2.5, 3.0))
        assertEquals("at the limit: half", 0.5f, StarVisibility.visibility(3.0, 3.0), 1e-6f)
        assertEquals("half a magnitude fainter: gone", 0f, StarVisibility.visibility(3.5, 3.0))
        assertEquals(1f, StarVisibility.visibility(-1.46, 3.0))
        assertEquals(0f, StarVisibility.visibility(6.0, 3.0))
    }

    @Test
    fun theFadeIsASmoothstep() {
        // smoothstep(0.25) = 0.15625, so a quarter of the way into the fade 1 - 0.15625 is left.
        assertEquals(0.84375f, StarVisibility.visibility(2.75, 3.0), 1e-6f)
        assertEquals(0.15625f, StarVisibility.visibility(3.25, 3.0), 1e-6f)
    }

    @Test
    fun theFadeIsMonotonic_fainterStarsAreNeverBrighter() {
        val values = (0..100).map { StarVisibility.visibility(2.0 + it * 0.02, 3.0) }
        values.zipWithNext().forEach { (brighter, fainter) -> assertTrue(fainter <= brighter) }
    }

    @Test
    fun defaultLimit_showsEveryStarInTheCatalogueAtFullBrightness() {
        assertEquals(StarVisibility.MAX_LIMIT, StarVisibility.DEFAULT_LIMIT, 0.0)
        assertEquals(1f, StarVisibility.visibility(StarAppearance.MAGNITUDE_LIMIT, StarVisibility.DEFAULT_LIMIT))
        assertTrue(StarVisibility.isUnlimited(StarVisibility.DEFAULT_LIMIT))
        assertFalse(StarVisibility.isUnlimited(6.9))
    }

    @Test
    fun tappable_exactlyWhenAtLeastAsBrightAsTheLimit() {
        assertTrue(StarVisibility.isTappable(3.0, 3.0))
        assertTrue(StarVisibility.isTappable(-1.46, 3.0))
        assertFalse(StarVisibility.isTappable(3.01, 3.0))
        assertFalse(StarVisibility.isTappable(4.7, 4.0))
        // A catalogue magnitude is a float: 4.7f is 4.69999981, 4.8f is 4.80000019.
        assertTrue(StarVisibility.isTappable(4.8f.toDouble(), 4.8))
        assertTrue(StarVisibility.isTappable(4.7f.toDouble(), 4.7))
        assertFalse(StarVisibility.isTappable(4.8f.toDouble(), 4.7))
    }

    @Test
    fun keepsSelection_dropsOnlyAStarThatIsNowTooFaint() {
        assertTrue(StarVisibility.keepsSelection(null, 1.0))
        assertTrue(StarVisibility.keepsSelection(2.0, 2.0))
        assertTrue(StarVisibility.keepsSelection(1.5, 2.0))
        assertFalse(StarVisibility.keepsSelection(2.1, 2.0))
    }

    @Test
    fun clampLimit_keepsTheSliderRange_andRepairsGarbage() {
        assertEquals(StarVisibility.MIN_LIMIT, StarVisibility.clampLimit(-3.0), 0.0)
        assertEquals(StarVisibility.MAX_LIMIT, StarVisibility.clampLimit(12.0), 0.0)
        assertEquals(4.2, StarVisibility.clampLimit(4.2), 0.0)
        assertEquals(StarVisibility.DEFAULT_LIMIT, StarVisibility.clampLimit(Double.NaN), 0.0)
        assertEquals(StarVisibility.MAX_LIMIT, StarVisibility.clampLimit(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test
    fun snapLimit_roundsToTenthsWithinTheRange() {
        assertEquals(6.1, StarVisibility.snapLimit(6.08), 1e-12)
        assertEquals(4.7, StarVisibility.snapLimit(4.699999809), 1e-12)
        assertEquals(6.5, StarVisibility.snapLimit(6.46), 1e-12)
        assertEquals(StarVisibility.MAX_LIMIT, StarVisibility.snapLimit(6.97), 0.0)
        assertEquals(StarVisibility.MIN_LIMIT, StarVisibility.snapLimit(0.2), 0.0)
        assertEquals(StarVisibility.DEFAULT_LIMIT, StarVisibility.snapLimit(Double.NaN), 0.0)
    }

    @Test
    fun snappedValuesAreUnaffectedBySnappingAgain() {
        for (i in 10..70) {
            val v = StarVisibility.snapLimit(i / 10.0)
            assertEquals(v, StarVisibility.snapLimit(v), 0.0)
        }
    }

    @Test
    fun shaderUsesTheSameCentredFade() {
        // The GLSL expression must stay in step with visibility(): same edges.
        assertTrue(StarShaders.VERTEX.contains("smoothstep(uLimitMag - 0.5, uLimitMag + 0.5, aVmag)"))
    }
}
