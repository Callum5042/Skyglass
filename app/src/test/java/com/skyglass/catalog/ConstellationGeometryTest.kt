package com.skyglass.catalog

import com.skyglass.assertVecEquals
import com.skyglass.astro.AstroTime
import com.skyglass.astro.Coordinates
import com.skyglass.astro.Equatorial
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos

class ConstellationGeometryTest {

    private fun star(hr: Int, raDeg: Double, decDeg: Double) =
        Star(hr, Equatorial(Math.toRadians(raDeg), Math.toRadians(decDeg)), 3.0, null, 0.0, 0.0)

    private val jd = AstroTime.JD_J2000

    private fun vertex(sky: ConstellationSky, index: Int) = Vector3(
        sky.lineVertices[index * 3].toDouble(), sky.lineVertices[index * 3 + 1].toDouble(), sky.lineVertices[index * 3 + 2].toDouble(),
    )

    private fun separationDeg(a: Vector3, b: Vector3) = Math.toDegrees(acos((a dot b).coerceIn(-1.0, 1.0)))

    @Test
    fun polylineBecomesConsecutiveSegments() {
        val stars = listOf(star(1, 0.0, 0.0), star(2, 10.0, 0.0), star(3, 10.0, 10.0))
        val figure = Constellation("Tst", "Test", "Testi", listOf(listOf(1, 2, 3)))
        val sky = ConstellationGeometry.build(listOf(figure), stars, jd)

        assertEquals("two segments, two vertices each", 4, sky.lineVertexCount)
        assertVecEquals(Coordinates.raDecToUnit(stars[0].j2000), vertex(sky, 0), 1e-6)
        assertVecEquals(Coordinates.raDecToUnit(stars[1].j2000), vertex(sky, 1), 1e-6)
        assertVecEquals(vertex(sky, 1), vertex(sky, 2), 1e-12) // the shared star
        assertVecEquals(Coordinates.raDecToUnit(stars[2].j2000), vertex(sky, 3), 1e-6)
    }

    @Test
    fun separatePolylinesAreNotJoined() {
        val stars = listOf(star(1, 0.0, 0.0), star(2, 10.0, 0.0), star(3, 50.0, 0.0), star(4, 60.0, 0.0))
        val figure = Constellation("Tst", "Test", "Testi", listOf(listOf(1, 2), listOf(3, 4)))
        assertEquals(4, ConstellationGeometry.build(listOf(figure), stars, jd).lineVertexCount)
    }

    @Test
    fun segmentsTouchingMissingStarsAreSkipped() {
        val stars = listOf(star(1, 0.0, 0.0), star(3, 10.0, 0.0))
        val figure = Constellation("Tst", "Test", "Testi", listOf(listOf(1, 2, 3)))
        val sky = ConstellationGeometry.build(listOf(figure), stars, jd)
        assertEquals(0, sky.lineVertexCount)
        assertEquals("label still uses the stars that exist", 1, sky.labels.size)
    }

    @Test
    fun labelIsAtTheNormalisedMeanDirection() {
        val stars = listOf(star(1, -20.0, 30.0), star(2, 20.0, 30.0), star(3, 0.0, 10.0), star(4, 0.0, 50.0))
        val figure = Constellation("Tst", "Test", "Testi", listOf(listOf(1, 2, 3), listOf(3, 4)))
        val label = ConstellationGeometry.build(listOf(figure), stars, jd).labels.single()

        assertEquals("Test", label.name)
        assertEquals(1.0, label.direction.length(), 1e-12)
        // By symmetry the mean lies on the RA = 0 meridian...
        val radec = Coordinates.unitToRaDec(label.direction)
        assertEquals(0.0, Math.toDegrees(radec.raRad).let { if (it > 180) it - 360 else it }, 1e-6)
        // ...and star 3, shared by both polylines, counts once.
        val expected = stars.map { Coordinates.raDecToUnit(it.j2000) }
            .fold(Vector3(0.0, 0.0, 0.0)) { a, v -> a + v }.normalized()
        assertVecEquals(expected, label.direction, 1e-6)
    }

    @Test
    fun figureWithoutAnyStarGetsNoLabel() {
        val figure = Constellation("Tst", "Test", "Testi", listOf(listOf(8, 9)))
        assertTrue(ConstellationGeometry.build(listOf(figure), listOf(star(1, 0.0, 0.0)), jd).labels.isEmpty())
    }

    @Test
    fun antipodalStarsGiveNoLabel() {
        val figure = Constellation("Tst", "Test", "Testi", listOf(listOf(1, 2)))
        val stars = listOf(star(1, 0.0, 0.0), star(2, 180.0, 0.0))
        assertTrue(ConstellationGeometry.build(listOf(figure), stars, jd).labels.isEmpty())
    }

