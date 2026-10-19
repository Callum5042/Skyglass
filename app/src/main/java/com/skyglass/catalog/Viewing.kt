package com.skyglass.catalog

import com.skyglass.astro.SkyBody
import kotlin.math.log10
import kotlin.math.max

/**
 * What someone might look through, smallest first: the dark-adapted eye, 10×50 binoculars, a small
 * (90 mm) telescope and a large (200 mm) one.
 */
enum class Instrument(val apertureMm: Double) {
    EYE(7.0),
    BINOCULARS(50.0),
    SMALL_TELESCOPE(90.0),
    LARGE_TELESCOPE(200.0);

    /**
     * How many magnitudes fainter than the naked-eye limit it reaches for a star: the light grasp over
     * a 7 mm pupil, less [ViewingGuide.REAL_WORLD_LOSS] for glass, a less than perfect eye and a hand-held view.
     */
    val gain: Double
        get() = if (this == EYE) 0.0 else 5 * log10(apertureMm / EYE.apertureMm) - ViewingGuide.REAL_WORLD_LOSS
}

/** Something a bigger instrument shows that the least one does not. */
enum class ViewingDetail { PHASE, CRATERS, SURFACE_MARKINGS, MOONS, RINGS, DISC, RESOLVED_STARS, RING_OR_DISC, STRUCTURE }

/** [detail] shows up in [instrument] (and anything bigger). */
data class CloserLook(val instrument: Instrument, val detail: ViewingDetail)

/** How to look at an object from the observer's sky. */
sealed interface Viewing {
    /** The Sun: never look at it, through anything, without a proper solar filter. */
    data object SunDanger : Viewing

    /** The object shows in [least] and anything bigger; [closerLook] is what a bigger one adds, if anything. */
    data class Needs(val least: Instrument, val closerLook: CloserLook? = null) : Viewing

    /** Too faint for every instrument, even the 200 mm telescope, from this sky. */
    data object BeyondReach : Viewing
}

/**
 * The least instrument that shows each kind of object under a sky whose naked-eye limiting magnitude
 * is `nelm`. A rough guide for a beginner, not a detection model: it ignores altitude, haze, the
 * Moon, filters and the observer's experience.
 */
object ViewingGuide {

    /** Magnitudes an instrument falls short of its full light grasp in practice (see [Instrument.gain]). */
    const val REAL_WORLD_LOSS = 1.0

    /** The limit assumed when the user hasn't set the slider and the map doesn't know the sky: a rural sky, the catalogue's own limit. */
    const val DEFAULT_LIMITING_MAGNITUDE = 6.5

    /** A diffuse object must be this much brighter than a star to be seen in the same instrument. */
    const val EXTENDED_MARGIN = 0.5

    /**
     * Surface brightness (mag/arcmin²) from which a diffuse object starts to look fainter than its
     * magnitude, and how many magnitudes it loses per magnitude of surface brightness beyond that.
     * Calibrated against Bortle's (2001) descriptions of M33 and the bright globulars (see the tests).
     */
    const val SURFACE_BRIGHTNESS_REFERENCE = 13.0
    const val SURFACE_BRIGHTNESS_PENALTY = 0.5

    /** A planet's moon sits in its planet's glare, which hides it as if it were this much fainter. */
    const val GLARE_PENALTY = 1.0

    /** A dark nebula at least this big (arcminutes) is a gap in the Milky Way the eye can see. */
    const val NAKED_EYE_DARK_NEBULA_ARCMIN = 60.0

    /**
     * The limit the signs are worked out for: the faintest-stars [slider] if the user has set it (it is
     * their own judgement of their sky), else the light-pollution map's estimate from [skyBrightness]
     * (mag/arcsec², null if unknown), else [DEFAULT_LIMITING_MAGNITUDE].
     */
    fun limitingMagnitude(slider: Double, skyBrightness: Double?): Double = when {
        !StarVisibility.isUnlimited(slider) -> slider
        skyBrightness != null -> Bortle.limitingMagnitudeForSkyBrightness(skyBrightness)
        else -> DEFAULT_LIMITING_MAGNITUDE
    }

    fun forStar(vmag: Double, nelm: Double): Viewing = reach(vmag, nelm, null)

