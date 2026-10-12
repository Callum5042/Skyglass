package com.skyglass.catalog

import com.skyglass.assertVecEquals
import com.skyglass.astro.AstroTime
import com.skyglass.astro.BodyPosition
import com.skyglass.astro.BodySize
import com.skyglass.astro.PlanetMoons
import com.skyglass.astro.SkyBody
import com.skyglass.astro.SolarSystem
import com.skyglass.epochMillis
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class BodyVerticesTest {

    private val bodies = SolarSystem.positions(
        AstroTime.julianDate(epochMillis("2026-10-07T21:00:00Z")), 51.5, -0.1,
    )

    private fun vector(data: FloatArray, index: Int, offset: Int): Vector3 {
        val base = index * BodyVertices.FLOATS_PER_BODY + offset
        return Vector3(data[base].toDouble(), data[base + 1].toDouble(), data[base + 2].toDouble())
    }

    private fun index(body: SkyBody) = bodies.indexOfFirst { it.body == body }

    @Test
    fun packsSixVerticesPerBody_withUnitPositionsAndSunDirections() {
        val data = BodyVertices.build(bodies)
        assertEquals(bodies.size * BodyVertices.VERTICES_PER_BODY * BodyVertices.FLOATS_PER_VERTEX, data.size)
        for (i in bodies.indices) {
            assertEquals(1.0, vector(data, i, BodyVertices.POSITION_OFFSET).length(), 1e-6)
            assertEquals(1.0, vector(data, i, BodyVertices.SUN_DIRECTION_OFFSET).length(), 1e-6)
            assertVecEquals(bodies[i].direction, vector(data, i, BodyVertices.POSITION_OFFSET), 1e-6)
        }
    }

    @Test
    fun eachBodyIsTwoTrianglesCoveringItsSquare_withTheSameValuesInEveryVertex() {
        val data = BodyVertices.build(bodies)
        for (i in bodies.indices) {
            val first = i * BodyVertices.FLOATS_PER_BODY
            val corners = (0 until BodyVertices.VERTICES_PER_BODY).map { v ->
                val base = first + v * BodyVertices.FLOATS_PER_VERTEX
                for (offset in 0 until BodyVertices.FLOATS_PER_VERTEX) {
                    if (offset == BodyVertices.CORNER_OFFSET || offset == BodyVertices.CORNER_OFFSET + 1) continue
                    assertEquals("body $i vertex $v float $offset", data[first + offset], data[base + offset], 0f)
                }
                data[base + BodyVertices.CORNER_OFFSET] to data[base + BodyVertices.CORNER_OFFSET + 1]
            }
            // Two triangles sharing the diagonal from (-1, -1) to (1, 1): together they cover the square.
            assertEquals(setOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f), corners.toSet())
            assertEquals(listOf(-1f to -1f, 1f to 1f), corners.groupingBy { it }.eachCount().filterValues { it == 2 }.keys.sortedBy { it.first })
        }
    }

    @Test
    fun theTrueRadiusIsTheTangentOfTheAngularRadius() {
        val data = BodyVertices.build(bodies)
        for (i in bodies.indices) {
            val expected = tan(Math.toRadians(BodySize.angularRadiusDeg(bodies[i].body, bodies[i].distanceAu)))
            assertEquals(bodies[i].body.name, expected, data[i * BodyVertices.FLOATS_PER_BODY + BodyVertices.TRUE_RADIUS_OFFSET].toDouble(), expected * 1e-6)
        }
    }

    @Test
    fun encodesTheSpriteKind() {
        val data = BodyVertices.build(bodies)
        fun kind(body: SkyBody) = data[index(body) * BodyVertices.FLOATS_PER_BODY + BodyVertices.KIND_OFFSET]
        assertEquals(BodyAppearance.KIND_SUN, kind(SkyBody.SUN), 0f)
        assertEquals(BodyAppearance.KIND_MOON, kind(SkyBody.MOON), 0f)
        for (planet in SkyBody.entries.filter { it.planet != null }) {
            assertEquals(planet.name, BodyAppearance.KIND_PLANET, kind(planet), 0f)
        }
    }

    @Test
    fun sizesAndColoursComeFromBodyAppearance() {
        val data = BodyVertices.build(bodies)
        val venus = bodies.first { it.body == SkyBody.VENUS }
        val base = index(SkyBody.VENUS) * BodyVertices.FLOATS_PER_BODY
        assertEquals(BodyAppearance.spriteSizeDp(venus), data[base + BodyVertices.SIZE_OFFSET], 0f)
        val colour = BodyAppearance.color(SkyBody.VENUS, venus.vmag)
        assertEquals(colour.g, data[base + BodyVertices.COLOR_OFFSET + 1], 0f)
    }

    @Test
    fun emptyList_givesEmptyBuffer() {
        assertEquals(0, BodyVertices.build(emptyList<BodyPosition>()).size)
    }

    @Test
    fun theSunAndMoonAreEnlargedBeyondAnyStar_andPlanetsAreBiggerThanStarsOfTheirBrightness() {
        val sirius = StarAppearance.pointSizeDp(-1.46)
        assertTrue(BodyAppearance.spriteSizeDp(SkyBody.SUN, -26.7) > sirius * 3)
        assertTrue(BodyAppearance.spriteSizeDp(SkyBody.MOON, -12.7) > sirius * 2)
        for (planet in SkyBody.entries.filter { it.planet != null }) {
            val vmag = 1.0
            assertTrue(planet.name, BodyAppearance.spriteSizeDp(planet, vmag) > StarAppearance.pointSizeDp(vmag))
        }
    }

    @Test
    fun faintPlanetsStayVisible_andBrightOnesDoNotExceedFullIntensity() {
        for (planet in SkyBody.entries.filter { it.planet != null }) {
            for (vmag in listOf(-4.5, 0.0, 5.7, 7.8)) {
                val c = BodyAppearance.color(planet, vmag)
                val brightest = maxOf(c.r, c.g, c.b)
                assertTrue("${planet.name} $vmag", brightest in 0.4f..1f && minOf(c.r, c.g, c.b) >= 0f)
            }
        }
    }

    @Test
    fun theDiscIsInsideTheSprite() {
        for (body in SkyBody.entries) {
            val sprite = BodyAppearance.spriteSizeDp(body, 0.0)
            val disc = BodyAppearance.discRadiusDp(body, 0.0)
            assertTrue(body.name, disc > 0f && disc < sprite / 2)
        }
    }

    private val moons = PlanetMoons.positions(AstroTime.julianDate(epochMillis("2026-10-07T21:00:00Z")), bodies)

    @Test
    fun theVisibleMoonsGoJustBeforeTheMoon_withTheirSeparation_andTheHiddenOnesAreLeftOut() {
        val withOneHidden = moons.mapIndexed { i, m -> if (i == 0) m.copy(hidden = true) else m }
        val data = BodyVertices.build(bodies, withOneHidden)
        val visible = withOneHidden.filter { !it.hidden }
        assertEquals((bodies.size + visible.size) * BodyVertices.FLOATS_PER_BODY, data.size)
        fun float(slot: Int, offset: Int) = data[slot * BodyVertices.FLOATS_PER_BODY + offset]
        val moonSlot = bodies.indexOfFirst { it.body == SkyBody.MOON }
        // The planets first, as before, never faded.
        for (slot in 0 until moonSlot) {
            assertEquals(BodyVertices.NO_SEPARATION, float(slot, BodyVertices.SEPARATION_OFFSET), 0f)
            assertEquals(bodies[slot].direction.x, float(slot, BodyVertices.POSITION_OFFSET).toDouble(), 1e-6)
        }
        // Then the moons, then the Moon last of all.
        for ((i, moon) in visible.withIndex()) {
            val slot = moonSlot + i
            assertEquals(BodyAppearance.KIND_SATELLITE, float(slot, BodyVertices.KIND_OFFSET), 0f)
            assertEquals(moon.separationTan, float(slot, BodyVertices.SEPARATION_OFFSET).toDouble(), 1e-9)
            assertEquals(moon.direction.y, float(slot, BodyVertices.POSITION_OFFSET + 1).toDouble(), 1e-6)
        }
        assertEquals(BodyAppearance.KIND_MOON, float(moonSlot + visible.size, BodyVertices.KIND_OFFSET), 0f)
    }
}
