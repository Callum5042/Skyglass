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
 * @property constellation English/Latin name of the constellation the star lies in ("Canis Major"), when
 *   known: from its catalogue designation, else from the constellation figure it is part of
 * @property spectralType the catalogue's MK type ("A1Vm")
 * @property colorName how the star looks, from its spectral class or else its B−V
 * @property bv B−V colour index
 * @property distance from the catalogue parallax, null when it is absent or too small to mean anything
 */
data class StarDescription(
    val title: String,
    val designation: String?,
    val hr: Int,
    val vmag: Double,
    val constellation: String? = null,
    val spectralType: String? = null,
    val colorName: StarColorName? = null,
    val bv: Double? = null,
    val distance: StarDistance? = null,
)

/** Builds display names for stars from the proper-name table and constellation genitives. */
class StarNamer(
    private val properNames: Map<Int, String>,
    private val genitives: Map<String, String>,
    private val constellationNames: Map<String, String> = emptyMap(),
    private val figureConstellations: Map<Int, String> = emptyMap(),
) {

    constructor(properNames: List<ProperName>, constellations: List<Constellation>) : this(
        properNames.associate { it.hr to it.name },
        // A constellation may have several figures (Serpens); they share a genitive.
        constellations.associate { it.abbreviation to it.genitive },
        namesByAbbreviation(constellations),
        abbreviationsByHr(constellations),
    )

    fun describe(star: Star): StarDescription {
        val designation = designation(star)
        val title = properNames[star.hr] ?: designation ?: "HR ${star.hr}"
        return StarDescription(
            title = title,
            designation = designation.takeIf { it != title },
            hr = star.hr,
            vmag = star.vmag,
            constellation = constellation(star),
            spectralType = star.spectralType,
            colorName = SpectralType.colorName(star.spectralType, star.bv),
            bv = star.bv,
            distance = StarDistance.fromParallax(star.parallaxArcsec, star.parallaxDynamical),
        )
    }

    /**
     * The constellation's name. The catalogue gives the abbreviation only for stars with a Bayer or
     * Flamsteed designation; for the rest it is the one whose stick figure the star belongs to, which
     * covers the brighter ones (there are no boundary tables, so a faint star in no figure has none).
     */
    fun constellation(star: Star): String? {
        val abbreviation = star.constellation ?: figureConstellations[star.hr] ?: return null
        return constellationNames[abbreviation] ?: abbreviation
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

        /** Serpens has two figures with their own names; the constellation is the part they share, word by word. */
        private fun namesByAbbreviation(constellations: List<Constellation>): Map<String, String> =
            constellations.groupBy { it.abbreviation }.mapValues { (_, figures) ->
                figures.map { it.name.split(" ") }
                    .reduce { a, b -> a.zip(b).takeWhile { (x, y) -> x == y }.map { it.first } }
                    .joinToString(" ")
            }

        /** HR number to constellation abbreviation, leaving out stars that two constellations' figures share. */
        private fun abbreviationsByHr(constellations: List<Constellation>): Map<Int, String> =
            constellations
                .flatMap { figure -> figure.starHrs.map { hr -> hr to figure.abbreviation } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, abbreviations) -> abbreviations.distinct() }
                .filterValues { it.size == 1 }
                .mapValues { it.value.single() }

        private const val SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

        fun bayerSymbol(abbreviation: String): String = GREEK[abbreviation] ?: abbreviation

        private fun superscript(digit: Char): String =
            if (digit in '0'..'9') SUPERSCRIPT_DIGITS[digit - '0'].toString() else digit.toString()
    }
}
