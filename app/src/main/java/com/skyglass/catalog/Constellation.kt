package com.skyglass.catalog

/**
 * A constellation stick figure: one or more polylines through catalogue stars.
 *
 * @property abbreviation IAU three-letter abbreviation, as in the Bright Star Catalogue
 * @property genitive Latin genitive used in star designations ("Canis Majoris")
 * @property polylines Yale BSC HR numbers; consecutive entries are joined by a line
 */
data class Constellation(
    val abbreviation: String,
    val name: String,
    val genitive: String,
    val polylines: List<List<Int>>,
) {
    /** Distinct stars used by the figure. */
    val starHrs: Set<Int> = polylines.flatten().toSet()
}

/**
 * Parser for `constellations.txt`: one figure per line, `Abbr|Name|Genitive|HR-HR-HR/HR-HR`,
 * with `#` comment lines. A constellation may appear more than once (Serpens has two figures).
 */
object ConstellationParser {

    fun parse(lines: Sequence<String>): List<Constellation> =
        lines.map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::parseLine)
            .toList()

    private fun parseLine(line: String): Constellation {
        val parts = line.split('|')
        require(parts.size == 4) { "expected Abbr|Name|Genitive|figure but got: $line" }
        val polylines = parts[3].split('/').map { polyline ->
            polyline.split('-').map { token ->
                requireNotNull(token.trim().toIntOrNull()) { "bad HR number '$token' in: $line" }
            }
        }
        return Constellation(parts[0].trim(), parts[1].trim(), parts[2].trim(), polylines)
    }
}

/** Names of constellations that have more than one figure. */
object ConstellationNames {

    /**
     * The name of the constellation that [figures] (those sharing one abbreviation) make up. Serpens has
     * two figures with their own names ("Serpens Caput", "Serpens Cauda"); the constellation's name is
     * the part they share, word by word ("Serpens"). With one figure it is that figure's name.
     */
    fun common(figures: List<Constellation>): String =
        figures.map { it.name.split(" ") }
            .reduce { a, b -> a.zip(b).takeWhile { (x, y) -> x == y }.map { it.first } }
            .joinToString(" ")
}
