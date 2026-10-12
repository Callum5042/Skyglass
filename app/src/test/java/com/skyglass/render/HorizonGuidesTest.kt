package com.skyglass.render

import com.skyglass.assertVecEquals
import com.skyglass.astro.AstroTime
import com.skyglass.astro.CompassPoint
import com.skyglass.astro.Coordinates
import com.skyglass.astro.SkyPlacement
import com.skyglass.epochMillis
import com.skyglass.math.Matrix3
import com.skyglass.math.Matrix4
import com.skyglass.math.Quaternion
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

/**
 * The horizon line, grid and compass letters: that they are what they claim to be (true-ENU unit
 * vectors at the right altitude and azimuth), and that through the same orientation, declination
 * and display rotation the stars use they land where the sky does.
 */
class HorizonGuidesTest {

    private val width = 1080
    private val height = 2340
    private val aspect = width.toDouble() / height
    private val fovYDeg = 60.0
    private val latDeg = 51.4779
    private val lonDeg = -0.0015
    private val declinationDeg = 10.0

    private fun rad(deg: Double) = Math.toRadians(deg)

    // --- Geometry ---------------------------------------------------------------------------

    private fun vertices(array: FloatArray): List<Vector3> =
        (0 until array.size / HorizonGuides.FLOATS_PER_VERTEX).map { i ->
            Vector3(array[3 * i].toDouble(), array[3 * i + 1].toDouble(), array[3 * i + 2].toDouble())
        }

    @Test
    fun horizon_isAClosedLoopOfUnitVectorsAtAltitudeZero() {
        val horizon = vertices(HorizonGuides.build().horizon)
        assertTrue(horizon.size % 2 == 0)
        assertTrue(horizon.size >= 2 * 90)
        for (v in horizon) {
            assertEquals("unit length", 1.0, v.length(), 1e-6)
            assertEquals("up component", 0.0, v.z, 1e-6)
            assertEquals("altitude", 0.0, Math.toDegrees(Coordinates.enuToHorizontal(v).altRad), 1e-4)
        }
        // Each segment starts where the previous one ended, and the last meets the first.
        for (i in 1 until horizon.size / 2) {
            assertVecEquals(horizon[2 * i - 1], horizon[2 * i], 1e-6, "segment $i")
        }
        assertVecEquals(horizon.last(), horizon.first(), 1e-6, "closing")
        // It goes all the way round: azimuths at 0, 90, 180 and 270 are on it.
        for (point in CompassPoint.entries) {
            val wanted = CompassLabels.horizonDirection(point)
            assertTrue("$point on the horizon", horizon.any { (it - wanted).length() < 0.02 })
        }
    }

    @Test
    fun grid_hasTheDocumentedAltitudeCirclesVerticalLinesAndZenithCross() {
        val grid = vertices(HorizonGuides.build().grid)
        assertTrue(grid.size % 2 == 0)
        for (v in grid) assertEquals("unit length", 1.0, v.length(), 1e-6)

        // Every vertex is on one of the circles, on a vertical line, or on the zenith cross.
        val altitudes = grid.map { Math.toDegrees(Coordinates.enuToHorizontal(it).altRad) }
        for (circle in HorizonGuides.ALTITUDE_CIRCLES_DEG) {
            val onCircle = altitudes.count { abs(it - circle) < 1e-4 }
            assertTrue("circle at $circle° has $onCircle vertices", onCircle >= 2 * 90)
        }
        // Nothing below the horizon, and nothing between the vertical lines' tops and the cross
        // except the cross itself.
        assertTrue("above the horizon", altitudes.all { it > -1e-4 })
        val crossAltitude = 90.0 - HorizonGuides.ZENITH_CROSS_RADIUS_DEG
        assertTrue(altitudes.all { it <= HorizonGuides.MERIDIAN_TOP_DEG + 1e-4 || abs(it - crossAltitude) < 1e-4 })

        // The vertical lines: one every 45° of azimuth, each with a vertex at the horizon and at the top.
        for (point in CompassPoint.entries) {
            val atHorizon = grid.any { (it - CompassLabels.horizonDirection(point)).length() < 1e-6 }
            val top = HorizonGuides.direction(HorizonGuides.MERIDIAN_TOP_DEG, point.azimuthDeg)
            assertTrue("$point at the horizon", atHorizon)
            assertTrue("$point at the top", grid.any { (it - top).length() < 1e-6 })
        }

        // The zenith cross: opposite points at the same altitude, so each chord passes through the zenith.
        val north = HorizonGuides.direction(crossAltitude, 0.0)
        val south = HorizonGuides.direction(crossAltitude, 180.0)
        assertTrue(grid.any { (it - north).length() < 1e-6 } && grid.any { (it - south).length() < 1e-6 })
    }

