package com.skyglass.catalog

import com.skyglass.astro.BodyPosition
import com.skyglass.astro.BodySize
import com.skyglass.astro.PlanetMoon
import com.skyglass.astro.PlanetMoonPosition
import com.skyglass.astro.SkyBody
import kotlin.math.max
import kotlin.math.tan

/**
 * How big a body is drawn in one view, in pixels (see [BodyAppearance.drawn]).
 *
 * @property spriteRadiusPx the whole quad, halo included; a tap anywhere on it reaches the body
 * @property discRadiusPx the disc itself
 * @property growthPx how far the disc has grown beyond its nominal size to keep to the true size;
 *   the halo is pushed out by the same amount
 * @property labelRadiusPx the solid-looking part, for placing labels and rings and for a tap to count
 *   as on the body
 */
data class DrawnBody(val spriteRadiusPx: Double, val discRadiusPx: Double, val growthPx: Double, val labelRadiusPx: Double)

/**
 * How the Sun, Moon and planets are drawn. All tuning constants live here, as for stars in
 * [StarAppearance]. At the default field of view the sprites are not to scale: the Sun and Moon are
 * only 0.5° across (about 7 dp), too small to show a phase or to be tapped, so they are enlarged.
 * Zoomed in, a body's disc is never drawn smaller than its true size ([drawn]), so the Moon fills the
 * screen at a 1° field of view and Jupiter shows as a disc.
 */
object BodyAppearance {

    /** Values of the `kind` vertex attribute; the fragment shader picks its look from it. */
    const val KIND_PLANET = 0f
    const val KIND_SUN = 1f
    const val KIND_MOON = 2f

    /** A planet's moon (Io, Titan, ...), drawn like a small, faint planet. */
    const val KIND_SATELLITE = 3f

    /**
     * A planet's moon fades in as it moves out of the planet's glow: it is invisible while it is within
     * the first of these distances (dp on screen) of the planet's centre and at full strength beyond the
     * second. At the default field of view every moon is hidden (Callisto, the farthest of Jupiter's, is
     * at most 2 dp out); in binoculars' 6° Jupiter's outer moons sit just beside its glare, as they do.
     */
    const val SATELLITE_FADE_START_DP = 5.0
    const val SATELLITE_FADE_FULL_DP = 9.0

    /**
     * Once a planet's disc grows to its true size, its halo (which stands for the glare of a point of
     * light) narrows as fast as the disc grows, so the whole glow keeps its size while the disc fills
     * it, down to this fraction of its nominal width, after which disc and halo grow together: zoomed
     * in, a planet is a crisp disc, as in a telescope, and a moon two radii out is not buried in its glow.
     */
    const val PLANET_MIN_HALO_SCALE = 0.3f

    /** Sprite diameters of the brightest moons (magnitude 5, Jupiter's) and the faintest (14, Hyperion). */
    private const val SATELLITE_BRIGHT_SPRITE_DP = 7f
    private const val SATELLITE_FAINT_SPRITE_DP = 4.5f
    private const val SATELLITE_BRIGHT_MAGNITUDE = 5.0
    private const val SATELLITE_FAINT_MAGNITUDE = 14.0
    private const val SATELLITE_MIN_INTENSITY = 0.35f
    private const val SATELLITE_MAX_INTENSITY = 0.85f

    /** The Sun sprite is mostly glow; the bright disc is this fraction of its radius. */
    const val SUN_DISC_FRACTION = 0.2f

    /** The Moon sprite is a disc inside a faint halo; the disc is this fraction of its radius. */
    const val MOON_DISC_FRACTION = 0.6f

    /** A planet sprite is a small disc in a soft halo; the disc's (soft) edge is at this fraction of its radius. */
    const val PLANET_DISC_FRACTION = 0.2f

    /**
     * Only the Moon's true disc may hide what is behind it, as a fraction of its drawn disc; never
     * less than this, so the shader's edge stays well defined.
     */
    const val MIN_MOON_OCCLUDER = 0.05f

    private const val SUN_SPRITE_DP = 72f
    private const val MOON_SPRITE_DP = 40f

