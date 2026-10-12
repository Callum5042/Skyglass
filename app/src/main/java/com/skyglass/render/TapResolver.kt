package com.skyglass.render

/** One thing a tap on the sky may mean, by its index in the list the hit test searched. */
sealed interface TapPick {
    data class Body(val index: Int) : TapPick
    data class Moon(val index: Int) : TapPick
    data class Star(val index: Int) : TapPick
    data class DeepSky(val index: Int) : TapPick

    /** [name] is the figure's (its label's) name, [abbreviation] the constellation's. */
    data class Constellation(val abbreviation: String, val name: String) : TapPick
}

/** What a tap on the sky does. */
sealed interface TapOutcome {
    /** Empty sky: clear the selection. */
    data object Nothing : TapOutcome

    data class Select(val pick: TapPick) : TapOutcome

    /** Several things are under the finger; ask which one, in this order. */
    data class Choose(val picks: List<TapPick>) : TapOutcome
}

/**
 * Decides what one tap means, from everything the hit tests found near the finger.
 *
 * 1. Everything the tap is *on* (a star's sprite or a body's disc plus `SkyHitTest.ON_TARGET_SLACK_DP`,
 *    inside a deep-sky object's ellipse, inside a constellation's name) is collected. If that is two
 *    things or more, the user is asked which ([TapOutcome.Choose]): bodies, then planets' moons, then
 *    stars (at most [MAX_STARS], nearest first), then deep-sky objects, then the constellation, at most
 *    [MAX_CHOICES].
 * 2. A tap on a constellation's name, with nothing else under the finger, selects the constellation,
 *    even if a star is within a fingertip: the user tapped the words, which are drawn over the stars.
 * 3. Otherwise the fingertip rules of [SkyHitTest.choose] pick a body, star or deep-sky object, unless a
 *    planet's moon is strictly nearer the finger than that pick (a moon sits a few dp from its planet,
 *    whose glow is within a fingertip of a tap on the moon).
 * 4. Failing all of those, the constellation whose lines the tap is beside.
 */
object TapResolver {

    const val MAX_STARS = 3
    const val MAX_CHOICES = 6

    /**
     * @param bodies, stars, deepSky the hits within a fingertip, nearest first (`SkyHitTest.bodiesAt` and its siblings)
     * @param label the constellation whose name is under the finger ([ConstellationHitTest.labelAt])
     * @param line the constellation whose lines are beside the finger ([ConstellationHitTest.nearestLine])
     * @param moons the planets' moons within a fingertip, nearest first ([SkyHitTest.moonsAt])
     */
    fun resolve(
        bodies: List<BodyHit>,
        stars: List<StarHit>,
        deepSky: List<DeepSkyHit>,
        label: ConstellationHit?,
        line: ConstellationHit?,
        moons: List<MoonHit> = emptyList(),
    ): TapOutcome {
        val onTarget = buildList {
            bodies.filter { it.onTarget }.forEach { add(TapPick.Body(it.index)) }
            moons.filter { it.onTarget }.forEach { add(TapPick.Moon(it.index)) }
            stars.filter { it.onTarget }.take(MAX_STARS).forEach { add(TapPick.Star(it.index)) }
            deepSky.filter { it.onTarget }.forEach { add(TapPick.DeepSky(it.index)) }
            label?.let { add(TapPick.Constellation(it.abbreviation, it.name)) }
        }
        if (onTarget.size >= 2) return TapOutcome.Choose(onTarget.take(MAX_CHOICES))
        if (label != null) return TapOutcome.Select(TapPick.Constellation(label.abbreviation, label.name))

        val body = bodies.firstOrNull()
        val star = stars.firstOrNull()
        val sky = deepSky.firstOrNull()
        val (pick, distancePx) = when (SkyHitTest.choose(body, star, sky)) {
            SkyHitTest.Target.BODY -> TapPick.Body(checkNotNull(body).index) to body.distancePx
            SkyHitTest.Target.STAR -> TapPick.Star(checkNotNull(star).index) to star.distancePx
            SkyHitTest.Target.DEEP_SKY -> TapPick.DeepSky(checkNotNull(sky).index) to sky.distancePx
            SkyHitTest.Target.NONE -> null to Double.MAX_VALUE
        }
        val moon = moons.firstOrNull()
        if (moon != null && moon.distancePx < distancePx) return TapOutcome.Select(TapPick.Moon(moon.index))
        if (pick != null) return TapOutcome.Select(pick)
        return line?.let { TapOutcome.Select(TapPick.Constellation(it.abbreviation, it.name)) } ?: TapOutcome.Nothing
    }
}