    // --- Compass directions and conventions ------------------------------------------------

    @Test
    fun compassDirections_areEnuAxes_withTrueNorthAsPlusY() {
        assertVecEquals(Vector3(0.0, 1.0, 0.0), CompassLabels.horizonDirection(CompassPoint.N), 1e-12, "N")
        assertVecEquals(Vector3(1.0, 0.0, 0.0), CompassLabels.horizonDirection(CompassPoint.E), 1e-12, "E")
        assertVecEquals(Vector3(0.0, -1.0, 0.0), CompassLabels.horizonDirection(CompassPoint.S), 1e-12, "S")
        assertVecEquals(Vector3(-1.0, 0.0, 0.0), CompassLabels.horizonDirection(CompassPoint.W), 1e-12, "W")
        val half = Math.sqrt(0.5)
        assertVecEquals(Vector3(half, half, 0.0), CompassLabels.horizonDirection(CompassPoint.NE), 1e-12, "NE")
        assertVecEquals(Vector3(-half, half, 0.0), CompassLabels.horizonDirection(CompassPoint.NW), 1e-12, "NW")
        assertVecEquals(Vector3(half, -half, 0.0), CompassLabels.horizonDirection(CompassPoint.SE), 1e-12, "SE")
        assertVecEquals(Vector3(-half, -half, 0.0), CompassLabels.horizonDirection(CompassPoint.SW), 1e-12, "SW")
    }

