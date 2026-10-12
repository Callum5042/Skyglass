package com.skyglass.catalog

import com.skyglass.astro.BodyPosition
import com.skyglass.astro.PlanetMoon
import com.skyglass.astro.SkyBody
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/** How big the Sun, Moon and planets are drawn: nominal at the default view, true size when zoomed in. */
class BodyAppearanceTest {

    private val density = 3f
    private val heightPx = 2400

    private fun focalPx(fovYDeg: Double) = (heightPx / 2.0) / tan(Math.toRadians(fovYDeg / 2))

    private fun position(body: SkyBody, distanceAu: Double, vmag: Double = 0.0) = BodyPosition(
        body = body,
        direction = Vector3(0.0, 0.0, 1.0),
        vmag = vmag,
        distanceAu = distanceAu,
        elongationDeg = 90.0,
        illuminatedFraction = 0.5,
        sunDirection = Vector3(1.0, 0.0, 0.0),
    )

    private val kmPerAu = 149_597_870.7
    private val moon = position(SkyBody.MOON, 384_400.0 / kmPerAu, -12.7)
    private val jupiter = position(SkyBody.JUPITER, 4.2, -2.5)
    private val sun = position(SkyBody.SUN, 1.0, -26.7)

    @Test
    fun atTheDefaultView_everyBodyIsDrawnAtItsNominalSize() {
        for (p in listOf(moon, jupiter, sun, position(SkyBody.NEPTUNE, 29.0, 7.8))) {
            val drawn = BodyAppearance.drawn(p, focalPx(60.0), density)
            assertEquals(p.body.name, 0.0, drawn.growthPx, 0.0)
            assertEquals(p.body.name, BodyAppearance.spriteSizeDp(p) * density / 2.0, drawn.spriteRadiusPx, 1e-9)
            assertEquals(p.body.name, BodyAppearance.discRadiusDp(p) * density.toDouble(), drawn.labelRadiusPx, 1e-4)
        }
    }

    @Test
    fun zoomedIn_theMoonsDiscIsItsTrueSize() {
        // Meeus, Astronomical Algorithms ch. 55: the Moon's geocentric semidiameter is 358 473 400 / Δ
        // arcseconds for Δ in km, so 932.55" at 384 400 km.
        val semidiameterRad = Math.toRadians(358_473_400.0 / 384_400.0 / 3600.0)
        val drawn = BodyAppearance.drawn(moon, focalPx(1.0), density)
        val expected = tan(semidiameterRad) * focalPx(1.0)
        assertEquals(expected, drawn.discRadiusPx, expected * 0.001)
        // About 640 px: the Moon fills half a 2400 px screen at a 1° field of view.
        assertTrue("${drawn.discRadiusPx}", drawn.discRadiusPx in 600.0..680.0)
    }

    @Test
    fun zoomedIn_JupitersDiscIsItsTrueSize() {
        // Meeus ch. 55: Jupiter's equatorial semidiameter is 98.44" at 1 AU, so 23.44" at 4.2 AU.
        val semidiameterRad = Math.toRadians(98.44 / 4.2 / 3600.0)
        val drawn = BodyAppearance.drawn(jupiter, focalPx(1.0), density)
        val expected = tan(semidiameterRad) * focalPx(1.0)
        assertEquals(expected, drawn.discRadiusPx, expected * 0.005)
    }

    @Test
    fun theSunAndMoonKeepTheirHaloOutsideTheGrownDisc() {
        for (p in listOf(moon, sun)) {
            val nominal = BodyAppearance.drawn(p, focalPx(60.0), density)
            val zoomed = BodyAppearance.drawn(p, focalPx(1.0), density)
            assertTrue(p.body.name, zoomed.growthPx > 0.0)
            assertEquals(p.body.name, nominal.spriteRadiusPx - nominal.discRadiusPx, zoomed.spriteRadiusPx - zoomed.discRadiusPx, 1e-6)
            assertEquals(p.body.name, nominal.labelRadiusPx + zoomed.growthPx, zoomed.labelRadiusPx, 1e-6)
        }
    }

