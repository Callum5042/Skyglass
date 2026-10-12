package com.skyglass.catalog

import com.skyglass.astro.AstroTime
import com.skyglass.astro.Coordinates
import com.skyglass.astro.Equatorial
import com.skyglass.astro.Precession
import com.skyglass.math.Vector3
import kotlin.math.abs

/**
 * One pin of an illustration to the sky: the pixel ([x] right, [y] down from the top-left corner)
 * that sits on Hipparcos star [hip], whose mean J2000 position is [j2000].
 *
 * @property vmag Hipparcos' V magnitude, for reference (null if it gives none)
 */
data class ArtAnchor(val x: Double, val y: Double, val hip: Int, val j2000: Equatorial, val vmag: Double?)

/**
 * A constellation illustration from `constellation_art.txt`: an image in `constellation_art/` and
 * the three anchors that place it on the sky.
 *
 * @property abbreviations the constellations it draws; usually one, but the Argo ship is Carina,
 *   Puppis and Vela, and Ophiuchus holds the serpent of Serpens
 */
data class ConstellationArt(
    val abbreviations: List<String>,
    val fileName: String,
    val width: Int,
    val height: Int,
    val anchors: List<ArtAnchor>,
)

/**
 * Parser for `constellation_art.txt`: one image per line,
 * `Abbr;Abbr|file|width|height|x,y,HIP,RA,Dec,Vmag|...|...` (RA and Dec in degrees, J2000), with
 * `#` comment lines (the file header describes each column).
 */
object ConstellationArtParser {

    fun parse(lines: Sequence<String>): List<ConstellationArt> =
        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map(::parseLine).toList()

    private fun parseLine(line: String): ConstellationArt {
        val fields = line.split("|")
        require(fields.size == 7) { "expected 7 fields: $line" }
        return ConstellationArt(
            abbreviations = fields[0].split(";").map { it.trim() }.filter { it.isNotEmpty() },
            fileName = fields[1].trim(),
            width = fields[2].trim().toInt(),
            height = fields[3].trim().toInt(),
            anchors = fields.subList(4, 7).map(::parseAnchor),
        )
    }

    private fun parseAnchor(field: String): ArtAnchor {
        val parts = field.split(",").map { it.trim() }
        require(parts.size == 6) { "expected x,y,HIP,RA,Dec,Vmag: $field" }
        return ArtAnchor(
            x = parts[0].toDouble(),
            y = parts[1].toDouble(),
            hip = parts[2].toInt(),
            j2000 = Equatorial(Math.toRadians(parts[3].toDouble()), Math.toRadians(parts[4].toDouble())),
            vmag = parts[5].takeIf { it.isNotEmpty() }?.toDouble(),
        )
    }
}

/**
 * Where each pixel of an illustration goes on the sky, from its three anchors, as Stellarium places
 * its art: the image plane is mapped *affinely* onto the plane through the three anchor stars'
 * directions (so each anchor pixel lands exactly on its star, and straight lines in the image stay
 * straight on that plane), and the point on that plane is then pushed out to the unit sphere. Any
 * rotation, scale, shear and mirroring the anchors imply is carried over.
 *
 * @param pixels the three anchor pixels (x, y), in image pixels
 * @param stars the three stars' unit vectors, in the same order
 */
class ArtProjection(pixels: List<Pair<Double, Double>>, stars: List<Vector3>) {

    private val originX: Double
    private val originY: Double
    private val origin: Vector3

    /** The change of the plane point per pixel to the right (x) and per pixel down (y). */
    val perPixelX: Vector3
    val perPixelY: Vector3

    init {
        require(pixels.size == 3 && stars.size == 3) { "need three anchors" }
        originX = pixels[0].first
        originY = pixels[0].second
        origin = stars[0]
        val dx1 = pixels[1].first - originX
        val dy1 = pixels[1].second - originY
        val dx2 = pixels[2].first - originX
        val dy2 = pixels[2].second - originY
        val det = dx1 * dy2 - dx2 * dy1
        require(abs(det) > MIN_DETERMINANT) { "anchor pixels are (nearly) in a line" }
        val d1 = stars[1] - origin
        val d2 = stars[2] - origin
        // Solve perPixelX * dx + perPixelY * dy = d for both anchors (Cramer's rule, per component).
        perPixelX = (d1 * dy2 - d2 * dy1) * (1.0 / det)
        perPixelY = (d2 * dx1 - d1 * dx2) * (1.0 / det)
    }

    /** The point on the anchors' plane for pixel ([x], [y]); not normalised. */
    fun planePoint(x: Double, y: Double): Vector3 = origin + perPixelX * (x - originX) + perPixelY * (y - originY)

    /** The unit vector on the sky for pixel ([x], [y]). */
    fun direction(x: Double, y: Double): Vector3 = planePoint(x, y).normalized()

    private companion object {
        /** In square pixels; three anchors this close to a line cannot fix an image. */
        const val MIN_DETERMINANT = 1.0
    }
}

/**
 * All illustrations as one indexed triangle mesh: each image is a [GRID] × [GRID] grid of quads
 * over the image, whose vertices are [ArtProjection] directions in the equatorial frame of date
 * (precession applied to the anchors, as for the stars; no proper motion, which over a few decades
 * moves an anchor by far less than a pixel of the art) and texture coordinates. Texture coordinate
 * (0, 0) is the image's top-left corner, the first row of pixels uploaded.
 *
 * Per vertex: position (3), texture coordinate (2). Image `i` is drawn with [INDICES_PER_IMAGE]
 * indices from `i * INDICES_PER_IMAGE`.
 */
