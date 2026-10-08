package com.skyglass.catalog

import com.skyglass.math.Vector3
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/** The semi-axes, in radians of sky (tangent-plane units), that an object is drawn with. */
data class DrawnSemiAxes(val majorRad: Double, val minorRad: Double)

/**
 * How deep-sky objects are drawn. All tuning constants live here, as for stars in [StarAppearance]
 * and the bodies in [BodyAppearance]. Each object is a quad on the sky, as large as the object
 * (so the Andromeda Galaxy really is six moons across) but never smaller than [MIN_RADIUS_DP], with a
 * per-type symbol (the `glyph`) and colour, kept dim so the stars stay what the eye goes to.
 */
object DeepSkyAppearance {

    /** Values of the `glyph` vertex attribute; `DeepSkyShaders` picks its look from it. */
    const val GLYPH_GALAXY = 0f
    const val GLYPH_GLOBULAR = 1f
    const val GLYPH_OPEN_CLUSTER = 2f
    const val GLYPH_NEBULA = 3f
    const val GLYPH_PLANETARY = 4f
    const val GLYPH_REMNANT = 5f
    const val GLYPH_DARK = 6f

    /** The quad reaches this many semi-axes from the centre, so glows have room to fade out. */
    const val QUAD_EXTENT = 1.35f

    /** Smallest radius an object is drawn with, in dp: a planetary nebula is arcseconds across, but must show as a symbol. */
    const val MIN_RADIUS_DP = 7f

    /** Overall strength of the symbols, so that even the brightest do not rival a bright star. */
    private const val GAIN = 0.7

    private const val BRIGHTEST_PEAK = 0.9
    private const val FAINTEST_PEAK = 0.14

    /** Surface brightness (mag per square arcminute) drawn at [BRIGHTEST_PEAK] less a step, and how fast the peak falls per magnitude. */
    private const val REFERENCE_SURFACE_BRIGHTNESS = 11.5
    private const val PEAK_PER_MAGNITUDE = 0.18

    /** A dark nebula has no magnitude; it is drawn as a faint outline. */
    private const val DARK_NEBULA_PEAK = 0.25

    fun glyph(type: DeepSkyType): Float = when (type) {
        DeepSkyType.GALAXY -> GLYPH_GALAXY
        DeepSkyType.GLOBULAR_CLUSTER -> GLYPH_GLOBULAR
        DeepSkyType.OPEN_CLUSTER, DeepSkyType.ASTERISM, DeepSkyType.DOUBLE_STAR -> GLYPH_OPEN_CLUSTER
        DeepSkyType.EMISSION_NEBULA, DeepSkyType.REFLECTION_NEBULA, DeepSkyType.STAR_CLOUD -> GLYPH_NEBULA
        DeepSkyType.PLANETARY_NEBULA -> GLYPH_PLANETARY
        DeepSkyType.SUPERNOVA_REMNANT -> GLYPH_REMNANT
        DeepSkyType.DARK_NEBULA -> GLYPH_DARK
    }

    /** One hue per kind of object, so the symbols tell galaxies, clusters and nebulae apart by colour as well as by shape. */
    fun color(type: DeepSkyType): Rgb = when (type) {
        DeepSkyType.GALAXY -> Rgb(0.80f, 0.70f, 1.00f)
        DeepSkyType.GLOBULAR_CLUSTER -> Rgb(1.00f, 0.82f, 0.45f)
        DeepSkyType.OPEN_CLUSTER, DeepSkyType.ASTERISM, DeepSkyType.DOUBLE_STAR -> Rgb(0.95f, 0.92f, 0.55f)
        DeepSkyType.EMISSION_NEBULA -> Rgb(1.00f, 0.38f, 0.50f)
        DeepSkyType.REFLECTION_NEBULA -> Rgb(0.40f, 0.55f, 1.00f)
        DeepSkyType.PLANETARY_NEBULA -> Rgb(0.45f, 1.00f, 0.55f)
        DeepSkyType.SUPERNOVA_REMNANT -> Rgb(0.40f, 0.95f, 0.85f)
        DeepSkyType.DARK_NEBULA -> Rgb(0.60f, 0.60f, 0.66f)
        DeepSkyType.STAR_CLOUD -> Rgb(0.85f, 0.88f, 1.00f)
    }

    /**
     * Mean surface brightness in magnitudes per square arcminute: the integrated magnitude spread over
     * the object's ellipse. A bright but huge object (the Large Magellanic Cloud) is dimmer to the eye
     * than a small one of the same magnitude, so this, not the magnitude, sets how strongly it is drawn.
     * Null for an object with no magnitude.
     */
    fun surfaceBrightness(obj: DeepSkyObject): Double? {
        val vmag = obj.vmag ?: return null
        val area = Math.PI / 4.0 * obj.majorArcmin * obj.minorArcmin
        return vmag + 2.5 * log10(max(area, 0.01))
    }

