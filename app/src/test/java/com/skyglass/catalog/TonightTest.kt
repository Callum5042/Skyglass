package com.skyglass.catalog

import com.skyglass.astro.AstroTime
import com.skyglass.astro.SkyBody
import com.skyglass.astro.SkyPlacement
import com.skyglass.astro.SolarSystem
import com.skyglass.epochMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TonightTest {

    private val data = BundledSkyData.load()
    private val showers = File(BundledSkyData.assets, StarCatalog.METEOR_SHOWERS_ASSET_NAME)
        .useLines(Charsets.UTF_8) { MeteorShowerParser.parse(it) }

    private fun jd(iso: String) = AstroTime.julianDate(epochMillis(iso))

    /** Minutes between two Julian dates. */
    private fun minutesBetween(a: Double, b: Double) = (a - b) * 24 * 60

    private fun plan(nowIso: String, latitudeDeg: Double, longitudeDeg: Double): Tonight.Plan {
        val now = jd(nowIso)
        return Tonight.plan(now, latitudeDeg, longitudeDeg, data.deepSky, DeepSkyVertices.directions(data.deepSky, now), showers)
    }

    // USNO, "Begin and End of Astronomical Twilight" for 2026 at W000°06′ N51°30′ (fetched 2026-10-12):
    // 12 Oct ends 19:04 UT, 13 Oct begins 04:30 UT; 21 Jun "Sun continuously above twilight limit".

    @Test
    fun darkness_inTheAfternoon_isTheComingNight() {
        val darkness = Tonight.darkness(jd("2026-10-12T12:00:00Z"), 51.5, -0.1) as Darkness.Later
        assertEquals(0.0, minutesBetween(darkness.startJd, jd("2026-10-12T19:04:00Z")), 1.5)
        assertEquals(0.0, minutesBetween(darkness.endJd!!, jd("2026-10-13T04:30:00Z")), 1.5)
    }

    @Test
    fun darkness_inTheNight_endsAtDawn() {
        val darkness = Tonight.darkness(jd("2026-10-12T23:00:00Z"), 51.5, -0.1) as Darkness.Now
        assertEquals(0.0, minutesBetween(darkness.endJd!!, jd("2026-10-13T04:30:00Z")), 1.5)
    }

    @Test
    fun darkness_atMidsummerInLondon_neverComes() {
        assertEquals(Darkness.None, Tonight.darkness(jd("2026-06-21T12:00:00Z"), 51.5, -0.1))
    }

    @Test
    fun darkness_inThePolarNight_lastsAllDay() {
        // At 89° N at the December solstice the Sun circles 23° below the horizon.
        assertEquals(Darkness.Now(null), Tonight.darkness(jd("2026-12-21T12:00:00Z"), 89.0, 0.0))
    }

    @Test
    fun plan_inTheAfternoon_describesTheSkyWhenItGetsDark() {
        val plan = plan("2026-10-12T12:00:00Z", 51.5, -0.1)
        val darkness = plan.darkness as Darkness.Later
        assertEquals(darkness.startJd, plan.atJd, 0.0)
        assertTrue(!plan.isNow)
    }

    @Test
    fun plan_withNoDarkness_describesTheSkyWhenTheSunIsLowest() {
        // Midsummer in London: the Sun is lowest near local midnight, about 00:01 UT at 0.1° W.
        val plan = plan("2026-06-21T12:00:00Z", 51.5, -0.1)
        assertEquals(Darkness.None, plan.darkness)
        assertEquals(0.0, minutesBetween(plan.atJd, jd("2026-06-22T00:02:00Z")), 10.0)
    }

    @Test
    fun plan_listsOnlyWhatIsUpAndBrightEnough_bestFirst() {
        val plan = plan("2026-10-12T22:00:00Z", 51.5, -0.1)
        assertTrue(plan.isNow)
        val bodies = SolarSystem.positions(plan.atJd, 51.5, -0.1)
        fun altitude(body: SkyBody) =
            SkyPlacement.of(bodies.first { it.body == body }.direction, plan.atJd, 51.5, -0.1).altitudeDeg

        // Every planet that is up and naked-eye is listed, and nothing else.
        val expected = bodies.filter {
            it.body.planet != null && it.vmag <= Tonight.MAX_PLANET_MAGNITUDE && altitude(it.body) >= Tonight.MIN_PLANET_ALTITUDE_DEG
        }.map { it.body }.toSet()
        assertEquals(expected, plan.planets.map { it.position.body }.toSet())
        assertTrue(SkyBody.NEPTUNE !in plan.planets.map { it.position.body })
        assertEquals(plan.planets.sortedBy { it.position.vmag }, plan.planets)
        for (planet in plan.planets) assertNotNull("${planet.position.body} sets within a day", planet.setJd)

        assertTrue(plan.deepSky.size <= Tonight.MAX_DEEP_SKY)
        assertTrue(plan.deepSky.isNotEmpty())
        for (item in plan.deepSky) {
            assertTrue(item.obj.id, item.placement.altitudeDeg >= Tonight.MIN_DEEP_SKY_ALTITUDE_DEG)
            assertTrue(item.obj.id, item.obj.vmag!! <= Tonight.MAX_DEEP_SKY_MAGNITUDE)
        }
        assertEquals(plan.deepSky.sortedBy { it.obj.vmag }, plan.deepSky)
        // No brighter object that is high enough was passed over.
        val faintestListed = plan.deepSky.last().obj.vmag!!
        val directions = DeepSkyVertices.directions(data.deepSky, plan.atJd)
        val missed = data.deepSky.indices.filter { i ->
            val obj = data.deepSky[i]
            obj.vmag != null && obj.vmag!! < faintestListed && obj !in plan.deepSky.map { it.obj } &&
                SkyPlacement.of(directions[i], plan.atJd, 51.5, -0.1).altitudeDeg >= Tonight.MIN_DEEP_SKY_ALTITUDE_DEG
        }
        assertEquals(emptyList<Int>(), missed)
    }

    @Test
    fun plan_includesTheOrionidsInOctober_withTheRadiantFromTheSun() {
        // 12 October is within the Orionids' 2 Oct – 7 Nov, nine days before the 21 October peak.
        val plan = plan("2026-10-12T22:00:00Z", 51.5, -0.1)
        val orionids = plan.showers.single { it.shower.code == "ORI" }
        assertEquals(-9, orionids.daysFromPeak)
        assertEquals(java.time.LocalDate.of(2026, 10, 21), orionids.peakDate)
        assertNull(plan.showers.firstOrNull { it.shower.code == "PER" })
    }

    @Test
    fun skyHeight_bands() {
        assertEquals(SkyHeight.BELOW, SkyHeight.of(-0.1))
        assertEquals(SkyHeight.BELOW, SkyHeight.of(0.0))
        assertEquals(SkyHeight.LOW, SkyHeight.of(0.1))
        assertEquals(SkyHeight.LOW, SkyHeight.of(24.9))
        assertEquals(SkyHeight.HALFWAY, SkyHeight.of(25.0))
        assertEquals(SkyHeight.HIGH, SkyHeight.of(55.0))
        assertEquals(SkyHeight.OVERHEAD, SkyHeight.of(80.0))
        assertEquals(SkyHeight.OVERHEAD, SkyHeight.of(90.0))
    }

    @Test
    fun plan_moonRiseOrSetComesAfterTheMoment() {
        val plan = plan("2026-10-12T22:00:00Z", 51.5, -0.1)
        val times = plan.moon.riseSet
        for (event in listOfNotNull(times.riseJd, times.setJd)) assertTrue(event > plan.atJd)
        assertEquals(SkyBody.MOON, plan.moon.position.body)
    }
}
