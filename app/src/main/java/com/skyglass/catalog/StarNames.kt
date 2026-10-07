package com.skyglass.catalog

/** One entry of `star_names.txt`; [designation] is the catalogue designation it was generated from. */
data class ProperName(val hr: Int, val name: String, val designation: String)

/** Parser for `star_names.txt`: `HR|Name|Designation`, with `#` comment lines. */
object ProperNameParser {

    fun parse(lines: Sequence<String>): List<ProperName> =
        lines.map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split('|')
                require(parts.size == 3) { "expected HR|Name|Designation but got: $line" }
                ProperName(
                    hr = requireNotNull(parts[0].trim().toIntOrNull()) { "bad HR number in: $line" },
                    name = parts[1].trim(),
                    designation = parts[2].trim(),
                )
            }
            .toList()
}

/**
 * What the tap overlay says about a star.
 *
 * @property title proper name if known, else the Bayer/Flamsteed designation, else "HR n"
 * @property designation Bayer/Flamsteed designation with constellation, when it differs from [title]
 */
data class StarDescription(
    val title: String,
    val designation: String?,
    val hr: Int,
    val vmag: Double,
)

/** Builds display names for stars from the proper-name table and constellation genitives. */
class StarNamer(
    private val properNames: Map<Int, String>,
    private val genitives: Map<String, String>,
) {

    constructor(properNames: List<ProperName>, constellations: List<Constellation>) : this(
        properNames.associate { it.hr to it.name },
        // A constellation may have several figures (Serpens); they share a genitive.
        constellations.associate { it.abbreviation to it.genitive },
    )

    fun describe(star: Star): StarDescription {
        val designation = designation(star)
        val title = properNames[star.hr] ?: designation ?: "HR ${star.hr}"
        return StarDescription(
            title = title,
            designation = designation.takeIf { it != title },
            hr = star.hr,
            vmag = star.vmag,
        )
    }

    /** "α Canis Majoris" or "61 Cygni"; Bayer wins over Flamsteed. Null without a constellation. */
    fun designation(star: Star): String? {
        val constellationAbbr = star.constellation ?: return null
        val constellation = genitives[constellationAbbr] ?: constellationAbbr
        val bayer = star.bayer
        val flamsteed = star.flamsteed
        val prefix = when {
            bayer != null -> bayerSymbol(bayer) + star.bayerIndex?.let(::superscript).orEmpty()
            flamsteed != null -> flamsteed.toString()
            else -> return null
        }
        return "$prefix $constellation"
    }

    companion object {
        private val GREEK = mapOf(
            "Alp" to "α", "Bet" to "β", "Gam" to "γ", "Del" to "δ", "Eps" to "ε", "Zet" to "ζ",
            "Eta" to "η", "The" to "θ", "Iot" to "ι", "Kap" to "κ", "Lam" to "λ", "Mu" to "μ",
            "Nu" to "ν", "Xi" to "ξ", "Omi" to "ο", "Pi" to "π", "Rho" to "ρ", "Sig" to "σ",
            "Tau" to "τ", "Ups" to "υ", "Phi" to "φ", "Chi" to "χ", "Psi" to "ψ", "Ome" to "ω",
        )

        private const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

        fun bayerSymbol(abbreviation: String): String = GREEK[abbreviation] ?: abbreviation

        private fun superscript(digit: Char): String =
            if (digit in '0'..'9') SUPERSCRIPT_DIGITS[digit - '0'].toString() else digit.toString()
    }
}
