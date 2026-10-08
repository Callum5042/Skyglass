package com.skyglass.catalog

import com.skyglass.assertAngleDegEquals
import com.skyglass.astro.AstroTime
import com.skyglass.astro.Coordinates
import com.skyglass.astro.Equatorial
import com.skyglass.astro.Galactic
import com.skyglass.astro.Precession
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class DeepSkyTest {

    private val data = BundledSkyData.load()
    private val MAX_DISTANCE_FROM_FIGURE_DEG = 20.0
    private val objects = data.deepSky
    private fun find(id: String) = checkNotNull(objects.firstOrNull { id in it.designations }) { "no $id" }

    @Test
    fun parser_readsAllFieldsOfALine() {
        val parsed = DeepSkyParser.parse(
            sequenceOf(
                "# a comment",
                "",
                "M31;NGC 224|Andromeda Galaxy|Great Andromeda Galaxy;Great Nebula|GAL|00:42:44.3|+41:16:09|3.4|190|60|35|Andromeda",
                "C 99|Coalsack Nebula||DN|12:50:00|-63:00:00||420|300||Crux",
            ),
        )
        assertEquals(2, parsed.size)
        val m31 = parsed[0]
        assertEquals(listOf("M31", "NGC 224"), m31.designations)
        assertEquals("M31", m31.id)
        assertEquals("Andromeda Galaxy", m31.title)
        assertEquals(listOf("Great Andromeda Galaxy", "Great Nebula"), m31.aliases)
        assertEquals(DeepSkyType.GALAXY, m31.type)
        assertEquals(10.6846, Math.toDegrees(m31.j2000.raRad), 0.001)
        assertEquals(41.2692, Math.toDegrees(m31.j2000.decRad), 0.001)
        assertEquals(3.4, m31.vmag!!, 0.0)
        assertEquals(190.0, m31.majorArcmin, 0.0)
        assertEquals(60.0, m31.minorArcmin, 0.0)
        assertEquals(35.0, m31.positionAngleDeg!!, 0.0)
        assertEquals(31, m31.messierNumber)
        val coalsack = parsed[1]
        assertNull(coalsack.vmag)
        assertEquals(-63.0, Math.toDegrees(coalsack.j2000.decRad), 1e-9)
        assertNull(coalsack.messierNumber)
    }

    @Test
    fun parser_negativeDeclinationsCarryTheirSignThroughMinutesAndSeconds() {
        assertEquals(-0.0 - (0.0 + 49.0 / 60 + 24.0 / 3600), DeepSkyParser.parseSexagesimal("-00:49:24"), 1e-12)
        assertEquals(5.5755, DeepSkyParser.parseSexagesimal("05:34:31.9"), 1e-4)
    }

    @Test
    fun parser_rejectsMalformedLines() {
        for (bad in listOf("M1|x|y|GAL|00:00:00|+00:00:00|1.0|1.0|1.0||Taurus|extra", "M1||GAL", "M1|||XXX|00:00:00|+00:00:00|1|1|1||Taurus")) {
            val failed = runCatching { DeepSkyParser.parse(sequenceOf(bad)) }.isFailure
            assertTrue("accepted '$bad'", failed)
        }
    }

    @Test
    fun bundledCatalogue_hasEveryMessierObjectExactlyOnce() {
        val numbers = objects.mapNotNull { it.messierNumber }
        assertEquals((1..110).toList(), numbers.sorted())
    }

    @Test
    fun bundledCatalogue_hasTheNotableNonMessierObjects() {
        for (name in listOf(
            "Large Magellanic Cloud", "Small Magellanic Cloud", "Omega Centauri", "47 Tucanae", "Carina Nebula", "Coathanger",
            "Hyades", "Double Cluster (h Persei)", "Double Cluster (chi Persei)", "Jewel Box", "North America Nebula", "Coalsack Nebula",
            "Helix Nebula", "Sculptor Galaxy", "Centaurus A", "Tarantula Nebula",
        )) {
            assertNotNull("missing $name", objects.firstOrNull { it.name == name })
        }
        assertTrue("expected at least 150 objects, got ${objects.size}", objects.size >= 150)
    }

    @Test
    fun bundledCatalogue_isInternallyConsistent() {
        // Serpens has two figures, named Caput and Cauda; objects there are simply in Serpens.
        val constellationNames = data.constellations.map { it.name }.toSet() + "Serpens"
        assertEquals("first designations are unique", objects.size, objects.map { it.id }.toSet().size)
        for (o in objects) {
            val where = "${o.id} (${o.title})"
            assertTrue("$where major axis", o.majorArcmin > 0)
            assertTrue("$where minor axis", o.minorArcmin > 0 && o.minorArcmin <= o.majorArcmin)
            o.vmag?.let { assertTrue("$where magnitude $it", it in -2.0..14.0) }
            o.positionAngleDeg?.let { assertTrue("$where position angle", it >= 0 && it < 180) }
            assertTrue("$where RA", Math.toDegrees(o.j2000.raRad) in 0.0..360.0)
            assertTrue("$where Dec", Math.toDegrees(o.j2000.decRad) in -90.0..90.0)
            assertTrue("$where constellation '${o.constellation}'", o.constellation in constellationNames)
            assertTrue("$where is a dark nebula iff it has no magnitude", (o.vmag == null) == (o.type == DeepSkyType.DARK_NEBULA))
        }
    }

    @Test
    fun bundledPositions_agreeWithAnIndependentCheck_theConstellationTheyAreIn() {
        // An object in a constellation is near one of the stars of its stick figure (the figures cover the
        // constellations, with gaps of up to about 16 degrees in Camelopardalis), so this catches gross typos only: an
        // error that stays inside the constellation (the wrong RA that NGC 6025 once had) needs a pinned value, below.
        val starsByHr = data.nakedEye.associateBy { it.hr }
        for (o in objects) {
            val figures = data.constellations.filter { it.name == o.constellation || it.name.startsWith(o.constellation + " ") }
            val figureStars = figures.flatMap { it.starHrs }.mapNotNull { starsByHr[it] }
            if (figureStars.isEmpty()) continue
            val here = Coordinates.raDecToUnit(o.j2000)
            val nearest = figureStars.minOf { Math.toDegrees(kotlin.math.acos((here dot Coordinates.raDecToUnit(it.j2000)).coerceIn(-1.0, 1.0))) }
            assertTrue("${o.id} is ${"%.1f".format(nearest)}° from the nearest star of ${o.constellation}", nearest < MAX_DISTANCE_FROM_FIGURE_DEG)
        }
    }

    @Test
    fun knownObjects_haveTheirKnownGalacticPositions() {
        // M31 l=121.17 b=-21.57; the Galactic centre's globulars lie near b = 0 .. -20; the Magellanic Clouds are near b = -33 and -44.
        val m31 = Galactic.fromEquatorial(find("M31").j2000)
        assertAngleDegEquals(121.17, m31.lDeg, 0.1)
        assertEquals(-21.57, m31.bDeg, 0.1)
        val lmc = Galactic.fromEquatorial(find("LMC").j2000)
        assertAngleDegEquals(280.5, lmc.lDeg, 0.3)
        assertEquals(-32.9, lmc.bDeg, 0.3)
        val smc = Galactic.fromEquatorial(find("SMC").j2000)
        assertAngleDegEquals(302.8, smc.lDeg, 0.3)
        assertEquals(-44.3, smc.bDeg, 0.3)
        val omegaCen = Galactic.fromEquatorial(find("NGC 5139").j2000)
        assertAngleDegEquals(309.1, omegaCen.lDeg, 0.1)
        assertEquals(14.97, omegaCen.bDeg, 0.1)
        val m42 = Galactic.fromEquatorial(find("M42").j2000)
        assertAngleDegEquals(209.0, m42.lDeg, 0.2)
        assertEquals(-19.4, m42.bDeg, 0.2)
    }

    @Test
    fun correctedPositions_stayCorrected() {
        // NGC 6025 (Caldwell 95) is at 16h 03.7m, not 15h 37m, which an earlier version had.
        val ngc6025 = find("NGC 6025")
        assertEquals(240.9, Math.toDegrees(ngc6025.j2000.raRad), 0.1)
        assertEquals(-60.5, Math.toDegrees(ngc6025.j2000.decRad), 0.1)
        // The Lagoon Nebula and M24 use the magnitudes the Messier tables of SEDS give (6.0 and 4.6).
        assertEquals(6.0, find("M8").vmag!!, 0.0)
        assertEquals(4.6, find("M24").vmag!!, 0.0)
    }

    @Test
    fun drawnMinorAxis_isTheMajorAxisWhenTheOrientationIsUnknown() {
        val m81 = find("M81") // position angle known: drawn as the ellipse it is
        assertEquals(m81.minorArcmin, m81.drawnMinorArcmin, 0.0)
        val m8 = find("M8") // 90' x 40' with no angle: drawn round
        assertNull(m8.positionAngleDeg)
        assertEquals(m8.majorArcmin, m8.drawnMinorArcmin, 0.0)
    }

    @Test
    fun sizeFormatting_picksTheUnitThatReadsBest() {
        assertEquals(DeepSkyMeasure.Unit.DEGREES, DeepSkyMeasure.of(190.0).unit)
        assertEquals(3.1667, DeepSkyMeasure.of(190.0).value, 1e-3)
        assertEquals(DeepSkyMeasure.Unit.ARCMINUTES, DeepSkyMeasure.of(8.7).unit)
        assertEquals(DeepSkyMeasure.Unit.ARCSECONDS, DeepSkyMeasure.of(0.3).unit)
        assertEquals(18.0, DeepSkyMeasure.of(0.3).value, 1e-9)
        assertNull(DeepSkySize.of(find("M13")).minor) // round
        assertNotNull(DeepSkySize.of(find("M31")).minor)
    }

    @Test
    fun precession_carriesAnObjectToTheMeanEquatorOfDate() {
        // M31 at 2026-10-08 (JD 2461321.5): precession moves RA by about +0.26° and Dec by about +0.07° in 26 years.
        val jd = 2461321.5
        val direction = DeepSkyVertices.directions(listOf(find("M31")), jd).single()
        val of = Coordinates.unitToRaDec(direction)
        val j2000 = find("M31").j2000
        val dRa = Math.toDegrees(of.raRad - j2000.raRad)
        val dDec = Math.toDegrees(of.decRad - j2000.decRad)
        val t = AstroTime.julianCenturiesSinceJ2000(jd)
        // m + n sin(RA) tan(Dec) per year, in degrees (Meeus eq. 21.1 with m = 3.075 s, n = 1.336 s).
        val m = 3.075 * 15 / 3600
        val n = 1.336 * 15 / 3600
        val expectedRa = (m + n * sin(j2000.raRad) * kotlin.math.tan(j2000.decRad)) * t * 100
        val expectedDec = n * cos(j2000.raRad) * t * 100
        assertEquals(expectedRa, dRa, 0.003)
        assertEquals(expectedDec, dDec, 0.003)
        assertEquals(1.0, direction.length(), 1e-12)
    }

    @Test
    fun vertices_packTwoTrianglesPerObjectWithItsAppearance() {
        val m13 = find("M13")
        val m31 = find("M31")
        val directions = DeepSkyVertices.directions(listOf(m13, m31), AstroTime.JD_J2000)
        val data = DeepSkyVertices.build(listOf(m13, m31), directions)
        assertEquals(2 * DeepSkyVertices.VERTICES_PER_OBJECT * DeepSkyVertices.FLOATS_PER_VERTEX, data.size)
        // The second object's first vertex.
        val base = DeepSkyVertices.VERTICES_PER_OBJECT * DeepSkyVertices.FLOATS_PER_VERTEX
        assertEquals(directions[1].x, data[base + DeepSkyVertices.POSITION_OFFSET].toDouble(), 1e-6)
        assertEquals(Math.toRadians(190.0 / 120.0), data[base + DeepSkyVertices.SEMI_MAJOR_OFFSET].toDouble(), 1e-6)
        assertEquals(Math.toRadians(60.0 / 120.0), data[base + DeepSkyVertices.SEMI_MINOR_OFFSET].toDouble(), 1e-6)
        assertEquals(Math.toRadians(35.0), data[base + DeepSkyVertices.POSITION_ANGLE_OFFSET].toDouble(), 1e-6)
        assertEquals(DeepSkyAppearance.GLYPH_GALAXY, data[base + DeepSkyVertices.GLYPH_OFFSET])
        // The six corners cover the square: each of the four corner positions appears.
        val corners = (0 until DeepSkyVertices.VERTICES_PER_OBJECT).map { v ->
            val b = base + v * DeepSkyVertices.FLOATS_PER_VERTEX + DeepSkyVertices.CORNER_OFFSET
            data[b] to data[b + 1]
        }.toSet()
        assertEquals(setOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f), corners)
        // Premultiplied colour never exceeds 1.
        for (v in 0 until data.size / DeepSkyVertices.FLOATS_PER_VERTEX) {
            for (k in 0..2) assertTrue(data[v * DeepSkyVertices.FLOATS_PER_VERTEX + DeepSkyVertices.COLOR_OFFSET + k] in 0f..1f)
        }
    }

    @Test
    fun quadCorners_followThePositionAngleFromNorthThroughEast() {
        val semi = 0.01
        for ((raDeg, decDeg) in listOf(0.0 to 0.0, 100.0 to 60.0, 250.0 to -45.0)) {
            val ra = Math.toRadians(raDeg)
            val dec = Math.toRadians(decDeg)
            val d = Coordinates.raDecToUnit(Equatorial(ra, dec))
            for (paDeg in listOf(0.0, 35.0, 90.0, 150.0)) {
                val corner = DeepSkyGeometry.cornerPoint(d, semi, semi * 0.4, Math.toRadians(paDeg), 1.0, 0.0).normalized()
                val target = Coordinates.unitToRaDec(corner)
                // Bearing of the corner from the object, from north through east.
                val dRa = target.raRad - ra
                val bearing = atan2(
                    sin(dRa) * cos(target.decRad),
                    cos(dec) * sin(target.decRad) - sin(dec) * cos(target.decRad) * cos(dRa),
                )
                assertAngleDegEquals(paDeg, Math.toDegrees(bearing), 0.05, "RA $raDeg Dec $decDeg PA $paDeg")
            }
        }
    }

    @Test
    fun quadCorners_putTheShortAxisAcrossTheLongOne() {
        val d = Coordinates.raDecToUnit(Equatorial(Math.toRadians(30.0), Math.toRadians(20.0)))
        val along = DeepSkyGeometry.cornerPoint(d, 0.02, 0.005, 0.7, 1.0, 0.0) - d
        val across = DeepSkyGeometry.cornerPoint(d, 0.02, 0.005, 0.7, 0.0, 1.0) - d
        assertEquals(0.0, along dot across, 1e-12)
        assertEquals(0.02 * DeepSkyAppearance.QUAD_EXTENT, along.length(), 1e-12)
        assertEquals(0.005 * DeepSkyAppearance.QUAD_EXTENT, across.length(), 1e-12)
    }

    @Test
    fun appearance_dimsBigFaintObjectsMoreThanSmallBrightOnes() {
        val omegaCen = find("NGC 5139")
        val m31 = find("M31")
        val lmc = find("LMC")
        val m101 = find("M101")
        assertEquals(13.3, DeepSkyAppearance.surfaceBrightness(m31)!!, 0.2)
        assertEquals(14.5, DeepSkyAppearance.surfaceBrightness(lmc)!!, 0.3)
        assertTrue(DeepSkyAppearance.peakIntensity(omegaCen) > DeepSkyAppearance.peakIntensity(m31))
        assertTrue(DeepSkyAppearance.peakIntensity(m31) > DeepSkyAppearance.peakIntensity(lmc))
        assertTrue(DeepSkyAppearance.peakIntensity(lmc) > DeepSkyAppearance.peakIntensity(m101) - 0.2) // both faint, both drawn
        for (o in objects) {
            val peak = DeepSkyAppearance.peakIntensity(o)
            assertTrue("${o.id} peak $peak", peak in 0.05f..0.7f)
        }
        assertNull(DeepSkyAppearance.surfaceBrightness(find("C 99")))
    }

    @Test
    fun appearance_usesADifferentGlyphAndColourPerKindOfObject() {
        val types = DeepSkyType.entries.filter { it != DeepSkyType.ASTERISM && it != DeepSkyType.DOUBLE_STAR }
        val colours = types.map { DeepSkyAppearance.color(it) }
        assertEquals(types.size, colours.toSet().size)
        for (c in colours) assertTrue(c.r <= 1f && c.g <= 1f && c.b <= 1f)
        val glyphs = types.map { DeepSkyAppearance.glyph(it) }.toSet()
        assertTrue("glyphs: $glyphs", glyphs.size >= 6)
    }

    @Test
    fun drawnSize_followsTheObjectButNeverFallsBelowTheMinimum() {
        val focalPx = 1000.0
        val m31 = DeepSkyAppearance.drawnSemiAxes(find("M31"), focalPx, 20.0)
        assertEquals(Math.toRadians(190.0 / 120.0), m31.majorRad, 1e-12) // true size, far above the minimum
        assertEquals(Math.toRadians(60.0 / 120.0), m31.minorRad, 1e-12)

        val catsEye = find("NGC 6543") // 0.3' across: enlarged until its radius is 20 px
        val drawn = DeepSkyAppearance.drawnSemiAxes(catsEye, focalPx, 20.0)
        assertEquals(20.0 / focalPx, drawn.majorRad, 1e-12)
        assertEquals(20.0, DeepSkyAppearance.drawnRadiusPx(catsEye, focalPx, 20.0), 1e-9)

        // A small elongated object keeps its shape when enlarged.
        val m65 = find("M65")
        val m65Drawn = DeepSkyAppearance.drawnSemiAxes(m65, focalPx, 20.0)
        assertEquals(m65.minorArcmin / m65.majorArcmin, m65Drawn.minorRad / m65Drawn.majorRad, 1e-9)
    }

    @Test
    fun labels_goToNamedBrightOrBigObjectsAndBrightMessierObjects() {
        assertEquals("Andromeda Galaxy", DeepSkyLabels.text(find("M31")))
        assertEquals("Orion Nebula", DeepSkyLabels.text(find("M42")))
        assertEquals("Large Magellanic Cloud", DeepSkyLabels.text(find("LMC")))
        assertEquals("Hyades", DeepSkyLabels.text(find("Mel 25")))
        assertEquals("M13", DeepSkyLabels.text(find("M13"))) // magnitude 5.8, named but not bright enough to be labelled by name
        assertNull(DeepSkyLabels.text(find("M3"))) // 6.2
        assertNull(DeepSkyLabels.text(find("M87")))
        assertNull(DeepSkyLabels.text(find("NGC 3115")))
        val labelled = objects.count { DeepSkyLabels.text(it) != null }
        assertTrue("$labelled labelled", labelled in 20..60)
        assertFalse(objects.all { DeepSkyLabels.text(it) != null })
    }

    @Test
    fun noObjectIsAtAPole_whereTheQuadsEastDirectionIsUndefined() {
        for (o in objects) assertTrue(o.id, abs(Math.toDegrees(o.j2000.decRad)) < 85.0)
    }

    @Test
    fun unitVectors_ofTheBundledObjects_survivePrecessionToday() {
        val jd = AstroTime.julianDate(System.currentTimeMillis())
        val directions = DeepSkyVertices.directions(objects, jd)
        val matrix = Precession.matrixFromJ2000(AstroTime.julianCenturiesSinceJ2000(jd))
        for ((i, d) in directions.withIndex()) {
            assertEquals(1.0, d.length(), 1e-12)
            val expected: Vector3 = matrix * Coordinates.raDecToUnit(objects[i].j2000)
            assertEquals(0.0, (d - expected).length(), 1e-12)
        }
    }
}
