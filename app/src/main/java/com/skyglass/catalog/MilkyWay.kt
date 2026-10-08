package com.skyglass.catalog

import com.skyglass.astro.AstroTime
import com.skyglass.astro.Galactic
import com.skyglass.astro.Precession
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * A procedural Milky Way: how bright the band is, and how warm, at a galactic longitude and latitude.
 *
 * This is a hand-built, qualitative model, not a photograph or a survey map (no suitably licensed
 * all-sky image is bundled, and the app has no network). It is built from a few things anyone can
 * see in a dark sky, each a smooth function of galactic coordinates:
 *
 *  - a thin disc, brightest toward the galactic centre (l = 0) and about 40% as bright toward
 *    the anticentre (l = 180), thicker near the centre, with a faint wider glow round it;
 *  - the central bulge in Sagittarius and Scorpius;
 *  - the brighter star clouds: Sagittarius, Scutum, Cygnus, Carina, Crux-Centaurus, Norma and the
 *    Perseus arm;
 *  - the dark lanes: the Great Rift (a lane that splits the band lengthwise from Cygnus through Aquila
 *    to the dark clouds of Ophiuchus by the centre) and the Coalsack.
 *
 * Positions of these features are good to a few degrees, and the shapes are idealised Gaussians.
 * Everything is a function of (l, b) in degrees; the result is a relative brightness in 0..1 whose
 * peak, near the galactic centre, is close to 1. [MilkyWayMesh] turns it into a drawable mesh.
 */
object MilkyWayModel {

    /** Brightness (0..1) at galactic longitude [lDeg] and latitude [bDeg]. */
    fun brightness(lDeg: Double, bDeg: Double): Double {
        val l = wrap(lDeg)
        val b = bDeg

        // Disc: brighter and thicker toward the centre.
        val longitudeWeight = 0.40 + 0.60 * exp(-(abs(l) / 65.0).pow(1.5))
        val thinSigma = 3.2 + 2.0 * gauss(l, 0.0, 45.0)
        val disc = longitudeWeight * (0.80 * gauss(b, 0.0, thinSigma) + 0.20 * gauss(b, 0.0, 12.0))

        // Bulge: elongated along the plane.
        val bulge = 0.70 * gauss(l, 0.0, 12.0) * gauss(b, 0.0, 6.5)

        // Brighter star clouds, as (amplitude, l, b, sigma l, sigma b).
        var clouds = 0.0
        for (c in STAR_CLOUDS) clouds += c.amplitude * gaussLongitude(l, c.l, c.sigmaL) * gauss(b, c.b, c.sigmaB)

        val total = (0.80 * disc + bulge + clouds) * darkLanes(l, b)
        return (total / PEAK).coerceIn(0.0, 1.0)
    }

    /** Multiplier from 1 (clear) down to a small value inside the Great Rift, the Coalsack and the clouds by the centre. */
    fun darkLanes(lDeg: Double, bDeg: Double): Double {
        val l = wrap(lDeg)
        // The Great Rift: a lane along the plane from l = -30 (Scorpius/Ophiuchus) to l = +80 (Cygnus),
        // lying a little north of the plane and rising to the Ophiuchus dark clouds near the centre.
        val riftWindow = smoothStep(-35.0, -15.0, l) * (1.0 - smoothStep(65.0, 90.0, l))
        val riftCentre = 1.0 + 3.0 * gauss(l, 0.0, 12.0)
        val riftWidth = 1.6 + 1.5 * gauss(l, 0.0, 15.0)
        val rift = 0.60 * riftWindow * gauss(bDeg, riftCentre, riftWidth)

        var dark = 1.0 - rift
        for (patch in DARK_PATCHES) {
            dark *= 1.0 - patch.depth * gaussLongitude(l, patch.l, patch.sigmaL) * gauss(bDeg, patch.b, patch.sigmaB)
        }
        return dark.coerceIn(0.0, 1.0)
    }

    /**
     * Warmth from 0 (cool, blue-white) to 1 (warm, yellow-white): the old stars of the central
     * bulge give the inner Galaxy a yellow cast that the outer disc lacks.
     */
    fun warmth(lDeg: Double, bDeg: Double): Double {
        val l = wrap(lDeg)
        return (gauss(l, 0.0, 40.0) * gauss(bDeg, 0.0, 14.0)).coerceIn(0.0, 1.0)
    }

    private class Feature(val amplitude: Double, val l: Double, val b: Double, val sigmaL: Double, val sigmaB: Double)

    private class Patch(val depth: Double, val l: Double, val b: Double, val sigmaL: Double, val sigmaB: Double)

    private val STAR_CLOUDS = listOf(
        Feature(0.22, 14.0, -1.0, 8.0, 3.0), // Sagittarius (M24 is at l = 15.6)
        Feature(0.22, 29.0, 0.0, 5.0, 2.5), // Scutum
        Feature(0.22, 78.0, 1.0, 10.0, 4.0), // Cygnus
        Feature(0.08, 130.0, 0.0, 12.0, 3.0), // Perseus-Cassiopeia
        Feature(0.18, 287.0, -1.0, 8.0, 3.0), // Carina
        Feature(0.18, 308.0, 0.0, 10.0, 3.5), // Crux-Centaurus
        Feature(0.15, 332.0, -1.0, 8.0, 3.0), // Norma
    )

