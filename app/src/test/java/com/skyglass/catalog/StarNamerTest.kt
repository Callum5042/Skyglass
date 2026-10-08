package com.skyglass.catalog

import com.skyglass.astro.Equatorial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarNamerTest {

    private val genitives = mapOf("CMa" to "Canis Majoris", "Cyg" to "Cygni", "Cen" to "Centauri", "UMa" to "Ursae Majoris")
    private val namer = StarNamer(mapOf(2491 to "Sirius", 5062 to "Alcor"), genitives)

    private val detailedNamer = StarNamer(
        emptyMap(),
        genitives,
        constellationNames = mapOf("CMa" to "Canis Major", "Cyg" to "Cygnus", "UMa" to "Ursa Major"),
        figureConstellations = mapOf(1234 to "Cyg", 77 to "UMa"),
    )

    private fun star(
        hr: Int,
        vmag: Double = 3.0,
        flamsteed: Int? = null,
        bayer: String? = null,
        bayerIndex: Char? = null,
        constellation: String? = null,
    ) = Star(hr, Equatorial(0.0, 0.0), vmag, null, 0.0, 0.0, flamsteed, bayer, bayerIndex, constellation)

    @Test
    fun properNameComesFirst_withDesignationAsDetail() {
        val d = namer.describe(star(2491, vmag = -1.46, flamsteed = 9, bayer = "Alp", constellation = "CMa"))
        assertEquals("Sirius", d.title)
        assertEquals("α Canis Majoris", d.designation)
        assertEquals(2491, d.hr)
        assertEquals(-1.46, d.vmag, 0.0)
    }

    @Test
    fun properNameWithOnlyFlamsteedDesignation() {
        val d = namer.describe(star(5062, flamsteed = 80, constellation = "UMa"))
        assertEquals("Alcor", d.title)
        assertEquals("80 Ursae Majoris", d.designation)
    }

    @Test
    fun bayerDesignationWithConstellation() {
        val d = namer.describe(star(1, bayer = "Gam", flamsteed = 53, constellation = "CMa"))
        assertEquals("γ Canis Majoris", d.title)
        assertNull("nothing more to say than the title", d.designation)
    }

    @Test
    fun bayerSuperscriptIsKept() {
        assertEquals("α¹ Centauri", namer.describe(star(1, bayer = "Alp", bayerIndex = '1', constellation = "Cen")).title)
        assertEquals("ο² Centauri", namer.describe(star(2, bayer = "Omi", bayerIndex = '2', constellation = "Cen")).title)
    }

    @Test
    fun flamsteedDesignationWhenNoBayerLetter() {
        assertEquals("61 Cygni", namer.describe(star(8085, flamsteed = 61, constellation = "Cyg")).title)
    }

    @Test
    fun fallsBackToHrNumber() {
        val d = namer.describe(star(1234))
        assertEquals("HR 1234", d.title)
        assertNull(d.designation)
        // A constellation without Bayer or Flamsteed number does not make a designation either.
        assertEquals("HR 77", namer.describe(star(77, constellation = "Cyg")).title)
    }

    @Test
    fun unknownConstellationAbbreviationIsShownAsIs() {
        assertEquals("β Xyz", namer.describe(star(3, bayer = "Bet", constellation = "Xyz")).title)
    }

    @Test
    fun buildsGenitiveTableFromConstellations() {
        val figures = listOf(
            Constellation("Ser", "Serpens Caput", "Serpentis", emptyList()),
            Constellation("Ser", "Serpens Cauda", "Serpentis", emptyList()),
        )
        val n = StarNamer(emptyList<ProperName>(), figures)
        assertEquals("α Serpentis", n.designation(star(1, bayer = "Alp", constellation = "Ser")))
    }


    @Test
    fun describeCarriesTheCatalogueDetails() {
        val star = Star(
            2491, Equatorial(0.0, 0.0), -1.46, 0.0, 0.0, 0.0, 9, "Alp", null, "CMa",
            spectralType = "A1Vm", parallaxArcsec = 0.375,
        )
        val d = detailedNamer.describe(star)
        assertEquals("Canis Major", d.constellation)
        assertEquals("A1Vm", d.spectralType)
        assertEquals(StarColorName.WHITE, d.colorName)
        assertEquals(0.0, d.bv!!, 0.0)
        assertEquals(8.7, d.distance!!.displayLightYears(), 1e-9)
    }

    @Test
    fun starWithoutDesignationGetsItsConstellationFromTheFigureItBelongsTo() {
        val d = detailedNamer.describe(star(1234))
        assertEquals("Cygnus", d.constellation)
        assertNull(d.spectralType)
        assertNull(d.colorName)
        assertNull(d.distance)
    }

    @Test
    fun catalogueConstellationWinsOverTheFigure() {
        // HR 1234 is in Cygnus's figure, but a catalogue designation says otherwise.
        assertEquals("Canis Major", detailedNamer.describe(star(1234, bayer = "Bet", constellation = "CMa")).constellation)
    }

    @Test
    fun starSharedByTwoFiguresHasNoFigureConstellation() {
        val figures = listOf(
            Constellation("Cyg", "Cygnus", "Cygni", listOf(listOf(5, 9))),
            Constellation("UMa", "Ursa Major", "Ursae Majoris", listOf(listOf(9, 10))),
        )
        val n = StarNamer(emptyList<ProperName>(), figures)
        assertNull("on the border between the two", n.describe(star(9)).constellation)
        assertEquals("Cygnus", n.describe(star(5)).constellation)
        assertEquals("Ursa Major", n.describe(star(10)).constellation)
    }

    @Test
    fun unknownStarHasNoConstellation() {
        assertNull(detailedNamer.describe(star(5555)).constellation)
    }

    @Test
    fun serpensFiguresShareTheConstellationName() {
        val figures = listOf(
            Constellation("Ser", "Serpens Caput", "Serpentis", listOf(listOf(1, 2))),
            Constellation("Ser", "Serpens Cauda", "Serpentis", listOf(listOf(3, 4))),
            Constellation("Cyg", "Cygnus", "Cygni", listOf(listOf(5, 6))),
        )
        val n = StarNamer(emptyList<ProperName>(), figures)
        assertEquals("Serpens", n.describe(star(3)).constellation)
        assertEquals("Serpens", n.describe(star(1, bayer = "Alp", constellation = "Ser")).constellation)
        assertEquals("Cygnus", n.describe(star(5)).constellation)
    }

    @Test
    fun bundledData_namesTheConstellationOfTheStarsATapCanReach() {
        val data = BundledSkyData.load()
        val bundled = StarNamer(data.properNames, data.constellations)
        val byHr = data.allStars.associateBy { it.hr }

        assertEquals("Canis Major", bundled.describe(byHr.getValue(2491)).constellation) // Sirius
        assertEquals("Orion", bundled.describe(byHr.getValue(2061)).constellation) // Betelgeuse
        assertEquals("Serpens", bundled.describe(byHr.getValue(5854)).constellation) // α Serpentis, Caput figure

        val named = data.nakedEye.count { bundled.describe(it).constellation != null }
        // Designated stars (~3,100) plus the figures cover about a third of them; faint undesignated ones have no boundary table to use.
        assertTrue("$named of ${data.nakedEye.size}", named in 3000..4000)
    }
    @Test
    fun everyGreekAbbreviationHasASymbol() {
        val abbreviations = "Alp Bet Gam Del Eps Zet Eta The Iot Kap Lam Mu Nu Xi Omi Pi Rho Sig Tau Ups Phi Chi Psi Ome".split(" ")
        assertEquals(24, abbreviations.map(StarNamer::bayerSymbol).toSet().size)
        assertEquals(emptyList<String>(), abbreviations.filter { StarNamer.bayerSymbol(it) == it })
    }
}
