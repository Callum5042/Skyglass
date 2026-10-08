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

    /** Inverse of [julianDate]: milliseconds since the Unix epoch. */
    fun epochMillis(julianDate: Double): Long = Math.round((julianDate - JD_UNIX_EPOCH) * MILLIS_PER_DAY)

    fun julianCenturiesSinceJ2000(julianDate: Double): Double = (julianDate - JD_J2000) / DAYS_PER_CENTURY

    /**
     * TT − UTC in seconds: 32.184 s plus the 37 leap seconds in force since 2017. Solar-system
     * ephemerides run on dynamical time, and the Moon moves 0.5″ per 3 s, so ignoring this
     * offset would displace it by about 0.01°. It is constant until the next leap second.
     */
    const val TT_MINUS_UTC_SECONDS = 69.184

    /** Julian Ephemeris Day (dynamical time) for a UTC Julian date. */
    fun julianEphemerisDay(julianDateUtc: Double): Double = julianDateUtc + TT_MINUS_UTC_SECONDS / 86_400.0

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
