package com.skyglass.sensors

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Confirms the sign convention that Coordinates.magneticToTrueEnu relies on:
 * GeomagneticField reports east declination as positive.
 */
@RunWith(AndroidJUnit4::class)
class MagneticDeclinationTest {

    private val time = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()

    @Test
    fun seattle_hasStrongEastDeclination() {
        val d = MagneticDeclination.degrees(47.61, -122.33, 0.0, time)
        assertTrue("Seattle declination $d°", d in 12.0..18.0)
    }

    @Test
    fun capeTown_hasStrongWestDeclination() {
        val d = MagneticDeclination.degrees(-33.92, 18.42, 0.0, time)
        assertTrue("Cape Town declination $d°", d in -30.0..-22.0)
    }
}
