package com.skyglass.catalog

import com.skyglass.astro.Equatorial

/** What a deep-sky object is, as far as the app draws, names and describes it differently. */
enum class DeepSkyType(val code: String) {
    GALAXY("GAL"),
    GLOBULAR_CLUSTER("GC"),
    OPEN_CLUSTER("OC"),

    /** An HII region: emission nebulae, and nebulae with a cluster in them (the Lagoon, the Eagle). */
    EMISSION_NEBULA("EN"),
    REFLECTION_NEBULA("RN"),
    PLANETARY_NEBULA("PN"),
    SUPERNOVA_REMNANT("SNR"),
    DARK_NEBULA("DN"),

    /** A bright patch of the Milky Way itself, such as M24. */
    STAR_CLOUD("SC"),

    /** A chance pattern of stars that is not a cluster (M73, the Coathanger). */
    ASTERISM("AST"),
    DOUBLE_STAR("DBL");

    companion object {
        fun fromCode(code: String): DeepSkyType =
            requireNotNull(entries.firstOrNull { it.code == code }) { "unknown deep-sky type '$code'" }
    }
}

/**
 * A deep-sky object from the bundled `deep_sky.txt`.
 *
 * @property designations catalogue designations ("M31", "NGC 224", "C 14"); the first is the one shown
 * @property name common name ("Andromeda Galaxy"), if it has one
 * @property aliases other names people know it by, for search ("Praesepe")
 * @property j2000 mean position, equinox and epoch J2000 (the objects are too far away for proper motion to matter)
 * @property vmag integrated visual magnitude, null for a dark nebula
 * @property majorArcmin long axis of the apparent size, arcminutes
 * @property minorArcmin short axis, arcminutes
 * @property positionAngleDeg angle of the long axis from north through east; null when unknown or when the
 *   object is nearly round, in which case it is drawn round
 * @property constellation full IAU name
 */
data class DeepSkyObject(
    val designations: List<String>,
    val name: String?,
    val aliases: List<String>,
    val type: DeepSkyType,
    val j2000: Equatorial,
    val vmag: Double?,
    val majorArcmin: Double,
    val minorArcmin: Double,
    val positionAngleDeg: Double?,
    val constellation: String,
) {
    /** The designation shown first: "M31", else "NGC 869". */
    val id: String get() = designations.first()

    /** What the object is called on screen: its common name, else its designation. */
    val title: String get() = name ?: id

    /** The Messier number, if it has one. */
    val messierNumber: Int? get() = designations.firstNotNullOfOrNull { MESSIER.matchEntire(it)?.groupValues?.get(1)?.toInt() }

    /**
     * The short axis the object is drawn with: the real one if the long axis's direction is known,
     * else the long axis, since an ellipse with a guessed direction would be wrong more often than a circle.
     */
    val drawnMinorArcmin: Double get() = if (positionAngleDeg != null) minorArcmin else majorArcmin

    private companion object {
        val MESSIER = Regex("M(\\d+)")
    }
}

/**
 * Parser for `deep_sky.txt`: one object per line,
 * `ids|Name|Aliases|Type|RA|Dec|Vmag|Major'|Minor'|PA|Constellation`, with `#` comment lines (the
 * file header describes each column). RA is `hh:mm:ss.s`, Dec `+dd:mm:ss`.
 */
object DeepSkyParser {

    fun parse(lines: Sequence<String>): List<DeepSkyObject> =
        lines.map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map(::parseLine)
            .toList()

    private fun parseLine(line: String): DeepSkyObject {
        val f = line.split('|')
        require(f.size == FIELD_COUNT) { "expected $FIELD_COUNT fields but got ${f.size}: $line" }
        val major = requireNotNull(f[7].trim().toDoubleOrNull()) { "bad size in: $line" }
        val designations = splitList(f[0])
        require(designations.isNotEmpty()) { "no designation in: $line" }
        return DeepSkyObject(
            designations = designations,
            name = f[1].trim().ifEmpty { null },
            aliases = splitList(f[2]),
            type = DeepSkyType.fromCode(f[3].trim()),
            j2000 = Equatorial(Math.toRadians(parseSexagesimal(f[4], line) * 15.0), Math.toRadians(parseSexagesimal(f[5], line))),
            vmag = f[6].trim().ifEmpty { null }?.let { requireNotNull(it.toDoubleOrNull()) { "bad magnitude in: $line" } },
            majorArcmin = major,
            minorArcmin = f[8].trim().ifEmpty { null }?.let { requireNotNull(it.toDoubleOrNull()) { "bad size in: $line" } } ?: major,
            positionAngleDeg = f[9].trim().ifEmpty { null }?.let { requireNotNull(it.toDoubleOrNull()) { "bad angle in: $line" } },
            constellation = f[10].trim().also { require(it.isNotEmpty()) { "no constellation in: $line" } },
        )
    }

    private fun splitList(field: String): List<String> = field.split(';').map(String::trim).filter { it.isNotEmpty() }

    /** "05:34:31.9" → 5.5755 (hours for RA, degrees for Dec); a leading sign applies to the whole value. */
    fun parseSexagesimal(text: String, context: String = text): Double {
        val trimmed = text.trim()
        val negative = trimmed.startsWith("-")
        val parts = trimmed.trimStart('+', '-').split(':')
        require(parts.size == 3) { "bad angle '$text' in: $context" }
        val values = parts.map { requireNotNull(it.toDoubleOrNull()) { "bad angle '$text' in: $context" } }
        val magnitude = values[0] + values[1] / 60.0 + values[2] / 3600.0
        return if (negative) -magnitude else magnitude
    }

    private const val FIELD_COUNT = 11
}

/** An angular size split into a value and the unit it reads best in, so the UI can format it from string resources. */
data class DeepSkyMeasure(val value: Double, val unit: Unit) {
    enum class Unit { DEGREES, ARCMINUTES, ARCSECONDS }

    companion object {
        /** Degrees from one degree up, arcminutes from one arcminute, arcseconds below that. */
        fun of(arcmin: Double): DeepSkyMeasure = when {
            arcmin >= 60.0 -> DeepSkyMeasure(arcmin / 60.0, Unit.DEGREES)
            arcmin >= 1.0 -> DeepSkyMeasure(arcmin, Unit.ARCMINUTES)
            else -> DeepSkyMeasure(arcmin * 60.0, Unit.ARCSECONDS)
        }
    }
}

/** The apparent size of [DeepSkyObject] for display: its long axis, and its short axis if that is noticeably shorter. */
data class DeepSkySize(val major: DeepSkyMeasure, val minor: DeepSkyMeasure?) {
    companion object {
        fun of(obj: DeepSkyObject): DeepSkySize = DeepSkySize(
            DeepSkyMeasure.of(obj.majorArcmin),
            if (obj.minorArcmin < obj.majorArcmin * 0.95) DeepSkyMeasure.of(obj.minorArcmin) else null,
        )
    }
}
