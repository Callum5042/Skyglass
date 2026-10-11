package com.skyglass.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeTest {

    private val fragmentShaders = mapOf(
        "StarShaders" to StarShaders.FRAGMENT,
        "LineShaders" to LineShaders.FRAGMENT,
        "BodyShaders" to BodyShaders.FRAGMENT,
        "DeepSkyShaders" to DeepSkyShaders.FRAGMENT,
        "MilkyWayShaders" to MilkyWayShaders.FRAGMENT,
        "ConstellationArtShaders" to ConstellationArtShaders.FRAGMENT,
    )

    private fun assertPremultipliedValid(p: Rgba) {
        val eps = 1e-12
        assertTrue("$p", p.r <= p.a + eps && p.g <= p.a + eps && p.b <= p.a + eps)
    }

    @Test
    fun sky_putsTheLumaInRed_andKeepsAlpha() {
        val white = NightMode.sky(Rgba(1.0, 1.0, 1.0, 1.0))
        assertEquals(1.0, white.r, 1e-6)
        assertEquals(0.0, white.g, 0.0)
        assertEquals(0.0, white.b, 0.0)
        assertEquals(1.0, white.a, 0.0)
        // The faint blue constellation lines stay faint (0.299·0.13 + 0.587·0.22 + 0.114·0.38).
        val line = NightMode.sky(Rgba(0.13, 0.22, 0.38, 0.0))
        assertEquals(0.21133, line.r, 1e-6)
        assertEquals(0.0, line.a, 0.0) // a glow with alpha 0 still adds in the enhanced view
        // A blue-white star (StarAppearance's desaturated B−V −0.4 colour) keeps most of its strength.
        assertEquals(0.299 * 0.766 + 0.587 * 0.814 + 0.114 * 1.0, NightMode.sky(Rgba(0.766, 0.814, 1.0, 1.0)).r, 1e-6)
    }

    @Test
    fun sky_keepsCameraViewFragmentsValidPremultipliedColour() {
        val fragments = listOf(
            Rgba(0.13, 0.22, 0.38, OverlayBlend.alphaFor(0.13, 0.22, 0.38)),
            Rgba(1.0, 0.9, 0.7, OverlayBlend.alphaFor(1.0, 0.9, 0.7)),
            Rgba(0.74, 0.74, 0.74, 0.93), // the Moon's disc: alpha above its colour
            Rgba(0.0, 0.0, 0.0, 0.0),
        )
        for (f in fragments) {
            assertPremultipliedValid(f)
            val red = NightMode.sky(f)
            assertPremultipliedValid(red)
            assertEquals(f.a, red.a, 0.0)
        }
    }

    @Test
    fun everyFragmentShaderDeclaresTheSwitchAndEndsWithTheNightModeStatement() {
        for ((name, source) in fragmentShaders) {
            assertTrue(name, source.contains(NightMode.GLSL_UNIFORM))
            val body = source.trimEnd().removeSuffix("}").trimEnd()
            assertTrue("$name must apply night mode last", body.endsWith(NightMode.GLSL_APPLY))
        }
    }

    @Test
    fun ui_matrixAndReferenceAgree_andWhiteBecomesFullRed() {
        val m = NightMode.UI_COLOR_MATRIX
        assertEquals(20, m.size)
        fun applyMatrix(c: Rgba): Rgba {
            val v = doubleArrayOf(c.r, c.g, c.b, c.a)
            val out = DoubleArray(4) { row -> (0 until 4).sumOf { m[row * 5 + it] * v[it] } + m[row * 5 + 4] / 255.0 }
            return Rgba(out[0], out[1], out[2], out[3])
        }
        for (c in listOf(Rgba(1.0, 1.0, 1.0, 1.0), Rgba(0.8, 0.82, 0.88, 0.8), Rgba(0.91, 0.72, 0.29, 1.0), Rgba(0.0, 0.0, 1.0, 0.5))) {
            val expected = NightMode.ui(c)
            val actual = applyMatrix(c)
            assertEquals(expected.r, actual.r, 1e-6)
            assertEquals(0.0, actual.g, 0.0)
            assertEquals(0.0, actual.b, 0.0)
            assertEquals(c.a, actual.a, 0.0)
        }
        // Rec. 601 luma weights sum to 1: white stays at full strength, mid-grey stays mid.
        assertEquals(1.0, NightMode.ui(Rgba(1.0, 1.0, 1.0, 1.0)).r, 1e-6)
        assertEquals(0.5, NightMode.ui(Rgba(0.5, 0.5, 0.5, 1.0)).r, 1e-6)
        // Pure green, red and blue in the usual 0.587 : 0.299 : 0.114 proportion.
        assertEquals(0.587, NightMode.ui(Rgba(0.0, 1.0, 0.0, 1.0)).r, 1e-6)
        assertEquals(0.299, NightMode.ui(Rgba(1.0, 0.0, 0.0, 1.0)).r, 1e-6)
        assertEquals(0.114, NightMode.ui(Rgba(0.0, 0.0, 1.0, 1.0)).r, 1e-6)
    }

    @Test
    fun clearColor_isOpaqueBlackInTheEnhancedView_andDimsTheCameraImageOnlyInNightMode() {
        assertEquals(Rgba(0.0, 0.0, 0.0, 1.0), OverlayBlend.clearColor(cameraMode = false, nightMode = false))
        assertEquals(Rgba(0.0, 0.0, 0.0, 1.0), OverlayBlend.clearColor(cameraMode = false, nightMode = true))
        assertEquals(Rgba(0.0, 0.0, 0.0, 0.0), OverlayBlend.clearColor(cameraMode = true, nightMode = false))
        val night = OverlayBlend.clearColor(cameraMode = true, nightMode = true)
        assertPremultipliedValid(night)
        assertEquals(NightMode.CAMERA_IMAGE_COVER, night.a, 0.0)
        assertTrue(night.a in 0.0..1.0)

        // A red star drawn "over" the dimmed surface stays valid premultiplied colour.
        val star = NightMode.sky(Rgba(0.4, 0.3, 0.2, OverlayBlend.alphaFor(0.4, 0.3, 0.2)))
        val pixel = OverlayBlend.blend(star, night, OverlayBlend.stars(cameraMode = true))
        assertPremultipliedValid(pixel)
        assertEquals(0.299 * 0.4 + 0.587 * 0.3 + 0.114 * 0.2, pixel.r, 1e-6)
        assertEquals(0.4 + 0.6 * NightMode.CAMERA_IMAGE_COVER, pixel.a, 1e-12)
    }
}
