package com.skyglass.render

import com.skyglass.assertAngleDegEquals
import com.skyglass.astro.AstroTime
import com.skyglass.astro.Coordinates
import com.skyglass.astro.Horizontal
import com.skyglass.epochMillis
import com.skyglass.math.Matrix3
import com.skyglass.math.Matrix4
import com.skyglass.math.Quaternion
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

class SkyNavigationTest {

    private val width = 1080
    private val height = 2160
    private val fovYDeg = 60.0
    private val aspect = width.toDouble() / height

    /** Eye at the origin looking down -Z with +Y up (right is +X): eye coordinates are the direction. */
    private val mvp = Matrix4.perspective(fovYDeg, aspect, SkyViewMath.NEAR, SkyViewMath.FAR)

    private fun guide(x: Double, y: Double, z: Double, insets: NavInsets = NavInsets.NONE) =
        SkyNavigation.guide(mvp, Vector3(x, y, z).normalized(), width, height, insets)

    private fun arrowOf(g: Guidance): EdgeArrow = checkNotNull(g.arrow) { "expected an arrow" }

    @Test
    fun targetAtTheCentre_isOnScreenWithNoArrow() {
        val g = guide(0.0, 0.0, -1.0)
        assertNull(g.arrow)
        assertEquals(width / 2.0, checkNotNull(g.onScreen).x, 1e-6)
        assertEquals(height / 2.0, g.onScreen!!.y, 1e-6)
        assertEquals(0.0, g.angularDistanceDeg, 1e-9)
    }

    @Test
    fun targetInView_needsNoArrowAndReportsItsDistanceFromTheCentre() {
        val g = guide(0.1, 0.0, -1.0)
        assertNull(g.arrow)
        assertNotNull(g.onScreen)
        assertEquals(Math.toDegrees(kotlin.math.atan(0.1)), g.angularDistanceDeg, 1e-9)
    }

    @Test
    fun offScreenTargets_getAnArrowOnTheEdgeFacingThem() {
        // Far enough off-axis to be outside the 30° half-height and the ~15° half-width.
        val right = arrowOf(guide(1.0, 0.0, -1.0))
        assertAngleDegEquals(0.0, right.angleDeg, 1e-9)
        assertEquals(width.toDouble(), right.position.x, 1e-9)
        assertEquals(height / 2.0, right.position.y, 1e-9)

        val left = arrowOf(guide(-1.0, 0.0, -1.0))
        assertAngleDegEquals(180.0, left.angleDeg, 1e-9)
        assertEquals(0.0, left.position.x, 1e-9)

        // Screen y points down, so "above" is a negative angle (a clockwise rotation, like Compose's).
        val up = arrowOf(guide(0.0, 2.0, -1.0))
        assertAngleDegEquals(-90.0, up.angleDeg, 1e-9)
        assertEquals(0.0, up.position.y, 1e-9)
        assertEquals(width / 2.0, up.position.x, 1e-9)

        val down = arrowOf(guide(0.0, -2.0, -1.0))
        assertAngleDegEquals(90.0, down.angleDeg, 1e-9)
        assertEquals(height.toDouble(), down.position.y, 1e-9)
    }

    @Test
    fun diagonalTarget_arrowSitsOnTheRayFromTheCentreAndInsideTheViewport() {
        val g = guide(1.0, 1.0, -1.0) // up and to the right
        val arrow = arrowOf(g)
        assertTrue("angle ${arrow.angleDeg}", arrow.angleDeg in -90.0..0.0)
        val dx = arrow.position.x - width / 2.0
        val dy = arrow.position.y - height / 2.0
        assertAngleDegEquals(arrow.angleDeg, Math.toDegrees(atan2(dy, dx)), 1e-9)
        assertTrue(arrow.position.x in 0.0..width.toDouble())
        assertTrue(arrow.position.y in 0.0..height.toDouble())
        // It sits on the border: the viewport is 1080 × 2160, so a 45° ray reaches the right edge first.
        assertEquals(width.toDouble(), arrow.position.x, 1e-9)
    }

    @Test
    fun angularDistance_isTheAngleOffTheViewAxis() {
        assertEquals(45.0, guide(1.0, 0.0, -1.0).angularDistanceDeg, 1e-9)
        assertEquals(90.0, guide(1.0, 0.0, 0.0).angularDistanceDeg, 1e-9)
        assertEquals(135.0, guide(0.0, 1.0, 1.0).angularDistanceDeg, 1e-9)
        assertEquals(180.0, guide(0.0, 0.0, 1.0).angularDistanceDeg, 1e-9)
    }

