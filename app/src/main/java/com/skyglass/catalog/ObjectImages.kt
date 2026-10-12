package com.skyglass.catalog

import com.skyglass.astro.PlanetMoon
import com.skyglass.astro.SkyBody

/**
 * A bundled photograph of a deep-sky object, the Sun, the Moon, a planet or a planet's moon, shown
 * on its details card. Stars have none: no photo shows a star as more than a point.
 *
 * @param fileName file in `object_images/` (WebP), [width] by [height] pixels
 * @param credit who to credit, as the source gives it
 * @param licence "Public domain" or "CC BY 4.0"
 */
data class ObjectImage(
    val fileName: String,
    val width: Int,
    val height: Int,
    val credit: String,
    val licence: String,
)

/** A moon in a planet's gallery; [name] is English-only asset text, like the star names. */
data class MoonImage(val name: String, val image: ObjectImage)

/** The photos by what they show; anything without one is simply missing. */
class ObjectImageIndex(
    private val deepSky: Map<String, ObjectImage>,
    private val bodies: Map<SkyBody, ObjectImage>,
    private val moons: Map<SkyBody, List<MoonImage>>,
) {
    /** The photo of a deep-sky object, by its [DeepSkyObject.id]. */
    fun forDeepSky(id: String): ObjectImage? = deepSky[id]

    fun forBody(body: SkyBody): ObjectImage? = bodies[body]

    /** A planet's moons, in the order of the file (innermost first); empty for one without. */
    fun moonsOf(body: SkyBody): List<MoonImage> = moons[body].orEmpty()

    /** The photo of one of the moons drawn in the sky, from its planet's gallery (matched by its English name). */
    fun forMoon(moon: PlanetMoon): ObjectImage? =
        moonsOf(moon.planet).firstOrNull { it.name.equals(moon.name, ignoreCase = true) }?.image

    val all: List<ObjectImage> get() = deepSky.values + bodies.values + moons.values.flatten().map { it.image }

    companion object {
        val EMPTY = ObjectImageIndex(emptyMap(), emptyMap(), emptyMap())
    }
}

/**
 * Reads `object_images.txt`: `key|file|width|height|credit|licence`, where key is a deep-sky
 * object's id (`M31`, `NGC 253`), `@BODY` for the Sun, Moon or a planet (a [SkyBody] name) or
 * `@BODY/Name` for one of a planet's moons.
 */
object ObjectImageParser {

    private const val BODY_PREFIX = "@"

    fun parse(lines: Sequence<String>): ObjectImageIndex {
        val deepSky = LinkedHashMap<String, ObjectImage>()
        val bodies = LinkedHashMap<SkyBody, ObjectImage>()
        val moons = LinkedHashMap<SkyBody, MutableList<MoonImage>>()
        for (line in lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val fields = line.split("|").map { it.trim() }
            require(fields.size == 6) { "expected 6 fields: $line" }
            val image = ObjectImage(
                fileName = fields[1],
                width = fields[2].toInt(),
                height = fields[3].toInt(),
                credit = fields[4],
                licence = fields[5],
            )
            val key = fields[0]
            if (!key.startsWith(BODY_PREFIX)) {
                require(deepSky.put(key, image) == null) { "duplicate key: $key" }
                continue
            }
            val bodyName = key.removePrefix(BODY_PREFIX).substringBefore("/")
            val body = SkyBody.entries.firstOrNull { it.name == bodyName } ?: throw IllegalArgumentException("unknown body: $key")
            if ("/" in key) {
                require(body.planet != null) { "only a planet has moons: $key" }
                val name = key.substringAfter("/")
                require(name.isNotEmpty()) { "moon without a name: $key" }
                moons.getOrPut(body) { mutableListOf() }.add(MoonImage(name, image))
            } else {
                require(bodies.put(body, image) == null) { "duplicate key: $key" }
            }
        }
        return ObjectImageIndex(deepSky, bodies, moons)
    }
}
