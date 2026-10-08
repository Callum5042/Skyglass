package com.skyglass.catalog

import com.skyglass.astro.BodyPosition
import com.skyglass.astro.SkyBody
import com.skyglass.math.Vector3
import java.text.Normalizer

/** What a search result stands for; enough to find it in the sky again. */
sealed interface SearchTarget {
    /** A catalogue star, by Yale BSC number. */
    data class StarTarget(val hr: Int) : SearchTarget

    data class BodyTarget(val body: SkyBody) : SearchTarget

    /** A constellation figure, by the name of its label (Serpens has two figures, with distinct names). */
    data class ConstellationTarget(val name: String) : SearchTarget

    /** A deep-sky object, by its first designation ([DeepSkyObject.id]: "M31", "NGC 869"). */
    data class DeepSkyTarget(val id: String) : SearchTarget
}

/**
 * Ranks bodies before constellations before stars before deep-sky objects when matches are otherwise
 * equal. Deep-sky objects come last because many are named after a star ("Alpha Persei Cluster",
 * "Omicron Velorum Cluster"), and typing the start of the star's designation must find the star first.
 */
enum class SearchKind { BODY, CONSTELLATION, STAR, DEEP_SKY }

/**
 * One line in the search results.
 *
 * @property detail a star's designation ("α Canis Majoris") or else its constellation; a deep-sky
 *   object's other designations ("NGC 224"); null otherwise
 * @property vmag a star's visual magnitude, for ranking and for noting that the faintest-stars setting hides it
 *   (deep-sky objects are not affected by that setting, so they have none)
 * @property rankMagnitude the magnitude that breaks ties between equally good matches, brighter first: a star's
 *   [vmag], or a deep-sky object's integrated magnitude
 */
data class SearchResult(
    val target: SearchTarget,
    val kind: SearchKind,
    val title: String,
    val detail: String? = null,
    val vmag: Double? = null,
    val rankMagnitude: Double? = vmag,
)

/**
 * The thing the user is being guided to. A star's or a constellation's place in the sky is fixed
 * (in the equatorial frame of date, like the vertex buffers), so [fixedDirection] holds it; a
 * body moves, so its direction is looked up in the current positions.
 */
data class NavigationTarget(val result: SearchResult, val fixedDirection: Vector3? = null) {

    /** Unit vector toward the target, equatorial of date; null for a body that has no position yet. */
    fun directionIn(bodies: List<BodyPosition>): Vector3? {
        val target = result.target
        return if (target is SearchTarget.BodyTarget) bodies.firstOrNull { it.body == target.body }?.direction else fixedDirection
    }
}

/**
 * Search over the stars, constellations, deep-sky objects and the Sun, Moon and planets by name, as
 * the app shows them: a star by its traditional name ("Sirius"), designation ("α Canis Majoris", also
 * typed "alpha canis majoris", "alpha cma" or "alpha CMa"; "61 Cygni") or HR number; a constellation by
 * its name; a body by its name; a deep-sky object by a catalogue designation ("M31", "m 31",
 * "Messier 31", "NGC 224", "C 14"), its common name ("Andromeda Galaxy") or an alias ("Praesepe").
 *
 * Matching works on [normalize]d text (lower case, no accents, Greek letters spelled out, only
 * letters, digits and single spaces). A result's rank is how closely its best name matches (whole
 * name, start of the name, start of a word, every word of the query starting a word, anywhere),
 * then bodies before constellations before stars, then brighter stars first.
 */
class SkySearchIndex private constructor(private val entries: List<Entry>) {

    private class Entry(val result: SearchResult, val keys: List<String>, val featured: Boolean)

    private class Match(val entry: Entry, val tier: Int)

    val size: Int get() = entries.size

