package com.skyglass.catalog

import android.content.res.AssetManager

object StarCatalog {

    const val ASSET_NAME = "bsc5.dat"
    const val CONSTELLATIONS_ASSET_NAME = "constellations.txt"
    const val STAR_NAMES_ASSET_NAME = "star_names.txt"

    /** Reads the bundled catalogue and keeps naked-eye stars. Blocking; call off the main thread. */
    fun loadNakedEyeStars(assets: AssetManager): List<Star> =
        assets.open(ASSET_NAME).bufferedReader(Charsets.US_ASCII).useLines { lines ->
            BscParser.parse(lines).filter { it.vmag <= StarAppearance.MAGNITUDE_LIMIT }
        }

    /** Blocking; call off the main thread. */
    fun loadConstellations(assets: AssetManager): List<Constellation> =
        assets.open(CONSTELLATIONS_ASSET_NAME).bufferedReader(Charsets.UTF_8).useLines(ConstellationParser::parse)

    /** Blocking; call off the main thread. */
    fun loadProperNames(assets: AssetManager): List<ProperName> =
        assets.open(STAR_NAMES_ASSET_NAME).bufferedReader(Charsets.UTF_8).useLines(ProperNameParser::parse)
}
