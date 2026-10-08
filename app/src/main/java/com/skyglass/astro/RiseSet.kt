package com.skyglass.astro

import com.skyglass.math.Vector3

/**
 * When something next rises and sets.
 *
 * @property riseJd Julian date (UTC) of the first rising within the search window, or null if it does not rise in it
 * @property setJd likewise for setting
 * @property alwaysAbove true when neither happens and the object stays above the horizon throughout
 *   (circumpolar, or the midnight Sun); false with both null means it stays below
 */
data class RiseSetTimes(val riseJd: Double?, val setJd: Double?, val alwaysAbove: Boolean)

/**
 * Rise and set times by scanning the altitude, so they follow a body that moves quickly (the Moon
 * travels 13° a day, which a once-only calculation at the current position would get wrong by up to
 * an hour). Altitude is geometric; the horizon is lowered by [STANDARD_REFRACTION_DEG] for the
 * refraction there, and by the semi-diameter for the Sun and Moon so that it is the upper limb that
 * touches it, as almanacs define rising and setting (Meeus, chapter 15).
 *
 * Each function takes a [checkActive] hook, called before every altitude evaluation, which the caller
 * can make throw to abandon the calculation (a coroutine's `ensureActive`); this package stays free of
 * coroutines.
 */
object RiseSet {

    /** Standard refraction at the horizon, 34′. */
    const val STANDARD_REFRACTION_DEG = 0.5667

    /** The Sun's semi-diameter plus refraction, 50′, Meeus's standard altitude for the Sun. */
    const val SUN_HORIZON_DEG = -0.8333

    /** Long enough that the Moon, which rises up to ~25 h apart, always shows one of each. */
    const val WINDOW_HOURS = 26.0

    private const val STEP_DAYS = 10.0 / (24 * 60)
    private const val BISECTION_STEPS = 12
    private const val EXTREMUM_STEPS = 24

    /**
     * A turning point of the altitude that stays within this many degrees of the horizon is searched
     * for a brief crossing, because the scan step can step right over a rise and set a few minutes apart.
     */
    private const val GRAZE_MARGIN_DEG = 1.0

    /** A star, fixed in the equatorial frame of date (its proper motion and precession over a day are negligible). */
    fun forStar(
        direction: Vector3,
        startJd: Double,
        latitudeDeg: Double,
        longitudeDeg: Double,
        checkActive: () -> Unit = {},
    ): RiseSetTimes =
        find(startJd, checkActive) { jd ->
            SkyPlacement.of(direction, jd, latitudeDeg, longitudeDeg).altitudeDeg + STANDARD_REFRACTION_DEG
        }

    /** The Sun, Moon or a planet; the Moon's position is the topocentric one, so parallax is already in. */
    fun forBody(
        body: SkyBody,
        startJd: Double,
        latitudeDeg: Double,
        longitudeDeg: Double,
        checkActive: () -> Unit = {},
    ): RiseSetTimes =
        find(startJd, checkActive) { jd ->
            val position = SolarSystem.position(body, jd, latitudeDeg, longitudeDeg)
            val placement = SkyPlacement.of(position.direction, jd, latitudeDeg, longitudeDeg)
            placement.altitudeDeg - horizonDeg(body, position.distanceAu)
        }

    /** Altitude at which the object counts as risen or set. */
    fun horizonDeg(body: SkyBody, distanceAu: Double): Double = when (body) {
        SkyBody.SUN -> SUN_HORIZON_DEG
        SkyBody.MOON -> -STANDARD_REFRACTION_DEG - BodySize.angularRadiusDeg(body, distanceAu)
        else -> -STANDARD_REFRACTION_DEG
    }

    /**
     * Finds the first upward and the first downward zero crossing of [altitudeAboveHorizonDeg]
     * (altitude minus the altitude that counts as the horizon) in the [WINDOW_HOURS] after [startJd]:
     * a coarse scan, then bisection to about a second. Where the scan sees the altitude turn within
     * [GRAZE_MARGIN_DEG] of zero without crossing it (a Sun that only just clears the horizon, a star
     * that barely skims it), the turning point is located and tested, so a rise and a set closer
     * together than the scan step are not lost. (Assumes the altitude has one turning point per two steps.)
     */
    fun find(startJd: Double, checkActive: () -> Unit = {}, altitudeAboveHorizonDeg: (Double) -> Double): RiseSetTimes {
        fun f(jd: Double): Double {
            checkActive()
            return altitudeAboveHorizonDeg(jd)
        }

        val endJd = startJd + WINDOW_HOURS / 24.0
        var rise: Double? = null
        var set: Double? = null

        val startAbove = f(startJd)
        var olderJd = Double.NaN
        var older = Double.NaN
        var previousJd = startJd
        var previous = startAbove
        while (previousJd < endJd && (rise == null || set == null)) {
            val jd = minOf(previousJd + STEP_DAYS, endJd)
            val value = f(jd)
            if (previous < 0 && value >= 0) {
                if (rise == null) rise = bisect(previousJd, jd, ::f)
            } else if (previous >= 0 && value < 0) {
                if (set == null) set = bisect(previousJd, jd, ::f)
            } else if (!older.isNaN()) {
                // `older`, `previous` and `value` are on the same side of zero; is `previous` a turning point close to it?
                val peaksBelow = previous < 0 && previous > -GRAZE_MARGIN_DEG && previous > older && previous >= value
                val dipsAbove = previous >= 0 && previous < GRAZE_MARGIN_DEG && previous < older && previous <= value
                if (peaksBelow || dipsAbove) {
                    val turnJd = extremum(olderJd, jd, maximum = peaksBelow, ::f)
                    val turn = f(turnJd)
                    if (peaksBelow && turn >= 0) {
                        if (rise == null) rise = bisect(olderJd, turnJd, ::f)
                        if (set == null) set = bisect(turnJd, jd, ::f)
                    } else if (dipsAbove && turn < 0) {
                        if (set == null) set = bisect(olderJd, turnJd, ::f)
                        if (rise == null) rise = bisect(turnJd, jd, ::f)
                    }
                }
            }
            olderJd = previousJd
            older = previous
            previousJd = jd
            previous = value
        }
        return RiseSetTimes(rise, set, alwaysAbove = rise == null && set == null && startAbove >= 0)
    }

    private fun bisect(lowJd: Double, highJd: Double, f: (Double) -> Double): Double {
        var low = lowJd
        var high = highJd
        val risingAtEnd = f(high) >= 0
        repeat(BISECTION_STEPS) {
            val mid = (low + high) / 2
            if ((f(mid) >= 0) == risingAtEnd) high = mid else low = mid
        }
        return (low + high) / 2
    }

    /** Ternary search for the highest (or lowest) point of [f] between [lowJd] and [highJd], taken to be unimodal there. */
    private fun extremum(lowJd: Double, highJd: Double, maximum: Boolean, f: (Double) -> Double): Double {
        var low = lowJd
        var high = highJd
        repeat(EXTREMUM_STEPS) {
            val a = low + (high - low) / 3
            val b = high - (high - low) / 3
            if ((f(a) < f(b)) == maximum) low = a else high = b
        }
        return (low + high) / 2
    }
}
