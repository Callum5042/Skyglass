package com.skyglass.catalog

import com.skyglass.astro.SkyBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected values come from Bortle's (2001) class descriptions, as tabulated on Wikipedia's "Bortle
 * scale" page, taken at the middle of each class's naked-eye limit (class 2: 7.3, 3: 6.8, 4: 6.3,
 * 5: 5.8, 7: 4.8, 8: 4.3), and from common observing references for the planets and their moons.
 */
class ViewingTest {

    private val deepSky = BundledSkyData.load().deepSky

    private fun dso(id: String) = deepSky.first { it.id == id }

    private fun least(viewing: Viewing): Instrument =
        (viewing as? Viewing.Needs ?: error("expected Viewing.Needs but got $viewing")).least

    @Test
    fun m33_isNakedEyeInClass2_butNotInClass4() {
        // Class 2: "M33 easily seen with direct vision"; class 4: "a difficult averted-vision object".
        assertEquals(Instrument.EYE, least(ViewingGuide.forDeepSky(dso("M33"), 7.3)))
        assertNotEquals(Instrument.EYE, least(ViewingGuide.forDeepSky(dso("M33"), 6.3)))
    }

    @Test
    fun brightGlobulars_areNakedEyeInClass3() {
        // Class 3: "M4, M5, M15 and M22 are distinct naked-eye objects".
        for (id in listOf("M4", "M5", "M15", "M22")) {
            assertEquals(id, Instrument.EYE, least(ViewingGuide.forDeepSky(dso(id), 6.8)))
        }
    }

    @Test
    fun m31AndM44_canStillBeGlimpsedInClass7And8() {
        // Class 7: "M31 and M44 may be glimpsed"; class 8: M31 barely.
        assertEquals(Instrument.EYE, least(ViewingGuide.forDeepSky(dso("M31"), 4.8)))
        assertEquals(Instrument.EYE, least(ViewingGuide.forDeepSky(dso("M44"), 4.8)))
        assertEquals(Instrument.EYE, least(ViewingGuide.forDeepSky(dso("M31"), 4.3)))
    }

    @Test
    fun m13_isNakedEyeUnderADarkSky_andBinocularsInASuburb() {
        assertEquals(Instrument.EYE, least(ViewingGuide.forDeepSky(dso("M13"), 6.8)))
        assertEquals(Instrument.BINOCULARS, least(ViewingGuide.forDeepSky(dso("M13"), 5.5)))
    }

    @Test
    fun ringNebula_needsBinocularsToFind_andATelescopeForTheRing() {
        val viewing = ViewingGuide.forDeepSky(dso("M57"), 6.5) as Viewing.Needs
        assertTrue(viewing.least >= Instrument.BINOCULARS)
        assertEquals(CloserLook(Instrument.SMALL_TELESCOPE, ViewingDetail.RING_OR_DISC), viewing.closerLook)
    }

    @Test
    fun theCoalsack_isANakedEyeDarkNebula() {
        assertEquals(Instrument.EYE, least(ViewingGuide.forDeepSky(dso("C 99"), 6.0)))
    }

    @Test
    fun outerPlanets_followTheirMagnitudes() {
        // Uranus (5.7) is just visible to the eye from a dark site; Neptune (7.8) never is.
        assertEquals(Instrument.EYE, least(ViewingGuide.forBody(SkyBody.URANUS, 5.7, 6.5)))
        assertEquals(Instrument.BINOCULARS, least(ViewingGuide.forBody(SkyBody.URANUS, 5.7, 5.0)))
        assertEquals(Instrument.BINOCULARS, least(ViewingGuide.forBody(SkyBody.NEPTUNE, 7.8, 6.5)))
    }

    @Test
    fun saturnsRings_needATelescope() {
        val viewing = ViewingGuide.forBody(SkyBody.SATURN, 0.5, 6.0) as Viewing.Needs
        assertEquals(Instrument.EYE, viewing.least)
        assertEquals(CloserLook(Instrument.SMALL_TELESCOPE, ViewingDetail.RINGS), viewing.closerLook)
    }

    @Test
    fun theSun_isADanger() {
        assertEquals(Viewing.SunDanger, ViewingGuide.forBody(SkyBody.SUN, -26.7, 6.5))
    }

    @Test
    fun theMoon_isNakedEye_withCratersInBinoculars() {
        assertEquals(
            Viewing.Needs(Instrument.EYE, CloserLook(Instrument.BINOCULARS, ViewingDetail.CRATERS)),
            ViewingGuide.forBody(SkyBody.MOON, -12.0, 2.0),
        )
    }

    @Test
    fun noPlanetNeedsALargeTelescope_evenFromACity() {
        val magnitudes = mapOf(
            SkyBody.MERCURY to 5.7, SkyBody.VENUS to -3.8, SkyBody.MARS to 1.8, SkyBody.JUPITER to -1.6,
            SkyBody.SATURN to 1.5, SkyBody.URANUS to 6.0, SkyBody.NEPTUNE to 8.0,
        )
        for ((body, vmag) in magnitudes) {
            assertNotEquals(body.name, Instrument.LARGE_TELESCOPE, least(ViewingGuide.forBody(body, vmag, 4.0)))
        }
    }

