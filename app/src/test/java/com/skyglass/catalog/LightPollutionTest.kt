package com.skyglass.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream

class LightPollutionTest {

    /**
     * A 1° grid of three rows centred on 1° N, the equator and 1° S, all the way round; cell (r, c)
     * holds byte (40 r + c) mod 256, so the stored row differences wrap past 255 as real data does.
     */
    private fun syntheticFile(): ByteArray {
        val rows = 3
        val cols = 360
        val value = { r: Int, c: Int -> (40 * r + c) % 256 }
        val body = ByteArrayOutputStream()
        DeflaterOutputStream(body).use { out ->
            for (r in 0 until rows) for (c in 0 until cols) {
                val above = if (r == 0) 0 else value(r - 1, c)
                out.write((value(r, c) - above) and 0xFF)
            }
        }
        val header = "# A comment\n# grid: rows cols lat0 lon0 step qmin qstep\ngrid 3 360 1.0 -179.5 1.0 14.00 0.04\n\n"
        return header.toByteArray(Charsets.US_ASCII) + body.toByteArray()
    }

    private fun sqm(r: Int, c: Int) = 14.0 + ((40 * r + c) % 256) * 0.04

    private fun lookup(lat: Double, lon: Double) =
        LightPollution.skyBrightness(ByteArrayInputStream(syntheticFile()), lat, lon)

    @Test
    fun cellCentres_giveTheirStoredValue() {
        assertEquals(sqm(0, 0), lookup(1.0, -179.5)!!, 1e-9)
        assertEquals(sqm(1, 100), lookup(0.0, -79.5)!!, 1e-9)
        assertEquals(sqm(2, 250), lookup(-1.0, 70.5)!!, 1e-9) // 80 + 250 wraps past 255
    }

    @Test
    fun betweenCentres_interpolatesInBothDirections() {
        val expected = (sqm(0, 10) + sqm(0, 11) + sqm(1, 10) + sqm(1, 11)) / 4
        assertEquals(expected, lookup(0.5, -169.0)!!, 1e-9)
    }

    @Test
    fun longitude_wrapsAroundTheAntimeridian() {
        val expected = (sqm(1, 359) + sqm(1, 0)) / 2
        assertEquals(expected, lookup(0.0, 180.0)!!, 1e-9)
        assertEquals(expected, lookup(0.0, -180.0)!!, 1e-9)
        assertEquals(sqm(1, 0), lookup(0.0, 180.5)!!, 1e-9)
    }

    @Test
    fun outsideTheRows_isNull() {
        assertEquals(sqm(0, 0), lookup(1.5, -179.5)!!, 1e-9)
        assertNull(lookup(1.6, -179.5))
        assertEquals(sqm(2, 0), lookup(-1.5, -179.5)!!, 1e-9)
        assertNull(lookup(-1.6, -179.5))
    }

    @Test
    fun header_isParsed() {
        val grid = LightPollution.parseHeader("# x\ngrid 2800 7200 74.977083333 -179.977083333 0.05 14.00 0.04\n")
        assertEquals(LightPollution.Grid(2800, 7200, 74.977083333, -179.977083333, 0.05, 14.0, 0.04), grid)
    }

    private fun bundled(lat: Double, lon: Double): Double? =
        File(BundledSkyData.assets, StarCatalog.LIGHT_POLLUTION_ASSET_NAME).inputStream().use { LightPollution.skyBrightness(it, lat, lon) }

    /** Broad facts about well-known places, not the model's own numbers. */
    @Test
    fun bundledMap_knowsCitiesFromDarkSkyParks() {
        // City centres are inner-city skies (Bortle 8 or 9).
        assertTrue(Bortle.classForSkyBrightness(bundled(51.508, -0.128)!!) >= 8) // Trafalgar Square, London
        assertTrue(Bortle.classForSkyBrightness(bundled(40.754, -73.984)!!) >= 8) // Midtown Manhattan
        // International Dark Sky Places are rural or darker (Bortle 3 or less).
        assertTrue(Bortle.classForSkyBrightness(bundled(55.10, -4.40)!!) <= 3) // Galloway Forest Park
        assertTrue(Bortle.classForSkyBrightness(bundled(36.46, -116.87)!!) <= 3) // Death Valley
        assertTrue(Bortle.classForSkyBrightness(bundled(-24.95, 16.0)!!) <= 3) // NamibRand Nature Reserve
        // Mid-ocean is the natural sky.
        assertEquals(22.0, bundled(30.0, -40.0)!!, 0.05)
    }

    @Test
    fun bundledMap_stopsAtTheSatelliteDataEdges() {
        assertNull(bundled(78.22, 15.65)) // Longyearbyen, Svalbard: VIIRS composites stop at 75° N
        assertNull(bundled(-77.85, 166.67)) // McMurdo: and at 65° S
    }
}
