package com.skyglass.catalog

import java.io.File

/** Test helper: loads the real bundled assets (unit tests run with the module directory as cwd). */
class BundledSkyData private constructor(
    val allStars: List<Star>,
    val constellations: List<Constellation>,
    val properNames: List<ProperName>,
    val deepSky: List<DeepSkyObject>,
    val constellationLore: List<ConstellationLore>,
    val constellationArt: List<ConstellationArt>,
) {
    val nakedEye: List<Star> = allStars.filter { it.vmag <= StarAppearance.MAGNITUDE_LIMIT }

    companion object {
        val assets = File("src/main/assets")

        fun load() = BundledSkyData(
            allStars = File(assets, StarCatalog.ASSET_NAME).useLines(Charsets.US_ASCII) { BscParser.parse(it) },
            constellations = File(assets, StarCatalog.CONSTELLATIONS_ASSET_NAME).useLines(Charsets.UTF_8) { ConstellationParser.parse(it) },
            properNames = File(assets, StarCatalog.STAR_NAMES_ASSET_NAME).useLines(Charsets.UTF_8) { ProperNameParser.parse(it) },
            deepSky = File(assets, StarCatalog.DEEP_SKY_ASSET_NAME).useLines(Charsets.UTF_8) { DeepSkyParser.parse(it) },
            constellationLore = File(assets, StarCatalog.CONSTELLATION_LORE_ASSET_NAME).useLines(Charsets.UTF_8) { ConstellationLoreParser.parse(it) },
            constellationArt = File(assets, StarCatalog.CONSTELLATION_ART_ASSET_NAME).useLines(Charsets.UTF_8) { ConstellationArtParser.parse(it) },
        )
    }
}