    private const val PLANET_MIN_SPRITE_DP = 6f
    private const val PLANET_MAX_SPRITE_DP = 17f

    /** Planets stay clearly visible even where a star of that magnitude would be dim. */
    private const val PLANET_MIN_INTENSITY = 0.55f

    fun kind(body: SkyBody): Float = when (body) {
        SkyBody.SUN -> KIND_SUN
        SkyBody.MOON -> KIND_MOON
        else -> KIND_PLANET
    }

    /** Diameter of the point sprite in dp, halo included. */
    fun spriteSizeDp(body: SkyBody, vmag: Double): Float = when (body) {
        SkyBody.SUN -> SUN_SPRITE_DP
        SkyBody.MOON -> MOON_SPRITE_DP
        // Larger than a star of the same magnitude, and not capped at Sirius's size.
        else -> (8.0 + (4.0 - vmag)).toFloat().coerceIn(PLANET_MIN_SPRITE_DP, PLANET_MAX_SPRITE_DP)
    }

    /** Radius in dp of the solid-looking part of the sprite at its nominal size, for placing labels and rings. */
    fun discRadiusDp(body: SkyBody, vmag: Double): Float {
        val radius = spriteSizeDp(body, vmag) / 2
        return when (body) {
            SkyBody.SUN -> radius * SUN_DISC_FRACTION
            SkyBody.MOON -> radius * MOON_DISC_FRACTION
            else -> radius / 2
        }
    }

    /** The disc's radius as a fraction of the nominal sprite's radius (the shader's disc for each kind). */
    fun discFraction(body: SkyBody): Float = when (body) {
        SkyBody.SUN -> SUN_DISC_FRACTION
        SkyBody.MOON -> MOON_DISC_FRACTION
        else -> PLANET_DISC_FRACTION
    }

    /** The tangent of the body's true angular radius: times a view's focal length, its true radius in pixels. */
    fun trueRadiusTan(position: BodyPosition): Double =
        tan(Math.toRadians(BodySize.angularRadiusDeg(position.body, position.distanceAu)))

    /**
     * How big [position] is drawn in a view of focal length [focalPx] (pixels), [density] pixels per dp.
     * The disc is its nominal size ([discFraction] of [spriteSizeDp]) or its true size, whichever is
     * larger, and the halo keeps its nominal width outside the disc ([haloScale] of it for a planet).
     * `BodyShaders` does the same sum.
     */
    fun drawn(position: BodyPosition, focalPx: Double, density: Float): DrawnBody {
        val nominalRadiusPx = spriteSizeDp(position) * density / 2.0
        val nominalDiscPx = discFraction(position.body) * nominalRadiusPx
        val discPx = max(nominalDiscPx, trueRadiusTan(position) * focalPx)
        val halo = haloScale(position.body, nominalRadiusPx, nominalDiscPx, discPx)
        return DrawnBody(
            spriteRadiusPx = discPx + (nominalRadiusPx - nominalDiscPx) * halo,
            discRadiusPx = discPx,
            growthPx = discPx - nominalDiscPx,
            labelRadiusPx = discPx + (discRadiusDp(position) * density - nominalDiscPx) * halo,
        )
    }

    /** How wide a body's halo is, as a fraction of its nominal width: narrower for a planet whose disc has grown (see [PLANET_MIN_HALO_SCALE]). */
    fun haloScale(body: SkyBody, nominalRadiusPx: Double, nominalDiscPx: Double, discPx: Double): Double = when (body) {
        SkyBody.SUN, SkyBody.MOON -> 1.0
        else -> (1.0 - (discPx - nominalDiscPx) / (nominalRadiusPx - nominalDiscPx)).coerceIn(PLANET_MIN_HALO_SCALE.toDouble(), 1.0)
    }

