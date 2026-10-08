package com.skyglass.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarDetailsTest {

    // --- distance ---

    @Test
    fun siriusParallaxGivesAbout8point7LightYears() {
        // BSC parallax 0.375″ (Hipparcos 0.379″): d = 3.2616 / 0.375 = 8.70 ly; the true value is 8.6.
        val distance = StarDistance.fromParallax(0.375)!!
        assertEquals(8.70, distance.lightYears, 0.01)
        assertEquals(8.7, distance.displayLightYears(), 1e-9)
        assertFalse(distance.rough)
    }

    @Test
    fun vegaParallaxGivesAboutTwentySevenLightYears() {
        // BSC 0.123″ → 26.5 ly (Hipparcos 25.0 ly), shown to two significant figures.
        val distance = StarDistance.fromParallax(0.123)!!
        assertEquals(26.5, distance.lightYears, 0.05)
        assertEquals(27.0, distance.displayLightYears(), 1e-9)
        assertFalse(distance.rough)
    }

    @Test
    fun displayRoundsToTwoSignificantFigures() {
        assertEquals(1.3, StarDistance(1.34, false).displayLightYears(), 1e-9)
        assertEquals(9.9, StarDistance(9.94, false).displayLightYears(), 1e-9)
        assertEquals(43.0, StarDistance(43.4, false).displayLightYears(), 1e-9)
        assertEquals(130.0, StarDistance(133.0, false).displayLightYears(), 1e-9)
        assertEquals(160.0, StarDistance(163.0, true).displayLightYears(), 1e-9)
        assertEquals(1200.0, StarDistance(1234.0, true).displayLightYears(), 1e-9)
    }

    @Test
    fun smallParallaxesAreRoughOrDropped() {
        // 0.05″ and up: 20 % or better. 0.02–0.05″: rough. Below 0.02″ the catalogue cannot tell.
        assertFalse(StarDistance.fromParallax(0.05)!!.rough)
        val rough = StarDistance.fromParallax(0.03)!!
        assertTrue(rough.rough)
        assertEquals(108.7, rough.lightYears, 0.1)
        assertTrue(StarDistance.fromParallax(0.02)!!.rough)

        assertNull("Betelgeuse, BSC parallax 0.005″", StarDistance.fromParallax(0.005))
        assertNull("Polaris, BSC parallax 0.007″", StarDistance.fromParallax(0.007))
        assertNull(StarDistance.fromParallax(0.0))
        assertNull(StarDistance.fromParallax(-0.01))
        assertNull(StarDistance.fromParallax(null))
    }

    @Test
    fun dynamicalParallaxIsAlwaysRough() {
        assertTrue(StarDistance.fromParallax(0.103, dynamical = true)!!.rough)
        assertFalse(StarDistance.fromParallax(0.103, dynamical = false)!!.rough)
        assertNull("still too small to use", StarDistance.fromParallax(0.009, dynamical = true))
    }

    @Test
    fun bundledCatalogue_flagsTheDynamicalParallaxes() {
        val stars = BundledSkyData.load().allStars
        assertEquals(388, stars.count { it.parallaxDynamical })
        assertTrue(stars.filter { it.parallaxDynamical }.all { it.parallaxArcsec != null })
    }

    // --- spectral class and colour ---

    @Test
    fun classLetter_readsTheHarvardClassFromCatalogueTypes() {
        assertEquals('A', SpectralType.classLetter("A1Vm"))
        assertEquals('F', SpectralType.classLetter("F7:Ib-II"))
        assertEquals('M', SpectralType.classLetter("M1-2Ia-Iab"))
        assertEquals('K', SpectralType.classLetter("K0IIIbCN-0.5"))
        assertEquals('G', SpectralType.classLetter("G5IIIe+G0III"))
        assertEquals('O', SpectralType.classLetter("O9.5Ib"))
    }

    @Test
    fun classLetter_skipsLowerCasePrefixes() {
        assertEquals('G', SpectralType.classLetter("gG9"))
        assertEquals('A', SpectralType.classLetter("kA2hA5mA7"))
        assertEquals('B', SpectralType.classLetter("sdB5"))
    }

    @Test
    fun classLetter_isNullForTypesWithoutOne() {
        assertNull("white dwarf", SpectralType.classLetter("DA3"))
        assertNull("carbon star", SpectralType.classLetter("C5,4"))
        assertNull("S-type", SpectralType.classLetter("S3,5e"))
        assertNull(SpectralType.classLetter(""))
        assertNull(SpectralType.classLetter(null))
    }

    @Test
    fun colorName_followsTheClass_andFallsBackToBV() {
        assertEquals(StarColorName.WHITE, SpectralType.colorName("A1Vm", 0.0)) // Sirius
        assertEquals(StarColorName.BLUE_WHITE, SpectralType.colorName("B8Ia", -0.03)) // Rigel
        assertEquals(StarColorName.YELLOW, SpectralType.colorName("G2V", 0.65)) // the Sun
        assertEquals(StarColorName.ORANGE, SpectralType.colorName("K1.5III", 1.23)) // Arcturus
        assertEquals(StarColorName.RED, SpectralType.colorName("M1-2Ia-Iab", 1.85)) // Betelgeuse
        assertEquals(StarColorName.BLUE, SpectralType.colorName("O9.5Ib", -0.2))
        assertEquals(StarColorName.YELLOW_WHITE, SpectralType.colorName("F5IV-V", 0.42)) // Procyon

        // The class wins over a B−V that disagrees; without a class B−V decides.
        assertEquals(StarColorName.WHITE, SpectralType.colorName("A0V", 1.5))
        assertEquals(StarColorName.ORANGE, SpectralType.colorName(null, 1.0))
        assertEquals(StarColorName.ORANGE, SpectralType.colorName("C5,4", 1.0))
        assertNull(SpectralType.colorName(null, null))
        assertNull(SpectralType.colorName("DA3", null))
    }

    @Test
    fun colorName_fromBV_coversTheWholeRangeInOrder() {
        val names = listOf(-0.4, -0.1, 0.1, 0.4, 0.7, 1.0, 1.6).map { SpectralType.colorName(null, it) }
        assertEquals(StarColorName.entries, names)
    }

    // --- against the real catalogue ---

    @Test
    fun bundledCatalogue_famousStars() {
        val stars = BundledSkyData.load().allStars.associateBy { it.hr }

        val sirius = stars.getValue(2491)
        assertEquals("A1Vm", sirius.spectralType)
        assertEquals(0.375, sirius.parallaxArcsec!!, 1e-9)

        val betelgeuse = stars.getValue(2061)
        assertEquals(StarColorName.RED, SpectralType.colorName(betelgeuse.spectralType, betelgeuse.bv))
        assertNull("too far for this catalogue", StarDistance.fromParallax(betelgeuse.parallaxArcsec))

        val alphaCentauriA = stars.getValue(5459)
        assertEquals(4.3, StarDistance.fromParallax(alphaCentauriA.parallaxArcsec)!!.displayLightYears(), 0.1)

        val rigel = stars.getValue(1713)
        assertEquals(StarColorName.BLUE_WHITE, SpectralType.colorName(rigel.spectralType, rigel.bv))
    }

    @Test
    fun bundledCatalogue_mostNakedEyeStarsHaveASpectralType() {
        val nakedEye = BundledSkyData.load().nakedEye
        val withType = nakedEye.count { it.spectralType != null }
        assertTrue("$withType of ${nakedEye.size}", withType > nakedEye.size * 0.95)
        val withClass = nakedEye.count { SpectralType.classLetter(it.spectralType) != null }
        assertTrue("$withClass of ${nakedEye.size}", withClass > nakedEye.size * 0.9)
        assertNotNull(nakedEye.first { it.hr == 7001 }.parallaxArcsec) // Vega
    }
}
