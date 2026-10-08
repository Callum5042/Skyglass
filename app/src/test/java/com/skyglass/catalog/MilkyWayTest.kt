package com.skyglass.catalog

import com.skyglass.assertVecEquals
import com.skyglass.astro.AstroTime
import com.skyglass.astro.Galactic
import com.skyglass.astro.Precession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MilkyWayTest {

    private fun brightness(l: Double, b: Double) = MilkyWayModel.brightness(l, b)

    @Test
    fun brightness_staysWithinZeroAndOne_andPeaksNearTheGalacticCentre() {
        var peak = 0.0
        var peakL = 0.0
        var peakB = 0.0
        for (l in 0 until 360) for (b in -90..90 step 2) {
            val v = brightness(l.toDouble(), b.toDouble())
            assertTrue("l=$l b=$b: $v", v in 0.0..1.0)
            if (v > peak) { peak = v; peakL = l.toDouble(); peakB = b.toDouble() }
        }
        assertTrue("peak $peak", peak > 0.9)
        assertTrue("peak at l=$peakL b=$peakB", (peakL <= 15 || peakL >= 345) && peakB in -8.0..8.0)
    }

    @Test
    fun theBandIsBrightestAlongThePlane() {
        for (l in listOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0)) {
            val plane = maxOf(brightness(l, 0.0), brightness(l, 2.0), brightness(l, -2.0))
            assertTrue("l=$l plane $plane", plane > brightness(l, 20.0) * 3)
            assertTrue("l=$l", brightness(l, 20.0) > brightness(l, 40.0))
            assertTrue("l=$l far from the plane", brightness(l, 60.0) < 0.01)
        }
    }

    @Test
    fun theInnerGalaxyIsBrighterThanTheAnticentre() {
        val centre = brightness(0.0, -2.0)
        val anticentre = brightness(180.0, 0.0)
        assertTrue("centre $centre anticentre $anticentre", centre > 2.5 * anticentre)
        assertTrue(anticentre > 0.1) // still a visible band on the far side
    }

    @Test
    fun theBandIsThickerNearTheCentreThanAtTheAnticentre() {
        // Latitude at which the band has fallen to a tenth of its value on the plane.
        fun halfWidth(l: Double): Double {
            val onPlane = brightness(l, 0.0)
            var b = 0.0
            while (b < 60 && brightness(l, b) > 0.1 * onPlane) b += 0.5
            return b
        }
        assertTrue(halfWidth(0.0) > halfWidth(180.0))
    }

    @Test
    fun theStarCloudsStandOutFromTheirSurroundings() {
        // Cygnus (l ~ 78) against the same latitude a little round the circle.
        assertTrue(brightness(78.0, 1.0) > brightness(105.0, 1.0))
        // Scutum (l ~ 29) against the Aquila side of it.
        assertTrue(brightness(29.0, 0.0) > brightness(50.0, 0.0))
        // Carina (l ~ 287) against Vela.
        assertTrue(brightness(287.0, -1.0) > brightness(255.0, -1.0))
    }

    @Test
    fun theGreatRiftSplitsTheBandInCygnusAndAquila() {
        // Along l = 30..70 the rift dims the plane just north of the equator against both sides of it.
        for (l in listOf(30.0, 45.0, 60.0)) {
            val rift = brightness(l, 2.5)
            val south = brightness(l, -4.0)
            assertTrue("l=$l rift $rift south $south", rift < south)
        }
        assertTrue(MilkyWayModel.darkLanes(45.0, 2.0) < 0.6)
        // No rift on the far side of the Galaxy, and none away from the plane.
        assertEquals(1.0, MilkyWayModel.darkLanes(180.0, 1.0), 1e-6)
        assertTrue(MilkyWayModel.darkLanes(45.0, 15.0) > 0.99)
    }

    @Test
    fun theCoalsackIsADarkPatchInTheSouthernMilkyWay() {
        assertTrue(MilkyWayModel.darkLanes(301.0, 1.0) < 0.3)
        assertTrue(MilkyWayModel.darkLanes(301.0, 12.0) > 0.99)
        assertTrue(brightness(301.0, 1.0) < brightness(301.0, -6.0))
    }

    @Test
    fun theLongitudeWrapsAround() {
        assertEquals(brightness(-30.0, 1.0), brightness(330.0, 1.0), 1e-12)
        assertEquals(brightness(0.0, 1.0), brightness(360.0, 1.0), 1e-12)
    }

    @Test
    fun theCentreIsWarmerThanTheOuterDisc() {
        assertTrue(MilkyWayModel.warmth(0.0, 0.0) > 0.9)
        assertTrue(MilkyWayModel.warmth(180.0, 0.0) < 0.05)
        assertTrue(MilkyWayModel.warmth(0.0, 0.0) in 0.0..1.0)
    }

    @Test
    fun mesh_hasUnitPositionsInTheEquatorialFrameOfDate() {
        val jd = AstroTime.JD_J2000 + 9500
        val mesh = MilkyWayMesh.build(jd)
        assertEquals(MilkyWayMesh.LATITUDES.size * MilkyWayMesh.LONGITUDES.size, mesh.vertexCount)
        for (i in 0 until mesh.vertexCount) {
            val base = i * MilkyWayMesh.FLOATS_PER_VERTEX
            val length = Math.sqrt(
                (0..2).sumOf { k -> (mesh.vertices[base + MilkyWayMesh.POSITION_OFFSET + k].toDouble()).let { it * it } },
            )
            assertEquals(1.0, length, 1e-6)
        }
        // The vertex at (l = 0, b = 0) is the galactic centre, precessed.
        val row = MilkyWayMesh.LATITUDES.toList().indexOf(0.0)
        val centre = Precession.matrixFromJ2000(AstroTime.julianCenturiesSinceJ2000(jd)) * Galactic.toEquatorialUnit(0.0, 0.0)
        val base = (row * MilkyWayMesh.LONGITUDES.size) * MilkyWayMesh.FLOATS_PER_VERTEX
        val actual = com.skyglass.math.Vector3(
            mesh.vertices[base].toDouble(), mesh.vertices[base + 1].toDouble(), mesh.vertices[base + 2].toDouble(),
        )
        assertVecEquals(centre, actual, 1e-6)
    }

    @Test
    fun mesh_coversTheWholeCircleWithAClosedSeam() {
        val columns = MilkyWayMesh.LONGITUDES.size
        assertEquals(0.0, MilkyWayMesh.LONGITUDES.first(), 0.0)
        assertEquals(360.0, MilkyWayMesh.LONGITUDES.last(), 1e-9)
        val mesh = MilkyWayMesh.build(AstroTime.JD_J2000)
        for (r in MilkyWayMesh.LATITUDES.indices) {
            val first = r * columns * MilkyWayMesh.FLOATS_PER_VERTEX
            val last = (r * columns + columns - 1) * MilkyWayMesh.FLOATS_PER_VERTEX
            for (k in 0 until MilkyWayMesh.FLOATS_PER_VERTEX) {
                assertEquals("row $r float $k", mesh.vertices[first + k].toDouble(), mesh.vertices[last + k].toDouble(), 1e-5)
            }
        }
        assertEquals(MilkyWayMesh.LATITUDES.sorted(), MilkyWayMesh.LATITUDES.toList())
        assertEquals(-MilkyWayMesh.MAX_LATITUDE_DEG, MilkyWayMesh.LATITUDES.first(), 0.0)
        assertEquals(MilkyWayMesh.MAX_LATITUDE_DEG, MilkyWayMesh.LATITUDES.last(), 0.0)
    }

    @Test
    fun mesh_indicesFormValidTriangles_andColoursAreFaintAndPremultiplied() {
        val mesh = MilkyWayMesh.build(AstroTime.JD_J2000)
        assertEquals(0, mesh.indices.size % 3)
        val triangles = (MilkyWayMesh.LATITUDES.size - 1) * (MilkyWayMesh.LONGITUDES.size - 1) * 2
        assertEquals(triangles * 3, mesh.indices.size)
        for (index in mesh.indices) assertTrue(index.toInt() in 0 until mesh.vertexCount)
        var brightest = 0f
        for (i in 0 until mesh.vertexCount) {
            for (k in 0..2) {
                val c = mesh.vertices[i * MilkyWayMesh.FLOATS_PER_VERTEX + MilkyWayMesh.COLOR_OFFSET + k]
                assertTrue(c in 0f..MilkyWayMesh.PEAK_INTENSITY)
                if (c > brightest) brightest = c
            }
        }
        // The band must stay a faint glow, far below the stars' full brightness, and not vanish either.
        assertTrue("brightest $brightest", brightest in 0.15f..0.3f)
        // Over a camera image it is drawn at only half strength.
        assertTrue(MilkyWayAppearance.CAMERA_GAIN <= 0.5f)
    }

    @Test
    fun mesh_edgeRowsAreEssentiallyBlack_soTheBandHasNoVisibleEdge() {
        val mesh = MilkyWayMesh.build(AstroTime.JD_J2000)
        val columns = MilkyWayMesh.LONGITUDES.size
        for (r in listOf(0, MilkyWayMesh.LATITUDES.size - 1)) for (c in 0 until columns) {
            for (k in 0..2) {
                val value = mesh.vertices[(r * columns + c) * MilkyWayMesh.FLOATS_PER_VERTEX + MilkyWayMesh.COLOR_OFFSET + k]
                assertTrue("row $r column $c: $value", value < 0.002f)
            }
        }
    }
}