    /** Peak brightness of the symbol, between [FAINTEST_PEAK] * gain and [BRIGHTEST_PEAK] * gain. */
    fun peakIntensity(obj: DeepSkyObject): Float {
        val surface = surfaceBrightness(obj)
        val peak = if (surface == null) {
            DARK_NEBULA_PEAK
        } else {
            (BRIGHTEST_PEAK - PEAK_PER_MAGNITUDE * (surface - REFERENCE_SURFACE_BRIGHTNESS)).coerceIn(FAINTEST_PEAK, BRIGHTEST_PEAK)
        }
        return (peak * GAIN).toFloat()
    }

    /**
     * The semi-axes the object is drawn with, as the vertex shader works them out: half its angular
     * size, enlarged (keeping its shape) until the long semi-axis is at least [minRadiusPx] pixels of
     * a view whose focal length is [focalPx] pixels. Taps and the overlay use this, so what is tapped
     * is what is drawn.
     */
    fun drawnSemiAxes(obj: DeepSkyObject, focalPx: Double, minRadiusPx: Double): DrawnSemiAxes {
        val major = Math.toRadians(obj.majorArcmin / 60.0) / 2.0
        val minor = Math.toRadians(obj.drawnMinorArcmin / 60.0) / 2.0
        val minRadius = minRadiusPx / focalPx
        val scale = max(1.0, minRadius / major)
        return DrawnSemiAxes(major * scale, minor * scale)
    }

    /** Radius in pixels of the circle round the object's drawn extent, for rings and taps. */
    fun drawnRadiusPx(obj: DeepSkyObject, focalPx: Double, minRadiusPx: Double): Double =
        drawnSemiAxes(obj, focalPx, minRadiusPx).majorRad * focalPx
}

/** Which deep-sky objects get a name drawn beside them; the rest can still be tapped and searched for. */
object DeepSkyLabels {

    /** A named object this bright, or a named object this large, is labelled. */
    private const val NAMED_MAGNITUDE_LIMIT = 5.0
    private const val NAMED_SIZE_LIMIT_ARCMIN = 120.0

    /** A Messier object this bright is labelled with its number even if it has no name. */
    private const val MESSIER_MAGNITUDE_LIMIT = 6.0

    /** The label text, or null if the object is not labelled. */
    fun text(obj: DeepSkyObject): String? {
        val vmag = obj.vmag
        val named = obj.name != null &&
            ((vmag != null && vmag <= NAMED_MAGNITUDE_LIMIT) || obj.majorArcmin >= NAMED_SIZE_LIMIT_ARCMIN)
        return when {
            named -> obj.title
            obj.messierNumber != null && vmag != null && vmag <= MESSIER_MAGNITUDE_LIMIT -> obj.id
            else -> null
        }
    }
}

/**
 * The corner of an object's quad on the sky: the CPU twin of `DeepSkyShaders.VERTEX`, which is the one that
 * runs, kept here so the orientation (position angle from north through east, in the equatorial frame of
 * date, where east is z × direction) is pinned by a unit test.
 */
object DeepSkyGeometry {

    /**
     * @param direction unit vector toward the object, equatorial of date (not at a pole)
     * @param semiMajor drawn semi-axes, tangent-plane units (see [DeepSkyAppearance.drawnSemiAxes])
     * @param angleRad position angle of the long axis, from north through east
     * @param cornerX -1 or 1, along the long axis
     * @param cornerY -1 or 1, along the short axis
     * @return a point in the tangent plane at [direction]; not a unit vector
     */
    fun cornerPoint(direction: Vector3, semiMajor: Double, semiMinor: Double, angleRad: Double, cornerX: Double, cornerY: Double): Vector3 {
        val axes = axes(direction, angleRad)
        return direction + (axes.first * (cornerX * semiMajor) + axes.second * (cornerY * semiMinor)) * DeepSkyAppearance.QUAD_EXTENT.toDouble()
    }

    /**
     * The ends of the ellipse's semi-axes, in the tangent plane at [direction]: where the long axis
     * and the short axis (each [semiMajor] / [semiMinor] long) reach the edge of the object. Projecting
     * them gives the ellipse on the screen, for hit testing.
     */
    fun semiAxisEnds(direction: Vector3, semiMajor: Double, semiMinor: Double, angleRad: Double): Pair<Vector3, Vector3> {
        val axes = axes(direction, angleRad)
        return Pair(direction + axes.first * semiMajor, direction + axes.second * semiMinor)
    }

    /** Unit vectors along the long and the short axis, in the tangent plane at [direction]. */
    private fun axes(direction: Vector3, angleRad: Double): Pair<Vector3, Vector3> {
        val east = Vector3(-direction.y, direction.x, 0.0).normalized()
        val north = direction cross east
        return Pair(north * cos(angleRad) + east * sin(angleRad), east * cos(angleRad) - north * sin(angleRad))
    }
}
