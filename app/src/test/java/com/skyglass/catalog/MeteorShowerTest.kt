package com.skyglass.catalog

import com.skyglass.astro.AstroTime
import com.skyglass.astro.Coordinates
import com.skyglass.assertAngleDegEquals
import com.skyglass.epochMillis
import com.skyglass.math.Matrix3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.atan2

class MeteorShowerTest {

    private val showers = File(BundledSkyData.assets, StarCatalog.METEOR_SHOWERS_ASSET_NAME)
        .useLines(Charsets.UTF_8) { MeteorShowerParser.parse(it) }

    private fun shower(code: String) = showers.single { it.code == code }

    private fun jd(iso: String) = AstroTime.julianDate(epochMillis(iso))

    @Test
    fun bundledTable_matchesThePublishedValues() {
        // Wikipedia's "List of meteor showers" (fetched 2026-10-12): Perseids 17 Jul – 24 Aug, peak 13 Aug,
        // ZHR 100, radiant 3.2h +58°; Geminids peak 14 Dec, ZHR 150, radiant 7.5h +33°.
        assertEquals(9, showers.size)
        val perseids = shower("PER")
        assertEquals("Perseids", perseids.name)
        assertEquals(100, perseids.zhr)
        assertEquals(3.2 * 15, Math.toDegrees(perseids.radiantJ2000.raRad), 1.0)
        assertEquals(58.0, Math.toDegrees(perseids.radiantJ2000.decRad), 1.0)
        assertTrue(perseids.isActive(LocalDate.of(2026, 7, 17)))
        assertTrue(perseids.isActive(LocalDate.of(2026, 8, 24)))
        assertFalse(perseids.isActive(LocalDate.of(2026, 7, 16)))
        assertFalse(perseids.isActive(LocalDate.of(2026, 8, 25)))

        val geminids = shower("GEM")
        assertEquals(150, geminids.zhr)
        assertEquals(7.5 * 15, Math.toDegrees(geminids.radiantJ2000.raRad), 1.0)
        assertEquals(33.0, Math.toDegrees(geminids.radiantJ2000.decRad), 1.0)
        assertEquals(0, geminids.daysFromPeak(LocalDate.of(2026, 12, 14)))
    }

    @Test
    fun anActivityPeriodThatRunsOverTheNewYear() {
        // Quadrantids: 28 Dec – 12 Jan, peak 3 Jan.
        val quadrantids = shower("QUA")
        assertEquals(-6, quadrantids.daysFromPeak(LocalDate.of(2026, 12, 28)))
        assertEquals(-2, quadrantids.daysFromPeak(LocalDate.of(2027, 1, 1)))
        assertEquals(9, quadrantids.daysFromPeak(LocalDate.of(2027, 1, 12)))
        assertTrue(quadrantids.isActive(LocalDate.of(2026, 12, 28)))
        assertTrue(quadrantids.isActive(LocalDate.of(2027, 1, 12)))
        assertFalse(quadrantids.isActive(LocalDate.of(2026, 12, 27)))
        assertFalse(quadrantids.isActive(LocalDate.of(2027, 1, 13)))
        assertFalse(quadrantids.isActive(LocalDate.of(2026, 7, 3)))
    }

    @Test
    fun theRadiantAtThePeakIsTheTabulatedOne_precessedToDate() {
        val perseids = shower("PER")
        val jd = jd("2000-01-01T12:00:00Z") // J2000 itself, so no precession
        val ra = Coordinates.unitToRaDec(perseids.radiantDirection(0.0, jd))
        assertAngleDegEquals(48.0, Math.toDegrees(ra.raRad), 1e-6)
        assertEquals(58.0, Math.toDegrees(ra.decRad), 1e-6)
    }

    @Test
    fun theRadiantKeepsItsPlaceRelativeToTheSun() {
        // Its ecliptic latitude stays put and its longitude moves at the Sun's mean rate, ~0.986° a day.
        val perseids = shower("PER")
        val jd = jd("2000-01-01T12:00:00Z")
        val obliquity = Coordinates.meanObliquityRad(0.0)
        fun ecliptic(days: Double) = Matrix3.rotationX(-obliquity) * perseids.radiantDirection(days, jd)
        val atPeak = ecliptic(0.0)
        val earlier = ecliptic(-27.0) // 17 July
        assertEquals(atPeak.z, earlier.z, 1e-9)
        val dLon = Math.toDegrees(atan2(atPeak.y, atPeak.x) - atan2(earlier.y, earlier.x))
        assertAngleDegEquals(27 * 0.9856, dLon, 0.01)
        // So the radiant was west of its peak place, at a smaller right ascension, as observers record.
        val raEarlier = Math.toDegrees(Coordinates.unitToRaDec(Matrix3.rotationX(obliquity) * earlier).raRad)
        assertTrue("RA $raEarlier", raEarlier in 0.0..30.0)
    }

    @Test
    fun localDate_isTheDateAtTheObserversLongitude() {
        // 02:00 UT is still the previous evening, 19:00 local mean time, at 105° W.
        assertEquals(LocalDate.of(2026, 8, 12), MeteorShower.localDate(jd("2026-08-13T02:00:00Z"), -105.0))
        assertEquals(LocalDate.of(2026, 8, 13), MeteorShower.localDate(jd("2026-08-13T02:00:00Z"), 0.0))
    }
}
