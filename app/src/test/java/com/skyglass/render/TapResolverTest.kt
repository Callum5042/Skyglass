package com.skyglass.render

import org.junit.Assert.assertEquals
import org.junit.Test

class TapResolverTest {

    private val orionName = ConstellationHit("Ori", "Orion", 3.0)
    private val orionLine = ConstellationHit("Ori", "Orion", 10.0)

    private fun resolve(
        bodies: List<BodyHit> = emptyList(),
        stars: List<StarHit> = emptyList(),
        deepSky: List<DeepSkyHit> = emptyList(),
        label: ConstellationHit? = null,
        line: ConstellationHit? = null,
    ) = TapResolver.resolve(bodies, stars, deepSky, label, line)

    private fun select(pick: TapPick) = TapOutcome.Select(pick)

    @Test
    fun emptySkyIsNothing() {
        assertEquals(TapOutcome.Nothing, resolve())
    }

    @Test
    fun aNameBeatsAStarThatIsOnlyWithinAFingertip() {
        // The bug this fixes: a faint star within 24 dp of nearly every tap made names untappable.
        val nearbyStar = StarHit(7, 40.0, onTarget = false)
        assertEquals(select(TapPick.Constellation("Ori", "Orion")), resolve(stars = listOf(nearbyStar), label = orionName))
        assertEquals(
            "and a body or deep-sky object that is near but not under the finger",
            select(TapPick.Constellation("Ori", "Orion")),
            resolve(bodies = listOf(BodyHit(0, 50.0)), deepSky = listOf(DeepSkyHit(0, 30.0)), label = orionName),
        )
    }

    @Test
    fun aNameWithAStarRightUnderTheFingerAsksWhich() {
        val starOnTarget = StarHit(7, 4.0, onTarget = true)
        assertEquals(
            TapOutcome.Choose(listOf(TapPick.Star(7), TapPick.Constellation("Ori", "Orion"))),
            resolve(stars = listOf(starOnTarget), label = orionName),
        )
    }

    @Test
    fun aStarAloneIsSelectedAsBefore() {
        assertEquals(select(TapPick.Star(7)), resolve(stars = listOf(StarHit(7, 4.0, onTarget = true))))
        assertEquals("within a fingertip still counts when nothing else is there", select(TapPick.Star(7)), resolve(stars = listOf(StarHit(7, 40.0))))
    }

    @Test
    fun aStarInsideACluster_asksWhich() {
        val choices = resolve(stars = listOf(StarHit(3, 2.0, onTarget = true)), deepSky = listOf(DeepSkyHit(5, 30.0, onTarget = true)))
        assertEquals(TapOutcome.Choose(listOf(TapPick.Star(3), TapPick.DeepSky(5))), choices)
    }

    @Test
    fun withOnlyOneThingUnderTheFinger_theFingertipRulesDecide() {
        // Inside a big object, near (not on) a star: SkyHitTest.choose's reckoning, unchanged.
        val star = StarHit(3, 20.0, onTarget = false)
        assertEquals(select(TapPick.Star(3)), resolve(stars = listOf(star), deepSky = listOf(DeepSkyHit(5, 40.0, onTarget = true))))
        assertEquals(select(TapPick.DeepSky(5)), resolve(stars = listOf(star), deepSky = listOf(DeepSkyHit(5, 10.0, onTarget = true))))
        assertEquals(select(TapPick.Body(1)), resolve(bodies = listOf(BodyHit(1, 20.0)), stars = listOf(StarHit(3, 20.0))))
    }

    @Test
    fun linesAreTheLastResort() {
        assertEquals(select(TapPick.Constellation("Ori", "Orion")), resolve(line = orionLine))
        assertEquals("a star near a line wins", select(TapPick.Star(3)), resolve(stars = listOf(StarHit(3, 40.0)), line = orionLine))
        assertEquals(
            "a line never makes a menu",
            select(TapPick.Star(3)),
            resolve(stars = listOf(StarHit(3, 2.0, onTarget = true)), line = orionLine),
        )
    }

    @Test
    fun theMenuListsBodiesStarsDeepSkyThenTheConstellation_andIsCapped() {
        val stars = (0 until 5).map { StarHit(it, it.toDouble(), onTarget = true) }
        val outcome = resolve(
            bodies = listOf(BodyHit(2, 6.0, onTarget = true)),
            stars = stars + StarHit(9, 30.0, onTarget = false),
            deepSky = listOf(DeepSkyHit(4, 5.0, onTarget = true)),
            label = orionName,
        )
        val expected = listOf(
            TapPick.Body(2),
            TapPick.Star(0), TapPick.Star(1), TapPick.Star(2), // at most MAX_STARS, nearest first
            TapPick.DeepSky(4),
            TapPick.Constellation("Ori", "Orion"),
        )
        assertEquals(TapResolver.MAX_STARS, 3)
        assertEquals(TapOutcome.Choose(expected.take(TapResolver.MAX_CHOICES)), outcome)
    }

    @Test
    fun aMoonNearerTheFingerThanItsPlanet_isTheMoon() {
        // Zoomed in, a tap on Io is also within a fingertip of Jupiter's glow; the nearer one wins.
        val jupiter = BodyHit(4, 30.0, onTarget = false)
        val io = MoonHit(0, 2.0, onTarget = true)
        assertEquals(select(TapPick.Moon(0)), TapResolver.resolve(listOf(jupiter), emptyList(), emptyList(), null, null, listOf(io)))
        // And the planet when the finger is nearer the planet.
        val farIo = MoonHit(0, 40.0, onTarget = false)
        assertEquals(select(TapPick.Body(4)), TapResolver.resolve(listOf(jupiter), emptyList(), emptyList(), null, null, listOf(farIo)))
    }

    @Test
    fun aMoonOnTargetWithAStar_asksWhich_bodiesAndMoonsFirst() {
        val io = MoonHit(0, 1.0, onTarget = true)
        val star = StarHit(7, 2.0, onTarget = true)
        assertEquals(
            TapOutcome.Choose(listOf(TapPick.Moon(0), TapPick.Star(7))),
            TapResolver.resolve(emptyList(), listOf(star), emptyList(), null, null, listOf(io)),
        )
    }
}