    private val DARK_PATCHES = listOf(
        Patch(0.80, 301.0, 1.0, 2.5, 2.5), // Coalsack
        Patch(0.55, 3.0, 6.0, 4.0, 3.0), // dark clouds of Ophiuchus, above the centre
    )

    /** Normalises the sum above so that the brightest point of the band (near the centre) is close to 1. */
    private const val PEAK = 1.32

    private fun wrap(lDeg: Double): Double {
        val l = AstroTime.normalizeDegrees(lDeg)
        return if (l > 180.0) l - 360.0 else l
    }

    /** A Gaussian in longitude, measured round the circle so features near l = 360 are not missed. */
    private fun gaussLongitude(l: Double, mean: Double, sigma: Double): Double = gauss(wrap(l - mean), 0.0, sigma)

    private fun gauss(x: Double, mean: Double, sigma: Double): Double {
        val d = (x - mean) / sigma
        return exp(-0.5 * d * d)
    }

    private fun smoothStep(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}

/** A drawable triangle mesh of the band: interleaved position (3) and premultiplied colour (3), and 16-bit indices. */
class MilkyWayMesh(val vertices: FloatArray, val indices: ShortArray) {
    val vertexCount: Int get() = vertices.size / FLOATS_PER_VERTEX

    companion object {
        const val FLOATS_PER_VERTEX = 6
        const val POSITION_OFFSET = 0
        const val COLOR_OFFSET = 3

        /** Colour of the band's cool outer parts and of its warm centre, before the brightness is applied. */
        private val COOL = floatArrayOf(0.62f, 0.72f, 0.95f)
        private val WARM = floatArrayOf(1.00f, 0.86f, 0.66f)

        /** The band's colour at its brightest, as a fraction of full white: it is a faint glow, not a lamp. */
        const val PEAK_INTENSITY = 0.24f

        /** The mesh covers galactic latitudes −40..+40°; beyond that the model is practically zero. */
        const val MAX_LATITUDE_DEG = 40.0

        /** Spacing in longitude; rows are closer together near the plane, where the rift and the band's edge are narrow. */
        const val LONGITUDE_STEP_DEG = 1.5

        private val LATITUDE_HALF_ROWS = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.5, 7.0, 9.0, 11.0, 14.0, 17.0, 21.0, 26.0, 32.0, MAX_LATITUDE_DEG)

        /** All latitude rows, south to north. */
        val LATITUDES: DoubleArray = (LATITUDE_HALF_ROWS.drop(1).reversed().map { -it } + LATITUDE_HALF_ROWS.toList()).toDoubleArray()

        /** Longitude columns, 0 to 360 inclusive: the last repeats the first so the seam needs no special case. */
        val LONGITUDES: DoubleArray = DoubleArray((360.0 / LONGITUDE_STEP_DEG).toInt() + 1) { it * LONGITUDE_STEP_DEG }

        /** @param julianDate the epoch of the equatorial frame of date the positions are given in */
        fun build(julianDate: Double): MilkyWayMesh {
            val precession = Precession.matrixFromJ2000(AstroTime.julianCenturiesSinceJ2000(julianDate))
            val rows = LATITUDES.size
            val columns = LONGITUDES.size
            check(rows * columns <= 0xFFFF) { "mesh too big for 16-bit indices" }
            val vertices = FloatArray(rows * columns * FLOATS_PER_VERTEX)
            for (r in 0 until rows) for (c in 0 until columns) {
                val l = LONGITUDES[c]
                val b = LATITUDES[r]
                val v = precession * Galactic.toEquatorialUnit(l, b)
                val brightness = MilkyWayModel.brightness(l, b).toFloat() * PEAK_INTENSITY
                val warm = MilkyWayModel.warmth(l, b).toFloat() * 0.8f
                val base = (r * columns + c) * FLOATS_PER_VERTEX
                vertices[base + POSITION_OFFSET] = v.x.toFloat()
                vertices[base + POSITION_OFFSET + 1] = v.y.toFloat()
                vertices[base + POSITION_OFFSET + 2] = v.z.toFloat()
                for (k in 0..2) vertices[base + COLOR_OFFSET + k] = (COOL[k] + (WARM[k] - COOL[k]) * warm) * brightness
            }
            val indices = ShortArray((rows - 1) * (columns - 1) * 6)
            var n = 0
            for (r in 0 until rows - 1) for (c in 0 until columns - 1) {
                val a = r * columns + c
                val b = a + 1
                val d = a + columns
                val e = d + 1
                indices[n++] = a.toShort(); indices[n++] = b.toShort(); indices[n++] = e.toShort()
                indices[n++] = a.toShort(); indices[n++] = e.toShort(); indices[n++] = d.toShort()
            }
            return MilkyWayMesh(vertices, indices)
        }
    }
}

/** How the band is blended; see `MilkyWayShaders`. */
object MilkyWayAppearance {
    /**
     * Strength of the band over a camera image, as a fraction of its strength on black. The camera
     * view composites the sky *over* the image, so anything drawn covers part of it in proportion to
     * its alpha; the band is wide, so it is kept to half strength to leave the image readable.
     */
    const val CAMERA_GAIN = 0.5f
}
