package com.skyglass.catalog

import com.skyglass.assertVecEquals
import com.skyglass.astro.AstroTime
import com.skyglass.astro.Coordinates
import com.skyglass.astro.Equatorial
import com.skyglass.astro.Precession
import com.skyglass.dmsToDeg
import com.skyglass.epochMillis
import com.skyglass.hmsToDeg
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import kotlin.math.acos
import kotlin.math.sqrt

class ConstellationArtTest {

    private val data = BundledSkyData.load()
    private val art = data.constellationArt
    private val starsByHr = data.allStars.associateBy { it.hr }

    private fun unit(raDeg: Double, decDeg: Double) =
        Coordinates.raDecToUnit(Equatorial(Math.toRadians(raDeg), Math.toRadians(decDeg)))

    /** 2026-10-09T00:00Z. */
    private val JD_2026 = AstroTime.julianDate(epochMillis("2026-10-09T00:00:00Z"))

    private fun separationDeg(a: Vector3, b: Vector3) = Math.toDegrees(acos((a dot b).coerceIn(-1.0, 1.0)))

    private fun projectionOf(image: ConstellationArt) =
        ArtProjection(image.anchors.map { it.x to it.y }, image.anchors.map { Coordinates.raDecToUnit(it.j2000) })

    // --- Parser -------------------------------------------------------------------------------------

    @Test
    fun parser_readsAllFieldsOfALine() {
        val parsed = ConstellationArtParser.parse(
            sequenceOf(
                "# comment",
                "",
                "Car;Pup;Vel|argonavis.png|512|256|202,455,45238,138.29990,-69.71721,1.67|62,216,50191,153.68398,-42.12194,|298,22,39757,121.88604,-24.30432,2.83",
            ),
        )
        assertEquals(1, parsed.size)
        val image = parsed.single()
        assertEquals(listOf("Car", "Pup", "Vel"), image.abbreviations)
        assertEquals("argonavis.png", image.fileName)
        assertEquals(512, image.width)
        assertEquals(256, image.height)
        val first = image.anchors[0]
        assertEquals(202.0, first.x, 0.0)
        assertEquals(455.0, first.y, 0.0)
        assertEquals(45238, first.hip)
        assertEquals(Math.toRadians(138.29990), first.j2000.raRad, 1e-12)
        assertEquals(Math.toRadians(-69.71721), first.j2000.decRad, 1e-12)
        assertEquals(1.67, first.vmag!!, 0.0)
        assertNull(image.anchors[1].vmag)
    }

    @Test
    fun parser_rejectsAWrongNumberOfAnchors() {
        assertThrows(IllegalArgumentException::class.java) {
            ConstellationArtParser.parse(sequenceOf("Ori|orion.png|512|512|1,2,3,4,5,6|1,2,3,4,5,6"))
        }
    }

    // --- Projection ---------------------------------------------------------------------------------

    private val syntheticPixels = listOf(10.0 to 20.0, 300.0 to 40.0, 120.0 to 400.0)
    private val syntheticStars = listOf(unit(80.0, 5.0), unit(95.0, 12.0), unit(88.0, -15.0))

    @Test
    fun projection_putsEachAnchorPixelOnItsStar() {
        val projection = ArtProjection(syntheticPixels, syntheticStars)
        syntheticPixels.zip(syntheticStars).forEach { (pixel, star) ->
            assertVecEquals(star, projection.direction(pixel.first, pixel.second), 1e-12, "anchor $pixel")
        }
    }

    @Test
    fun projection_isAffineOnTheAnchorPlane() {
        // An affine map sends the midpoint of two pixels to the midpoint of their images, before normalising.
        val projection = ArtProjection(syntheticPixels, syntheticStars)
        val mid = projection.planePoint((10.0 + 300.0) / 2, (20.0 + 40.0) / 2)
        assertVecEquals((syntheticStars[0] + syntheticStars[1]) * 0.5, mid, 1e-12)
        // And a pixel far outside the anchors stays on the plane through the three stars.
        val normal = (syntheticStars[1] - syntheticStars[0]) cross (syntheticStars[2] - syntheticStars[0])
        val far = projection.planePoint(-500.0, 900.0)
        assertEquals(0.0, (far - syntheticStars[0]) dot normal, 1e-12)
    }

