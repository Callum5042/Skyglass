package com.skyglass.catalog

import com.skyglass.astro.AstroTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos

class ConstellationDetailsTest {

    private val data = BundledSkyData.load()
    private val jd = AstroTime.JD_J2000
    private val positions = StarPositions.ofDate(data.nakedEye, jd)
    private val namer = StarNamer(data.properNames, data.constellations)
    private val lore = data.constellationLore.associateBy { it.abbreviation }

    private fun describe(abbreviation: String) =
        ConstellationDescriber.describe(abbreviation, data.constellations, data.nakedEye, positions, lore, namer)

    @Test
    fun orionIsDescribedWithItsBrightestStarsAndItsLore() {
        val orion = checkNotNull(describe("Ori"))
        assertEquals("Orion", orion.name)
        assertEquals("Orionis", orion.genitive)
        assertEquals(listOf("Orion"), orion.figureNames)
        assertEquals("the Hunter", orion.lore?.figure)
        assertEquals(ConstellationOrigin.ANCIENT, orion.lore?.origin)
        assertEquals(listOf("Rigel", "Betelgeuse"), orion.brightStars.take(2).map { it.title })
        assertEquals(ConstellationDescriber.BRIGHT_STAR_COUNT, orion.brightStars.size)
        assertTrue("brightest first", orion.brightStars.zipWithNext().all { (a, b) -> a.vmag <= b.vmag })
        assertEquals(data.constellations.single { it.abbreviation == "Ori" }.starHrs.size, orion.figureStars.size)
        assertNotNull(orion.chart)
    }

    @Test
    fun theDirectionIsInsideTheFigure() {
        for (abbreviation in data.constellations.map { it.abbreviation }.toSet()) {
            val description = checkNotNull(describe(abbreviation))
            assertEquals(1.0, description.direction.length(), 1e-9)
            val farthest = description.figureStars.maxOf { Math.toDegrees(acos((it.direction dot description.direction).coerceIn(-1.0, 1.0))) }
            assertTrue("$abbreviation: $farthest deg from the middle to a star", farthest < 60.0)
        }
    }

    @Test
    fun aStarBorrowedFromANeighbourIsDrawnButNotCounted() {
        val pegasus = checkNotNull(describe("Peg"))
        val alpheratz = data.nakedEye.single { it.hr == 15 } // alpha Andromedae, the corner of the Great Square
        assertTrue("Alpheratz is in Pegasus' figure", 15 in data.constellations.single { it.abbreviation == "Peg" }.starHrs)
        assertFalse("but is not one of Pegasus' brightest", pegasus.brightStars.any { it.hr == alpheratz.hr })
        assertEquals("Enif is Pegasus' brightest", "Enif", pegasus.brightStars.first().title)
    }

    @Test
    fun serpensIsOneConstellationWithTwoFigures() {
        val serpens = checkNotNull(describe("Ser"))
        assertEquals("Serpens", serpens.name)
        assertEquals(listOf("Serpens Caput", "Serpens Cauda"), serpens.figureNames)
        assertEquals("the Serpent", serpens.lore?.figure)
        assertEquals(
            data.constellations.filter { it.abbreviation == "Ser" }.flatMap { it.starHrs }.toSet().size,
            serpens.figureStars.size,
        )
    }

    @Test
    fun everyConstellationIsDescribedWithItsLore() {
        for (abbreviation in data.constellations.map { it.abbreviation }.toSet()) {
            val description = checkNotNull(describe(abbreviation)) { abbreviation }
            assertNotNull("$abbreviation: lore", description.lore)
            assertNotNull("$abbreviation: chart", description.chart)
            assertTrue("$abbreviation: bright stars", description.brightStars.isNotEmpty())
            assertTrue("$abbreviation: name", description.name.isNotBlank())
        }
    }

    @Test
    fun unknownConstellationIsNull() {
        assertNull(describe("Zzz"))
    }

    @Test
    fun missingLoreLeavesTheRestOfTheDescription() {
        val description = checkNotNull(ConstellationDescriber.describe("Cru", data.constellations, data.nakedEye, positions, emptyMap(), namer))
        assertNull(description.lore)
        assertEquals("Crux", description.name)
    }

    @Test
    fun theNamesOfMultiFigureConstellationsShareTheirCommonWords() {
        val figures = listOf(Constellation("Ser", "Serpens Caput", "Serpentis", emptyList()), Constellation("Ser", "Serpens Cauda", "Serpentis", emptyList()))
        assertEquals("Serpens", ConstellationNames.common(figures))
        assertEquals("Orion", ConstellationNames.common(listOf(Constellation("Ori", "Orion", "Orionis", emptyList()))))
    }
}
