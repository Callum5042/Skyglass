package com.skyglass.catalog

import com.skyglass.astro.Equatorial

/**
 * Parser for the fixed-width `catalog` file of the Yale Bright Star Catalogue, 5th revised
 * edition (CDS/VizieR V/50). Column positions below are the 1-based byte ranges from the
 * catalogue's ReadMe.
 */
object BscParser {

    private const val RECORD_LENGTH = 197

    fun parse(lines: Sequence<String>): List<Star> = lines.mapNotNull(::parseLine).toList()

    /** Returns null for entries without a J2000 position or magnitude (novae, clusters, galaxies). */
    fun parseLine(line: String): Star? {
        // Trailing blanks are trimmed in the distributed file, so pad back to full width.
        val record = line.padEnd(RECORD_LENGTH)

        val raHours = record.int(76, 77) ?: return null
        val raMinutes = record.int(78, 79) ?: return null
        val raSeconds = record.double(80, 83) ?: return null
        val decDegrees = record.int(85, 86) ?: return null
        val decMinutes = record.int(87, 88) ?: return null
        val decSeconds = record.int(89, 90) ?: return null
        val vmag = record.double(103, 107) ?: return null

        // The sign has its own column; reading it from the degrees field would lose "−00".
        val decSign = if (record[84 - 1] == '-') -1.0 else 1.0
        val raDeg = 15.0 * (raHours + raMinutes / 60.0 + raSeconds / 3600.0)
        val decDeg = decSign * (decDegrees + decMinutes / 60.0 + decSeconds / 3600.0)

        return Star(
            hr = record.int(1, 4) ?: return null,
            j2000 = Equatorial(Math.toRadians(raDeg), Math.toRadians(decDeg)),
            vmag = vmag,
            bv = record.double(110, 114),
            pmRaArcsecPerYear = record.double(149, 154) ?: 0.0,
            pmDecArcsecPerYear = record.double(155, 160) ?: 0.0,
        )
    }

    private fun String.field(start: Int, end: Int): String = substring(start - 1, end).trim()

    private fun String.int(start: Int, end: Int): Int? = field(start, end).toIntOrNull()

    private fun String.double(start: Int, end: Int): Double? = field(start, end).toDoubleOrNull()
}
