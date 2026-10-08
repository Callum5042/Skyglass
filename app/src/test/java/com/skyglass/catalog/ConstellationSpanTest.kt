package com.skyglass.catalog

import com.skyglass.astro.AstroTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstellationSpanTest {

    private val data = BundledSkyData.load()
    private val sky = ConstellationGeometry.build(data.constellations, data.nakedEye, AstroTime.JD_J2000)

    @Test
    fun spansAreContiguousAndCoverEverySegmentExactlyOnce() {
        assertEquals(data.constellations.size, sky.spans.size)
        var expectedStart = 0
        for ((figure, span) in data.constellations.zip(sky.spans)) {
            assertEquals(figure.name, span.name)
            assertEquals(figure.abbreviation, span.abbreviation)
            assertEquals("${figure.name} starts where the previous figure ended", expectedStart, span.firstVertex)
            assertEquals("${figure.name} vertex count", 2 * figure.polylines.sumOf { it.size - 1 }, span.vertexCount)
            assertEquals("whole segments only", 0, span.vertexCount % 2)
            expectedStart += span.vertexCount
        }
        assertEquals(sky.lineVertexCount, expectedStart)
    }

    @Test
    fun rangesOfAConstellationAreItsFiguresVertexRanges() {
        val orion = sky.spans.single { it.abbreviation == "Ori" }
        assertArrayEquals(intArrayOf(orion.firstVertex, orion.vertexCount), sky.rangesOf("Ori"))
    }

    @Test
    fun serpensHasTwoRanges() {
        val ser = sky.spans.filter { it.abbreviation == "Ser" }
        assertEquals(2, ser.size)
        assertArrayEquals(
            intArrayOf(ser[0].firstVertex, ser[0].vertexCount, ser[1].firstVertex, ser[1].vertexCount),
            sky.rangesOf("Ser"),
        )
    }

    @Test
    fun anUnknownOrEmptyConstellationHasNoRanges() {
        assertEquals(0, sky.rangesOf("Zzz").size)
        val empty = ConstellationSky(FloatArray(0), emptyList(), listOf(ConstellationSpan("Tst", "Test", 0, 0)))
        assertEquals(0, empty.rangesOf("Tst").size)
    }

    @Test
    fun labelsCarryTheirConstellationsAbbreviation() {
        assertEquals(data.constellations.map { it.abbreviation }, sky.labels.map { it.abbreviation })
        assertTrue(sky.labels.none { it.abbreviation.isEmpty() })
    }
}
