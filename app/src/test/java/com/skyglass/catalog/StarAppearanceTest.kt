package com.skyglass.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarAppearanceTest {

    @Test
    fun pointSize_growsAsStarsGetBrighter() {
        val sizes = (65 downTo -15).map { StarAppearance.pointSizeDp(it / 10.0) }
        sizes.zipWithNext().forEach { (fainter, brighter) -> assertTrue(brighter > fainter) }
    }

    @Test
    fun pointSize_isClampedBeyondTheCatalogueRange() {
        assertEquals(StarAppearance.pointSizeDp(6.5), StarAppearance.pointSizeDp(9.0))
        assertEquals(StarAppearance.pointSizeDp(-1.5), StarAppearance.pointSizeDp(-4.0))
        assertTrue(StarAppearance.pointSizeDp(6.5) >= 1f)
    }

    @Test
    fun intensity_isFullForBrightStarsAndDimForFaintOnes() {
        assertEquals(1f, StarAppearance.intensity(-1.46))
        assertEquals(1f, StarAppearance.intensity(1.0))
        val faint = StarAppearance.intensity(6.5)
        assertTrue(faint > 0f && faint < 0.5f)
        assertTrue(StarAppearance.intensity(3.0) > StarAppearance.intensity(5.0))
    }

    @Test
    fun color_redForCoolStars_blueForHotStars_whiteWhenUnknown() {
        val betelgeuseLike = StarAppearance.color(1.5)
        assertTrue(betelgeuseLike.r > betelgeuseLike.b)

        val rigelLike = StarAppearance.color(-0.3)
        assertTrue(rigelLike.b > rigelLike.r)

        assertEquals(Rgb(1f, 1f, 1f), StarAppearance.color(null))
    }

    @Test
    fun color_isClampedAndInRange() {
        for (bv in listOf(-2.0, -0.4, 0.0, 0.65, 1.2, 2.0, 5.0)) {
            val c = StarAppearance.color(bv)
            for (channel in listOf(c.r, c.g, c.b)) assertTrue("$bv → $c", channel in 0f..1f)
        }
        assertEquals(StarAppearance.color(2.0), StarAppearance.color(5.0))
        assertEquals(StarAppearance.color(-0.4), StarAppearance.color(-2.0))
    }
}