    /**
     * Up to [limit] results, best first. An empty (or all-punctuation) [query] gives the suggestions:
     * the Sun, Moon and planets, then the brightest stars that have a traditional name.
     */
    fun search(query: String, limit: Int = MAX_RESULTS): List<SearchResult> {
        val normalized = normalize(query)
        if (normalized.isEmpty()) {
            return entries.filter { it.featured }.map { Match(it, 0) }.sortedWith(RANKING).take(limit).map { it.entry.result }
        }
        val tokens = normalized.split(' ')
        val matches = ArrayList<Match>()
        for (entry in entries) {
            var best = NO_MATCH
            for (key in entry.keys) {
                val tier = tierOf(key, normalized, tokens)
                if (tier < best) best = tier
            }
            if (best != NO_MATCH) matches += Match(entry, best)
        }
        return matches.sortedWith(RANKING).take(limit).map { it.entry.result }
    }

    companion object {
        const val MAX_RESULTS = 20

        private const val EXACT = 0
        private const val PREFIX = 1
        private const val WORD_START = 2
        private const val ALL_WORDS_START = 3
        private const val SUBSTRING = 4
        private const val NO_MATCH = Int.MAX_VALUE

        /** Matching inside a word ("ius" in "sirius") is only useful, and not noise, with a few letters. */
        private const val MIN_SUBSTRING_LENGTH = 3

        private val RANKING = compareBy<Match>({ it.tier }, { it.entry.result.kind.ordinal }, { it.entry.result.rankMagnitude ?: Double.MAX_VALUE }, { it.entry.result.title })

        private fun tierOf(key: String, query: String, tokens: List<String>): Int = when {
            key == query -> EXACT
            key.startsWith(query) -> PREFIX
            key.contains(" $query") -> WORD_START
            tokens.size > 1 && key.split(' ').let { words -> tokens.all { token -> words.any { it.startsWith(token) } } } -> ALL_WORDS_START
            query.length >= MIN_SUBSTRING_LENGTH && key.contains(query) -> SUBSTRING
            else -> NO_MATCH
        }

        /**
         * @param stars the catalogue stars the sky shows
         * @param constellationNames the names of the constellation figures that have a label
         * @param bodyNames the user-visible name of each body (localised by the caller)
         * @param deepSky the deep-sky objects the sky shows
         */
        fun build(
            stars: List<Star>,
            namer: StarNamer,
            constellationNames: List<String>,
            bodyNames: Map<SkyBody, String>,
            deepSky: List<DeepSkyObject> = emptyList(),
        ): SkySearchIndex {
            val entries = ArrayList<Entry>(stars.size + constellationNames.size + bodyNames.size + deepSky.size)
            for ((body, name) in bodyNames) {
                entries += Entry(SearchResult(SearchTarget.BodyTarget(body), SearchKind.BODY, name), listOf(normalize(name)), featured = true)
            }
            for (name in constellationNames) {
                entries += Entry(
                    SearchResult(SearchTarget.ConstellationTarget(name), SearchKind.CONSTELLATION, name),
                    listOf(normalize(name)),
                    featured = false,
                )
            }
            for (obj in deepSky) {
                val others = if (obj.name != null) obj.designations else obj.designations.drop(1)
                val result = SearchResult(
                    target = SearchTarget.DeepSkyTarget(obj.id),
                    kind = SearchKind.DEEP_SKY,
                    title = obj.title,
                    detail = others.joinToString(" · ").ifEmpty { null },
                    rankMagnitude = obj.vmag,
                )
                entries += Entry(result, deepSkyKeys(obj).filter { it.isNotEmpty() }, featured = false)
            }
            for (star in stars) {
                val description = namer.describe(star)
                val result = SearchResult(
                    target = SearchTarget.StarTarget(star.hr),
                    kind = SearchKind.STAR,
                    title = description.title,
                    detail = description.designation ?: description.constellation,
                    vmag = star.vmag,
                )
                entries += Entry(result, starKeys(star, namer, description).filter { it.isNotEmpty() }, featured = namer.properName(star) != null)
            }
            return SkySearchIndex(entries)
        }

        /**
         * Every way of writing the star's name that someone might type: title, designation (with and
         * without the Bayer superscript, so "alpha centauri" finds α¹ and α²), the catalogue's own
         * abbreviation ("alpha cma", "61 cyg") and the HR number.
         */
        private fun starKeys(star: Star, namer: StarNamer, description: StarDescription): Set<String> {
            val keys = LinkedHashSet<String>()
            keys += normalize(description.title)
            namer.designation(star)?.let { designation ->
                val normalized = normalize(designation)
                keys += normalized
                if (star.bayerIndex != null) keys += normalized.replaceFirst(BAYER_INDEX, "$1")
            }
            val abbreviation = star.constellation?.let(::normalize)
            if (abbreviation != null) {
                star.bayer?.let { bayer ->
                    val letter = normalize(StarNamer.bayerSymbol(bayer))
                    keys += "$letter $abbreviation"
                    star.bayerIndex?.let { keys += "$letter$it $abbreviation" }
                }
                star.flamsteed?.let { keys += "$it $abbreviation" }
            }
            keys += "hr ${star.hr}"
            return keys
        }

        /**
         * Every way of writing a deep-sky object's name that someone might type: its common name and
         * aliases, and each designation as catalogued ("ngc 224"), run together ("ngc224") and with the
         * catalogue's full name ("messier 31", "caldwell 14", "melotte 25", "collinder 399").
         */
        private fun deepSkyKeys(obj: DeepSkyObject): Set<String> {
            val keys = LinkedHashSet<String>()
            obj.name?.let { keys += normalize(it) }
            obj.aliases.forEach { keys += normalize(it) }
            for (designation in obj.designations) {
                val key = normalize(designation)
                keys += key
                val match = DESIGNATION.matchEntire(key) ?: continue
                val (prefix, number) = match.destructured
                keys += "$prefix $number"
                keys += "$prefix$number"
                CATALOGUE_NAMES[prefix]?.let { full ->
                    keys += "$full $number"
                    keys += "$full$number"
                }
            }
            return keys
        }

        /** "ngc 224", "m31", "c 14": letters, an optional space, digits. */
        private val DESIGNATION = Regex("^([a-z]+) ?(\\d+)$")

        private val CATALOGUE_NAMES = mapOf("m" to "messier", "c" to "caldwell", "mel" to "melotte", "cr" to "collinder")

        /** "alpha1 centauri": the digit that directly follows a Bayer letter's name. */
        private val BAYER_INDEX = Regex("^(\\p{L}+)\\d(?= )")

        private val GREEK_NAMES = mapOf(
            'α' to "alpha", 'β' to "beta", 'γ' to "gamma", 'δ' to "delta", 'ε' to "epsilon", 'ζ' to "zeta",
            'η' to "eta", 'θ' to "theta", 'ι' to "iota", 'κ' to "kappa", 'λ' to "lambda", 'μ' to "mu",
            'ν' to "nu", 'ξ' to "xi", 'ο' to "omicron", 'π' to "pi", 'ρ' to "rho", 'σ' to "sigma",
            'ς' to "sigma", 'τ' to "tau", 'υ' to "upsilon", 'φ' to "phi", 'χ' to "chi", 'ψ' to "psi",
            'ω' to "omega",
        )

        /**
         * Lower case, accents and combining marks dropped, superscript digits as plain digits, Greek
         * letters spelled out ("α¹" is "alpha1"), every other run of characters that are not letters or
         * digits a single space, no leading or trailing space.
         */
        fun normalize(text: String): String {
            val out = StringBuilder(text.length + 8)
            var pendingSpace = false
            for (raw in Normalizer.normalize(text, Normalizer.Form.NFKD)) {
                val ch = raw.lowercaseChar()
                if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
                val greek = GREEK_NAMES[ch]
                if (greek != null || ch.isLetterOrDigit()) {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    if (greek != null) out.append(greek) else out.append(ch)
                } else if (out.isNotEmpty()) {
                    pendingSpace = true
                }
            }
            return out.toString()
        }
    }
}