    /** Premultiplied by brightness. Warm yellow Sun, pale Moon, and each planet's familiar tint. */
    fun color(body: SkyBody, vmag: Double): Rgb {
        val base = when (body) {
            SkyBody.SUN -> Rgb(1.00f, 0.93f, 0.72f)
            SkyBody.MOON -> Rgb(0.93f, 0.93f, 0.88f)
            SkyBody.MERCURY -> Rgb(0.82f, 0.80f, 0.78f)
            SkyBody.VENUS -> Rgb(1.00f, 0.97f, 0.85f)
            SkyBody.MARS -> Rgb(1.00f, 0.52f, 0.34f)
            SkyBody.JUPITER -> Rgb(1.00f, 0.90f, 0.74f)
            SkyBody.SATURN -> Rgb(1.00f, 0.86f, 0.55f)
            SkyBody.URANUS -> Rgb(0.62f, 0.92f, 0.96f)
            SkyBody.NEPTUNE -> Rgb(0.52f, 0.62f, 1.00f)
        }
        val brightness = when (body) {
            SkyBody.SUN, SkyBody.MOON -> 1f
            else -> StarAppearance.intensity(vmag).coerceAtLeast(PLANET_MIN_INTENSITY)
        }
        return Rgb(base.r * brightness, base.g * brightness, base.b * brightness)
    }

    /** How far between the faintest and the brightest moon [vmag] is, 0 to 1. */
    private fun satelliteBrightness(vmag: Double): Float =
        ((SATELLITE_FAINT_MAGNITUDE - vmag) / (SATELLITE_FAINT_MAGNITUDE - SATELLITE_BRIGHT_MAGNITUDE)).coerceIn(0.0, 1.0).toFloat()

    /** Diameter in dp of a moon's sprite, halo included: a few dp, a little larger for the brighter ones. */
    fun satelliteSizeDp(vmag: Double): Float =
        SATELLITE_FAINT_SPRITE_DP + (SATELLITE_BRIGHT_SPRITE_DP - SATELLITE_FAINT_SPRITE_DP) * satelliteBrightness(vmag)

    /** Premultiplied by brightness: near white, Io a little yellow, Titan orange, Callisto darker. */
    fun satelliteColor(moon: PlanetMoon, vmag: Double): Rgb {
        val base = when (moon) {
            PlanetMoon.IO -> Rgb(1.00f, 0.95f, 0.75f)
            PlanetMoon.TITAN -> Rgb(1.00f, 0.82f, 0.55f)
            PlanetMoon.CALLISTO -> Rgb(0.85f, 0.83f, 0.80f)
            else -> Rgb(0.95f, 0.95f, 0.93f)
        }
        val brightness = SATELLITE_MIN_INTENSITY + (SATELLITE_MAX_INTENSITY - SATELLITE_MIN_INTENSITY) * satelliteBrightness(vmag)
        return Rgb(base.r * brightness, base.g * brightness, base.b * brightness)
    }

    /**
     * How strongly a moon [separationTan] (tangent of its angular distance) from its planet's centre is
     * drawn in a view of focal length [focalPx]: 0 near the planet, 1 once clear of it (see
     * [SATELLITE_FADE_START_DP]). `BodyShaders` does the same sum.
     */
    fun satelliteFade(separationTan: Double, focalPx: Double, density: Float): Float {
        val separationDp = separationTan * focalPx / density
        val t = ((separationDp - SATELLITE_FADE_START_DP) / (SATELLITE_FADE_FULL_DP - SATELLITE_FADE_START_DP)).coerceIn(0.0, 1.0)
        return (t * t * (3 - 2 * t)).toFloat()
    }

    /** How big a moon is drawn, in pixels: always its nominal sprite (its true disc is a pixel or less even at 1°). */
    fun drawn(moon: PlanetMoonPosition, density: Float): DrawnBody {
        val radiusPx = satelliteSizeDp(moon.vmag) * density / 2.0
        return DrawnBody(
            spriteRadiusPx = radiusPx,
            discRadiusPx = radiusPx * PLANET_DISC_FRACTION,
            growthPx = 0.0,
            labelRadiusPx = radiusPx / 2,
        )
    }

    fun spriteSizeDp(position: BodyPosition): Float = spriteSizeDp(position.body, position.vmag)

    fun discRadiusDp(position: BodyPosition): Float = discRadiusDp(position.body, position.vmag)
}
