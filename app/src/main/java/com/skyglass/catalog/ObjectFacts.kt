package com.skyglass.catalog

import com.skyglass.astro.SkyBody

/**
 * A few words about something on a details card, from `object_facts.txt`: [about] says what it is,
 * [fact] is a "did you know?". English-only asset text, like the star names and the constellation lore.
 */
data class ObjectFact(val about: String, val fact: String)

/** The facts by what they describe; anything without one is simply missing. */
class ObjectFactIndex(
    private val deepSky: Map<String, ObjectFact>,
    private val bodies: Map<SkyBody, ObjectFact>,
    private val stars: Map<Int, ObjectFact>,
) {
    /** By the deep-sky object's [DeepSkyObject.id]. */
    fun forDeepSky(id: String): ObjectFact? = deepSky[id]

    fun forBody(body: SkyBody): ObjectFact? = bodies[body]

    /** By the star's HR number. */
    fun forStar(hr: Int): ObjectFact? = stars[hr]

    companion object {
        val EMPTY = ObjectFactIndex(emptyMap(), emptyMap(), emptyMap())
    }
}

/**
 * Reads `object_facts.txt`: `Key|Name|About|Fact`, where key is `@BODY` for the Sun, Moon or a planet
 * (a [SkyBody] name), `HR n` for a star, or else a deep-sky object's id (`M31`, `NGC 253`). The name is
 * there for the people editing the file and for the tests, which check it against the catalogues.
 */
object ObjectFactParser {

    private const val BODY_PREFIX = "@"
    private const val STAR_PREFIX = "HR "

    fun parse(lines: Sequence<String>): ObjectFactIndex {
        val deepSky = LinkedHashMap<String, ObjectFact>()
        val bodies = LinkedHashMap<SkyBody, ObjectFact>()
        val stars = LinkedHashMap<Int, ObjectFact>()
        for (line in lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val fields = line.split("|").map { it.trim() }
            require(fields.size == 4) { "expected Key|Name|About|Fact: $line" }
            require(fields.all { it.isNotEmpty() }) { "empty field in: $line" }
            val key = fields[0]
            val fact = ObjectFact(about = fields[2], fact = fields[3])
            val duplicate = when {
                key.startsWith(BODY_PREFIX) -> {
                    val name = key.removePrefix(BODY_PREFIX)
                    val body = SkyBody.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown body: $key")
                    bodies.put(body, fact)
                }
                key.startsWith(STAR_PREFIX) -> {
                    val hr = key.removePrefix(STAR_PREFIX).toIntOrNull() ?: throw IllegalArgumentException("bad HR number: $key")
                    stars.put(hr, fact)
                }
                else -> deepSky.put(key, fact)
            }
            require(duplicate == null) { "duplicate key: $key" }
        }
        return ObjectFactIndex(deepSky, bodies, stars)
    }
}
