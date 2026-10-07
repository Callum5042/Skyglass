package com.skyglass.render

import com.skyglass.catalog.StarVertices
import com.skyglass.math.Matrix4
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyHitTestTest {

    private val width = 1080
    private val height = 2160
    private val density = 3f
    private val mvp = Matrix4.perspective(60.0, width.toDouble() / height, SkyViewMath.NEAR, SkyViewMath.FAR)

    private class Star(val direction: Vector3, val sizeDp: Float)

    private fun vertices(vararg stars: Star): FloatArray {
        val out = FloatArray(stars.size * StarVertices.FLOATS_PER_STAR)
        stars.forEachIndexed { i, s ->
            val d = s.direction.normalized()
            val base = i * StarVertices.FLOATS_PER_STAR
            out[base + StarVertices.POSITION_OFFSET] = d.x.toFloat()
            out[base + StarVertices.POSITION_OFFSET + 1] = d.y.toFloat()
            out[base + StarVertices.POSITION_OFFSET + 2] = d.z.toFloat()
            out[base + StarVertices.SIZE_OFFSET] = s.sizeDp
        }
        return out
    }

    private fun screenOf(direction: Vector3): ScreenPoint = checkNotNull(SkyProjection.project(mvp, direction.normalized(), width, height))

    private fun hit(vertices: FloatArray, x: Double, y: Double) =
        SkyHitTest.nearestStar(mvp, vertices, width, height, x, y, density)

    private val centre = Vector3(0.0, 0.0, -1.0)
    private val right = Vector3(0.2, 0.0, -1.0)
    private val up = Vector3(0.0, 0.2, -1.0)

    @Test
    fun tapOnAStar_hitsIt() {
        val v = vertices(Star(centre, 3f), Star(right, 3f))
        val p = screenOf(right)
        val result = checkNotNull(hit(v, p.x, p.y))
        assertEquals(1, result.index)
        assertEquals("float vertex rounding only", 0.0, result.distancePx, 0.01)
    }

    @Test
    fun tapNearAStar_hitsItWithinTolerance() {
        val v = vertices(Star(centre, 3f))
        val p = screenOf(centre)
        val reach = SkyHitTest.MIN_TAP_RADIUS_DP * density
        assertEquals(0, hit(v, p.x + reach - 2, p.y)?.index)
        assertNull("just outside reach", hit(v, p.x + reach + 2, p.y))
    }

    @Test
    fun tapInEmptySky_hitsNothing() {
        val v = vertices(Star(centre, 3f), Star(right, 3f))
        val p = screenOf(up) // nothing there
        assertNull(hit(v, p.x, p.y))
        assertNull(SkyHitTest.nearestStar(mvp, FloatArray(0), width, height, 10.0, 10.0, density))
    }

    @Test
    fun picksTheNearestOfSeveralCandidates() {
        // Two stars 40 px apart; the tap is 15 px from the second, 25 px from the first.
        val a = Vector3(0.0, 0.0, -1.0)
        val pa = screenOf(a)
        val focalPx = (height / 2.0) / kotlin.math.tan(Math.toRadians(30.0))
        val b = Vector3(40.0 / focalPx, 0.0, -1.0)
        val v = vertices(Star(a, 3f), Star(b, 3f))
        assertEquals(1, hit(v, pa.x + 25, pa.y)?.index)
        assertEquals(0, hit(v, pa.x + 15, pa.y)?.index)
    }

    @Test
    fun starsBehindTheViewerAreIgnored() {
        // The mirror image of a visible star, behind the camera, projects to the same pixel
        // if w's sign is ignored; it must not be selectable.
        val v = vertices(Star(Vector3(0.2, 0.0, 1.0), 3f))
        val p = screenOf(right)
        assertNull(hit(v, p.x, p.y))
    }

    @Test
    fun aLargeSpriteCanBeHitAcrossItsWholeGlow() {
        val bigSizeDp = 200f
        val v = vertices(Star(centre, bigSizeDp))
        val p = screenOf(centre)
        val reach = SkyHitTest.MIN_TAP_RADIUS_DP * density
        val glowRadiusPx = bigSizeDp * density / 2
        assertTrue(glowRadiusPx > reach)
        assertNotNull(hit(v, p.x, p.y + glowRadiusPx - 5))
        assertNull(hit(v, p.x, p.y + glowRadiusPx + 5))
    }

    @Test
    fun hitReachScalesWithScreenDensity() {
        val v = vertices(Star(centre, 3f))
        val p = screenOf(centre)
        val x = p.x + 60 // 60 px away
        assertNull("at 1x density 24 dp = 24 px", SkyHitTest.nearestStar(mvp, v, width, height, x, p.y, 1f))
        assertNotNull("at 3x density 24 dp = 72 px", SkyHitTest.nearestStar(mvp, v, width, height, x, p.y, 3f))
    }
}