    @Test
    fun targetBehindTheViewer_isReachedByTurningTheNearerWay() {
        // Behind and to the right: the plain projection would mirror it to the left of the screen.
        assertAngleDegEquals(0.0, arrowOf(guide(0.5, 0.0, 0.8)).angleDeg, 1e-9)
        assertAngleDegEquals(180.0, arrowOf(guide(-0.5, 0.0, 0.8)).angleDeg, 1e-9)
        assertAngleDegEquals(-90.0, arrowOf(guide(0.0, 0.5, 0.8)).angleDeg, 1e-9)
        assertAngleDegEquals(90.0, arrowOf(guide(0.0, -0.5, 0.8)).angleDeg, 1e-9)
        assertNull("a target behind the viewer is never on screen", guide(0.5, 0.2, 0.8).onScreen)
    }

    @Test
    fun targetDirectlyBehind_getsAnArrowRatherThanNothing() {
        val g = guide(0.0, 0.0, 1.0)
        val arrow = arrowOf(g)
        assertAngleDegEquals(0.0, arrow.angleDeg, 1e-9)
        assertEquals(width.toDouble(), arrow.position.x, 1e-9)
        assertEquals(height / 2.0, arrow.position.y, 1e-9)
        assertNull(g.onScreen)
    }

    @Test
    fun targetOnTheHorizonOfTheView_pointsTheWayWithoutDividingByZero() {
        // 90° off axis: clip w is 0, the projection is undefined but the direction is not.
        val arrow = arrowOf(guide(1.0, 0.0, 0.0))
        assertAngleDegEquals(0.0, arrow.angleDeg, 1e-9)
        assertTrue(arrow.position.x.isFinite() && arrow.position.y.isFinite())
    }

    @Test
    fun insets_moveTheArrowInAndMakeTheMarginPartOfTheOffScreenArea() {
        val insets = NavInsets(left = 40.0, top = 300.0, right = 60.0, bottom = 200.0)
        val up = arrowOf(guide(0.0, 2.0, -1.0, insets))
        assertEquals(300.0, up.position.y, 1e-9)
        val right = arrowOf(guide(1.0, 0.0, -1.0, insets))
        assertEquals(width - 60.0, right.position.x, 1e-9)
        val left = arrowOf(guide(-1.0, 0.0, -1.0, insets))
        assertEquals(40.0, left.position.x, 1e-9)
        val down = arrowOf(guide(0.0, -2.0, -1.0, insets))
        assertEquals(height - 200.0, down.position.y, 1e-9)
    }

    @Test
    fun targetOnScreenButUnderAnInset_isStillMarkedAndAlsoGetsAnArrow() {
        // 20° above the axis is y = 1080 - tan(20°) * focal ≈ 1080 - 0.364 * 1870 ≈ 399 px: on screen, but under a 500 px top inset.
        val g = guide(0.0, Math.tan(Math.toRadians(20.0)), -1.0, NavInsets(0.0, 500.0, 0.0, 0.0))
        assertNotNull(g.onScreen)
        val arrow = arrowOf(g)
        assertAngleDegEquals(-90.0, arrow.angleDeg, 1e-9)
        assertEquals(500.0, arrow.position.y, 1e-9)
    }

    @Test
    fun targetInsideTheUsableArea_needsNoArrow() {
        val g = guide(0.0, Math.tan(Math.toRadians(20.0)), -1.0, NavInsets(0.0, 200.0, 0.0, 0.0))
        assertNull(g.arrow)
        assertNotNull(g.onScreen)
    }

    @Test
    fun insetsLargerThanTheScreen_stillGiveAnArrowAtOrNearTheCentre() {
        val arrow = arrowOf(guide(0.0, 2.0, -1.0, NavInsets(0.0, 5000.0, 0.0, 5000.0)))
        assertAngleDegEquals(-90.0, arrow.angleDeg, 1e-9)
        assertEquals(height / 2.0, arrow.position.y, 1e-9)
    }

    // The chain tests below use the real view maths, so they pin the arrow to what the renderer draws.

    private fun mvpFor(rotation: Int, quaternion: Quaternion, lst: Double, lat: Double, aspect: Double = this.aspect) =
        SkyViewMath.modelViewProjection(quaternion, 0.0, lst, lat, rotation, fovYDeg, aspect)

    private fun randomDirections(count: Int): List<Vector3> {
        val random = Random(42)
        return List(count) { Vector3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalized() }
    }

    @Test
    fun frontTargets_pointTheWayTheyAreProjected_inEveryDisplayRotation() {
        val orientation = Quaternion.fromMatrix3(Matrix3.rotationZ(0.7) * Matrix3.rotationX(1.1))
        for (rotation in 0..3) {
            val m = mvpFor(rotation, orientation, 33.0, 51.0)
            var checked = 0
            for (direction in randomDirections(400)) {
                val p = SkyProjection.project(m, direction, width, height) ?: continue
                if (SkyProjection.isOnScreen(p, width, height)) continue
                val arrow = arrowOf(SkyNavigation.guide(m, direction, width, height))
                assertAngleDegEquals(
                    Math.toDegrees(atan2(p.y - height / 2.0, p.x - width / 2.0)), arrow.angleDeg, 1e-6, "rotation $rotation",
                )
                checked++
            }
            assertTrue("rotation $rotation: $checked front targets checked", checked > 50)
        }
    }

