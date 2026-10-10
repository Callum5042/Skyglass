package com.skyglass.catalog

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory

object StarCatalog {

    const val ASSET_NAME = "bsc5.dat"
    const val CONSTELLATIONS_ASSET_NAME = "constellations.txt"
    const val CONSTELLATION_LORE_ASSET_NAME = "constellation_lore.txt"
    const val STAR_NAMES_ASSET_NAME = "star_names.txt"
    const val DEEP_SKY_ASSET_NAME = "deep_sky.txt"
    const val CONSTELLATION_ART_ASSET_NAME = "constellation_art.txt"
    const val CONSTELLATION_ART_DIRECTORY = "constellation_art"
    const val OBJECT_IMAGES_ASSET_NAME = "object_images.txt"
    const val OBJECT_IMAGES_DIRECTORY = "object_images"

    /** Reads the bundled catalogue and keeps naked-eye stars. Blocking; call off the main thread. */
    fun loadNakedEyeStars(assets: AssetManager): List<Star> =
        assets.open(ASSET_NAME).bufferedReader(Charsets.US_ASCII).useLines { lines ->
            BscParser.parse(lines).filter { it.vmag <= StarAppearance.MAGNITUDE_LIMIT }
        }

    /** Blocking; call off the main thread. */
    fun loadConstellations(assets: AssetManager): List<Constellation> =
        assets.open(CONSTELLATIONS_ASSET_NAME).bufferedReader(Charsets.UTF_8).useLines(ConstellationParser::parse)

    /** Blocking; call off the main thread. */
    fun loadConstellationLore(assets: AssetManager): List<ConstellationLore> =
        assets.open(CONSTELLATION_LORE_ASSET_NAME).bufferedReader(Charsets.UTF_8).useLines(ConstellationLoreParser::parse)

    /** Blocking; call off the main thread. */
    fun loadProperNames(assets: AssetManager): List<ProperName> =
        assets.open(STAR_NAMES_ASSET_NAME).bufferedReader(Charsets.UTF_8).useLines(ProperNameParser::parse)

    /** Blocking; call off the main thread. */
    fun loadDeepSky(assets: AssetManager): List<DeepSkyObject> =
        assets.open(DEEP_SKY_ASSET_NAME).bufferedReader(Charsets.UTF_8).useLines(DeepSkyParser::parse)

    /** Blocking; call off the main thread. */
    fun loadConstellationArt(assets: AssetManager): List<ConstellationArt> =
        assets.open(CONSTELLATION_ART_ASSET_NAME).bufferedReader(Charsets.UTF_8).useLines(ConstellationArtParser::parse)

    /** Blocking; call off the main thread. */
    fun loadObjectImageIndex(assets: AssetManager): ObjectImageIndex =
        assets.open(OBJECT_IMAGES_ASSET_NAME).bufferedReader(Charsets.UTF_8).useLines(ObjectImageParser::parse)

    /**
     * Decodes a photo, subsampled by a power of two while it stays at least [minSidePx] on its
     * shorter side (so a thumbnail does not hold the full image). Null if it cannot be decoded.
     * Blocking; call off the main thread.
     */
    fun loadObjectImage(assets: AssetManager, image: ObjectImage, minSidePx: Int): Bitmap? {
        var sample = 1
        while (minOf(image.width, image.height) / (sample * 2) >= minSidePx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return assets.open("$OBJECT_IMAGES_DIRECTORY/${image.fileName}").use { BitmapFactory.decodeStream(it, null, options) }
    }

    /**
     * Decodes an illustration and keeps one channel (the art is grey; see [ArtImage]). Blocking, a few
     * milliseconds per image; call off the main thread.
     */
    fun loadArtImage(assets: AssetManager, art: ConstellationArt): ArtImage {
        val bitmap = assets.open("$CONSTELLATION_ART_DIRECTORY/${art.fileName}").use { BitmapFactory.decodeStream(it) }
            ?: error("cannot decode ${art.fileName}")
        try {
            check(bitmap.width == art.width && bitmap.height == art.height) {
                "${art.fileName} is ${bitmap.width}x${bitmap.height}, expected ${art.width}x${art.height}"
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return ArtImage(bitmap.width, bitmap.height, ByteArray(pixels.size) { (pixels[it] shr 8).toByte() })
        } finally {
            bitmap.recycle()
        }
    }
}
