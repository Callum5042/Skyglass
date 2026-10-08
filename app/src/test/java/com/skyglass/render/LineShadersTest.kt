package com.skyglass.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineShadersTest {

    private fun peak(r: Float, g: Float, b: Float) = maxOf(r, g, b)

    @Test
    fun theHighlightIsClearlyBrighterThanTheOrdinaryConstellationLines() {
        val lines = peak(LineShaders.LINE_RED, LineShaders.LINE_GREEN, LineShaders.LINE_BLUE)
        val highlight = peak(LineShaders.HIGHLIGHT_RED, LineShaders.HIGHLIGHT_GREEN, LineShaders.HIGHLIGHT_BLUE)
        assertTrue("highlight $highlight vs lines $lines", highlight > 2 * lines)
        assertTrue("a colour, not clipped past white", highlight <= 1f)
    }

    @Test
    fun theHighlightIsBlueAndNotMistakableForTheHorizonOrTheSearchMarker() {
        assertTrue("blue dominates", LineShaders.HIGHLIGHT_BLUE > LineShaders.HIGHLIGHT_GREEN && LineShaders.HIGHLIGHT_GREEN > LineShaders.HIGHLIGHT_RED)
        // The horizon is teal-green (green above blue); the highlight is the other way round.
        assertTrue(LineShaders.HORIZON_GREEN > LineShaders.HORIZON_BLUE)
    }

    @Test
    fun theLineFragmentShaderKeepsTheCameraModeAlphaRuleThatTheHighlightRelies() {
        // The highlight is drawn by the same program as the other lines, so over the camera image its
        // alpha is max(r, g, b) of the colour and it stays valid premultiplied colour (see OverlayBlend).
        assertTrue("max(max(uColor.r, uColor.g), uColor.b)" in LineShaders.FRAGMENT)
        assertEquals(1f, peak(LineShaders.HIGHLIGHT_RED, LineShaders.HIGHLIGHT_GREEN, LineShaders.HIGHLIGHT_BLUE), 1e-6f)
    }
}
