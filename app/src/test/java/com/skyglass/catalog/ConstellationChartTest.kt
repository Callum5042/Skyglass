package com.skyglass.catalog

import com.skyglass.astro.AstroTime
import com.skyglass.astro.Equatorial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstellationChartTest {

    private val jd = AstroTime.JD_J2000

    private fun star(hr: Int, raDeg: Double, decDeg: Double, vmag: Double) =
        Star(hr, Equatorial(Math.toRadians(raDeg), Math.toRadians(decDeg)), vmag, null, 0.0, 0.0)

    private fun chartOf(figure: Constellation, stars: List<Star>) =
        ConstellationCharts.build(listOf(figure), stars, StarPositions.ofDate(stars, jd))

    private fun ConstellationChart.figureStar(vmag: Double) = stars.single { it.inFigure && it.vmag == vmag }

    @Test
    fun eastIsOnTheLeftAndNorthAtTheTop() {
        // Vmags identify the stars: 1 is at the middle, 2 east of it, 3 west, 4 north, 5 south.
        val stars = listOf(
            star(1, 100.0, 20.0, 1.0),
            star(2, 106.0, 20.0, 2.0),
            star(3, 94.0, 20.0, 3.0),
            star(4, 100.0, 26.0, 4.0),
            star(5, 100.0, 14.0, 5.0),
        )
        val chart = checkNotNull(chartOf(Constellation("Tst", "Test", "Testi", listOf(listOf(1, 2, 3), listOf(1, 4, 5))), stars))
        val middle = chart.figureStar(1.0)
        assertTrue("east is to the left", chart.figureStar(2.0).x < middle.x - 0.05)
        assertTrue("west is to the right", chart.figureStar(3.0).x > middle.x + 0.05)
        assertTrue("north is up (smaller y)", chart.figureStar(4.0).y < middle.y - 0.05)
        assertTrue("south is down", chart.figureStar(5.0).y > middle.y + 0.05)
        assertEquals("a symmetric figure is symmetric", middle.x - chart.figureStar(2.0).x, chart.figureStar(3.0).x - middle.x, 0.02)
    }

    @Test
    fun theLongerSideIsOneAndEverythingFitsInTheBox() {
        val stars = listOf(star(1, 0.0, 0.0, 2.0), star(2, 30.0, 4.0, 3.0), star(3, 12.0, -3.0, 4.0), star(4, 15.0, 1.0, 5.0))
        val chart = checkNotNull(chartOf(Constellation("Tst", "Test", "Testi", listOf(listOf(1, 3, 2))), stars))
        assertEquals(1.0, maxOf(chart.width, chart.height), 1e-12)
        assertTrue("a wide figure is wider than tall", chart.width > chart.height)
        assertEquals(chart.width / chart.height, chart.aspect, 1e-12)
        for (s in chart.stars) {
            assertTrue("x ${s.x}", s.x in -1e-9..chart.width + 1e-9)
            assertTrue("y ${s.y}", s.y in -1e-9..chart.height + 1e-9)
        }
        // The figure leaves a margin on every side.
        val figure = chart.stars.filter { it.inFigure }
        assertTrue(figure.minOf { it.x } > 0.02 && figure.maxOf { it.x } < chart.width - 0.02)
        assertTrue(figure.minOf { it.y } > 0.02 && figure.maxOf { it.y } < chart.height - 0.02)
    }

    @Test
    fun segmentsJoinTheFigureStarsAndNeighboursAreDimBackground() {
        // The box round the figure is only about a degree tall: star 3 is in it, 4 is in it but too faint, 5 is far outside.
        val stars = listOf(
            star(1, 10.0, 10.0, 2.0), star(2, 14.0, 10.0, 2.5), star(3, 12.0, 10.3, 4.0),
            star(4, 12.0, 10.2, 6.0), star(5, 12.0, 14.0, 3.0),
        )
        val chart = checkNotNull(chartOf(Constellation("Tst", "Test", "Testi", listOf(listOf(1, 2), listOf(2, 9))), stars))
        assertEquals("the segment to a missing star is dropped", 1, chart.segments.size)
        val a = chart.figureStar(2.0)
        val b = chart.figureStar(2.5)
        val segment = chart.segments.single()
        val ends = setOf(segment.x1 to segment.y1, segment.x2 to segment.y2)
        assertEquals(setOf(a.x to a.y, b.x to b.y), ends)
        // Star 3 is in the box and bright enough: background. Star 4 (magnitude 6) is too faint to draw.
        assertEquals(listOf(4.0), chart.stars.filter { !it.inFigure }.map { it.vmag })
        assertEquals(2, chart.stars.count { it.inFigure })
    }

    @Test
    fun returnsNullWithoutAnyFigureStar() {
        val stars = listOf(star(1, 0.0, 0.0, 3.0))
        assertNull(chartOf(Constellation("Tst", "Test", "Testi", listOf(listOf(8, 9))), stars))
    }

    @Test
    fun aFigureAtTheCelestialPoleStillGetsAChart() {
        val stars = listOf(star(1, 0.0, -80.0, 4.0), star(2, 120.0, -80.0, 4.5), star(3, 240.0, -80.0, 5.0))
        val chart = checkNotNull(chartOf(Constellation("Tst", "Test", "Testi", listOf(listOf(1, 2, 3, 1))), stars))
        assertEquals(3, chart.segments.size)
        assertTrue(chart.stars.all { it.x.isFinite() && it.y.isFinite() })
    }

    // ---- the real, bundled data ----

    private val data = BundledSkyData.load()
    private val positions = StarPositions.ofDate(data.nakedEye, jd)

    private fun realChart(abbreviation: String): ConstellationChart =
        checkNotNull(ConstellationCharts.build(data.constellations.filter { it.abbreviation == abbreviation }, data.nakedEye, positions))

    @Test
    fun orionBetelgeuseIsUpperLeftOfRigel() {
        val chart = realChart("Ori")
        fun starOf(hr: Int): ChartStar {
            val vmag = data.nakedEye.single { it.hr == hr }.vmag
            return chart.stars.single { it.inFigure && it.vmag == vmag }
        }
        val betelgeuse = starOf(2061)
        val rigel = starOf(1713)
        val bellatrix = starOf(1790)
        assertTrue("Betelgeuse (RA 5h55) is east of Rigel (5h14): to the left", betelgeuse.x < rigel.x)
        assertTrue("Betelgeuse (+7 deg) is north of Rigel (-8 deg): higher", betelgeuse.y < rigel.y)
        assertTrue("Betelgeuse is east of Bellatrix (5h25)", betelgeuse.x < bellatrix.x)
        assertTrue("Orion is taller than wide", chart.height > chart.width)
    }

    @Test
    fun everyBundledConstellationMakesAUsableChart() {
        for (abbreviation in data.constellations.map { it.abbreviation }.toSet()) {
            val figures = data.constellations.filter { it.abbreviation == abbreviation }
            val chart = realChart(abbreviation)
            assertEquals("$abbreviation: longer side", 1.0, maxOf(chart.width, chart.height), 1e-9)
            assertTrue("$abbreviation: not degenerate", minOf(chart.width, chart.height) > 0.02)
            assertEquals(
                "$abbreviation: figure stars",
                figures.flatMap { it.starHrs }.toSet().size,
                chart.stars.count { it.inFigure },
            )
            assertEquals(
                "$abbreviation: segments",
                figures.sumOf { f -> f.polylines.sumOf { it.size - 1 } },
                chart.segments.size,
            )
            for (s in chart.stars) {
                assertTrue("$abbreviation: ${s.x}, ${s.y}", s.x in -1e-9..chart.width + 1e-9 && s.y in -1e-9..chart.height + 1e-9)
            }
        }
    }

    @Test
    fun serpensShowsBothHalvesInOneChart() {
        val chart = realChart("Ser")
        val serpens = data.constellations.filter { it.abbreviation == "Ser" }
        assertEquals(2, serpens.size)
        assertEquals(serpens.sumOf { f -> f.polylines.sumOf { it.size - 1 } }, chart.segments.size)
        assertTrue("both halves are in the box", chart.stars.count { it.inFigure } == serpens.flatMap { it.starHrs }.toSet().size)
    }

    @Test
    fun theLongestFigureIsNotStretched() {
        // Hydra is about 100 degrees long and bent; in a stereographic chart it must still come out clearly wider than tall.
        val chart = realChart("Hya")
        assertTrue("Hydra's aspect is ${chart.aspect}", chart.aspect > 1.3)
    }
}
