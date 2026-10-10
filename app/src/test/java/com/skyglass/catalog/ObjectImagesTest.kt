package com.skyglass.catalog

import com.skyglass.astro.SkyBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ObjectImagesTest {

    private val data = BundledSkyData.load()
    private val images = data.objectImages

    private fun parse(vararg lines: String) = ObjectImageParser.parse(lines.asSequence())

    // --- Parser -------------------------------------------------------------------------------------

    @Test
    fun parser_readsDeepSkyBodiesAndMoons() {
        val index = parse(
            "# comment",
            "",
            "M31|m31.webp|768|512|ESA/Hubble & Digitized Sky Survey 2|CC BY 4.0",
            "@JUPITER|jupiter.webp|700|768|NASA/JPL/University of Arizona|Public domain",
            "@JUPITER/Io|jupiter_io.webp|768|768|NASA/JPL/University of Arizona|Public domain",
            "@JUPITER/Europa|jupiter_europa.webp|768|768|NASA/JPL|Public domain",
        )
        assertEquals(ObjectImage("m31.webp", 768, 512, "ESA/Hubble & Digitized Sky Survey 2", "CC BY 4.0"), index.forDeepSky("M31"))
        assertEquals("jupiter.webp", index.forBody(SkyBody.JUPITER)?.fileName)
        assertEquals(listOf("Io", "Europa"), index.moonsOf(SkyBody.JUPITER).map { it.name })
        assertEquals("jupiter_io.webp", index.moonsOf(SkyBody.JUPITER)[0].image.fileName)
        assertNull(index.forDeepSky("M32"))
        assertNull(index.forBody(SkyBody.SATURN))
        assertTrue(index.moonsOf(SkyBody.SATURN).isEmpty())
        assertEquals(4, index.all.size)
    }

    @Test
    fun parser_rejectsBadLines() {
        assertThrows(IllegalArgumentException::class.java) { parse("M31|m31.webp|768|512|credit") }
        assertThrows(IllegalArgumentException::class.java) { parse("@PLUTO|pluto.webp|1|1|NASA|Public domain") }
        assertThrows(IllegalArgumentException::class.java) { parse("@MOON/Pebble|pebble.webp|1|1|NASA|Public domain") }
        assertThrows(IllegalArgumentException::class.java) { parse("@SUN/X|x.webp|1|1|NASA|Public domain") }
        assertThrows(IllegalArgumentException::class.java) { parse("@MARS/|x.webp|1|1|NASA|Public domain") }
        assertThrows(IllegalArgumentException::class.java) {
            parse("M31|a.webp|1|1|c|CC BY 4.0", "M31|b.webp|1|1|c|CC BY 4.0")
        }
    }

    // --- Bundled data -------------------------------------------------------------------------------

    private val indexLines = File(BundledSkyData.assets, StarCatalog.OBJECT_IMAGES_ASSET_NAME)
        .readLines().filter { it.isNotBlank() && !it.startsWith("#") }

    @Test
    fun bundledImages_deepSkyKeysAreCatalogueIds() {
        val ids = data.deepSky.map { it.id }.toSet()
        val keys = indexLines.map { it.substringBefore("|") }.filter { !it.startsWith("@") }
        assertTrue("some deep-sky photos", keys.size > 100)
        for (key in keys) assertTrue("$key is not a deep-sky id", key in ids)
        assertNotNull(images.forDeepSky("M31"))
        assertNotNull(images.forDeepSky("M42"))
    }

    @Test
    fun bundledImages_everyBodyHasOneAndThePlanetsHaveTheirMoons() {
        for (body in SkyBody.entries) assertNotNull("$body", images.forBody(body))
        assertEquals(listOf("Io", "Europa", "Ganymede", "Callisto"), images.moonsOf(SkyBody.JUPITER).map { it.name })
        assertEquals(listOf("Phobos", "Deimos"), images.moonsOf(SkyBody.MARS).map { it.name })
        assertTrue("Titan", images.moonsOf(SkyBody.SATURN).any { it.name == "Titan" })
        assertTrue("Triton", images.moonsOf(SkyBody.NEPTUNE).any { it.name == "Triton" })
        assertTrue(images.moonsOf(SkyBody.MERCURY).isEmpty())
        assertTrue(images.moonsOf(SkyBody.VENUS).isEmpty())
    }

    @Test
    fun bundledImages_areWebPFilesOfTheStatedSizeWithinBudget() {
        var total = 0L
        val names = images.all.map { it.fileName }
        assertEquals("one file per image", names.size, names.toSet().size)
        for (image in images.all) {
            val file = File(BundledSkyData.assets, "${StarCatalog.OBJECT_IMAGES_DIRECTORY}/${image.fileName}")
            assertTrue("${image.fileName} missing", file.isFile)
            total += file.length()
            val (width, height) = webpSize(file.readBytes())
            assertEquals("${image.fileName} width", image.width, width)
            assertEquals("${image.fileName} height", image.height, height)
            assertTrue("${image.fileName} is ${width}x$height", maxOf(width, height) <= 768)
        }
        assertTrue("photos take ${total / 1_000_000} MB", total <= 16L * 1024 * 1024)
    }

    @Test
    fun bundledImages_areCreditedUnderAnAllowedLicence() {
        for (image in images.all) {
            assertTrue("${image.fileName}: ${image.licence}", image.licence in setOf("Public domain", "CC BY 4.0"))
            assertTrue("${image.fileName} has no credit", image.credit.isNotBlank())
        }
        val licence = File(BundledSkyData.assets, "${StarCatalog.OBJECT_IMAGES_DIRECTORY}/LICENSE.txt").readText()
        assertTrue("the CC BY 4.0 notice must ship with the photos", licence.contains("creativecommons.org/licenses/by/4.0"))
    }

    /** Width and height from a WebP file's first chunk (lossy VP8, lossless VP8L or extended VP8X). */
    private fun webpSize(bytes: ByteArray): Pair<Int, Int> {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun le24(i: Int) = le16(i) or (u8(i + 2) shl 16)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
        return when (val chunk = String(bytes, 12, 4, Charsets.US_ASCII)) {
            "VP8 " -> (le16(26) and 0x3FFF) to (le16(28) and 0x3FFF)
            "VP8L" -> {
                val bits = le16(21) or (le16(23) shl 16)
                ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
            }
            "VP8X" -> (le24(24) + 1) to (le24(27) + 1)
            else -> throw AssertionError("unknown WebP chunk $chunk")
        }
    }
}
