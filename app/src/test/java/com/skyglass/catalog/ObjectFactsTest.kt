package com.skyglass.catalog

import com.skyglass.astro.SkyBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ObjectFactsTest {

    private val data = BundledSkyData.load()
    private val facts = data.objectFacts

    private fun parse(vararg lines: String) = ObjectFactParser.parse(lines.asSequence())

    // --- Parser -------------------------------------------------------------------------------------

    @Test
    fun parser_readsBodiesStarsAndDeepSky() {
        val index = parse(
            "# comment",
            "",
            "@SATURN|Saturn|The ringed planet.|It would float.",
            "HR 2491|Sirius|The brightest star.|It has a white dwarf companion.",
            "NGC 253|Sculptor Galaxy|A dusty spiral.|Caroline Herschel found it.",
        )
        assertEquals(ObjectFact("The ringed planet.", "It would float."), index.forBody(SkyBody.SATURN))
        assertEquals(ObjectFact("The brightest star.", "It has a white dwarf companion."), index.forStar(2491))
        assertEquals("Caroline Herschel found it.", index.forDeepSky("NGC 253")?.fact)
        assertNull(index.forBody(SkyBody.MARS))
        assertNull(index.forStar(424))
        assertNull(index.forDeepSky("M31"))
    }

    @Test
    fun parser_rejectsBadLines() {
        assertThrows(IllegalArgumentException::class.java) { parse("M31|Andromeda Galaxy|About only") }
        assertThrows(IllegalArgumentException::class.java) { parse("M31|Andromeda Galaxy||A fact") }
        assertThrows(IllegalArgumentException::class.java) { parse("@PLUTO|Pluto|About|Fact") }
        assertThrows(IllegalArgumentException::class.java) { parse("HR Sirius|Sirius|About|Fact") }
        assertThrows(IllegalArgumentException::class.java) { parse("M31|A|About|Fact", "M31|A|About|Fact") }
        assertThrows(IllegalArgumentException::class.java) { parse("HR 1|A|About|Fact", "HR 1|A|About|Fact") }
    }

    // --- Bundled data -------------------------------------------------------------------------------

    /** Key, Name, About and Fact of each entry, read straight from the file, since the index drops the name. */
    private val entries = File(BundledSkyData.assets, StarCatalog.OBJECT_FACTS_ASSET_NAME)
        .readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split("|") }

    @Test
    fun bundledFacts_everyBodyAndDeepSkyObjectHasOne() {
        for (body in SkyBody.entries) assertNotNull("$body", facts.forBody(body))
        for (obj in data.deepSky) assertNotNull(obj.id, facts.forDeepSky(obj.id))
    }

    @Test
    fun bundledFacts_namesMatchTheCatalogues() {
        // A mistyped key would put one object's words on another's card; the name column catches it.
        val deepSkyTitles = data.deepSky.associate { it.id to it.title }
        val starNames = data.properNames.associate { it.hr to it.name }
        var stars = 0
        for ((key, name) in entries) {
            when {
                key.startsWith("@") -> {
                    val body = SkyBody.valueOf(key.removePrefix("@"))
                    assertEquals(key, body.name.lowercase().replaceFirstChar { it.uppercase() }, name)
                }
                key.startsWith("HR ") -> {
                    stars++
                    assertEquals(key, starNames[key.removePrefix("HR ").toInt()], name)
                }
                else -> assertEquals(key, deepSkyTitles[key], name)
            }
        }
        assertTrue("$stars stars", stars >= 50)
        assertEquals("Sirius", starNames[2491])
        assertNotNull(facts.forStar(2491))
    }

    @Test
    fun bundledFacts_areShort() {
        // A card on a sky view, not an encyclopaedia.
        for ((key, _, about, fact) in entries) {
            assertTrue("$key about: ${about.length} characters", about.length <= MAX_FIELD_LENGTH)
            assertTrue("$key fact: ${fact.length} characters", fact.length <= MAX_FIELD_LENGTH)
        }
    }

    private companion object {
        const val MAX_FIELD_LENGTH = 220
    }
}
