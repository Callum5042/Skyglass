package com.skyglass.render

import com.skyglass.astro.Coordinates
import com.skyglass.astro.Equatorial
import com.skyglass.catalog.DeepSkyObject
import com.skyglass.catalog.DeepSkyType
import com.skyglass.catalog.StarVertices
import com.skyglass.math.Matrix3
import com.skyglass.math.Matrix4
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/**
 * Tapping deep-sky objects, and choosing between them and stars. The view looks at RA 0°, Dec 0° of the
 * equatorial frame; there east (increasing RA) is up the screen and north is to the right.
 */
class DeepSkyHitTestTest {

    private val width = 1080
    private val height = 2160
    private val fovYDeg = 60.0
    private val view = Matrix4.fromMatrix3(Matrix3.rotationY(Math.PI / 2)) // RA 0, Dec 0 → straight ahead (−Z)
    private val mvp = Matrix4.perspective(fovYDeg, width.toDouble() / height, SkyViewMath.NEAR, SkyViewMath.FAR) * view
    private val focalPx = (height / 2.0) / tan(Math.toRadians(fovYDeg / 2))

    private fun direction(raDeg: Double, decDeg: Double) =
        Coordinates.raDecToUnit(Equatorial(Math.toRadians(raDeg), Math.toRadians(decDeg)))

    private fun obj(
        id: String,
        major: Double,
        minor: Double = major,
        positionAngleDeg: Double? = null,
        type: DeepSkyType = DeepSkyType.GALAXY,
    ) = DeepSkyObject(
        designations = listOf(id), name = null, aliases = emptyList(), type = type, j2000 = Equatorial(0.0, 0.0),
        vmag = 8.0, majorArcmin = major, minorArcmin = minor, positionAngleDeg = positionAngleDeg, constellation = "Test",
    )

    private fun screenOf(direction: Vector3) = checkNotNull(SkyProjection.project(mvp, direction, width, height))

    private fun hit(objects: List<DeepSkyObject>, directions: List<Vector3>, x: Double, y: Double, density: Float = 3f) =
        SkyHitTest.nearestDeepSky(mvp, objects, directions, width, height, x, y, density, focalPx)

    private val centre = direction(0.0, 0.0)
    private val right = direction(0.0, 11.3) // north of the centre: to the right

    @Test
    fun tapOnADeepSkyObject_hitsIt() {
        val objects = listOf(obj("A", 5.0), obj("B", 5.0))
        val p = screenOf(right)
        val result = checkNotNull(hit(objects, listOf(centre, right), p.x, p.y))
        assertEquals(1, result.index)
        assertEquals(0.0, result.distancePx, 0.01)
    }

    @Test
    fun aSmallObjectIsHitWithinAFingertip_butNotBeyond() {
        val objects = listOf(obj("A", 1.0))
        val p = screenOf(centre)
        assertNotNull(hit(objects, listOf(centre), p.x + 20 * 3, p.y))
        assertNull(hit(objects, listOf(centre), p.x + 40 * 3, p.y))
    }

    @Test
    fun aBigObjectIsHitAnywhereInsideItsDrawnExtent() {
        // 10 degrees across at this field of view is a radius of about 160 px.
        val objects = listOf(obj("LMC", 600.0))
        val p = screenOf(centre)
        val radiusPx = objects[0].majorArcmin / 120.0 * Math.PI / 180 * focalPx
        assertTrue(radiusPx > 100)
        assertNotNull(hit(objects, listOf(centre), p.x + radiusPx * 0.9, p.y))
        assertNotNull(hit(objects, listOf(centre), p.x, p.y - radiusPx * 0.9))
        assertNull(hit(objects, listOf(centre), p.x + radiusPx * 1.1, p.y))
    }

    @Test
    fun aThinGalaxyIsHitAlongItsLongAxis_notBesideIt() {
        // 10 degrees by 1 degree, long axis north through east at PA 0, i.e. across the screen to the right:
        // about 163 px by 16 px.
        val objects = listOf(obj("thin", 600.0, 60.0, positionAngleDeg = 0.0))
        val p = screenOf(centre)
        assertNotNull("along the long axis", hit(objects, listOf(centre), p.x + 100, p.y, density = 1f))
        assertNotNull("along the long axis, other way", hit(objects, listOf(centre), p.x - 100, p.y, density = 1f))
        assertNull("beside the middle of it, beyond a fingertip and its width", hit(objects, listOf(centre), p.x, p.y - 60, density = 1f))
        assertNull("beside its far end", hit(objects, listOf(centre), p.x + 100, p.y - 60, density = 1f))
        // The same object turned a quarter turn (PA 90°, along east: up the screen) is hit up there instead.
        val turned = listOf(obj("thin", 600.0, 60.0, positionAngleDeg = 90.0))
        assertNotNull(hit(turned, listOf(centre), p.x, p.y - 100, density = 1f))
        assertNull(hit(turned, listOf(centre), p.x + 100, p.y, density = 1f))
    }

    @Test
    fun anObjectWithNoPositionAngleIsHitAsACircle() {
        val objects = listOf(obj("round-ish", 600.0, 60.0, positionAngleDeg = null))
        val p = screenOf(centre)
        assertNotNull(hit(objects, listOf(centre), p.x, p.y - 100, density = 1f))
    }

