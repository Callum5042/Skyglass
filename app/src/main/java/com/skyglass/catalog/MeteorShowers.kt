package com.skyglass.catalog

import com.skyglass.astro.AstroTime
import com.skyglass.astro.Coordinates
import com.skyglass.astro.Equatorial
import com.skyglass.astro.Precession
import com.skyglass.math.Matrix3
import com.skyglass.math.Vector3
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * An annual meteor shower from the bundled `meteor_showers.txt`.
 *
 * @property code the IMO code ("PER")
 * @property name English name ("Perseids"); an English-only asset, like the star names
 * @property start first day of activity (UT); may fall in the year before [peak], as the Quadrantids' does
 * @property zhr zenithal hourly rate at the peak: what one observer would count in an hour under a dark
 *   sky with the radiant overhead, so an upper limit on what anyone sees
 * @property radiantJ2000 the radiant at the peak, J2000
 */
data class MeteorShower(
    val code: String,
    val name: String,
    val start: MonthDay,
    val peak: MonthDay,
    val end: MonthDay,
    val zhr: Int,
    val radiantJ2000: Equatorial,
) {
    private val daysBefore = wrappedDays(start, peak)
    private val daysAfter = wrappedDays(peak, end)

    /** Whole days from the nearest peak to [date]: negative before it, so −2 for the Quadrantids on 1 January. */
    fun daysFromPeak(date: LocalDate): Int =
        (date.year - 1..date.year + 1)
            .map { ChronoUnit.DAYS.between(peak.atYear(it), date).toInt() }
            .minBy { abs(it) }

    fun isActive(date: LocalDate): Boolean = daysFromPeak(date) in -daysBefore..daysAfter

    /**
     * Unit vector toward the radiant in the equatorial frame of date, [daysFromPeak] days (fractions
     * allowed) from the peak. A radiant drifts east by about a degree a day because the Earth meets the
     * stream from a direction that keeps roughly its place relative to the Sun; so the radiant's
     * ecliptic longitude is moved with the Sun's mean motion and its ecliptic latitude kept.
     */
    fun radiantDirection(daysFromPeak: Double, julianDate: Double): Vector3 {
        val obliquity = Coordinates.meanObliquityRad(0.0)
        val ecliptic = Matrix3.rotationX(-obliquity) * Coordinates.raDecToUnit(radiantJ2000)
        val moved = Matrix3.rotationZ(Math.toRadians(daysFromPeak * SUN_MEAN_MOTION_DEG_PER_DAY)) * ecliptic
        val precession = Precession.matrixFromJ2000(AstroTime.julianCenturiesSinceJ2000(julianDate))
        return precession * (Matrix3.rotationX(obliquity) * moved)
    }

    companion object {
        const val SUN_MEAN_MOTION_DEG_PER_DAY = 360.0 / 365.2422

        /** Days from [from] to [to] going forward, across the new year if need be (in a common year). */
        private fun wrappedDays(from: MonthDay, to: MonthDay): Int {
            val days = ChronoUnit.DAYS.between(from.atYear(COMMON_YEAR), to.atYear(COMMON_YEAR)).toInt()
            return if (days < 0) days + 365 else days
        }

        private const val COMMON_YEAR = 2001

        /**
         * The calendar date at the observer's longitude (local mean time) at [julianDate], which is the
         * date a shower's evening is reckoned by wherever the observer is; [longitudeDeg] positive east.
         */
        fun localDate(julianDate: Double, longitudeDeg: Double): LocalDate =
            Instant.ofEpochMilli(AstroTime.epochMillis(julianDate + longitudeDeg / 360.0)).atZone(ZoneOffset.UTC).toLocalDate()
    }
}

/**
 * Parser for `meteor_showers.txt`: one shower per line, `Code|Name|Start|Peak|End|ZHR|RA|Dec`, with
 * `#` comment lines (the file header describes each column). Dates are `MM-DD`, the radiant J2000 degrees.
 */
object MeteorShowerParser {

    fun parse(lines: Sequence<String>): List<MeteorShower> =
        lines.map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::parseLine)
            .toList()

    private fun parseLine(line: String): MeteorShower {
        val f = line.split('|').map(String::trim)
        require(f.size == FIELD_COUNT) { "expected $FIELD_COUNT fields but got ${f.size}: $line" }
        fun date(text: String) = requireNotNull(runCatching { MonthDay.parse("--$text") }.getOrNull()) { "bad date '$text' in: $line" }
        fun number(text: String) = requireNotNull(text.toDoubleOrNull()) { "bad number '$text' in: $line" }
        return MeteorShower(
            code = f[0].also { require(it.isNotEmpty()) { "no code in: $line" } },
            name = f[1].also { require(it.isNotEmpty()) { "no name in: $line" } },
            start = date(f[2]),
            peak = date(f[3]),
            end = date(f[4]),
            zhr = requireNotNull(f[5].toIntOrNull()) { "bad ZHR in: $line" },
            radiantJ2000 = Equatorial(Math.toRadians(number(f[6])), Math.toRadians(number(f[7]))),
        )
    }

    private const val FIELD_COUNT = 8
}
