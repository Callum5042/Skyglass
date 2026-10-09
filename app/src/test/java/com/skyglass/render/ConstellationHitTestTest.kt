package com.skyglass.render

import com.skyglass.catalog.ConstellationLabel
import com.skyglass.catalog.ConstellationSky
import com.skyglass.catalog.ConstellationSpan
import com.skyglass.math.Matrix4
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstellationHitTestTest {

    private val width = 1080
    private val height = 2160
    private val density = 3f
    private val mvp = Matrix4.perspective(60.0, width.toDouble() / height, SkyViewMath.NEAR, SkyViewMath.FAR)
    private val reachPx = ConstellationHitTest.LINE_REACH_DP * density

    /** Straight ahead, which is the middle of the screen, (540, 1080). */
    private val centre = Vector3(0.0, 0.0, -1.0)

    private fun screenOf(direction: Vector3) = checkNotNull(SkyProjection.project(mvp, direction.normalized(), width, height))

    private fun sky(vararg figures: Triple<String, Vector3, Vector3>, labels: List<ConstellationLabel> = emptyList()): ConstellationSky {
        val vertices = ArrayList<Float>()
        val spans = ArrayList<ConstellationSpan>()
        for ((i, figure) in figures.withIndex()) {
            for (v in listOf(figure.second, figure.third)) {
                val n = v.normalized()
                vertices += listOf(n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
            }
            spans += ConstellationSpan(figure.first, "Figure ${figure.first}", 2 * i, 2)
        }
        return ConstellationSky(vertices.toFloatArray(), labels, spans)
    }

    private fun nearest(sky: ConstellationSky, x: Double, y: Double) =
        ConstellationHitTest.nearestLine(mvp, sky, width, height, x, y, density)

    private fun labelAt(sky: ConstellationSky, sizes: FloatArray, x: Double, y: Double) =
        ConstellationHitTest.labelAt(mvp, sky, sizes, width, height, x, y, density)

    /** One horizontal line through the middle of the screen. */
    private val across = sky(Triple("Aaa", Vector3(-0.2, 0.0, -1.0), Vector3(0.2, 0.0, -1.0)))

    @Test
    fun tapOnALineHitsItsConstellation() {
        val hit = checkNotNull(nearest(across, 540.0, 1080.0))
        assertEquals("Aaa", hit.abbreviation)
        assertEquals("Figure Aaa", hit.name)
        assertEquals(0.0, hit.distancePx, 0.5)
    }

    @Test
    fun tapBesideALineHitsWithinReachAndMissesBeyondIt() {
        val onLine = screenOf(Vector3(0.1, 0.0, -1.0))
        val near = nearest(across, onLine.x, onLine.y + reachPx - 3)
        assertNotNull(near)
        assertEquals(reachPx - 3, near!!.distancePx, 0.5)
        assertNull(nearest(across, onLine.x, onLine.y + reachPx + 3))
    }

    @Test
    fun theSegmentEndsStopTheLine() {
        val end = screenOf(Vector3(0.2, 0.0, -1.0))
        assertNotNull("just past the end, within reach", nearest(across, end.x + 20, end.y))
        assertNull("well past the end", nearest(across, end.x + reachPx + 20, end.y))
    }

    @Test
    fun theNearerOfTwoConstellationsWins() {
        val two = sky(
            Triple("Aaa", Vector3(-0.2, 0.05, -1.0), Vector3(0.2, 0.05, -1.0)),
            Triple("Bbb", Vector3(-0.2, -0.05, -1.0), Vector3(0.2, -0.05, -1.0)),
        )
        val a = screenOf(Vector3(0.0, 0.05, -1.0))
        val b = screenOf(Vector3(0.0, -0.05, -1.0))
        assertTrue("the lines are further apart than a fingertip, so a tap can tell them apart", b.y - a.y > 2 * reachPx)
        assertEquals("Aaa", nearest(two, a.x, a.y + 5)!!.abbreviation)
        assertEquals("Bbb", nearest(two, b.x, b.y - 5)!!.abbreviation)
        assertNull("between them, out of reach of both", nearest(two, a.x, (a.y + b.y) / 2))
    }

    @Test
    fun aLineBehindTheViewerIsNotHit() {
        val behind = sky(Triple("Aaa", Vector3(-0.2, 0.0, 1.0), Vector3(0.2, 0.0, 1.0)))
        assertNull(nearest(behind, 540.0, 1080.0))
        assertNull(nearest(behind, 0.0, 1080.0))
    }

    @Test
    fun aLineRunningBehindTheViewerCountsOnlyWhereItIsDrawn() {
        // From straight ahead to behind and to the right: drawn as a ray from the centre toward the right edge and beyond.
        val partly = sky(Triple("Aaa", centre, Vector3(1.0, 0.0, 0.5)))
        assertNotNull("on the drawn part", nearest(partly, 700.0, 1080.0))
        assertNull("off it", nearest(partly, 700.0, 1080.0 + 200))
        assertNull("to the left of the start", nearest(partly, 100.0, 1080.0))
    }

    private val paddingPx = ConstellationHitTest.LABEL_PADDING_DP * density

    @Test
    fun tapOnTheNameOfAConstellationHitsIt_asFarAsTheDrawnTextAndItsPadding() {
        val named = sky(labels = listOf(ConstellationLabel("Figure Aaa", centre, "Aaa")))
        // A long name: 300 x 48 px, centred on the middle of the screen.
        val sizes = floatArrayOf(300f, 48f)
        val halfWidth = 150 + paddingPx
        val halfHeight = 24 + paddingPx
        val hit = checkNotNull(labelAt(named, sizes, 540.0 + halfWidth - 2, 1080.0 + halfHeight - 2))
        assertEquals("Aaa", hit.abbreviation)
        assertEquals("Figure Aaa", hit.name)
        assertNull(labelAt(named, sizes, 540.0 + halfWidth + 2, 1080.0))
        assertNull(labelAt(named, sizes, 540.0, 1080.0 + halfHeight + 2))
        assertNull("the names are not lines", nearest(named, 540.0, 1080.0))
    }

    @Test
    fun aShortNameHasASmallerBoxThanALongOne() {
        val named = sky(labels = listOf(ConstellationLabel("Figure Aaa", centre, "Aaa")))
        val x = 540.0 + 100 + paddingPx - 2
        assertNotNull(labelAt(named, floatArrayOf(200f, 48f), x, 1080.0))
        assertNull(labelAt(named, floatArrayOf(60f, 48f), x, 1080.0))
    }

    @Test
    fun aNameWithoutAMeasuredSizeUsesTheFallbackBox() {
        val named = sky(labels = listOf(ConstellationLabel("Figure Aaa", centre, "Aaa")))
        val halfWidth = (ConstellationHitTest.FALLBACK_LABEL_WIDTH_DP / 2 + ConstellationHitTest.LABEL_PADDING_DP) * density
        assertNotNull(labelAt(named, FloatArray(0), 540.0 + halfWidth - 2, 1080.0))
        assertNull(labelAt(named, FloatArray(0), 540.0 + halfWidth + 2, 1080.0))
    }

    @Test
    fun ofTwoOverlappingNamesTheOneWhoseMiddleIsNearerWins() {
        val a = Vector3(0.0, 0.0, -1.0)
        val b = Vector3(0.0, -0.02, -1.0) // a little lower on the screen
        val named = sky(labels = listOf(ConstellationLabel("Figure Aaa", a, "Aaa"), ConstellationLabel("Figure Bbb", b, "Bbb")))
        val sizes = floatArrayOf(300f, 48f, 300f, 48f)
        val pa = screenOf(a)
        val pb = screenOf(b)
        assertTrue("the boxes overlap", pb.y - pa.y < 48 + 2 * paddingPx)
        assertEquals("Aaa", labelAt(named, sizes, pa.x, pa.y + 2)!!.abbreviation)
        assertEquals("Bbb", labelAt(named, sizes, pb.x, pb.y - 2)!!.abbreviation)
    }

    @Test
    fun aNameWhoseAnchorIsOffScreenOrBehindIsNotHit() {
        // Not drawn by the overlay, so not tappable, even where its box would reach onto the screen.
        val offRight = Vector3(0.30, 0.0, -1.0)
        val p = screenOf(offRight)
        assertTrue("the anchor is just off the right edge", p.x > width && p.x < width + 100)
        val named = sky(labels = listOf(ConstellationLabel("Figure Aaa", offRight, "Aaa")))
        assertNull(labelAt(named, floatArrayOf(400f, 48f), width - 5.0, p.y))
        val behind = sky(labels = listOf(ConstellationLabel("Figure Aaa", Vector3(0.0, 0.0, 1.0), "Aaa")))
        assertNull(labelAt(behind, floatArrayOf(400f, 48f), 540.0, 1080.0))
    }

    @Test
    fun noConstellationsMeansNoHit() {
        assertNull(nearest(ConstellationSky(FloatArray(0), emptyList(), emptyList()), 540.0, 1080.0))
    }
}
