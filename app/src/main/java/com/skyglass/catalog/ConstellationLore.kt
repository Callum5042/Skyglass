package com.skyglass.catalog

/**
 * Where a constellation comes from. [ANCIENT] ones are in Ptolemy's list of 48 (the three parts of
 * the ship Argo Navis count, since Ptolemy listed it whole) and carry a myth; [MODERN] ones were
 * devised between the 16th and 18th centuries and have a history of the name instead.
 */
enum class ConstellationOrigin { ANCIENT, MODERN }

/**
 * The words about one constellation, from `constellation_lore.txt`.
 *
 * @property abbreviation IAU three-letter abbreviation, as in [Constellation.abbreviation]
 * @property figure what the constellation depicts ("the Hunter")
 * @property tradition whose story it is, or who devised the figure and about when ("Greek", "Lacaille, 1750s")
 * @property story a few sentences: the myth for an [ConstellationOrigin.ANCIENT] constellation, the history of
 *   the name for a [ConstellationOrigin.MODERN] one (which says plainly that there is no myth)
 */
data class ConstellationLore(
    val abbreviation: String,
    val figure: String,
    val origin: ConstellationOrigin,
    val tradition: String,
    val story: String,
)

/**
 * Parser for `constellation_lore.txt`: one constellation per line,
 * `Abbr|Figure|ancient or modern|Tradition|Story`, with `#` comment lines. One entry per abbreviation
 * (Serpens' two figures share one).
 */
object ConstellationLoreParser {

    fun parse(lines: Sequence<String>): List<ConstellationLore> {
        val entries = lines.map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::parseLine)
            .toList()
        val duplicate = entries.groupBy { it.abbreviation }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "lore for ${duplicate?.key} appears more than once" }
        return entries
    }

    private fun parseLine(line: String): ConstellationLore {
        val parts = line.split('|').map(String::trim)
        require(parts.size == 5) { "expected Abbr|Figure|Origin|Tradition|Story but got: $line" }
        require(parts.all { it.isNotEmpty() }) { "empty field in: $line" }
        val origin = when (parts[2].lowercase()) {
            "ancient" -> ConstellationOrigin.ANCIENT
            "modern" -> ConstellationOrigin.MODERN
            else -> throw IllegalArgumentException("origin must be ancient or modern, not '${parts[2]}' in: $line")
        }
        return ConstellationLore(parts[0], parts[1], origin, parts[3], parts[4])
    }
}
