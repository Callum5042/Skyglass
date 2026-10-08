package com.skyglass.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StarCatalogAssetTest {

    @Test
    fun bundledCatalogue_loadsNakedEyeStars() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val stars = StarCatalog.loadNakedEyeStars(assets)

        assertTrue("loaded ${stars.size}", stars.size in 8300..8500)
        assertTrue(stars.all { it.vmag <= StarAppearance.MAGNITUDE_LIMIT })
        assertEquals(2491, stars.minBy { it.vmag }.hr) // Sirius
    }

    @Test
    fun bundledDeepSkyCatalogue_loadsAllMessierObjects() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val objects = StarCatalog.loadDeepSky(assets)

        assertEquals((1..110).toList(), objects.mapNotNull { it.messierNumber }.sorted())
        assertTrue("loaded ${objects.size}", objects.size >= 150)
    }

    @Test
    fun bundledConstellationLore_coversEveryConstellation() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val lore = StarCatalog.loadConstellationLore(assets)
        val figures = StarCatalog.loadConstellations(assets).map { it.abbreviation }.toSet()

        assertEquals(88, lore.size)
        assertEquals(figures, lore.map { it.abbreviation }.toSet())
    }
}
