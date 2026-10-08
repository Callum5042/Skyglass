package com.skyglass.catalog

import com.skyglass.dmsToDeg
import com.skyglass.hmsToDeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BscParserTest {

    // Verbatim records from the CDS V/50 `catalog` file. Trailing blanks are trimmed there.
    private val polaris =
        " 424  1Alp UMiBD+88    8   8890   308 907    1477  Alp UMi  012233.7+884626023148.7+891551123.28 26.46 2.02  +0.60 +0.38 +0.31   F7:Ib-II          v+0.038-0.015 +.007-017SBO    17  6.8  18.4AB   5*"
    private val sirius =
        "2491  9Alp CMaBD-16 1591  48915151881 257I   5423           064044.6-163444064508.9-164258227.22-08.88-1.46   0.00 -0.05 -0.03   A1Vm               -0.553-1.205 +.375-008SBO    13 10.3  11.2AB   4*"
    private val hr939Dynamical =
        " 939   The HyiCP-72  219  19400255945 113  W                030202.8-721735030215.4-715409289.93-41.76 5.53  -0.14 -0.51         B3V+A0IV           +0.025+0.014D+.103+012       44  0.0   0.1      *"
    private val hr101 =
        " 101 10    CetBD-00   63   2273128760              Var?     002129.6-003612002637.4-000259109.50-62.27 6.19  +0.90 +0.55         G8III              +0.073-0.001      -023V                         *"
    private val hr2ShortRecord =
        "   2          BD-01 4525      6128569                       235956.2-010330000503.8-003011 98.33-61.14 6.29  +1.10 +1.02        gG9                 +0.045-0.060      +014V"
    private val nova1572 =
        "  92 NOVA 1572                                     B Cas                                                                                                                                            *"

    private val arcsecDeg = 1.0 / 3600.0

    private fun raDeg(star: Star) = Math.toDegrees(star.j2000.raRad)
    private fun decDeg(star: Star) = Math.toDegrees(star.j2000.decRad)

    @Test
    fun parsesPolaris() {
        val star = parse(polaris)
        assertEquals(424, star.hr)
        assertEquals(hmsToDeg(2, 31, 48.7), raDeg(star), 1e-9)
        assertEquals(dmsToDeg(+1, 89, 15, 51.0), decDeg(star), 1e-9)
        assertEquals(2.02, star.vmag, 1e-9)
        assertEquals(0.60, star.bv!!, 1e-9)
        assertEquals(0.038, star.pmRaArcsecPerYear, 1e-9)
        assertEquals(-0.015, star.pmDecArcsecPerYear, 1e-9)
        assertEquals(1, star.flamsteed)
        assertEquals("Alp", star.bayer)
        assertEquals(null, star.bayerIndex)
        assertEquals("UMi", star.constellation)
    }

    @Test
    fun parsesRecordWithFlamsteedNumberOnly() {
        val star = parse(hr101)
        assertEquals(10, star.flamsteed)
        assertEquals(null, star.bayer)
        assertEquals("Cet", star.constellation)
    }

    @Test
    fun parsesRecordWithoutAnyDesignation() {
        val star = parse(hr2ShortRecord)
        assertEquals(null, star.flamsteed)
        assertEquals(null, star.bayer)
        assertEquals(null, star.constellation)
    }

    @Test
    fun parsesSirius_negativeMagnitudeAndDeclination() {
        val star = parse(sirius)
        assertEquals(2491, star.hr)
        assertEquals(hmsToDeg(6, 45, 8.9), raDeg(star), 1e-9)
        assertEquals(dmsToDeg(-1, 16, 42, 58.0), decDeg(star), 1e-9)
        assertEquals(-1.46, star.vmag, 1e-9)
        assertEquals(0.0, star.bv!!, 1e-9)
        assertEquals(-0.553, star.pmRaArcsecPerYear, 1e-9)
        assertEquals(-1.205, star.pmDecArcsecPerYear, 1e-9)
    }

    @Test
    fun parsesSpectralTypeAndParallax() {
        val siriusStar = parse(sirius)
        assertEquals("A1Vm", siriusStar.spectralType)
        assertEquals(0.375, siriusStar.parallaxArcsec!!, 1e-9)

        val polarisStar = parse(polaris)
        assertEquals("F7:Ib-II", polarisStar.spectralType) // the 'v' flag in column 148 is not part of it
        assertEquals(0.007, polarisStar.parallaxArcsec!!, 1e-9)
    }

    @Test
    fun missingParallaxIsNull_andLowercasePrefixStaysInTheType() {
        val star101 = parse(hr101)
        assertEquals("G8III", star101.spectralType)
        assertNull(star101.parallaxArcsec)

        val shortStar = parse(hr2ShortRecord)
        assertEquals("gG9", shortStar.spectralType)
        assertNull(shortStar.parallaxArcsec)
    }

    @Test
    fun flagsDynamicalParallaxes() {
        val dynamical = parse(hr939Dynamical)
        assertEquals(0.103, dynamical.parallaxArcsec!!, 1e-9)
        assertTrue(dynamical.parallaxDynamical)
        assertEquals("B3V+A0IV", dynamical.spectralType)

        assertTrue("trigonometric", !parse(sirius).parallaxDynamical)
        assertTrue("no parallax at all", !parse(hr101).parallaxDynamical)
    }

    @Test
    fun keepsSignOfMinusZeroDegreeDeclination() {
        val star = parse(hr101)
        assertEquals(dmsToDeg(-1, 0, 2, 59.0), decDeg(star), 1e-9)
        assertTrue(decDeg(star) < 0)
    }

    @Test
    fun parsesRecordWithTrimmedTrailingBlanks() {
        val star = parse(hr2ShortRecord)
        assertEquals(2, star.hr)
        assertEquals(dmsToDeg(-1, 0, 30, 11.0), decDeg(star), arcsecDeg)
        assertEquals(6.29, star.vmag, 1e-9)
        assertEquals(1.10, star.bv!!, 1e-9)
        assertEquals(0.045, star.pmRaArcsecPerYear, 1e-9)
    }

    @Test
    fun skipsEntriesWithoutPosition() {
        assertNull(BscParser.parseLine(nova1572))
        assertNull(BscParser.parseLine(""))
    }

    @Test
    fun parsesWholeBundledCatalogue() {
        // Unit tests run with the module directory as the working directory.
        val file = File("src/main/assets/${StarCatalog.ASSET_NAME}")
        val stars = file.useLines { BscParser.parse(it) }

        assertEquals("9110 records minus 14 without positions", 9096, stars.size)
        assertEquals(2491, stars.minBy { it.vmag }.hr) // Sirius
        val nakedEye = stars.count { it.vmag <= StarAppearance.MAGNITUDE_LIMIT }
        assertTrue("naked-eye count $nakedEye", nakedEye in 8300..8500)
        assertTrue(stars.all { it.j2000.decRad in -Math.PI / 2..Math.PI / 2 })
        assertTrue(stars.all { it.j2000.raRad in 0.0..2 * Math.PI })
    }

    private fun parse(line: String): Star = checkNotNull(BscParser.parseLine(line)) { "failed to parse: $line" }
}
