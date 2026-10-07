package com.skyglass.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the bundled `star_names.txt` and the designation columns of the real catalogue. */
class ProperNamesDataTest {

    private val data = BundledSkyData.load()
    private val byHr = data.allStars.associateBy { it.hr }
    private val namer = StarNamer(data.properNames, data.constellations)

    private fun describe(hr: Int) = namer.describe(checkNotNull(byHr[hr]) { "HR $hr not in catalogue" })

    @Test
    fun parsesDesignationColumnsOfRealRecords() {
        val sirius = checkNotNull(byHr[2491])
        assertEquals(9, sirius.flamsteed)
        assertEquals("Alp", sirius.bayer)
        assertEquals(null, sirius.bayerIndex)
        assertEquals("CMa", sirius.constellation)

        val acrux = checkNotNull(byHr[4730])
        assertEquals(null, acrux.flamsteed)
        assertEquals("Alp", acrux.bayer)
        assertEquals('1', acrux.bayerIndex)
        assertEquals("Cru", acrux.constellation)

        val alcor = checkNotNull(byHr[5062])
        assertEquals(80, alcor.flamsteed)
        assertEquals(null, alcor.bayer)
        assertEquals("UMa", alcor.constellation)

        val undesignated = checkNotNull(byHr[2])
        assertEquals(null, undesignated.flamsteed)
        assertEquals(null, undesignated.bayer)
        assertEquals(null, undesignated.constellation)
    }

    @Test
    fun everyProperNameBelongsToTheStarItWasGeneratedFrom() {
        for (entry in data.properNames) {
            val star = checkNotNull(byHr[entry.hr]) { "${entry.name}: HR ${entry.hr} not in catalogue" }
            val parts = entry.designation.split(' ')
            assertEquals("${entry.name}: ${entry.designation}", 2, parts.size)
            val (id, constellation) = parts
            assertEquals("${entry.name} constellation", constellation, star.constellation)
            val ids = listOfNotNull(star.bayer?.let { it + (star.bayerIndex ?: "") }, star.flamsteed?.toString())
            // The source may name a close pair by its letter alone, which resolves to component 1.
            assertTrue("${entry.name}: expected $id but catalogue has $ids", ids.any { it == id || it == id + "1" })
        }
    }

    @Test
    fun properNamesAreUnique_andEveryStarIsNamedOnce() {
        val names = data.properNames.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals(data.properNames.size, data.properNames.map { it.hr }.toSet().size)
        assertTrue("only ${data.properNames.size} names", data.properNames.size > 200)
    }

    @Test
    fun wellKnownStarsAreDescribedAsExpected() {
        assertEquals("Sirius", describe(2491).title)
        assertEquals("α Canis Majoris", describe(2491).designation)
        assertEquals("Polaris", describe(424).title)
        assertEquals("Betelgeuse", describe(2061).title)
        assertEquals("α Orionis", describe(2061).designation)
        assertEquals("Vega", describe(7001).title)
        assertEquals("Arcturus", describe(5340).title)
        assertEquals("Alcor", describe(5062).title)
        assertEquals("80 Ursae Majoris", describe(5062).designation)
        assertEquals("Acrux", describe(4730).title)
        assertEquals("α¹ Crucis", describe(4730).designation)
        assertEquals("Magnitude check", -1.46, describe(2491).vmag, 1e-9)
    }

    @Test
    fun unnamedStarsFallBackToDesignationThenHr() {
        // HR 2 has no name and no designation; HR 3 is 33 Piscium.
        assertEquals("HR 2", describe(2).title)
        assertEquals("33 Piscium", describe(3).title)
        assertNull(describe(3).designation)
    }
}
