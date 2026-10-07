package com.skyglass.catalog

import java.io.File

/** Test helper: loads the real bundled assets (unit tests run with the module directory as cwd). */
class BundledSkyData private constructor(
    val allStars: List<Star>,
    val constellations: List<Constellation>,
    val properNames: List<ProperName>,
) {
    val nakedEye: List<Star> = allStars.filter { it.vmag <= StarAppearance.MAGNITUDE_LIMIT }

    companion object {
        private val assets = File("src/main/assets")

        fun load() = BundledSkyData(
            allStars = File(assets, StarCatalog.ASSET_NAME).useLines(Charsets.US_ASCII) { BscParser.parse(it) },
            constellations = File(assets, StarCatalog.CONSTELLATIONS_ASSET_NAME).useLines(Charsets.UTF_8) { ConstellationParser.parse(it) },
            properNames = File(assets, StarCatalog.STAR_NAMES_ASSET_NAME).useLines(Charsets.UTF_8) { ProperNameParser.parse(it) },
        )
    }
}
