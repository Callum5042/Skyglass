package com.skyglass.catalog

import com.skyglass.astro.Equatorial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StarNamerTest {

    private val genitives = mapOf("CMa" to "Canis Majoris", "Cyg" to "Cygni", "Cen" to "Centauri", "UMa" to "Ursae Majoris")
    private val namer = StarNamer(mapOf(2491 to "Sirius", 5062 to "Alcor"), genitives)

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
    fun everyGreekAbbreviationHasASymbol() {
        val abbreviations = "Alp Bet Gam Del Eps Zet Eta The Iot Kap Lam Mu Nu Xi Omi Pi Rho Sig Tau Ups Phi Chi Psi Ome".split(" ")
        assertEquals(24, abbreviations.map(StarNamer::bayerSymbol).toSet().size)
        assertEquals(emptyList<String>(), abbreviations.filter { StarNamer.bayerSymbol(it) == it })
    }
}
