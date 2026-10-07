package com.skyglass.astro

/**
 * Time scales for positional astronomy. UTC is used throughout in place of UT1/TT; the
 * difference (< 1.5 minutes) moves stars by well under a degree's worth of precession.
 */
object AstroTime {

    const val JD_J2000 = 2451545.0
    private const val JD_UNIX_EPOCH = 2440587.5
    private const val MILLIS_PER_DAY = 86_400_000.0
    private const val DAYS_PER_CENTURY = 36525.0

    fun julianDate(epochMillis: Long): Double = epochMillis / MILLIS_PER_DAY + JD_UNIX_EPOCH

    fun julianCenturiesSinceJ2000(julianDate: Double): Double = (julianDate - JD_J2000) / DAYS_PER_CENTURY

    /** Greenwich mean sidereal time in degrees, [0, 360). Meeus, *Astronomical Algorithms*, eq. 12.4. */
    fun greenwichMeanSiderealTimeDeg(julianDate: Double): Double {
        val d = julianDate - JD_J2000
        val t = d / DAYS_PER_CENTURY
        val theta = 280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38_710_000.0
        return normalizeDegrees(theta)
    }

    /** Local mean sidereal time in degrees, [0, 360). [longitudeDeg] is positive east. */
    fun localSiderealTimeDeg(julianDate: Double, longitudeDeg: Double): Double =
        normalizeDegrees(greenwichMeanSiderealTimeDeg(julianDate) + longitudeDeg)

    fun normalizeDegrees(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }
}
