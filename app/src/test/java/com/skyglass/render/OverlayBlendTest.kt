package com.skyglass.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayBlendTest {

    private val clear = Rgba(0.0, 0.0, 0.0, 0.0)
    private val black = Rgba(0.0, 0.0, 0.0, 1.0)

    /** A star/line/glow fragment as the shaders emit it in camera mode: alpha is the largest channel. */
    private fun fragment(r: Double, g: Double, b: Double) = Rgba(r, g, b, OverlayBlend.alphaFor(r, g, b))

    private fun assertPremultipliedValid(p: Rgba) {
        val eps = 1e-12
        assertTrue("$p", p.r <= p.a + eps && p.g <= p.a + eps && p.b <= p.a + eps)
        assertTrue("$p", p.a in 0.0..1.0)
    }

    @Test
    fun enhancedView_addsColourAndLeavesTheOpaqueAlphaAlone() {
        val f = OverlayBlend.stars(cameraMode = false)
        val once = OverlayBlend.blend(Rgba(0.3, 0.2, 0.1, 1.0), black, f)
        assertEquals(0.3, once.r, 1e-12)
        assertEquals(1.0, once.a, 0.0)
        val twice = OverlayBlend.blend(Rgba(0.3, 0.2, 0.1, 1.0), once, f)
        assertEquals(0.6, twice.r, 1e-12)
        assertEquals(0.4, twice.g, 1e-12)
        assertEquals(1.0, twice.a, 0.0)
        // Saturates rather than wrapping.
        assertEquals(1.0, OverlayBlend.blend(Rgba(0.9, 0.0, 0.0, 1.0), twice, f).r, 0.0)
    }

    @Test
    fun enhancedView_bodyGlowsStillAdd() {
        // Alpha 0 glow with the "over" factors: out = src + dst.
        val out = OverlayBlend.blend(Rgba(0.2, 0.2, 0.1, 0.0), Rgba(0.3, 0.3, 0.3, 1.0), OverlayBlend.bodies())
        assertEquals(0.5, out.r, 1e-12)
        assertEquals(0.4, out.b, 1e-12)
        assertEquals(1.0, out.a, 0.0)
    }

    @Test
    fun cameraView_overlappingFragmentsStayValidPremultipliedColour() {
        val f = OverlayBlend.stars(cameraMode = true)
        var pixel = clear
        val fragments = listOf(
            fragment(0.13, 0.22, 0.38), // constellation line
            fragment(1.0, 0.9, 0.7), // bright star core
            fragment(0.05, 0.04, 0.03), // faint star edge
            fragment(0.6, 0.6, 0.6),
            fragment(0.0, 0.0, 0.0),
        )
        for (round in 1..3) for (frag in fragments) {
            assertPremultipliedValid(frag)
            pixel = OverlayBlend.blend(frag, pixel, f)
            assertPremultipliedValid(pixel)
        }
    }

    @Test
    fun cameraView_aFragmentOverTransparentKeepsItsColour_andOverAnOpaqueMoonIsBlendedOverIt() {
        val f = OverlayBlend.stars(cameraMode = true)
        val star = fragment(0.4, 0.3, 0.2)
        val onClear = OverlayBlend.blend(star, clear, f)
        assertEquals(0.4, onClear.r, 1e-12)
        assertEquals(0.4, onClear.a, 1e-12)

        val moon = Rgba(0.8, 0.8, 0.8, 1.0)
        // out = src + (1 − src.a) · dst, with src.a = 0.4: the star lightens the moon a little.
        val overMoon = OverlayBlend.blend(star, moon, f)
        assertPremultipliedValid(overMoon)
        assertEquals(0.4 + 0.6 * 0.8, overMoon.r, 1e-12)
        assertEquals(0.3 + 0.6 * 0.8, overMoon.g, 1e-12)
        assertEquals(0.2 + 0.6 * 0.8, overMoon.b, 1e-12)
        assertEquals(1.0, overMoon.a, 1e-12)
    }

    @Test
    fun cameraView_bodiesAreValidToo() {
        val f = OverlayBlend.bodies()
        var pixel = OverlayBlend.blend(fragment(0.9, 0.8, 0.6), clear, f) // Sun glow
        pixel = OverlayBlend.blend(Rgba(0.7, 0.7, 0.6, 1.0), pixel, f) // Moon disc
        pixel = OverlayBlend.blend(fragment(0.3, 0.2, 0.2), pixel, f) // planet halo
        assertPremultipliedValid(pixel)
    }

    @Test
    fun alphaIsTheLargestChannelClampedToOne() {
        assertEquals(0.7, OverlayBlend.alphaFor(0.2, 0.7, 0.4), 0.0)
        assertEquals(1.0, OverlayBlend.alphaFor(1.5, 0.0, 0.0), 0.0)
        assertEquals(0.0, OverlayBlend.alphaFor(0.0, 0.0, 0.0), 0.0)
    }

    @Test
    fun enhancedViewBlendIsTheOriginalOne() {
        // glBlendFunc(ONE, ONE) for colour, with alpha untouched; the Moon pass used (ONE, ONE_MINUS_SRC_ALPHA).
        val s = OverlayBlend.stars(cameraMode = false)
        assertEquals(OverlayBlend.Factor.ONE, s.srcRgb)
        assertEquals(OverlayBlend.Factor.ONE, s.dstRgb)
        assertEquals(OverlayBlend.Factor.ZERO, s.srcAlpha)
        assertEquals(OverlayBlend.Factor.ONE, s.dstAlpha)
        val b = OverlayBlend.bodies()
        assertEquals(OverlayBlend.Factor.ONE, b.srcRgb)
        assertEquals(OverlayBlend.Factor.ONE_MINUS_SRC_ALPHA, b.dstRgb)
    }
}