    /** The Sun, the Moon or a planet of magnitude [vmag]. */
    fun forBody(body: SkyBody, vmag: Double, nelm: Double): Viewing = when (body) {
        SkyBody.SUN -> Viewing.SunDanger
        SkyBody.MOON -> needs(Instrument.EYE, CloserLook(Instrument.BINOCULARS, ViewingDetail.CRATERS))
        SkyBody.MERCURY, SkyBody.VENUS -> reach(vmag, nelm, CloserLook(Instrument.SMALL_TELESCOPE, ViewingDetail.PHASE))
        SkyBody.MARS -> reach(vmag, nelm, CloserLook(Instrument.SMALL_TELESCOPE, ViewingDetail.SURFACE_MARKINGS))
        SkyBody.JUPITER -> reach(vmag, nelm, CloserLook(Instrument.BINOCULARS, ViewingDetail.MOONS))
        SkyBody.SATURN -> reach(vmag, nelm, CloserLook(Instrument.SMALL_TELESCOPE, ViewingDetail.RINGS))
        SkyBody.URANUS, SkyBody.NEPTUNE -> reach(vmag, nelm, CloserLook(Instrument.SMALL_TELESCOPE, ViewingDetail.DISC))
    }

    /** A moon of Jupiter or Saturn of magnitude [vmag]: dimmed by its planet's glare, and never a naked-eye object. */
    fun forPlanetMoon(vmag: Double, nelm: Double): Viewing =
        reach(vmag + GLARE_PENALTY, nelm, null, floor = Instrument.BINOCULARS)

    /**
     * A deep-sky object, by its [effectiveMagnitude] against each instrument's reach less
     * [EXTENDED_MARGIN]. A dark nebula has no magnitude, so it goes by its size.
     */
    fun forDeepSky(obj: DeepSkyObject, nelm: Double): Viewing {
        val magnitude = effectiveMagnitude(obj)
        val closerLook = when (obj.type) {
            DeepSkyType.GLOBULAR_CLUSTER -> CloserLook(Instrument.SMALL_TELESCOPE, ViewingDetail.RESOLVED_STARS)
            DeepSkyType.OPEN_CLUSTER -> CloserLook(Instrument.BINOCULARS, ViewingDetail.RESOLVED_STARS)
            DeepSkyType.PLANETARY_NEBULA -> CloserLook(Instrument.SMALL_TELESCOPE, ViewingDetail.RING_OR_DISC)
            DeepSkyType.GALAXY -> CloserLook(Instrument.LARGE_TELESCOPE, ViewingDetail.STRUCTURE)
            else -> null
        }
        return when {
            magnitude != null -> reach(magnitude + EXTENDED_MARGIN, nelm, closerLook)
            obj.majorArcmin >= NAKED_EYE_DARK_NEBULA_ARCMIN -> needs(Instrument.EYE, closerLook)
            else -> needs(Instrument.LARGE_TELESCOPE, closerLook)
        }
    }

    /**
     * The magnitude a diffuse object looks like to the eye: its integrated magnitude, made fainter
     * the more thinly that light is spread (its surface brightness beyond [SURFACE_BRIGHTNESS_REFERENCE]).
     * Null for an object with no magnitude.
     */
    fun effectiveMagnitude(obj: DeepSkyObject): Double? {
        val vmag = obj.vmag ?: return null
        val surface = DeepSkyAppearance.surfaceBrightness(obj) ?: return null
        return vmag + SURFACE_BRIGHTNESS_PENALTY * max(0.0, surface - SURFACE_BRIGHTNESS_REFERENCE)
    }

    /** The smallest instrument whose reach includes [magnitude]; null if even the large telescope falls short. */
    private fun leastFor(magnitude: Double, nelm: Double): Instrument? =
        Instrument.entries.firstOrNull { magnitude <= nelm + it.gain }

    /** The least instrument that reaches [magnitude], not smaller than [floor]; [Viewing.BeyondReach] if none does. */
    private fun reach(magnitude: Double, nelm: Double, closerLook: CloserLook?, floor: Instrument = Instrument.EYE): Viewing {
        val least = leastFor(magnitude, nelm) ?: return Viewing.BeyondReach
        return needs(maxOf(least, floor), closerLook)
    }

    /** A closer look is only worth mentioning if it needs more than [least]. */
    private fun needs(least: Instrument, closerLook: CloserLook?): Viewing =
        Viewing.Needs(least, closerLook?.takeIf { it.instrument > least })
}
