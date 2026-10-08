package com.skyglass.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstellationLoreTest {

    @Test
    fun parsesEntriesAndSkipsCommentsAndBlankLines() {
        val lore = ConstellationLoreParser.parse(
            sequenceOf(
                "# a comment",
                "",
                "Ori|the Hunter|ancient|Greek|A giant hunter.",
                "Ant|the Air Pump|MODERN|Lacaille, 1750s|No myth here.",
            ),
        )
        assertEquals(2, lore.size)
        assertEquals(ConstellationLore("Ori", "the Hunter", ConstellationOrigin.ANCIENT, "Greek", "A giant hunter."), lore[0])
        assertEquals("origin is not case sensitive", ConstellationOrigin.MODERN, lore[1].origin)
        assertEquals("Lacaille, 1750s", lore[1].tradition)
    }

    @Test
    fun rejectsMalformedLines() {
        fun bad(line: String) = assertThrows(line, IllegalArgumentException::class.java) { ConstellationLoreParser.parse(sequenceOf(line)) }
        bad("Ori|the Hunter|ancient|Greek") // too few fields
        bad("Ori|the Hunter|ancient|Greek|Story|extra") // too many
        bad("Ori|the Hunter|medieval|Greek|Story") // unknown origin
        bad("Ori||ancient|Greek|Story") // empty field
        bad("Ori|the Hunter|ancient|Greek|  ") // blank story
    }

    @Test
    fun rejectsTwoEntriesForOneConstellation() {
        assertThrows(IllegalArgumentException::class.java) {
            ConstellationLoreParser.parse(sequenceOf("Ori|a|ancient|b|c", "Ori|d|modern|e|f"))
        }
    }

    // ---- the real, bundled data ----

    private val data = BundledSkyData.load()
    private val lore = data.constellationLore

    /** Ptolemy's 48 (Argo Navis counted as one) with Argo's three parts, by IAU abbreviation. */
    private val ancient = setOf(
        "And", "Aqr", "Aql", "Ara", "Ari", "Aur", "Boo", "Cnc", "CMa", "CMi", "Cap", "Cas", "Cen", "Cep", "Cet",
        "CrA", "CrB", "Crv", "Crt", "Cyg", "Del", "Dra", "Equ", "Eri", "Gem", "Her", "Hya", "Leo", "Lep", "Lib",
        "Lup", "Lyr", "Oph", "Ori", "Peg", "Per", "Psc", "PsA", "Sge", "Sgr", "Sco", "Ser", "Tau", "Tri", "UMa",
        "UMi", "Vir", // 47: Ptolemy's 48 without Argo Navis...
        "Car", "Pup", "Vel", // ...whose three parts make 50
    )

    @Test
    fun everyConstellationOfTheSkyHasExactlyOneEntry() {
        val figures = data.constellations.map { it.abbreviation }.toSet()
        assertEquals(88, figures.size)
        assertEquals(88, lore.size)
        assertEquals(figures, lore.map { it.abbreviation }.toSet())
    }

    @Test
    fun ancientAndModernAreSplitAsPtolemysListSays() {
        assertEquals(50, ancient.size)
        assertEquals(ancient, lore.filter { it.origin == ConstellationOrigin.ANCIENT }.map { it.abbreviation }.toSet())
        assertEquals(38, lore.count { it.origin == ConstellationOrigin.MODERN })
    }

    @Test
    fun theModernConstellationsCreditTheirInventors() {
        val modern = lore.filter { it.origin == ConstellationOrigin.MODERN }
        // Lacaille's fourteen southern figures of instruments and the arts.
        val lacaille = modern.filter { it.tradition.startsWith("Lacaille") }.map { it.abbreviation }.toSet()
        assertEquals(setOf("Ant", "Cae", "Cir", "For", "Hor", "Men", "Mic", "Nor", "Oct", "Pic", "Pyx", "Ret", "Scl", "Tel"), lacaille)
        // The twelve southern figures from Keyser and de Houtman's observations.
        val dutch = modern.filter { it.tradition.startsWith("Keyser") }.map { it.abbreviation }.toSet()
        assertEquals(setOf("Aps", "Cha", "Dor", "Gru", "Hyi", "Ind", "Mus", "Pav", "Phe", "TrA", "Tuc", "Vol"), dutch)
        // The seven that Hevelius added.
        val hevelius = modern.filter { it.tradition.startsWith("Hevelius") }.map { it.abbreviation }.toSet()
        assertEquals(setOf("CVn", "Lac", "LMi", "Lyn", "Sct", "Sex", "Vul"), hevelius)
        // The rest: Plancius's (Cam, Col, Cru, Mon) and Berenice's Hair.
        val others = modern.map { it.abbreviation }.toSet() - lacaille - dutch - hevelius
        assertEquals(setOf("Cam", "Col", "Com", "Cru", "Mon"), others)
    }

    @Test
    fun modernEntriesDoNotPassOffAHistoryAsAMyth() {
        // The honest answer for a constellation devised in the 1500s to 1700s is "there is no myth", said so.
        val needsDisclaimer = lore.filter { it.origin == ConstellationOrigin.MODERN } .filter { it.abbreviation !in setOf("Com", "Cru", "Sct", "Col") }
        for (entry in needsDisclaimer) {
            assertTrue("${entry.abbreviation} should say there is no myth", "no myth" in entry.story || "no classical myth" in entry.story)
        }
    }

    @Test
    fun storiesAreShortCleanProse() {
        for (entry in lore) {
            val where = entry.abbreviation
            assertTrue("$where: story length ${entry.story.length}", entry.story.length in 150..700)
            assertTrue("$where: ends in a full stop", entry.story.endsWith("."))
            assertFalse("$where: double space", "  " in entry.story)
            assertTrue("$where: figure", entry.figure.length in 5..40)
            assertTrue("$where: figure starts lower case or capital article", entry.figure.first().isLetter())
            assertFalse("$where: field separator in text", '|' in entry.story + entry.figure + entry.tradition)
        }
    }

    @Test
    fun wellKnownMythsMentionTheirCharacters() {
        fun story(abbreviation: String) = lore.single { it.abbreviation == abbreviation }.story
        assertTrue("Perseus" in story("And"))
        assertTrue("Medusa" in story("Per") && "Algol" in story("Per"))
        assertTrue("Scorpion" in story("Ori") || "scorpion" in story("Ori"))
        assertTrue("Callisto" in story("UMa"))
        assertTrue("Sirius" in story("CMa"))
        assertTrue("Ganymede" in story("Aqr"))
        assertTrue("Asclepius" in story("Oph"))
        assertTrue("Castor" in story("Gem") && "Pollux" in story("Gem"))
        assertTrue("Europa" in story("Tau"))
        assertTrue("Polaris" in story("UMi"))
        assertTrue("Lacaille" in story("Ant"))
        assertTrue("Sobieski" in story("Sct"))
        assertTrue("Berenice" in story("Com"))
    }
}
