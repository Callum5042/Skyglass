package com.skyglass.catalog

import com.skyglass.astro.AstroTime
import com.skyglass.astro.BodyPosition
import com.skyglass.astro.SkyBody
import com.skyglass.astro.SolarSystem
import com.skyglass.assertVecEquals
import com.skyglass.epochMillis
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun packsElevenFloatsPerBody_withUnitPositionsAndSunDirections() {
        val data = BodyVertices.build(bodies)
        assertEquals(bodies.size * BodyVertices.FLOATS_PER_BODY, data.size)
        for (i in bodies.indices) {
            assertEquals(1.0, vector(data, i, BodyVertices.POSITION_OFFSET).length(), 1e-6)
            assertEquals(1.0, vector(data, i, BodyVertices.SUN_DIRECTION_OFFSET).length(), 1e-6)
            assertVecEquals(bodies[i].direction, vector(data, i, BodyVertices.POSITION_OFFSET), 1e-6)
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
}