    @Test
    fun planetMoons_scaleFromBinocularsToALargeTelescope() {
        // Ganymede 4.6, Titan 8.4, Rhea 9.7, Iapetus ~11.0, Mimas 12.9 at a typical opposition. Each moon
        // counts 1 mag fainter for its planet's glare, against NELM 6.5 plus the gains 3.27 (binoculars),
        // 4.55 (90 mm) and 6.28 (200 mm): reach 9.77, 11.05 and 12.78.
        assertEquals(Instrument.BINOCULARS, least(ViewingGuide.forPlanetMoon(4.6, 6.5))) // 5.6 would be naked-eye, but never for a moon
        assertEquals(Instrument.BINOCULARS, least(ViewingGuide.forPlanetMoon(8.4, 6.5))) // 9.4 <= 9.77
        assertEquals(Instrument.SMALL_TELESCOPE, least(ViewingGuide.forPlanetMoon(9.7, 6.5))) // 10.7 <= 11.05
        assertEquals(Instrument.LARGE_TELESCOPE, least(ViewingGuide.forPlanetMoon(11.0, 6.5))) // 12.0 <= 12.78
        assertEquals(Viewing.BeyondReach, ViewingGuide.forPlanetMoon(12.9, 6.5)) // 13.9 > 12.78
    }

    @Test
    fun aPlanetMoonPastTheLargeTelescope_isBeyondReach() {
        // Rhea (9.7) + 1.0 = 10.7 against NELM 4.0 + 6.28 = 10.28.
        assertEquals(Viewing.BeyondReach, ViewingGuide.forPlanetMoon(9.7, 4.0))
    }

    @Test
    fun aStarPastTheLargeTelescope_isBeyondReach() {
        // From NELM 6.5 the 200 mm telescope reaches 12.78.
        assertEquals(Instrument.LARGE_TELESCOPE, least(ViewingGuide.forStar(12.7, 6.5)))
        assertEquals(Viewing.BeyondReach, ViewingGuide.forStar(12.9, 6.5))
    }

    @Test
    fun aDeepSkyObjectPastTheLargeTelescope_isBeyondReach() {
        // The Rosette Nebula: 9.0 mag over 80' x 80' (5027 arcmin²) is 9.0 + 2.5 log10(5027) = 18.25 mag/arcmin²,
        // so it looks like 9.0 + 0.5 (18.25 - 13) = 11.63, and 12.13 with the diffuse margin.
        // That is within reach of 200 mm from NELM 6.5 (12.78) but not from NELM 4.0 (10.28).
        assertEquals(Instrument.LARGE_TELESCOPE, least(ViewingGuide.forDeepSky(dso("NGC 2237"), 6.5)))
        assertEquals(Viewing.BeyondReach, ViewingGuide.forDeepSky(dso("NGC 2237"), 4.0))
    }

    @Test
    fun aCloserLook_isOnlyGivenWhenItNeedsMoreThanTheLeast() {
        // The Pleiades are naked-eye, so binoculars add something; an open cluster that needs binoculars gets nothing more.
        assertEquals(
            CloserLook(Instrument.BINOCULARS, ViewingDetail.RESOLVED_STARS),
            (ViewingGuide.forDeepSky(dso("M45"), 6.0) as Viewing.Needs).closerLook,
        )
        val m103 = ViewingGuide.forDeepSky(dso("M103"), 6.0) as Viewing.Needs
        assertEquals(Instrument.BINOCULARS, m103.least)
        assertNull(m103.closerLook)
    }

    @Test
    fun limitingMagnitude_prefersTheSlider_thenTheMap_thenTheDefault() {
        assertEquals(5.2, ViewingGuide.limitingMagnitude(5.2, 21.9), 1e-9)
        assertEquals(Bortle.limitingMagnitudeForSkyBrightness(19.0), ViewingGuide.limitingMagnitude(StarVisibility.MAX_LIMIT, 19.0), 1e-9)
        assertEquals(ViewingGuide.DEFAULT_LIMITING_MAGNITUDE, ViewingGuide.limitingMagnitude(StarVisibility.MAX_LIMIT, null), 1e-9)
    }

    @Test
    fun everyDeepSkyObject_isWithinReachOfALargeTelescopeFromARuralSky() {
        // 200 mm reaches 6.5 + 6.28 = 12.78 (see theInstrumentsReachFurtherWithAperture).
        val largeTelescopeReach = 12.78
        for (obj in deepSky) {
            val effective = ViewingGuide.effectiveMagnitude(obj) ?: continue
            assertNotEquals(obj.id, Viewing.BeyondReach, ViewingGuide.forDeepSky(obj, 6.5))
            assertTrue(obj.id, effective + ViewingGuide.EXTENDED_MARGIN <= largeTelescopeReach)
        }
    }

    @Test
    fun theInstrumentsReachFurtherWithAperture() {
        val gains = Instrument.entries.map { it.gain }
        assertEquals(gains.sorted(), gains)
        assertEquals(0.0, Instrument.EYE.gain, 0.0)
        // 5 log10(D / 7 mm) - 1: binoculars 5 log10(50/7) - 1, then 90 mm and 200 mm.
        assertEquals(3.27, Instrument.BINOCULARS.gain, 0.01)
        assertEquals(4.55, Instrument.SMALL_TELESCOPE.gain, 0.01)
        assertEquals(6.28, Instrument.LARGE_TELESCOPE.gain, 0.01)
    }
}