class ConstellationArtMesh(val vertices: FloatArray, val indices: ShortArray, val art: List<ConstellationArt>) {

    /** The index of the image that draws constellation [abbreviation], or -1 if none does. */
    fun imageOf(abbreviation: String): Int = art.indexOfFirst { abbreviation in it.abbreviations }

    companion object {
        const val FLOATS_PER_VERTEX = 5
        const val POSITION_OFFSET = 0
        const val TEXCOORD_OFFSET = 3

        /**
         * Quads per side of each image. The projection bends straight image lines into curves on the
         * sphere; 8 × 8 keeps the chord error small even for Hydra and Eridanus, as in Stellarium.
         */
        const val GRID = 8
        const val VERTICES_PER_IMAGE = (GRID + 1) * (GRID + 1)
        const val INDICES_PER_IMAGE = GRID * GRID * 6

        /** @param julianDate the epoch of the equatorial frame of date the positions are given in */
        fun build(art: List<ConstellationArt>, julianDate: Double): ConstellationArtMesh {
            check(art.size * VERTICES_PER_IMAGE <= 0xFFFF) { "mesh too big for 16-bit indices" }
            val precession = Precession.matrixFromJ2000(AstroTime.julianCenturiesSinceJ2000(julianDate))
            val vertices = FloatArray(art.size * VERTICES_PER_IMAGE * FLOATS_PER_VERTEX)
            val indices = ShortArray(art.size * INDICES_PER_IMAGE)
            var n = 0
            art.forEachIndexed { i, image ->
                val projection = ArtProjection(
                    image.anchors.map { it.x to it.y },
                    image.anchors.map { precession * Coordinates.raDecToUnit(it.j2000) },
                )
                val first = i * VERTICES_PER_IMAGE
                for (row in 0..GRID) for (column in 0..GRID) {
                    val u = column.toDouble() / GRID
                    val v = row.toDouble() / GRID
                    val d = projection.direction(u * image.width, v * image.height)
                    val base = (first + row * (GRID + 1) + column) * FLOATS_PER_VERTEX
                    vertices[base + POSITION_OFFSET] = d.x.toFloat()
                    vertices[base + POSITION_OFFSET + 1] = d.y.toFloat()
                    vertices[base + POSITION_OFFSET + 2] = d.z.toFloat()
                    vertices[base + TEXCOORD_OFFSET] = u.toFloat()
                    vertices[base + TEXCOORD_OFFSET + 1] = v.toFloat()
                }
                for (row in 0 until GRID) for (column in 0 until GRID) {
                    val a = first + row * (GRID + 1) + column
                    val b = a + 1
                    val c = a + GRID + 1
                    val d = c + 1
                    indices[n++] = a.toShort(); indices[n++] = b.toShort(); indices[n++] = d.toShort()
                    indices[n++] = a.toShort(); indices[n++] = d.toShort(); indices[n++] = c.toShort()
                }
            }
            return ConstellationArtMesh(vertices, indices, art)
        }
    }
}

/**
 * A decoded illustration: one byte of brightness per pixel, rows from the top. The art is grey
 * line work on black (the few RGB files differ between channels by at most 5 of 255), so one
 * channel is all that is kept, and [ConstellationArtAppearance] tints it.
 */
class ArtImage(val width: Int, val height: Int, val luminance: ByteArray) {
    init {
        require(luminance.size == width * height) { "expected ${width * height} bytes, got ${luminance.size}" }
    }
}

/** How the illustrations are drawn; see `ConstellationArtShaders`. */
object ConstellationArtAppearance {

    /** Colour of the art's white, before [GAIN]: a cool, slightly blue grey, akin to the lines. */
    val TINT = floatArrayOf(0.80f, 0.88f, 1.00f)

    /**
     * Strength of the art on black. The illustrations' bodies are mid grey (about 0.4) with brighter
     * outlines, so this puts most of a figure at about 0.12, under the Milky Way's brightest (0.24)
     * and far under a star: the art is a backdrop that should not compete with the sky.
     */
    const val GAIN = 0.30f

    /** Strength of the selected constellation's art, drawn even when the art is switched off. */
    const val HIGHLIGHT_GAIN = 0.65f

    /**
     * Strength over a camera image, as a fraction of the above. The camera view composites the sky
     * over the image, and the art covers large areas, so it is kept down as the Milky Way is.
     */
    const val CAMERA_GAIN = 0.6f

    /** Width of the fade at each image edge, as a fraction of the image, so art that runs to the edge has no hard cut. */
    const val EDGE_FADE = 0.04f

    /**
     * Zoomed in, the art fades out between these vertical fields of view (full strength at the first,
     * gone at the second). The images have about 10 to 20 pixels per degree, already magnified a
     * little at the default 60°; beyond a few times that they are blurred outlines over a part of a
     * figure, not a picture of it.
     */
    const val ZOOM_FADE_FULL_FOV_DEG = 25.0
    const val ZOOM_FADE_GONE_FOV_DEG = 10.0

    /** The art's strength at a vertical field of view of [fovYDeg], from 1 (zoomed out) to 0 (zoomed in); see [ZOOM_FADE_FULL_FOV_DEG]. */
    fun zoomFade(fovYDeg: Double): Float {
        val t = ((fovYDeg - ZOOM_FADE_GONE_FOV_DEG) / (ZOOM_FADE_FULL_FOV_DEG - ZOOM_FADE_GONE_FOV_DEG)).coerceIn(0.0, 1.0)
        return (t * t * (3 - 2 * t)).toFloat()
    }
}
