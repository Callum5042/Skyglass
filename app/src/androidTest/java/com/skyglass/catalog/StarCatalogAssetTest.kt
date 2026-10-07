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
}
