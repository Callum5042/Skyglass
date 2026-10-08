package com.skyglass.ui

import androidx.annotation.StringRes
import com.skyglass.R
import com.skyglass.astro.CompassPoint
import com.skyglass.astro.MoonPhase
import com.skyglass.astro.SkyBody
import com.skyglass.catalog.DeepSkyType
import com.skyglass.catalog.SearchKind
import com.skyglass.catalog.StarColorName

/** Display-name resources for the pure-Kotlin astro types, so the UI text lives in `strings.xml`. */
@StringRes
fun SkyBody.nameRes(): Int = when (this) {
    SkyBody.SUN -> R.string.body_sun
    SkyBody.MOON -> R.string.body_moon
    SkyBody.MERCURY -> R.string.body_mercury
    SkyBody.VENUS -> R.string.body_venus
    SkyBody.MARS -> R.string.body_mars
    SkyBody.JUPITER -> R.string.body_jupiter
    SkyBody.SATURN -> R.string.body_saturn
    SkyBody.URANUS -> R.string.body_uranus
    SkyBody.NEPTUNE -> R.string.body_neptune
}

@StringRes
fun MoonPhase.nameRes(): Int = when (this) {
    MoonPhase.NEW -> R.string.moon_phase_new
    MoonPhase.WAXING_CRESCENT -> R.string.moon_phase_waxing_crescent
    MoonPhase.FIRST_QUARTER -> R.string.moon_phase_first_quarter
    MoonPhase.WAXING_GIBBOUS -> R.string.moon_phase_waxing_gibbous
    MoonPhase.FULL -> R.string.moon_phase_full
    MoonPhase.WANING_GIBBOUS -> R.string.moon_phase_waning_gibbous
    MoonPhase.LAST_QUARTER -> R.string.moon_phase_last_quarter
    MoonPhase.WANING_CRESCENT -> R.string.moon_phase_waning_crescent
}

@StringRes
fun CompassPoint.nameRes(): Int = when (this) {
    CompassPoint.N -> R.string.compass_n
    CompassPoint.NE -> R.string.compass_ne
    CompassPoint.E -> R.string.compass_e
    CompassPoint.SE -> R.string.compass_se
    CompassPoint.S -> R.string.compass_s
    CompassPoint.SW -> R.string.compass_sw
    CompassPoint.W -> R.string.compass_w
    CompassPoint.NW -> R.string.compass_nw
}

@StringRes
fun StarColorName.nameRes(): Int = when (this) {
    StarColorName.BLUE -> R.string.star_color_blue
    StarColorName.BLUE_WHITE -> R.string.star_color_blue_white
    StarColorName.WHITE -> R.string.star_color_white
    StarColorName.YELLOW_WHITE -> R.string.star_color_yellow_white
    StarColorName.YELLOW -> R.string.star_color_yellow
    StarColorName.ORANGE -> R.string.star_color_orange
    StarColorName.RED -> R.string.star_color_red
}

@StringRes
fun SearchKind.labelRes(): Int = when (this) {
    SearchKind.BODY -> R.string.search_kind_body
    SearchKind.CONSTELLATION -> R.string.search_kind_constellation
    SearchKind.DEEP_SKY -> R.string.search_kind_deep_sky
    SearchKind.STAR -> R.string.search_kind_star
}

@StringRes
fun DeepSkyType.nameRes(): Int = when (this) {
    DeepSkyType.GALAXY -> R.string.dso_type_galaxy
    DeepSkyType.GLOBULAR_CLUSTER -> R.string.dso_type_globular_cluster
    DeepSkyType.OPEN_CLUSTER -> R.string.dso_type_open_cluster
    DeepSkyType.EMISSION_NEBULA -> R.string.dso_type_emission_nebula
    DeepSkyType.REFLECTION_NEBULA -> R.string.dso_type_reflection_nebula
    DeepSkyType.PLANETARY_NEBULA -> R.string.dso_type_planetary_nebula
    DeepSkyType.SUPERNOVA_REMNANT -> R.string.dso_type_supernova_remnant
    DeepSkyType.DARK_NEBULA -> R.string.dso_type_dark_nebula
    DeepSkyType.STAR_CLOUD -> R.string.dso_type_star_cloud
    DeepSkyType.ASTERISM -> R.string.dso_type_asterism
    DeepSkyType.DOUBLE_STAR -> R.string.dso_type_double_star
}
