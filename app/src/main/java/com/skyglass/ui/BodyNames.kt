package com.skyglass.ui

import androidx.annotation.StringRes
import com.skyglass.R
import com.skyglass.astro.MoonPhase
import com.skyglass.astro.SkyBody

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