    @Test
    fun compassPoint_azimuthAndCardinalFlag() {
        assertEquals(listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0), CompassPoint.entries.map { it.azimuthDeg })
        assertEquals(
            listOf(CompassPoint.N, CompassPoint.E, CompassPoint.S, CompassPoint.W),
            CompassPoint.entries.filter { it.isCardinal },
        )
        for (point in CompassPoint.entries) assertEquals(point, CompassPoint.of(point.azimuthDeg))
    }

    @Test
    fun anchor_sitsAboveTheHorizonPointOnTheSameAzimuth() {
        for (point in CompassPoint.entries) {
            val h = Coordinates.enuToHorizontal(CompassLabels.anchorDirection(point))
            assertEquals(CompassLabels.ANCHOR_ALTITUDE_DEG, Math.toDegrees(h.altRad), 1e-9)
            assertEquals(point.azimuthDeg, Math.toDegrees(h.azRad), 1e-9)
        }
    }

    // --- Through the view matrices ---------------------------------------------------------

    /** Device→true-ENU for a portrait phone whose back points at (alt, az), top edge upward. */
    private fun portraitPointingAt(altDeg: Double, azDeg: Double): Matrix3 {
        val forward = HorizonGuides.direction(altDeg, azDeg)
        val up = Vector3(0.0, 0.0, 1.0)
        val deviceY = (up - forward * (up dot forward)).normalized()
        val deviceZ = -forward
        val deviceX = deviceY cross deviceZ
        return Matrix3.fromColumns(deviceX, deviceY, deviceZ)
    }

    /**
     * Device→true-ENU for the same view with the display rotated by [displayRotation]: the eye
     * frame must come out upright, so device = portrait · S(r). (eye = S · deviceᵀ · v = S · S⁻¹ · portraitᵀ · v.)
     */
    private fun pointingAt(altDeg: Double, azDeg: Double, displayRotation: Int): Matrix3 =
        portraitPointingAt(altDeg, azDeg) * SkyViewMath.deviceToScreen(displayRotation)

    /** What the sensor reports: device → *magnetic* ENU, from a true-ENU orientation and the declination. */
    private fun sensor(deviceToTrueEnu: Matrix3, declDeg: Double = declinationDeg): Quaternion =
        Quaternion.fromMatrix3(Coordinates.magneticToTrueEnu(rad(declDeg)).transpose() * deviceToTrueEnu)

    private fun horizonMvp(q: Quaternion, declDeg: Double = declinationDeg, displayRotation: Int = 0) =
        SkyViewMath.horizonModelViewProjection(q, declDeg, displayRotation, fovYDeg, aspect)

    private fun ndc(mvp: Matrix4, v: Vector3): Triple<Double, Double, Double> {
        val c = mvp.transform(v.x, v.y, v.z)
        return Triple(c[0] / c[3], c[1] / c[3], c[3])
    }

    @Test
    fun facingNorth_northIsAtCentre_eastToTheRight_westToTheLeft() {
        val q = sensor(portraitPointingAt(0.0, 0.0))
        val mvp = horizonMvp(q)
        val (nx, ny, nw) = ndc(mvp, CompassLabels.horizonDirection(CompassPoint.N))
        assertTrue(nw > 0)
        assertEquals(0.0, nx, 1e-9)
        assertEquals(0.0, ny, 1e-9)
        assertTrue("east to the right", ndc(mvp, HorizonGuides.direction(0.0, 30.0)).first > 0.1)
        assertTrue("west to the left", ndc(mvp, HorizonGuides.direction(0.0, -30.0)).first < -0.1)
    }

    @Test
    fun facingEast_eastIsAtCentre_andNorthIsToTheLeft() {
        val mvp = horizonMvp(sensor(portraitPointingAt(0.0, 90.0)))
        val (ex, ey, _) = ndc(mvp, CompassLabels.horizonDirection(CompassPoint.E))
        assertEquals(0.0, ex, 1e-9)
        assertEquals(0.0, ey, 1e-9)
        // Turning right from north reaches east, so from east north is on the left.
        assertTrue(ndc(mvp, HorizonGuides.direction(0.0, 60.0)).first < -0.1)
    }

    @Test
    fun declinationCorrection_putsTrueNorthWhereThePhoneIsReallyPointing() {
        // Declination +10°: magnetic north is 10° east of true north, so a phone aimed at true north
        // is aimed 10° *west* of magnetic north: the sensor reads a magnetic azimuth of 350°.
        // The quaternion is built from that reading directly, not through magneticToTrueEnu.
        val magneticReading = Quaternion.fromMatrix3(portraitPointingAt(0.0, -declinationDeg))

        val corrected = ndc(horizonMvp(magneticReading, declDeg = declinationDeg), CompassLabels.horizonDirection(CompassPoint.N))
        assertEquals("true north at the centre", 0.0, corrected.first, 1e-9)

        // Left uncorrected the letters would be off by the declination, to the right of centre:
        // the sky would be turned 10° the wrong way. tan(10°)/tan(fovX/2) of half the width.
        val uncorrected = ndc(horizonMvp(magneticReading, declDeg = 0.0), CompassLabels.horizonDirection(CompassPoint.N))
        val halfFovXTan = tan(rad(fovYDeg / 2)) * aspect
        assertEquals(tan(rad(declinationDeg)) / halfFovXTan, uncorrected.first, 1e-9)
        assertTrue(uncorrected.first > 0.3)

        // And for a western declination the correction goes the other way.
        val west = Quaternion.fromMatrix3(portraitPointingAt(0.0, 7.0))
        assertEquals(0.0, ndc(horizonMvp(west, declDeg = -7.0), CompassLabels.horizonDirection(CompassPoint.N)).first, 1e-9)
    }

    @Test
    fun tiltedUp_theHorizonDropsBelowCentre() {
        val mvp = horizonMvp(sensor(portraitPointingAt(20.0, 0.0)))
        // Dead ahead at altitude 0 is 20° below the view centre: y = -tan(20°)/tan(fovY/2).
        val (x, y, w) = ndc(mvp, CompassLabels.horizonDirection(CompassPoint.N))
        assertTrue(w > 0)
        assertEquals(0.0, x, 1e-9)
        assertEquals(-tan(rad(20.0)) / tan(rad(fovYDeg / 2)), y, 1e-9)
    }

    @Test
    fun allFourDisplayRotations_keepTheLettersAtTheirPlaces() {
        for (rotation in 0..3) {
            val q = sensor(pointingAt(0.0, 0.0, rotation))
            val mvp = horizonMvp(q, displayRotation = rotation)
            val (nx, ny, nw) = ndc(mvp, CompassLabels.horizonDirection(CompassPoint.N))
            assertTrue("rotation $rotation: in front", nw > 0)
            assertEquals("rotation $rotation: N x", 0.0, nx, 1e-9)
            assertEquals("rotation $rotation: N y", 0.0, ny, 1e-9)
            assertTrue("rotation $rotation: E right", ndc(mvp, HorizonGuides.direction(0.0, 30.0)).first > 0.05)
            assertTrue("rotation $rotation: up is up", ndc(mvp, HorizonGuides.direction(20.0, 0.0)).second > 0.05)

            // Placed through the pixel mapping: the north letter is centred horizontally and its
            // anchor, a degree up the sky, is above the screen centre (smaller y).
            val placed = CompassLabels.place(mvp, width, height).first { it.point == CompassPoint.N }
            assertEquals("rotation $rotation", width / 2.0, placed.position.x, 1e-6)
            assertTrue("rotation $rotation: above the line", placed.position.y < height / 2.0)
        }
    }

    @Test
    fun lettersBehindTheViewer_areRejected() {
        val mvp = horizonMvp(sensor(portraitPointingAt(0.0, 0.0)))
        val points = CompassLabels.place(mvp, width, height, marginPx = 1e6).map { it.point }
        assertTrue(CompassPoint.N in points)
        for (behind in listOf(CompassPoint.S, CompassPoint.SE, CompassPoint.SW)) {
            assertFalse("$behind is behind the viewer", behind in points)
        }
        // Even a huge margin cannot bring back something with clip w <= 0, whose perspective
        // divide would mirror it through the centre.
        assertNull(SkyProjection.project(mvp, CompassLabels.anchorDirection(CompassPoint.S), width, height))
    }

    @Test
    fun lettersOffScreen_areDropped_butOnesJustOutsideSurviveWithAMargin() {
        // Facing north the field of view is only about 30° across, so just N is on screen.
        val mvp = horizonMvp(sensor(portraitPointingAt(0.0, 0.0)))
        assertEquals(listOf(CompassPoint.N), CompassLabels.place(mvp, width, height).map { it.point })

        // Turn until NE sits 16° right of the centre, a little past the right edge (half the field is 14.9°).
        val q = sensor(portraitPointingAt(0.0, 45.0 - 16.0))
        val m = horizonMvp(q)
        val edge = SkyProjection.project(m, CompassLabels.anchorDirection(CompassPoint.NE), width, height)!!
        assertTrue("just right of the screen: x = ${edge.x}", edge.x in width.toDouble()..width + 60.0)
        assertFalse(CompassPoint.NE in CompassLabels.place(m, width, height).map { it.point })
        assertTrue(CompassPoint.NE in CompassLabels.place(m, width, height, marginPx = 100.0).map { it.point })
    }

    // --- Agreement with the equatorial chain -----------------------------------------------

    @Test
    fun horizonMvp_agreesWithTheStarMvp_onceTheSkyIsTurnedBackToEnu() {
        val lstDeg = AstroTime.localSiderealTimeDeg(AstroTime.julianDate(epochMillis("2026-10-07T21:00:00Z")), lonDeg)
        val enuToEquatorial = Coordinates.equatorialToEnuMatrix(rad(lstDeg), rad(latDeg)).transpose()
        for (rotation in 0..3) {
            val q = sensor(pointingAt(25.0, 140.0, rotation))
            val star = SkyViewMath.modelViewProjection(q, declinationDeg, lstDeg, latDeg, rotation, fovYDeg, aspect)
            val horizon = horizonMvp(q, displayRotation = rotation)
            for (point in CompassPoint.entries) {
                for (altitude in listOf(0.0, 1.0, 40.0)) {
                    val enu = HorizonGuides.direction(altitude, point.azimuthDeg)
                    val viaStars = ndc(star, enuToEquatorial * enu)
                    val direct = ndc(horizon, enu)
                    assertEquals("$point $altitude rotation $rotation x", viaStars.first, direct.first, 1e-9)
                    assertEquals("$point $altitude rotation $rotation y", viaStars.second, direct.second, 1e-9)
                    assertEquals("$point $altitude rotation $rotation w", viaStars.third, direct.third, 1e-9)
                }
            }
        }
    }

    @Test
    fun horizonVertices_areAtAltitudeZeroForTheObserver_whenCarriedToTheEquatorialFrame() {
        val jd = AstroTime.julianDate(epochMillis("2026-10-07T21:00:00Z"))
        val lstDeg = AstroTime.localSiderealTimeDeg(jd, lonDeg)
        val enuToEquatorial = Coordinates.equatorialToEnuMatrix(rad(lstDeg), rad(latDeg)).transpose()
        for (v in vertices(HorizonGuides.build().horizon)) {
            val placement = SkyPlacement.of(enuToEquatorial * v, jd, latDeg, lonDeg)
            assertEquals(0.0, placement.altitudeDeg, 1e-4)
        }
        // The same, from the other side, for a labelled point: the south point of the horizon has
        // azimuth 180° and the celestial equator crosses the meridian there at altitude 90° − φ.
        val southEquatorial = enuToEquatorial * CompassLabels.horizonDirection(CompassPoint.S)
        val placement = SkyPlacement.of(southEquatorial, jd, latDeg, lonDeg)
        assertEquals(180.0, placement.azimuthDeg, 1e-6)
        assertEquals(0.0, placement.altitudeDeg, 1e-6)
        assertEquals(-(90.0 - latDeg), placement.decDeg, 1e-6)
    }

    @Test
    fun viewMatrix_isHorizonViewTimesTheEquatorialStep() {
        val lstDeg = 123.4
        val q = sensor(pointingAt(10.0, 200.0, 1))
        val full = SkyViewMath.viewMatrix(q, declinationDeg, lstDeg, latDeg, 1)
        val composed = SkyViewMath.horizonViewMatrix(q, declinationDeg, 1) *
            Coordinates.equatorialToEnuMatrix(rad(lstDeg), rad(latDeg))
        for (i in 0..2) for (j in 0..2) assertEquals("[$i,$j]", full[i, j], composed[i, j], 1e-15)
    }

    @Test
    fun theLettersSitTheSameDistanceAboveTheLine_atAnyZoom_inEveryDisplayRotation() {
        val density = 3f
        for (rotation in 0..3) for (fov in listOf(1.0, 60.0, 90.0)) {
            val q = sensor(pointingAt(0.0, 0.0, rotation))
            val mvp = SkyViewMath.horizonModelViewProjection(q, declinationDeg, rotation, fov, aspect)
            val focalPx = (height / 2.0) / tan(rad(fov / 2))
            val raise = CompassLabels.anchorAltitudeDeg(focalPx, density)
            val placed = CompassLabels.place(mvp, width, height, anchorAltitudeDeg = raise).first { it.point == CompassPoint.N }
            val onLine = checkNotNull(SkyProjection.project(mvp, CompassLabels.horizonDirection(CompassPoint.N), width, height))
            assertEquals("rotation $rotation fov $fov", width / 2.0, placed.position.x, 1e-6)
            assertEquals("rotation $rotation fov $fov", CompassLabels.ANCHOR_RAISE_DP * density, onLine.y - placed.position.y, 1e-6)
        }
    }

    @Test
    fun atTheDefaultView_theRaiseIsAboutADegree() {
        // On a 2340 px tall phone at 3x the 13 dp raise is close to the degree the letters always sat at.
        val focalPx = (height / 2.0) / tan(rad(fovYDeg / 2))
        assertEquals(CompassLabels.ANCHOR_ALTITUDE_DEG, CompassLabels.anchorAltitudeDeg(focalPx, 3f), 0.15)
    }
}
