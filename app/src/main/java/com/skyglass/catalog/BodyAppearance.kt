package com.skyglass.catalog

import com.skyglass.astro.BodyPosition
import com.skyglass.astro.SkyBody

/**
 * How the Sun, Moon and planets are drawn. All tuning constants live here, as for stars in
 * [StarAppearance]. Sprites are not to scale: the Sun and Moon are only 0.5° across (about 7 dp
 * at this field of view), too small to show a phase or to be tapped, so they are enlarged.
 */
object BodyAppearance {

    /** Values of the `kind` vertex attribute; the fragment shader picks its look from it. */
    const val KIND_PLANET = 0f
    const val KIND_SUN = 1f
    const val KIND_MOON = 2f

    /** The Sun sprite is mostly glow; the bright disc is this fraction of its radius. */
    const val SUN_DISC_FRACTION = 0.2f

    /** The Moon sprite is a disc inside a faint halo; the disc is this fraction of its radius. */
    const val MOON_DISC_FRACTION = 0.6f

    private const val SUN_SPRITE_DP = 72f
    const val MOON_SPRITE_DP = 40f

    /** Mean angular radius of the Moon. Only this much of its enlarged drawn disc hides what is behind it. */
    const val MOON_TRUE_RADIUS_DEG = 0.26

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

    /** Radius in dp of the solid-looking part of the sprite, for placing labels and rings. */
    fun discRadiusDp(body: SkyBody, vmag: Double): Float {
        val radius = spriteSizeDp(body, vmag) / 2
        return when (body) {
            SkyBody.SUN -> radius * SUN_DISC_FRACTION
            SkyBody.MOON -> radius * MOON_DISC_FRACTION
            else -> radius / 2
        }
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

    fun spriteSizeDp(position: BodyPosition): Float = spriteSizeDp(position.body, position.vmag)

    fun discRadiusDp(position: BodyPosition): Float = discRadiusDp(position.body, position.vmag)
}