    @Test
    fun behindTargets_pointTheWayOfTheirMirrorImageInFrontOfTheViewer() {
        val orientation = Quaternion.fromMatrix3(Matrix3.rotationZ(-0.4) * Matrix3.rotationX(2.0))
        for (rotation in 0..3) {
            val m = mvpFor(rotation, orientation, 120.0, -35.0)
            val forward = Vector3(m[3, 0], m[3, 1], m[3, 2]) // clip w is the depth along the view direction
            assertEquals(1.0, forward.length(), 1e-9)
            var checked = 0
            for (direction in randomDirections(400)) {
                if (SkyProjection.project(m, direction, width, height) != null) continue
                if (abs(direction dot forward) < 0.05) continue // grazing: w is nearly zero
                val mirror = direction - forward * (2.0 * (direction dot forward))
                val p = checkNotNull(SkyProjection.project(m, mirror, width, height))
                val arrow = arrowOf(SkyNavigation.guide(m, direction, width, height))
                assertAngleDegEquals(
                    Math.toDegrees(atan2(p.y - height / 2.0, p.x - width / 2.0)), arrow.angleDeg, 1e-6, "rotation $rotation",
                )
                checked++
            }
            assertTrue("rotation $rotation: $checked behind targets checked", checked > 50)
        }
    }

    private val lstDeg = AstroTime.localSiderealTimeDeg(AstroTime.julianDate(epochMillis("2026-10-07T21:00:00Z")), -0.0015)
    private val latDeg = 51.4779

    private fun skyAt(altDeg: Double, azDeg: Double): Vector3 {
        val enu = Coordinates.horizontalToEnu(Horizontal(Math.toRadians(altDeg), Math.toRadians(azDeg)))
        return Coordinates.equatorialToEnuMatrix(Math.toRadians(lstDeg), Math.toRadians(latDeg)).transpose() * enu
    }

    /** Device→true-ENU (declination 0, so also magnetic) for a portrait phone whose back points at (alt, az), top edge up. */
    private fun portraitPointingAt(altDeg: Double, azDeg: Double): Quaternion {
        val forward = Coordinates.horizontalToEnu(Horizontal(Math.toRadians(altDeg), Math.toRadians(azDeg)))
        val up = Vector3(0.0, 0.0, 1.0)
        val deviceY = (up - forward * (up dot forward)).normalized()
        val deviceZ = -forward
        val deviceX = deviceY cross deviceZ
        return Quaternion.fromMatrix3(Matrix3.fromColumns(deviceX, deviceY, deviceZ))
    }

    @Test
    fun facingNorth_aTargetInTheEastIsToTheRight_andOneInTheWestToTheLeft() {
        val m = mvpFor(0, portraitPointingAt(30.0, 0.0), lstDeg, latDeg)
        val east = arrowOf(SkyNavigation.guide(m, skyAt(30.0, 70.0), width, height))
        assertTrue("east: ${east.angleDeg}", cos(Math.toRadians(east.angleDeg)) > 0.8)
        val west = arrowOf(SkyNavigation.guide(m, skyAt(30.0, -70.0), width, height))
        assertTrue("west: ${west.angleDeg}", cos(Math.toRadians(west.angleDeg)) < -0.8)
    }

    @Test
    fun facingNorth_aTargetHigherInTheSkyIsAbove_andTheNadirIsBelow() {
        val m = mvpFor(0, portraitPointingAt(10.0, 0.0), lstDeg, latDeg)
        val zenith = arrowOf(SkyNavigation.guide(m, skyAt(89.0, 0.0), width, height))
        assertAngleDegEquals(-90.0, zenith.angleDeg, 1.0)
        val ground = arrowOf(SkyNavigation.guide(m, skyAt(-80.0, 0.0), width, height))
        assertAngleDegEquals(90.0, ground.angleDeg, 1.0)
    }

    @Test
    fun targetStraightAhead_isInViewWhateverTheOrientation() {
        val alt = 47.0
        val az = 215.0
        val m = mvpFor(0, portraitPointingAt(alt, az), lstDeg, latDeg)
        val g = SkyNavigation.guide(m, skyAt(alt, az), width, height)
        assertNull(g.arrow)
        assertEquals(0.0, g.angularDistanceDeg, 1e-6)
        assertEquals(width / 2.0, checkNotNull(g.onScreen).x, 1e-3)
        assertEquals(height / 2.0, g.onScreen!!.y, 1e-3)
    }
}
