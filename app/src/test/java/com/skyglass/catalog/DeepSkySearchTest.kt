package com.skyglass.catalog

import com.skyglass.astro.SkyBody
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Searching for deep-sky objects, against the real bundled catalogue. */
class DeepSkySearchTest {

    private val data = BundledSkyData.load()
    private val index = SkySearchIndex.build(
        data.nakedEye,
        StarNamer(data.properNames, data.constellations),
        data.constellations.map { it.name },
        SkyBody.entries.associateWith { it.name.lowercase().replaceFirstChar(Char::uppercase) },
        data.deepSky,
    )

    private fun firstDeepSkyId(query: String): String? =
        (index.search(query).firstOrNull()?.target as? SearchTarget.DeepSkyTarget)?.id

    @Test
    fun messierNumbers_areFoundHoweverTheyAreTyped() {
        for (query in listOf("M31", "m31", "m 31", "M 31", "messier 31", "Messier31", "ngc 224", "NGC224", "NGC 224", "andromeda galaxy", "Andromeda Galaxy", "great andromeda")) {
            assertEquals("query '$query'", "M31", firstDeepSkyId(query))
        }
    }

    @Test
    fun theExactNumberComesBeforeThoseThatMerelyStartWithIt() {
        val results = index.search("m1")
        assertEquals("M1", (results.first().target as SearchTarget.DeepSkyTarget).id)
        // M10..M19 and M100..M110 also start with "m1", after the exact match.
        assertTrue(results.drop(1).all { (it.target as? SearchTarget.DeepSkyTarget)?.id?.startsWith("M1") == true })
        assertEquals("M110", firstDeepSkyId("m110"))
        assertEquals("M13", firstDeepSkyId("m 13"))
    }

    @Test
    fun otherCataloguesAndNamesAreFound() {
        assertEquals("NGC 5139", firstDeepSkyId("omega centauri"))
        assertEquals("NGC 5139", firstDeepSkyId("ngc 5139"))
        assertEquals("NGC 5139", firstDeepSkyId("caldwell 80"))
        assertEquals("NGC 5139", firstDeepSkyId("c80"))
        assertEquals("NGC 104", firstDeepSkyId("47 tucanae"))
        assertEquals("NGC 3372", firstDeepSkyId("carina nebula"))
        assertEquals("NGC 3372", firstDeepSkyId("eta carinae nebula"))
        assertEquals("M44", firstDeepSkyId("praesepe"))
        assertEquals("M44", firstDeepSkyId("beehive"))
        assertEquals("M45", firstDeepSkyId("pleiades"))
        assertEquals("M45", firstDeepSkyId("seven sisters"))
        assertEquals("Cr 399", firstDeepSkyId("coathanger"))
        assertEquals("Mel 25", firstDeepSkyId("hyades"))
        assertEquals("LMC", firstDeepSkyId("large magellanic cloud"))
        assertEquals("LMC", firstDeepSkyId("lmc"))
        assertEquals("SMC", firstDeepSkyId("small magellanic"))
        assertEquals("M42", firstDeepSkyId("orion nebula"))
        assertEquals("M104", firstDeepSkyId("sombrero"))
        assertEquals("NGC 869", firstDeepSkyId("h persei"))
    }

    @Test
    fun theDoubleClusterGivesBothClusters() {
        val ids = index.search("double cluster").mapNotNull { (it.target as? SearchTarget.DeepSkyTarget)?.id }
        assertTrue(ids.containsAll(listOf("NGC 869", "NGC 884")))
    }

    @Test
    fun everyObjectCanBeFoundByItsOwnTitleAndFirstDesignation() {
        for (o in data.deepSky) {
            for (query in listOf(o.title, o.id)) {
                val ids = index.search(query).mapNotNull { (it.target as? SearchTarget.DeepSkyTarget)?.id }
                assertTrue("'$query' did not find ${o.id}: $ids", o.id in ids)
            }
        }
    }

    @Test
    fun resultsDescribeTheObject() {
        val m31 = index.search("m31").first()
        assertEquals(SearchKind.DEEP_SKY, m31.kind)
        assertEquals("Andromeda Galaxy", m31.title)
        assertEquals("M31 · NGC 224", m31.detail)
        assertEquals(3.4, m31.rankMagnitude!!, 0.0)
        assertNull("not subject to the faintest-stars setting", m31.vmag)
        val m3 = index.search("m3").first()
        assertEquals("M3", m3.title) // no common name: the designation is the title
        assertEquals("NGC 5272", m3.detail)
    }

    @Test
    fun deepSkyResultsRankAfterConstellationsAndStarsOnEqualMatches() {
        val kinds = index.search("andromeda").map { it.kind }
        assertEquals(SearchKind.CONSTELLATION, kinds.first())
        assertTrue(kinds.contains(SearchKind.DEEP_SKY))
        assertTrue(SearchKind.CONSTELLATION.ordinal < SearchKind.STAR.ordinal && SearchKind.STAR.ordinal < SearchKind.DEEP_SKY.ordinal)
    }

    @Test
    fun theStartOfAStarsDesignation_findsTheStarBeforeAClusterNamedAfterIt() {
        // "Alpha Persei Cluster" and "Tau Canis Majoris Cluster" are named after stars (so is "Omicron Velorum Cluster", but
        // omicron Velorum is not in the sky catalogue's naked-eye list, so there is no star to confuse it with).
        for ((query, hr) in listOf("alpha pers" to 1017, "alpha persei" to 1017, "tau canis" to 2782, "tau canis majoris" to 2782)) {
            val first = index.search(query).first()
            assertEquals("query '$query' gave ${first.title}", SearchKind.STAR, first.kind)
            assertEquals("query '$query'", hr, (first.target as SearchTarget.StarTarget).hr)
        }
        // The full cluster names still find the clusters.
        assertEquals("Mel 20", firstDeepSkyId("alpha persei cluster"))
        assertEquals("IC 2391", firstDeepSkyId("omicron velorum cluster"))
        assertEquals("NGC 2362", firstDeepSkyId("tau canis majoris cluster"))
    }

    @Test
    fun suggestionsForAnEmptyQuery_arePlanetsAndNamedStarsOnly() {
        val suggestions = index.search("")
        assertTrue(suggestions.none { it.kind == SearchKind.DEEP_SKY })
        assertTrue(suggestions.take(SkyBody.entries.size).all { it.kind == SearchKind.BODY })
    }

    @Test
    fun existingStarSearchesAreUnaffected() {
        assertEquals(2491, (index.search("sirius").first().target as SearchTarget.StarTarget).hr)
        assertEquals(2491, (index.search("hr 2491").first().target as SearchTarget.StarTarget).hr)
    }

    @Test
    fun navigationTarget_pointsAtTheFixedDirectionOfADeepSkyObject() {
        val result = index.search("m31").first()
        val direction = Vector3(0.1, 0.2, 0.97).normalized()
        assertEquals(direction, NavigationTarget(result, direction).directionIn(emptyList()))
    }
}
