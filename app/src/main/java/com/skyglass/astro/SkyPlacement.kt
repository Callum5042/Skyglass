package com.skyglass.astro

import com.skyglass.math.Vector3

/**
 * Where something is, in both the equatorial frame of date and the observer's horizon frame.
 *
 * @property raDeg right ascension of date (mean equator and equinox), [0, 360)
 * @property decDeg declination of date, -90..90
 * @property altitudeDeg geometric altitude above the horizon (no refraction), negative when below it
 * @property azimuthDeg measured from true north through east, [0, 360)
 */
data class SkyPlacement(val raDeg: Double, val decDeg: Double, val altitudeDeg: Double, val azimuthDeg: Double) {

    companion object {
        /**
         * @param direction unit vector in the equatorial frame of date, as the star and body vertices use
         * @param longitudeDeg positive east
         */
        fun of(direction: Vector3, julianDateUtc: Double, latitudeDeg: Double, longitudeDeg: Double): SkyPlacement {
            val equatorial = Coordinates.unitToRaDec(direction)
            val lstRad = Math.toRadians(AstroTime.localSiderealTimeDeg(julianDateUtc, longitudeDeg))
            val enu = Coordinates.equatorialToEnuMatrix(lstRad, Math.toRadians(latitudeDeg)) * direction
            val horizontal = Coordinates.enuToHorizontal(enu)
            return SkyPlacement(
                raDeg = Math.toDegrees(equatorial.raRad),
                decDeg = Math.toDegrees(equatorial.decRad),
                altitudeDeg = Math.toDegrees(horizontal.altRad),
                azimuthDeg = Math.toDegrees(horizontal.azRad),
            )
        }
    }
}

/** The eight compass points, for saying roughly which way an azimuth is. */
enum class CompassPoint {
    N, NE, E, SE, S, SW, W, NW;

    /** The azimuth of the point itself, from true north through east: N 0°, NE 45°, E 90° and so on. */
    val azimuthDeg: Double get() = ordinal * 45.0

    /** N, E, S and W, as opposed to the four in between. */
    val isCardinal: Boolean get() = ordinal % 2 == 0

    companion object {
        /** The point whose 45° sector contains [azimuthDeg] (measured from north through east). */
        fun of(azimuthDeg: Double): CompassPoint =
            entries[(Math.round(AstroTime.normalizeDegrees(azimuthDeg) / 45.0).toInt()) % entries.size]
    }
}

/** Splits an angle into the sign and whole units, minutes and seconds the almanacs print, with carries done. */
data class Sexagesimal(val negative: Boolean, val whole: Int, val minutes: Int, val seconds: Int) {

    companion object {
        /**
         * Rounds to the nearest whole second, so 59.6 s becomes the next minute rather than "60".
         * Used for right ascension (pass hours) and declination (pass degrees).
         */
        fun of(value: Double): Sexagesimal {
            val totalSeconds = Math.round(Math.abs(value) * 3600.0)
            return Sexagesimal(
                negative = value < 0 && totalSeconds > 0,
                whole = (totalSeconds / 3600).toInt(),
                minutes = (totalSeconds / 60 % 60).toInt(),
                seconds = (totalSeconds % 60).toInt(),
            )
        }

        /** As [of] for hours of right ascension, wrapping 24h to 0h. */
        fun ofHours(hours: Double): Sexagesimal = of(hours).let { if (it.whole >= 24) it.copy(whole = 0) else it }
    }
}
