package com.skyglass.catalog

import com.skyglass.astro.BodyPosition
import com.skyglass.astro.Equatorial
import com.skyglass.astro.SkyBody
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkySearchTest {

    private val genitives = mapOf("CMa" to "Canis Majoris", "Cen" to "Centauri", "Cyg" to "Cygni", "Ori" to "Orionis")
    private val namer = StarNamer(mapOf(2491 to "Sirius", 5459 to "Rigil Kentaurus", 2061 to "Betelgeuse"), genitives)

    private fun star(
        hr: Int,
        vmag: Double,
        flamsteed: Int? = null,
        bayer: String? = null,
        bayerIndex: Char? = null,
        constellation: String? = null,
    ) = Star(hr, Equatorial(0.0, 0.0), vmag, null, 0.0, 0.0, flamsteed, bayer, bayerIndex, constellation)

    private val stars = listOf(
        star(2491, -1.46, flamsteed = 9, bayer = "Alp", constellation = "CMa"), // Sirius
        star(5459, -0.01, bayer = "Alp", bayerIndex = '1', constellation = "Cen"), // Rigil Kentaurus
        star(5460, 1.33, bayer = "Alp", bayerIndex = '2', constellation = "Cen"), // α² Centauri
        star(8085, 5.2, flamsteed = 61, constellation = "Cyg"), // 61 Cygni
        star(2061, 0.42, flamsteed = 58, bayer = "Alp", constellation = "Ori"), // Betelgeuse
        star(1234, 5.9), // no name at all
    )

    private val bodyNames = SkyBody.entries.associateWith { it.name.lowercase().replaceFirstChar(Char::uppercase) }
    private val index = SkySearchIndex.build(stars, namer, listOf("Orion", "Canis Major", "Serpens Caput", "Serpens Cauda"), bodyNames)

    private fun titles(query: String) = index.search(query).map { it.title }

    @Test
    fun resultFor_givesTheRowSearchWouldShow() {
        assertEquals(index.search("sirius").first(), index.resultFor(SearchTarget.StarTarget(2491)))
        assertEquals("Serpens Cauda", index.resultFor(SearchTarget.ConstellationTarget("Serpens Cauda"))?.title)
        assertEquals(SearchKind.BODY, index.resultFor(SearchTarget.BodyTarget(SkyBody.MOON))?.kind)
        assertEquals(null, index.resultFor(SearchTarget.StarTarget(1)))
    }

    @Test
    fun normalize_spellsOutGreekAndDropsAccentsCaseAndPunctuation() {
        assertEquals("alpha1 centauri", SkySearchIndex.normalize("α¹ Centauri"))
        assertEquals("omicron2 cen", SkySearchIndex.normalize("  Ο² CEN "))
        assertEquals("cafe etoile", SkySearchIndex.normalize("Café-Étoile"))
        assertEquals("al nair", SkySearchIndex.normalize("Al-Nair"))
        assertEquals("sigma", SkySearchIndex.normalize("ς"))
        assertEquals("", SkySearchIndex.normalize(" -- "))
        assertEquals("", SkySearchIndex.normalize(""))
    }

    @Test
    fun aStarIsFoundByEveryNameItGoesBy() {
        for (query in listOf(
            "sirius", "SIRIUS", "siri", "  sirius ", "irius",
            "alpha canis majoris", "α Canis Majoris", "α CMa", "alpha cma", "alpha canis", "9 cma",
            "hr 2491", "HR 2491", "2491",
        )) {
            assertEquals("query '$query'", "Sirius", index.search(query).firstOrNull()?.title)
        }
    }

    @Test
    fun resultsDescribeTheStar() {
        val sirius = index.search("sirius").single()
        assertEquals(SearchTarget.StarTarget(2491), sirius.target)
        assertEquals(SearchKind.STAR, sirius.kind)
        assertEquals("α Canis Majoris", sirius.detail)
        assertEquals(-1.46, sirius.vmag!!, 0.0)
    }

    @Test
    fun aBayerSuperscriptCanBeLeftOut_andThenBrighterStarsComeFirst() {
        assertEquals(listOf("Rigil Kentaurus", "α² Centauri"), titles("alpha centauri"))
        assertEquals(listOf("α² Centauri"), titles("alpha2 centauri"))
        assertEquals(listOf("α² Centauri"), titles("alpha2 cen"))
        assertEquals(listOf("Rigil Kentaurus"), titles("alpha1 centauri"))
    }

    @Test
    fun flamsteedDesignation() {
        assertEquals(listOf("61 Cygni"), titles("61 cygni"))
        assertEquals(listOf("61 Cygni"), titles("61 cyg"))
    }

    @Test
    fun wordsOfTheQueryMayComeInAnyOrder() {
        assertEquals(listOf("Rigil Kentaurus", "α² Centauri"), titles("centauri alpha"))
    }

    @Test
    fun aStarWithNoNameCanStillBeFoundByItsHrNumber() {
        assertEquals(listOf("HR 1234"), titles("hr 1234"))
        assertEquals(listOf("HR 1234"), titles("1234"))
    }

    @Test
    fun bodiesAndConstellationsAreFoundByName() {
        val mars = index.search("mars").single()
        assertEquals(SearchTarget.BodyTarget(SkyBody.MARS), mars.target)
        assertEquals(SearchKind.BODY, mars.kind)
        val orion = index.search("orion").first()
        assertEquals(SearchTarget.ConstellationTarget("Orion"), orion.target)
        assertEquals(SearchKind.CONSTELLATION, orion.kind)
        assertEquals(listOf("Serpens Caput", "Serpens Cauda"), titles("serpens"))
    }

    @Test
    fun betterMatchesComeFirst_thenBodiesConstellationsAndStars() {
        // Exact name beats a name that merely contains the query: the constellation, then the stars named after it.
        val results = index.search("canis major")
        assertEquals("Canis Major", results.first().title)
        assertEquals(SearchKind.CONSTELLATION, results.first().kind)
        assertEquals("Sirius", results[1].title)

        // The same tier: a body, then the star, in that order.
        val s = index.search("s").map { it.title }
        assertTrue(s.toString(), s.indexOf("Sun") < s.indexOf("Sirius"))
        assertEquals("a whole-name match ranks above any prefix match", "Sun", index.search("sun").first().title)
    }

    @Test
    fun matchesInsideAWordNeedAFewLetters() {
        assertTrue(titles("ri").none { it == "Sirius" })
        assertTrue(titles("iri").contains("Sirius"))
    }

    @Test
    fun noMatch_givesNothing() {
        assertEquals(emptyList<SearchResult>(), index.search("zzzz"))
        assertEquals(emptyList<SearchResult>(), index.search("sirius b"))
    }

    @Test
    fun anEmptyQuery_suggestsTheBodiesThenTheNamedStars() {
        val suggestions = index.search("")
        assertEquals(SkyBody.entries.size + 3, suggestions.size)
        assertTrue(suggestions.take(SkyBody.entries.size).all { it.kind == SearchKind.BODY })
        assertEquals(listOf("Sirius", "Rigil Kentaurus", "Betelgeuse"), suggestions.drop(SkyBody.entries.size).map { it.title })
        assertEquals(suggestions, index.search("  - "))
    }

    @Test
    fun resultsAreCapped() {
        assertEquals(2, index.search("a", limit = 2).size)
        assertTrue(index.search("a").size <= SkySearchIndex.MAX_RESULTS)
    }

    @Test
    fun navigationTarget_findsABodyWhereItIsNowAndOtherThingsWhereTheyAreFixed() {
        val marsHere = Vector3(1.0, 0.0, 0.0)
        val bodies = listOf(
            BodyPosition(SkyBody.MARS, marsHere, 1.0, 1.5, 90.0, 0.9, Vector3(0.0, 1.0, 0.0)),
        )
        val mars = NavigationTarget(index.search("mars").single())
        assertEquals(marsHere, mars.directionIn(bodies))
        assertNull("no position known yet", mars.directionIn(emptyList()))

        val fixed = Vector3(0.0, 0.0, 1.0)
        val orion = NavigationTarget(index.search("orion").first(), fixedDirection = fixed)
        assertEquals(fixed, orion.directionIn(bodies))
        assertEquals(fixed, orion.directionIn(emptyList()))
    }

    // The real catalogue.

    private val data = BundledSkyData.load()
    private val bundledNamer = StarNamer(data.properNames, data.constellations)
    private val bundled = SkySearchIndex.build(
        data.nakedEye, bundledNamer, data.constellations.map { it.name },
        SkyBody.entries.associateWith { it.name.lowercase().replaceFirstChar(Char::uppercase) },
    )

    private fun bundledHrs(query: String) = bundled.search(query).mapNotNull { (it.target as? SearchTarget.StarTarget)?.hr }

    @Test
    fun bundledCatalogue_findsFamousStarsByName() {
        assertEquals(2491, bundledHrs("sirius").first())
        assertEquals(2061, bundledHrs("betelgeuse").first())
        assertEquals(424, bundledHrs("polaris").first())
        assertEquals(5459, bundledHrs("rigil").first())
        assertEquals(5459, bundledHrs("alpha centauri").first()) // α¹, brighter than α²
    }

    @Test
    fun bundledCatalogue_findsBayerAndFlamsteedDesignations() {
        assertEquals(2491, bundledHrs("alpha canis majoris").first())
        assertEquals(2491, bundledHrs("alpha cma").first())
        assertEquals(2061, bundledHrs("α Orionis").first())
        assertEquals(8085, bundledHrs("61 cygni").first()) // 61 Cygni A
        assertEquals(2491, bundledHrs("hr 2491").first())
    }

    @Test
    fun bundledCatalogue_findsConstellationsAndBodies() {
        val orion = bundled.search("orion").first()
        assertEquals(SearchKind.CONSTELLATION, orion.kind)
        assertEquals("Orion", orion.title)
        assertEquals("Ursa Major", bundled.search("ursa major").first().title)
        assertEquals(2, bundled.search("serpens").count { it.kind == SearchKind.CONSTELLATION })
        assertEquals(SearchTarget.BodyTarget(SkyBody.SATURN), bundled.search("saturn").first().target)
    }

    @Test
    fun bundledCatalogue_everyProperNameFindsItsStar() {
        val inSky = data.nakedEye.map { it.hr }.toSet()
        val missing = data.properNames.filter { it.hr in inSky }.filter { name ->
            name.hr !in bundledHrs(name.name).take(3)
        }
        assertEquals(emptyList<ProperName>(), missing)
    }
}
