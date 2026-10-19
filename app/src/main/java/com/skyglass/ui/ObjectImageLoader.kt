package com.skyglass.ui

import android.content.res.AssetManager
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.skyglass.catalog.ObjectImage
import com.skyglass.catalog.StarCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded photos, so reopening a card or the full-size view is instant. Sized in bytes: the full
 * images are about 768 x 768 x 4 = 2.4 MB each, so about ten of those, or many more thumbnails.
 */
private object ObjectImageCache {
    private const val MAX_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private fun key(image: ObjectImage, minSidePx: Int) = "${image.fileName}@$minSidePx"

    fun peek(image: ObjectImage, minSidePx: Int): ImageBitmap? = cache.get(key(image, minSidePx))

    fun get(assets: AssetManager, image: ObjectImage, minSidePx: Int): ImageBitmap? {
        peek(image, minSidePx)?.let { return it }
        val bitmap = StarCatalog.loadObjectImage(assets, image, minSidePx)?.asImageBitmap() ?: return null
        cache.put(key(image, minSidePx), bitmap)
        return bitmap
    }
}

/**
 * The photo, decoded in the background at no less than [minSidePx] on its shorter side; null
 * while it loads (or if it cannot be decoded).
 */
@Composable
internal fun rememberObjectImage(image: ObjectImage, minSidePx: Int): ImageBitmap? {
    val assets = LocalContext.current.assets
    // Keyed so a reused card slot never shows the previous photo while the new one decodes.
    val bitmap = remember(image, minSidePx) { mutableStateOf(ObjectImageCache.peek(image, minSidePx)) }
    LaunchedEffect(image, minSidePx) {
        if (bitmap.value == null) {
            bitmap.value = withContext(Dispatchers.IO) { ObjectImageCache.get(assets, image, minSidePx) }
        }
    }
    return bitmap.value
}
