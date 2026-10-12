package com.skyglass.catalog

import com.skyglass.astro.BodyPosition
import com.skyglass.astro.RiseSet
import com.skyglass.astro.RiseSetTimes
import com.skyglass.astro.SkyBody
import com.skyglass.astro.SkyPlacement
import com.skyglass.astro.SolarSystem
import com.skyglass.math.Vector3
import java.time.LocalDate

/**
 * How high something is, in the words a beginner looks by ("low in the east") rather than degrees.
 * Low is where haze and buildings get in the way; overhead is within 10° of the zenith, where a compass
 * direction stops meaning much.
 */
enum class SkyHeight {
    BELOW, LOW, HALFWAY, HIGH, OVERHEAD;

    companion object {
        fun of(altitudeDeg: Double): SkyHeight = when {
            altitudeDeg <= 0 -> BELOW
            altitudeDeg < 25 -> LOW
            altitudeDeg < 55 -> HALFWAY
            altitudeDeg < 80 -> HIGH
            else -> OVERHEAD
        }
    }
}

/** Astronomical darkness: the Sun's centre more than 18° below the horizon, when the faintest stars show. */
sealed interface Darkness {
    /** Dark already; it ends at [endJd], or not within a day (polar night) when null. */
    data class Now(val endJd: Double?) : Darkness

    /** Not dark yet; dark from [startJd] until [endJd], or past the day searched when null. */
    data class Later(val startJd: Double, val endJd: Double?) : Darkness

    /** The Sun stays less than 18° below the horizon all night (summer at high latitudes). */
    data object None : Darkness
}

/**
 * The Moon at [Tonight.Plan.atJd]: its phase, where it is, and its next rise or set after then.
 * [placement] is geometric, so "up" means [SkyPlacement.altitudeDeg] above zero.
 */
data class TonightMoon(val position: BodyPosition, val placement: SkyPlacement, val riseSet: RiseSetTimes)

/** A planet that is up at [Tonight.Plan.atJd]; [setJd] is when it next sets, null if not within a day. */
data class TonightPlanet(val position: BodyPosition, val placement: SkyPlacement, val setJd: Double?)

/**
 * An active shower, with its radiant (equatorial of date), [daysFromPeak] (negative before the peak) and
 * the date of the peak it is counted from.
 */
data class TonightShower(
    val shower: MeteorShower,
    val direction: Vector3,
    val placement: SkyPlacement,
    val daysFromPeak: Int,
    val peakDate: LocalDate,
)

data class TonightDeepSky(val obj: DeepSkyObject, val direction: Vector3, val placement: SkyPlacement)

/**
 * What is worth looking at tonight, best first: the Moon, the planets that are up (brightest first),
 * the active meteor showers (strongest first), then bright deep-sky objects high in the sky (brightest
 * first). Bodies come first because anyone can find them; showers need no telescope; deep-sky objects
 * want a dark sky and often binoculars.
 *
 * Everything is worked out for one instant, [Plan.atJd]: now if it is already dark, else when darkness
 * begins, else (no full darkness) when the Sun is lowest. So a list opened in the afternoon shows the
 * coming night, not the daytime sky.
 */
object Tonight {

    /** Planets lower than this are lost in haze and behind trees. */
    const val MIN_PLANET_ALTITUDE_DEG = 5.0

    /** Uranus (5.7) is just visible to the eye in a dark sky; Neptune (7.8) never is. */
    const val MAX_PLANET_MAGNITUDE = 6.0

    /** Below about 30° the air dims and reddens a faint, diffuse object by half a magnitude or more. */
    const val MIN_DEEP_SKY_ALTITUDE_DEG = 30.0

    /** Bright enough for binoculars from a suburban garden, and the brightest for the naked eye. */
    const val MAX_DEEP_SKY_MAGNITUDE = 6.0

    const val MAX_DEEP_SKY = 6

    /** Astronomical twilight ends when the Sun's centre is this far below the horizon. */
    const val DARK_SUN_ALTITUDE_DEG = -18.0

    private const val LOWEST_SUN_STEP_DAYS = 10.0 / (24 * 60)

    data class Plan(
        /** When the plan was made. */
        val nowJd: Double,
        /** The instant the Moon, planets and so on are for; [nowJd] when it is already dark. */
        val atJd: Double,
        val darkness: Darkness,
        val moon: TonightMoon,
        val planets: List<TonightPlanet>,
        val showers: List<TonightShower>,
        val deepSky: List<TonightDeepSky>,
    ) {
        val isNow: Boolean get() = atJd == nowJd
    }