    @Test
    fun linesUseTheSamePositionsAsTheStarBuffer() {
        val stars = listOf(star(1, 101.3, -16.7), star(2, 279.2, 38.8))
        val figure = Constellation("Tst", "Test", "Testi", listOf(listOf(1, 2)))
        val date = AstroTime.JD_J2000 + 9500
        val sky = ConstellationGeometry.build(listOf(figure), stars, date)
        val buffer = StarVertices.build(stars, date)
        for (i in 0..1) {
            val base = i * StarVertices.FLOATS_PER_STAR
            val starVector = Vector3(buffer[base].toDouble(), buffer[base + 1].toDouble(), buffer[base + 2].toDouble())
            assertVecEquals(starVector, vertex(sky, i), 1e-6)
        }
    }

    // ---- the real, bundled data ----

    private val data = BundledSkyData.load()

    @Test
    fun bundledFiguresCoverEveryConstellationInTheCatalogue() {
        val inCatalogue = data.allStars.mapNotNull { it.constellation }.toSet()
        val figured = data.constellations.map { it.abbreviation }.toSet()
        assertEquals(88, figured.size)
        assertEquals(inCatalogue, figured)
    }

    @Test
    fun bundledFiguresUseOnlyNakedEyeCatalogueStars() {
        val known = data.nakedEye.map { it.hr }.toSet()
        for (c in data.constellations) {
            for (polyline in c.polylines) {
                assertTrue("${c.name}: polyline needs two stars", polyline.size >= 2)
                polyline.zipWithNext().forEach { (a, b) -> assertTrue("${c.name}: HR $a repeated", a != b) }
                assertTrue("${c.name}: unknown HR in $polyline", polyline.all { it in known })
            }
        }
    }

    @Test
    fun bundledFiguresMostlyStayInsideTheirOwnConstellation() {
        val byHr = data.allStars.associateBy { it.hr }
        for (c in data.constellations) {
            val tagged = c.starHrs.mapNotNull { byHr[it]?.constellation }
            if (tagged.size < 3) continue
            val own = tagged.count { it == c.abbreviation }
            assertTrue("${c.name}: only $own of ${tagged.size} stars belong to it", own * 2 >= tagged.size)
        }
    }

    @Test
    fun bundledSegmentsAreNotImplausiblyLong() {
        val sky = ConstellationGeometry.build(data.constellations, data.nakedEye, jd)
        val expectedSegments = data.constellations.sumOf { c -> c.polylines.sumOf { it.size - 1 } }
        assertEquals("every segment resolved", expectedSegments * 2, sky.lineVertexCount)
        for (i in 0 until sky.lineVertexCount step 2) {
            val sep = separationDeg(vertex(sky, i), vertex(sky, i + 1))
            assertTrue("segment $i is $sep deg long", sep < 30.0)
        }
    }

    @Test
    fun bundledLabelsSitInsideTheirFigures() {
        val sky = ConstellationGeometry.build(data.constellations, data.nakedEye, jd)
        assertEquals(data.constellations.size, sky.labels.size)
        val positions = StarPositions.ofDate(data.nakedEye, jd)
        val byHr = data.nakedEye.indices.associate { data.nakedEye[it].hr to positions[it] }
        data.constellations.zip(sky.labels).forEach { (c, label) ->
            assertEquals(c.name, label.name)
            val farthest = c.starHrs.maxOf { separationDeg(byHr.getValue(it), label.direction) }
            // Hydra, the longest figure, spans ~100 degrees, so its middle is ~50 from either end.
            assertTrue("${c.name}: label is $farthest deg from a figure star", farthest < 60.0)
        }
    }

    @Test
    fun orionLabelIsNearTheBelt() {
        val sky = ConstellationGeometry.build(data.constellations, data.nakedEye, jd)
        val orion = sky.labels.single { it.name == "Orion" }
        val index = data.nakedEye.indexOfFirst { it.hr == 1903 } // epsilon Orionis, Alnilam
        assertTrue("Alnilam missing", index >= 0)
        val alnilam = StarPositions.ofDate(data.nakedEye, jd)[index]
        val sep = separationDeg(orion.direction, alnilam)
        assertTrue("Orion label is $sep deg from Alnilam", sep < 10.0)
    }

    @Test
    fun serpensHasTwoFigures() {
        val names = data.constellations.filter { it.abbreviation == "Ser" }.map { it.name }
        assertEquals(listOf("Serpens Caput", "Serpens Cauda"), names)
    }
}
