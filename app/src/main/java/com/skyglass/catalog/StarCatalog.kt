package com.skyglass.catalog

import android.content.res.AssetManager

object StarCatalog {

    const val ASSET_NAME = "bsc5.dat"

    /** Reads the bundled catalogue and keeps naked-eye stars. Blocking; call off the main thread. */
    fun loadNakedEyeStars(assets: AssetManager): List<Star> =
        assets.open(ASSET_NAME).bufferedReader(Charsets.US_ASCII).useLines { lines ->
            BscParser.parse(lines).filter { it.vmag <= StarAppearance.MAGNITUDE_LIMIT }
        }
}