    @Test
    fun projection_rejectsAnchorsInALine() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtProjection(listOf(0.0 to 0.0, 100.0 to 100.0, 200.0 to 200.0), syntheticStars)
        }
    }

    // --- Mesh ---------------------------------------------------------------------------------------

    @Test
    fun mesh_hasAGridOfUnitVectorsPerImageWithTheImageCornersAsTextureCoordinates() {
        val mesh = ConstellationArtMesh.build(art, JD_2026)
        val perImage = ConstellationArtMesh.VERTICES_PER_IMAGE
        assertEquals(art.size * perImage * ConstellationArtMesh.FLOATS_PER_VERTEX, mesh.vertices.size)
        assertEquals(art.size * ConstellationArtMesh.INDICES_PER_IMAGE, mesh.indices.size)
        for (v in 0 until art.size * perImage) {
            val base = v * ConstellationArtMesh.FLOATS_PER_VERTEX
            val x = mesh.vertices[base].toDouble()
            val y = mesh.vertices[base + 1].toDouble()
            val z = mesh.vertices[base + 2].toDouble()
            assertEquals("vertex $v", 1.0, sqrt(x * x + y * y + z * z), 1e-6)
        }
        // Each image's indices stay inside its own vertices.
        for (i in art.indices) {
            for (k in 0 until ConstellationArtMesh.INDICES_PER_IMAGE) {
                val index = mesh.indices[i * ConstellationArtMesh.INDICES_PER_IMAGE + k].toInt() and 0xFFFF
                assertTrue("image $i index $index", index in i * perImage until (i + 1) * perImage)
            }
        }
        // First vertex of an image is its top-left corner (0, 0), last its bottom-right (1, 1).
        val t = ConstellationArtMesh.TEXCOORD_OFFSET
        assertEquals(0f, mesh.vertices[t], 0f)
        assertEquals(0f, mesh.vertices[t + 1], 0f)
        val last = (perImage - 1) * ConstellationArtMesh.FLOATS_PER_VERTEX + t
        assertEquals(1f, mesh.vertices[last], 0f)
        assertEquals(1f, mesh.vertices[last + 1], 0f)
    }

    @Test
    fun mesh_placesTheAnchorsOnTheStarsOfDate() {
        // Precessing the anchors and then projecting must equal projecting and then precessing, since
        // precession is a rotation; so the art moves with the stars, which are precessed the same way.
        val jd = JD_2026
        val precession = Precession.matrixFromJ2000(AstroTime.julianCenturiesSinceJ2000(jd))
        val orion = art.single { "Ori" in it.abbreviations }
        val ofDate = ArtProjection(orion.anchors.map { it.x to it.y }, orion.anchors.map { precession * Coordinates.raDecToUnit(it.j2000) })
        val j2000 = projectionOf(orion)
        for ((x, y) in listOf(0.0 to 0.0, 256.0 to 256.0, 512.0 to 100.0)) {
            assertVecEquals(precession * j2000.direction(x, y), ofDate.direction(x, y), 1e-12, "pixel ($x, $y)")
        }
    }

    @Test
    fun mesh_findsTheImageOfEachConstellation() {
        val mesh = ConstellationArtMesh.build(art, JD_2026)
        val argo = art.indexOfFirst { it.fileName == "argonavis.png" }
        assertEquals(argo, mesh.imageOf("Car"))
        assertEquals(argo, mesh.imageOf("Pup"))
        assertEquals(argo, mesh.imageOf("Vel"))
        assertEquals(art.indexOfFirst { it.fileName == "ophiuchus.png" }, mesh.imageOf("Ser"))
        assertEquals(art.indexOfFirst { it.fileName == "orion.png" }, mesh.imageOf("Ori"))
        assertEquals(-1, mesh.imageOf("Xyz"))
    }

    // --- Bundled data -------------------------------------------------------------------------------

    @Test
    fun bundledArt_coversEveryConstellationExactlyOnce() {
        val figures = data.constellations.map { it.abbreviation }.toSet()
        assertEquals(88, figures.size)
        for (abbreviation in figures) {
            val images = art.filter { abbreviation in it.abbreviations }.map { it.fileName }
            assertEquals("$abbreviation is drawn by $images", 1, images.size)
        }
        assertEquals(figures, art.flatMap { it.abbreviations }.toSet())
    }

    @Test
    fun bundledArt_imageFilesExistWithTheStatedSize() {
        for (image in art) {
            val file = File(BundledSkyData.assets, "${StarCatalog.CONSTELLATION_ART_DIRECTORY}/${image.fileName}")
            assertTrue("${image.fileName} missing", file.isFile)
            // The PNG header: 8-byte signature, IHDR length and type, then width and height, big-endian.
            DataInputStream(file.inputStream()).use { input ->
                input.skipBytes(16)
                assertEquals("${image.fileName} width", image.width, input.readInt())
                assertEquals("${image.fileName} height", image.height, input.readInt())
            }
        }
        val licence = File(BundledSkyData.assets, "${StarCatalog.CONSTELLATION_ART_DIRECTORY}/LICENSE.txt")
        assertTrue("the Free Art License notice must ship with the images", licence.readText().contains("Free Art License"))
    }

    @Test
    fun bundledArt_anchorPixelsAreInsideTheirImages() {
        for (image in art) for (anchor in image.anchors) {
            assertTrue("${image.fileName} HIP ${anchor.hip}", anchor.x in 0.0..image.width.toDouble() && anchor.y in 0.0..image.height.toDouble())
        }
    }

    /**
     * The anchors' positions were resolved from Hipparcos numbers by the generator; the Bright Star
     * Catalogue is an independent source, so every anchor bright enough to be in it must have a
     * catalogue star within 2 arcminutes (a pixel of the art is 3 to 10 arcminutes).
     */
    @Test
    fun bundledArt_brightAnchorsSitOnBrightStarCatalogueStars() {
        val bsc = data.allStars.map { Coordinates.raDecToUnit(it.j2000) }
        var checked = 0
        for (image in art) for (anchor in image.anchors) {
            val vmag = anchor.vmag ?: continue
            if (vmag > 6.0) continue
            val a = Coordinates.raDecToUnit(anchor.j2000)
            val nearestArcmin = bsc.minOf { separationDeg(a, it) } * 60
            assertTrue("${image.fileName} HIP ${anchor.hip}: nearest BSC star $nearestArcmin'", nearestArcmin < 2.0)
            checked++
        }
        assertTrue("checked $checked anchors", checked >= 240)
    }

    @Test
    fun bundledArt_altairAnchorsAquila() {
        // Altair, HIP 97649: 19h 50m 47.0s, +08° 52′ 06″ (J2000; SIMBAD).
        val aquila = art.single { "Aql" in it.abbreviations }
        val altair = aquila.anchors.single { it.hip == 97649 }
        val expected = unit(hmsToDeg(19, 50, 47.0), dmsToDeg(1, 8, 52, 6.0))
        assertTrue(separationDeg(expected, Coordinates.raDecToUnit(altair.j2000)) * 3600 < 5.0)
    }

    /**
     * The middle of each picture lies near the middle of its constellation's stick figure (the mean of
     * the figure's stars, from the Bright Star Catalogue), which catches a wrong star or a swapped
     * anchor. The tolerance is wide because the art reaches past the figure (Orion's raised club, the
     * Argo's sail) and the Argo and Ophiuchus pictures span several figures.
     */
    @Test
    fun bundledArt_imageCentresLieNearTheirFigures() {
        for (image in art) {
            val figureStars = data.constellations.filter { it.abbreviation in image.abbreviations }.flatMap { it.starHrs }
            val centre = figureStars.map { Coordinates.raDecToUnit(starsByHr.getValue(it).j2000) }
                .reduce { a, b -> a + b }.normalized()
            val middle = projectionOf(image).direction(image.width / 2.0, image.height / 2.0)
            val offset = separationDeg(centre, middle)
            assertTrue("${image.fileName}: picture centre is $offset° from the figure's", offset < 25.0)
        }
    }

    @Test
    fun bundledArt_pixelScalesArePlausible() {
        for (image in art) {
            val projection = projectionOf(image)
            val c = projection.direction(image.width / 2.0, image.height / 2.0)
            val right = projection.direction(image.width / 2.0 + 1, image.height / 2.0)
            val down = projection.direction(image.width / 2.0, image.height / 2.0 + 1)
            for ((name, step) in listOf("x" to separationDeg(c, right), "y" to separationDeg(c, down))) {
                assertTrue("${image.fileName} $name: $step° per pixel", step in 0.01..0.5)
            }
        }
    }

    /**
     * The art is drawn as seen from inside the sphere, like the sky itself, not mirrored as on a
     * celestial globe. Seen from inside with the image's up toward the viewer's up, its right is the
     * viewer's right; the direction out of the screen is then −p at the image's centre p, i.e.
     * right × up = −p. With right = ∂/∂x and up = −∂/∂y that is (∂/∂x × ∂/∂y)·p > 0.
     */
    @Test
    fun bundledArt_noImageIsMirrored() {
        for (image in art) {
            val projection = projectionOf(image)
            val centre = projection.direction(image.width / 2.0, image.height / 2.0)
            val handedness = (projection.perPixelX cross projection.perPixelY) dot centre
            assertTrue("${image.fileName} is mirrored", handedness > 0)
        }
    }

    /** The plane-to-sphere step must not fold the grid: every triangle keeps the image's handedness. */
    @Test
    fun bundledArt_meshTrianglesDoNotFold() {
        val mesh = ConstellationArtMesh.build(art, JD_2026)
        fun vertex(i: Int): Vector3 {
            val base = i * ConstellationArtMesh.FLOATS_PER_VERTEX
            return Vector3(mesh.vertices[base].toDouble(), mesh.vertices[base + 1].toDouble(), mesh.vertices[base + 2].toDouble())
        }
        art.forEachIndexed { i, image ->
            val start = i * ConstellationArtMesh.INDICES_PER_IMAGE
            for (k in 0 until ConstellationArtMesh.INDICES_PER_IMAGE / 3) {
                val a = vertex(mesh.indices[start + 3 * k].toInt() and 0xFFFF)
                val b = vertex(mesh.indices[start + 3 * k + 1].toInt() and 0xFFFF)
                val c = vertex(mesh.indices[start + 3 * k + 2].toInt() and 0xFFFF)
                // Triangles run (x, y) -> (x+1, y) -> (x+1, y+1): the same sense as ∂/∂x then ∂/∂y.
                val sense = ((b - a) cross (c - a)) dot a
                assertTrue("${image.fileName} triangle $k folds", sense > 0)
            }
        }
    }
}