    @Test
    fun whereObjectsOverlap_theNearerCentreWins() {
        // M32 inside M31's glow.
        val objects = listOf(obj("M31", 190.0, 60.0, 35.0), obj("M32", 8.7, 6.5))
        val m32 = direction(0.0, 0.69)
        val p = screenOf(m32)
        assertEquals(1, checkNotNull(hit(objects, listOf(centre, m32), p.x, p.y)).index)
        val q = screenOf(centre)
        assertEquals(0, checkNotNull(hit(objects, listOf(centre, m32), q.x, q.y)).index)
    }

    @Test
    fun objectsBehindTheViewerAreNotHit() {
        val objects = listOf(obj("A", 600.0))
        assertNull(hit(objects, listOf(direction(180.0, 0.0)), width / 2.0, height / 2.0))
    }

    // Choosing between the hits of one tap.

    private fun starAt(raDeg: Double, decDeg: Double): FloatArray {
        val d = direction(raDeg, decDeg)
        val out = FloatArray(StarVertices.FLOATS_PER_STAR)
        out[StarVertices.POSITION_OFFSET] = d.x.toFloat()
        out[StarVertices.POSITION_OFFSET + 1] = d.y.toFloat()
        out[StarVertices.POSITION_OFFSET + 2] = d.z.toFloat()
        out[StarVertices.SIZE_OFFSET] = 3f
        return out
    }

    private fun tap(x: Double, y: Double, objects: List<DeepSkyObject>, directions: List<Vector3>, stars: FloatArray): SkyHitTest.Target {
        val deepSky = SkyHitTest.nearestDeepSky(mvp, objects, directions, width, height, x, y, 3f, focalPx)
        val star = SkyHitTest.nearestStar(mvp, stars, width, height, x, y, 3f)
        return SkyHitTest.choose(null, star, deepSky)
    }

    @Test
    fun tappingTheMiddleOfABigObject_selectsItEvenWithAStarWithinAFingertip() {
        // The LMC-sized object, with a star 40 px from the middle: a finger's reach is 72 px, so the star is a hit too.
        val objects = listOf(obj("LMC", 600.0))
        val starAhead = starAt(0.0, 40.0 / focalPx * 180 / Math.PI)
        val p = screenOf(centre)
        assertEquals(SkyHitTest.Target.DEEP_SKY, tap(p.x, p.y, objects, listOf(centre), starAhead))
        assertEquals(SkyHitTest.Target.DEEP_SKY, tap(p.x + 10, p.y, objects, listOf(centre), starAhead))
    }

    @Test
    fun tappingRightOnAStarInsideABigObject_selectsTheStar() {
        val objects = listOf(obj("LMC", 600.0))
        val starRa = 0.0
        val starDec = 80.0 / focalPx * 180 / Math.PI // 80 px right of the middle
        val stars = starAt(starRa, starDec)
        val s = screenOf(direction(starRa, starDec))
        assertEquals(SkyHitTest.Target.STAR, tap(s.x, s.y, objects, listOf(centre), stars))
        assertEquals(SkyHitTest.Target.STAR, tap(s.x + 8, s.y, objects, listOf(centre), stars))
        // Off the star, in the object: the object.
        assertEquals(SkyHitTest.Target.DEEP_SKY, tap(s.x - 70, s.y + 20, objects, listOf(centre), stars))
    }

    @Test
    fun nearTheRimOfABigObject_aStarWithinAFingertipWinsAsItDoesAnywhere() {
        val objects = listOf(obj("LMC", 600.0))
        val rimPx = 150.0 // inside the ~163 px radius
        val stars = starAt(0.0, (rimPx + 30) / focalPx * 180 / Math.PI)
        val p = screenOf(centre)
        // 30 px from the star, 150 px from the middle: the object's effective distance is ~66 px.
        assertEquals(SkyHitTest.Target.STAR, tap(p.x + rimPx, p.y, objects, listOf(centre), stars))
    }

    @Test
    fun choose_prefersABodyOnATie_aStarOnlyIfCloser_andADeepSkyObjectOnlyIfStrictlyCloser() {
        val body = BodyHit(0, 10.0)
        val star = StarHit(0, 10.0)
        val sky = DeepSkyHit(0, 10.0)
        assertEquals(SkyHitTest.Target.BODY, SkyHitTest.choose(body, star, null))
        assertEquals(SkyHitTest.Target.STAR, SkyHitTest.choose(body, StarHit(0, 9.0), null))
        assertEquals(SkyHitTest.Target.BODY, SkyHitTest.choose(body, null, null))
        assertEquals(SkyHitTest.Target.STAR, SkyHitTest.choose(null, star, null))
        assertEquals(SkyHitTest.Target.DEEP_SKY, SkyHitTest.choose(null, null, sky))
        assertEquals(SkyHitTest.Target.NONE, SkyHitTest.choose(null, null, null))
        assertEquals("ties go to the foreground", SkyHitTest.Target.BODY, SkyHitTest.choose(body, star, sky))
        assertEquals(SkyHitTest.Target.STAR, SkyHitTest.choose(null, star, sky))
        assertEquals(SkyHitTest.Target.DEEP_SKY, SkyHitTest.choose(body, star, DeepSkyHit(0, 9.9)))
        assertEquals(SkyHitTest.Target.BODY, SkyHitTest.choose(BodyHit(0, 5.0), StarHit(0, 8.0), DeepSkyHit(0, 6.0)))
    }
}
