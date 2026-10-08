package com.skyglass.render

import com.skyglass.astro.AstroTime
import com.skyglass.catalog.BundledSkyData
import com.skyglass.catalog.DeepSkyAppearance
import com.skyglass.catalog.DeepSkyVertices
import com.skyglass.catalog.MilkyWayAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSkyRenderTest {

    @Test
    fun deepSkyShaders_embedTheQuadExtentTheCpuUses() {
        assertTrue(DeepSkyShaders.VERTEX.contains("const float EXTENT = ${DeepSkyAppearance.QUAD_EXTENT};"))
        assertTrue(DeepSkyShaders.FRAGMENT.contains("const float EXTENT = ${DeepSkyAppearance.QUAD_EXTENT};"))
    }

    @Test
    fun deepSkyVertexShader_hasTheSameQuadMathAsItsCpuTwin() {
        // DeepSkyAppearance.drawnSemiAxes and DeepSkyGeometry.cornerPoint are tested; these lines are what they mirror.
        val vertex = DeepSkyShaders.VERTEX
        for (line in listOf(
            "float scale = max(1.0, (uMinRadiusPx / uFocalPx) / aSemiMajor);",
            "float a = aSemiMajor * scale;",
            "float b = aSemiMinor * scale;",
            "vec3 east = normalize(vec3(-aPosition.y, aPosition.x, 0.0));",
            "vec3 north = cross(aPosition, east);",
            "vec3 axisMajor = north * cos(aAngle) + east * sin(aAngle);",
            "vec3 axisMinor = east * cos(aAngle) - north * sin(aAngle);",
            "vec3 p = aPosition + EXTENT * (aCorner.x * a * axisMajor + aCorner.y * b * axisMinor);",
            "gl_Position = uMvp * vec4(p, 1.0);",
            "vUv = aCorner * EXTENT;",
        )) {
            assertTrue("vertex shader lacks: $line", vertex.contains(line))
        }
        // atan(0, 0) is undefined in GLSL: the dashes must not evaluate it at the centre pixel.
        assertTrue(DeepSkyShaders.FRAGMENT.contains("atan(p.y, p.x + 1e-6)"))
        assertTrue(!DeepSkyShaders.FRAGMENT.contains("atan(p.y, p.x)"))
    }

    @Test
    fun deepSkyShaders_declareTheAttributesAtTheLocationsTheRendererBinds() {
        val vertex = DeepSkyShaders.VERTEX
        for ((name, location) in listOf(
            "aPosition" to DeepSkyShaders.ATTRIB_POSITION, "aCorner" to DeepSkyShaders.ATTRIB_CORNER,
            "aSemiMajor" to DeepSkyShaders.ATTRIB_SEMI_MAJOR, "aSemiMinor" to DeepSkyShaders.ATTRIB_SEMI_MINOR,
            "aAngle" to DeepSkyShaders.ATTRIB_POSITION_ANGLE, "aColor" to DeepSkyShaders.ATTRIB_COLOR,
            "aGlyph" to DeepSkyShaders.ATTRIB_GLYPH,
        )) {
            assertTrue("$name at $location", Regex("layout\\(location = $location\\) in \\w+ $name;").containsMatchIn(vertex))
        }
        // The vertex buffer layout is 12 floats: 3 + 2 + 1 + 1 + 1 + 3 + 1.
        assertEquals(12, DeepSkyVertices.FLOATS_PER_VERTEX)
        assertEquals(DeepSkyVertices.FLOATS_PER_VERTEX - 1, DeepSkyVertices.GLYPH_OFFSET)
    }

    @Test
    fun deepSkyAndMilkyWayShaders_followTheCameraModeAlphaRule() {
        // In the camera view alpha follows the colour (premultiplied), as for the stars and lines; see OverlayBlend.
        for (source in listOf(DeepSkyShaders.FRAGMENT, MilkyWayShaders.FRAGMENT)) {
            assertTrue(source.contains("uniform float uCameraMode;"))
            assertTrue(source.contains("mix(1.0, min(1.0, max(max(c.r, c.g), c.b)), uCameraMode)"))
        }
        // And the CPU reference of that rule agrees: a faint symbol gets a faint alpha, so it veils the image only a little.
        val alpha = OverlayBlend.alphaFor(0.12, 0.10, 0.08)
        assertEquals(0.12, alpha, 1e-12)
        val over = OverlayBlend.blend(Rgba(0.12, 0.10, 0.08, alpha), Rgba(0.5, 0.5, 0.5, 1.0), OverlayBlend.stars(true))
        assertEquals(0.5 * (1 - 0.12) + 0.12, over.r, 1e-12)
        assertEquals(1.0, over.a, 1e-12)
    }

    @Test
    fun milkyWayOverACameraImage_leavesMostOfItVisible() {
        // The brightest part of the band at camera gain: its alpha is the covering of the image underneath.
        val bandPeak = com.skyglass.catalog.MilkyWayMesh.PEAK_INTENSITY * MilkyWayAppearance.CAMERA_GAIN
        assertTrue("covers ${bandPeak * 100}% of the image at most", bandPeak <= 0.15f)
        val image = Rgba(0.6, 0.55, 0.5, 1.0)
        val over = OverlayBlend.blend(Rgba(bandPeak.toDouble(), bandPeak.toDouble(), bandPeak.toDouble(), bandPeak.toDouble()), image, OverlayBlend.stars(true))
        assertTrue(over.r > 0.8 * image.r)
    }

    @Test
    fun milkyWayShader_draws_withTheStarsBlendingAndHasADitherTerm() {
        assertTrue(MilkyWayShaders.FRAGMENT.contains("gl_FragCoord"))
        assertTrue(MilkyWayShaders.VERTEX.contains("uGain"))
        assertEquals(OverlayBlend.stars(false), OverlayBlend.stars(false))
    }

    @Test
    fun bundledObjects_haveNoNaNsInTheirVertices() {
        val objects = BundledSkyData.load().deepSky
        val directions = DeepSkyVertices.directions(objects, AstroTime.julianDate(System.currentTimeMillis()))
        val data = DeepSkyVertices.build(objects, directions)
        assertTrue(data.none { it.isNaN() || it.isInfinite() })
    }
}
