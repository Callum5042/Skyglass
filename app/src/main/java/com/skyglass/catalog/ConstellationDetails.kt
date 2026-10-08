package com.skyglass.catalog

import com.skyglass.math.Vector3

/** One of a constellation's brightest stars, named as the sky names it ("Rigel", "α Pegasi"). */
data class ConstellationStar(val hr: Int, val title: String, val vmag: Double)

/** A star of the stick figure, where it is in the sky and how bright, so the overlay can ring it. */
data class FigureStar(val direction: Vector3, val vmag: Double)

/**
 * Everything the constellation card and the sky overlay need to know about one constellation.
 *
 * @property name the constellation's name ("Orion", "Serpens" for both its figures)
 * @property genitive Latin genitive, as in star designations ("Orionis")
 * @property lore the words about it; null only if the lore file lacks the constellation
 * @property figureNames the labels of its figures ("Serpens Caput", "Serpens Cauda"), as drawn in the sky
 * @property direction unit vector to the middle of the figure (the mean of its stars), equatorial of date
 * @property brightStars the brightest few stars of the figure that belong to it, brightest first
 * @property figureStars every star of the stick figure
 * @property chart the picture of the figure; null only if none of its stars is in the catalogue
 */
class ConstellationDescription(
    val abbreviation: String,
    val name: String,
    val genitive: String,
    val lore: ConstellationLore?,
    val figureNames: List<String>,
    val direction: Vector3,
    val brightStars: List<ConstellationStar>,
    val figureStars: List<FigureStar>,
    val chart: ConstellationChart?,
)

object ConstellationDescriber {

    const val BRIGHT_STAR_COUNT = 3

    /**
     * Describes the constellation [abbreviation] from the figures in [constellations], the catalogue
     * [stars] and their [positions] (same order; equatorial of date), the [lore] by abbreviation and the
     * [namer] that titles the stars. Null if the constellation has no figure star in the catalogue.
     *
     * A figure may borrow a star from a neighbour (Alpheratz, alpha Andromedae, is a corner of the Great
     * Square of Pegasus); such a star is drawn but not counted among the constellation's brightest.
     */
    fun describe(
        abbreviation: String,
        constellations: List<Constellation>,
        stars: List<Star>,
        positions: List<Vector3>,
        lore: Map<String, ConstellationLore>,
        namer: StarNamer,
    ): ConstellationDescription? {
        val figures = constellations.filter { it.abbreviation == abbreviation }
        if (figures.isEmpty()) return null
        val hrs = figures.flatMap { it.starHrs }.toSet()
        val indices = stars.indices.filter { stars[it].hr in hrs }
        if (indices.isEmpty()) return null

        val sum = indices.fold(Vector3(0.0, 0.0, 0.0)) { acc, i -> acc + positions[i] }
        val direction = if (sum.length() < 1e-6) positions[indices.first()] else sum.normalized()
        val bright = indices
            .filter { stars[it].constellation.let { c -> c == null || c == abbreviation } }
            .sortedBy { stars[it].vmag }
            .take(BRIGHT_STAR_COUNT)
            .map { ConstellationStar(stars[it].hr, namer.describe(stars[it]).title, stars[it].vmag) }
        return ConstellationDescription(
            abbreviation = abbreviation,
            name = ConstellationNames.common(figures),
            genitive = figures.first().genitive,
            lore = lore[abbreviation],
            figureNames = figures.map { it.name },
            direction = direction,
            brightStars = bright,
            figureStars = indices.map { FigureStar(positions[it], stars[it].vmag) },
            chart = ConstellationCharts.build(figures, stars, positions),
        )
    }
}