    /**
     * @param deepSkyDirections unit vectors toward [deepSky] in the equatorial frame of date, in the same order
     * @param longitudeDeg positive east
     * @param checkActive called often; may throw to abandon the work (see [RiseSet])
     */
    fun plan(
        nowJd: Double,
        latitudeDeg: Double,
        longitudeDeg: Double,
        deepSky: List<DeepSkyObject>,
        deepSkyDirections: List<Vector3>,
        showers: List<MeteorShower>,
        checkActive: () -> Unit = {},
    ): Plan {
        require(deepSky.size == deepSkyDirections.size)
        fun place(direction: Vector3, jd: Double) = SkyPlacement.of(direction, jd, latitudeDeg, longitudeDeg)

        val darkness = darkness(nowJd, latitudeDeg, longitudeDeg, checkActive)
        val atJd = when (darkness) {
            is Darkness.Now -> nowJd
            is Darkness.Later -> darkness.startJd
            Darkness.None -> lowestSunJd(nowJd, latitudeDeg, longitudeDeg, checkActive)
        }

        val bodies = SolarSystem.positions(atJd, latitudeDeg, longitudeDeg)
        val moonPosition = bodies.first { it.body == SkyBody.MOON }
        val moon = TonightMoon(
            position = moonPosition,
            placement = place(moonPosition.direction, atJd),
            riseSet = RiseSet.forBody(SkyBody.MOON, atJd, latitudeDeg, longitudeDeg, checkActive),
        )

        val planets = bodies
            .filter { it.body.planet != null && it.vmag <= MAX_PLANET_MAGNITUDE }
            .map { it to place(it.direction, atJd) }
            .filter { (_, placement) -> placement.altitudeDeg >= MIN_PLANET_ALTITUDE_DEG }
            .sortedBy { (position, _) -> position.vmag }
            .map { (position, placement) ->
                val setJd = RiseSet.forBody(position.body, atJd, latitudeDeg, longitudeDeg, checkActive).setJd
                TonightPlanet(position, placement, setJd)
            }

        val date = MeteorShower.localDate(atJd, longitudeDeg)
        val activeShowers = showers
            .filter { it.isActive(date) }
            .sortedByDescending { it.zhr }
            .map { shower ->
                val days = shower.daysFromPeak(date)
                val direction = shower.radiantDirection(days.toDouble(), atJd)
                TonightShower(shower, direction, place(direction, atJd), days, date.minusDays(days.toLong()))
            }

        val brightDeepSky = deepSky.indices
            .filter { deepSky[it].vmag?.let { vmag -> vmag <= MAX_DEEP_SKY_MAGNITUDE } == true }
            .map { TonightDeepSky(deepSky[it], deepSkyDirections[it], place(deepSkyDirections[it], atJd)) }
            .filter { it.placement.altitudeDeg >= MIN_DEEP_SKY_ALTITUDE_DEG }
            .sortedBy { it.obj.vmag }
            .take(MAX_DEEP_SKY)

        return Plan(nowJd, atJd, darkness, moon, planets, activeShowers, brightDeepSky)
    }

    /** The next spell of astronomical darkness, found by scanning the Sun's altitude like a rising and setting. */
    fun darkness(nowJd: Double, latitudeDeg: Double, longitudeDeg: Double, checkActive: () -> Unit = {}): Darkness {
        // Positive while dark, so "rising" through zero is darkness beginning and "setting" is it ending.
        val times = RiseSet.find(nowJd, checkActive) { jd -> DARK_SUN_ALTITUDE_DEG - sunAltitudeDeg(jd, latitudeDeg, longitudeDeg) }
        val darkNow = sunAltitudeDeg(nowJd, latitudeDeg, longitudeDeg) < DARK_SUN_ALTITUDE_DEG
        return when {
            darkNow -> Darkness.Now(times.setJd)
            times.riseJd != null -> Darkness.Later(times.riseJd, times.setJd)
            else -> Darkness.None
        }
    }

    /** When the Sun is lowest in the day after [nowJd], to the scan step: the darkest the sky gets. */
    private fun lowestSunJd(nowJd: Double, latitudeDeg: Double, longitudeDeg: Double, checkActive: () -> Unit): Double {
        var bestJd = nowJd
        var bestAltitude = Double.MAX_VALUE
        var jd = nowJd
        while (jd <= nowJd + 1.0) {
            checkActive()
            val altitude = sunAltitudeDeg(jd, latitudeDeg, longitudeDeg)
            if (altitude < bestAltitude) {
                bestAltitude = altitude
                bestJd = jd
            }
            jd += LOWEST_SUN_STEP_DAYS
        }
        return bestJd
    }

    private fun sunAltitudeDeg(jd: Double, latitudeDeg: Double, longitudeDeg: Double): Double =
        SkyPlacement.of(SolarSystem.position(SkyBody.SUN, jd, latitudeDeg, longitudeDeg).direction, jd, latitudeDeg, longitudeDeg).altitudeDeg
}