    @Test
    fun aGrownPlanetsHaloNarrows_asItsDiscGrows_butNotToNothing() {
        val nominal = BodyAppearance.drawn(jupiter, focalPx(60.0), density)
        val nominalHalo = nominal.spriteRadiusPx - nominal.discRadiusPx
        var previousHalo = nominalHalo
        var fov = 60.0
        while (fov > 1.0) {
            fov *= 0.95
            val drawn = BodyAppearance.drawn(jupiter, focalPx(fov), density)
            val halo = drawn.spriteRadiusPx - drawn.discRadiusPx
            assertTrue("fov $fov", halo <= previousHalo + 1e-9)
            assertTrue("fov $fov: the disc and halo together never shrink", drawn.spriteRadiusPx >= nominal.spriteRadiusPx - 1e-9)
            previousHalo = halo
        }
        assertTrue("narrower at 1°: $previousHalo of $nominalHalo", previousHalo < 0.8 * nominalHalo)
        // A disc far bigger than its nominal glow (Jupiter at half an AU, at 1°) keeps the narrowest halo.
        val huge = BodyAppearance.drawn(position(SkyBody.JUPITER, 0.5, -2.5), focalPx(1.0), density)
        assertEquals(nominalHalo * BodyAppearance.PLANET_MIN_HALO_SCALE, huge.spriteRadiusPx - huge.discRadiusPx, 1e-6)
    }

    @Test
    fun theDiscGrowsSmoothlyFromItsNominalSize_neverJumping() {
        // Zoom in a step at a time: the disc never shrinks, and never leaps.
        var previous = BodyAppearance.drawn(moon, focalPx(60.0), density).discRadiusPx
        var fov = 60.0
        while (fov > 1.0) {
            fov *= 0.98
            val disc = BodyAppearance.drawn(moon, focalPx(fov), density).discRadiusPx
            assertTrue("fov $fov", disc >= previous)
            assertTrue("fov $fov: $previous -> $disc", disc <= previous * 1.03 + 1e-9)
            previous = disc
        }
    }

    @Test
    fun jupitersMoonsAreHiddenAtTheDefaultView_andClearAtOneDegree() {
        // Callisto never strays more than about 10′ from Jupiter: 6 px (2 dp) at 60° on this screen.
        assertEquals(0f, BodyAppearance.satelliteFade(Math.tan(Math.toRadians(10.0 / 60)), focalPx(60.0), density), 0f)
        // Io, 2.3′ out at its farthest, is about 92 px (31 dp) from Jupiter at 1°.
        assertEquals(1f, BodyAppearance.satelliteFade(Math.tan(Math.toRadians(2.3 / 60)), focalPx(1.0), density), 0f)
    }

    @Test
    fun theMoonsFadeInSmoothly_asTheViewZoomsIn() {
        val separation = Math.tan(Math.toRadians(2.3 / 60))
        var previous = 0f
        var fov = 60.0
        while (fov > 1.0) {
            val fade = BodyAppearance.satelliteFade(separation, focalPx(fov), density)
            assertTrue("fov $fov", fade >= previous && fade in 0f..1f)
            previous = fade
            fov *= 0.97
        }
        assertEquals(1f, previous, 0f)
    }

    @Test
    fun brighterMoonsAreDrawnBiggerAndBrighter() {
        assertTrue(BodyAppearance.satelliteSizeDp(5.0) > BodyAppearance.satelliteSizeDp(12.0))
        val ganymede = BodyAppearance.satelliteColor(PlanetMoon.GANYMEDE, 4.6)
        val mimas = BodyAppearance.satelliteColor(PlanetMoon.MIMAS, 12.9)
        assertTrue(ganymede.g > mimas.g && mimas.g > 0f)
    }
}
