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

    private class Star(val direction: Vector3, val sizeDp: Float, val vmag: Float = 0f)

    private fun vertices(vararg stars: Star): FloatArray {
        val out = FloatArray(stars.size * StarVertices.FLOATS_PER_STAR)
        stars.forEachIndexed { i, s ->
            val d = s.direction.normalized()
            val base = i * StarVertices.FLOATS_PER_STAR
            out[base + StarVertices.POSITION_OFFSET] = d.x.toFloat()
            out[base + StarVertices.POSITION_OFFSET + 1] = d.y.toFloat()
            out[base + StarVertices.POSITION_OFFSET + 2] = d.z.toFloat()
            out[base + StarVertices.SIZE_OFFSET] = s.sizeDp
            out[base + StarVertices.VMAG_OFFSET] = s.vmag
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

    @Test
    fun starsDimmedOutByTheLimitingMagnitudeCannotBeTapped() {
        val v = vertices(Star(centre, 3f, vmag = 5.0f), Star(right, 3f, vmag = 1.0f))
        val p = screenOf(centre)
        fun hitWithLimit(limit: Double) = SkyHitTest.nearestStar(mvp, v, width, height, p.x, p.y, density, limit)

        assertEquals("no dimming", 0, hitWithLimit(7.0)?.index)
        assertEquals("at the limit", 0, hitWithLimit(5.0)?.index)
        assertNull("just fainter than the limit", hitWithLimit(4.9))
        // The bright star at the right is 0.2 rad away: far outside the 24 dp reach, so nothing else is hit.
        assertNull("well past the limit", hitWithLimit(3.0))
    }

    @Test
    fun aBrightStarStaysTappableWhenFaintOnesAreDimmed() {
        val v = vertices(Star(centre, 3f, vmag = 5.0f), Star(right, 3f, vmag = 1.0f))
        val p = screenOf(right)
        assertEquals(1, SkyHitTest.nearestStar(mvp, v, width, height, p.x, p.y, density, 2.0)?.index)
    }

    @Test
    fun aStarIsOnTargetOnlyWithinItsSpritePlusTheSlack() {
        val v = vertices(Star(centre, 3f))
        val p = screenOf(centre)
        val onTargetPx = (3f / 2 + SkyHitTest.ON_TARGET_SLACK_DP) * density // 16.5 px
        assertTrue(checkNotNull(hit(v, p.x + onTargetPx - 1, p.y)).onTarget)
        val near = checkNotNull(hit(v, p.x + onTargetPx + 1, p.y))
        assertTrue("still within a fingertip", !near.onTarget)
        // A big bright star is on target across its whole sprite and the slack beyond.
        val bright = vertices(Star(centre, 12f))
        assertTrue(checkNotNull(hit(bright, p.x + (6 + SkyHitTest.ON_TARGET_SLACK_DP) * density - 1, p.y)).onTarget)
    }

    @Test
    fun starsAtListsEveryStarWithinReach_nearestFirst() {
        val a = Vector3(0.004, 0.0, -1.0)
        val b = Vector3(-0.001, 0.0, -1.0)
        val v = vertices(Star(a, 3f), Star(b, 3f), Star(right, 3f))
        val p = screenOf(centre)
        val hits = SkyHitTest.starsAt(mvp, v, width, height, p.x, p.y, density)
        assertEquals(listOf(1, 0), hits.map { it.index })
        assertEquals(hits.first(), hit(v, p.x, p.y))
    }
}

class SkyHitTestBodiesTest {

    private val width = 1080
    private val height = 2160
    private val density = 3f
    private val mvp = Matrix4.perspective(60.0, width.toDouble() / height, SkyViewMath.NEAR, SkyViewMath.FAR)

    private fun body(body: com.skyglass.astro.SkyBody, direction: Vector3, vmag: Double = 0.0) =
        com.skyglass.astro.BodyPosition(
            body = body,
            direction = direction.normalized(),
            vmag = vmag,
            distanceAu = 1.0,
            elongationDeg = 90.0,
            illuminatedFraction = 0.5,
            sunDirection = Vector3(1.0, 0.0, 0.0),
        )

    private fun screenOf(direction: Vector3): ScreenPoint =
        checkNotNull(SkyProjection.project(mvp, direction.normalized(), width, height))

    private val centre = Vector3(0.0, 0.0, -1.0)
    private val right = Vector3(0.2, 0.0, -1.0)

    @Test
    fun tapOnABody_hitsIt() {
        val bodies = listOf(
            body(com.skyglass.astro.SkyBody.VENUS, centre, -4.0),
            body(com.skyglass.astro.SkyBody.MARS, right, 1.0),
        )
        val p = screenOf(right)
        val hit = checkNotNull(SkyHitTest.nearestBody(mvp, bodies, width, height, p.x, p.y, density))
        assertEquals(1, hit.index)
        assertEquals(0.0, hit.distancePx, 0.01)
    }

    @Test
    fun tapInEmptySky_hitsNoBody() {
        val bodies = listOf(body(com.skyglass.astro.SkyBody.MARS, centre, 1.0))
        val p = screenOf(right)
        assertNull(SkyHitTest.nearestBody(mvp, bodies, width, height, p.x, p.y, density))
        assertNull(SkyHitTest.nearestBody(mvp, emptyList(), width, height, 10.0, 10.0, density))
    }

    @Test
    fun theSunsLargeGlowCanBeHitAwayFromItsCentre() {
        val sun = body(com.skyglass.astro.SkyBody.SUN, centre, -26.7)
        val p = screenOf(centre)
        val glowRadiusPx = com.skyglass.catalog.BodyAppearance.spriteSizeDp(sun) * density / 2
        assertTrue(glowRadiusPx > SkyHitTest.MIN_TAP_RADIUS_DP * density)
        assertNotNull(SkyHitTest.nearestBody(mvp, listOf(sun), width, height, p.x, p.y + glowRadiusPx - 5, density))
        assertNull(SkyHitTest.nearestBody(mvp, listOf(sun), width, height, p.x, p.y + glowRadiusPx + 5, density))
    }

    @Test
    fun bodiesBehindTheViewerAreIgnored() {
        val behind = listOf(body(com.skyglass.astro.SkyBody.MOON, Vector3(0.2, 0.0, 1.0), -12.7))
        val p = screenOf(right)
        assertNull(SkyHitTest.nearestBody(mvp, behind, width, height, p.x, p.y, density))
    }

    @Test
    fun aSpriteDrawnSmallerThanNominal_isHitOnlyAcrossWhatIsDrawn() {
        val sun = body(com.skyglass.astro.SkyBody.SUN, centre, -26.7)
        val p = screenOf(centre)
        // Nominal radius is 108 px at 3x, but the GPU draws at most 100 px across, i.e. 50 px radius.
        // The 24 dp (72 px) minimum reach still applies, so use a point beyond both.
        val tap = p.y + 80
        assertNotNull(SkyHitTest.nearestBody(mvp, listOf(sun), width, height, p.x, tap, density))
        assertNull(SkyHitTest.nearestBody(mvp, listOf(sun), width, height, p.x, tap, density, maxSpriteSizePx = 100f))
    }

    @Test
    fun aBodyIsOnTargetOnItsDiscNotAcrossItsWholeGlow() {
        val sun = body(com.skyglass.astro.SkyBody.SUN, centre, -26.7)
        val p = screenOf(centre)
        val discPx = (com.skyglass.catalog.BodyAppearance.discRadiusDp(sun) + SkyHitTest.ON_TARGET_SLACK_DP) * density
        val glowRadiusPx = com.skyglass.catalog.BodyAppearance.spriteSizeDp(sun) * density / 2
        assertTrue(discPx < glowRadiusPx - 10)
        assertTrue(checkNotNull(SkyHitTest.nearestBody(mvp, listOf(sun), width, height, p.x, p.y + discPx - 1, density)).onTarget)
        val inGlow = checkNotNull(SkyHitTest.nearestBody(mvp, listOf(sun), width, height, p.x, p.y + glowRadiusPx - 5, density))
        assertTrue("hit, but not on target", !inGlow.onTarget)
    }
}
