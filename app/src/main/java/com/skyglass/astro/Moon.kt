package com.skyglass.astro

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Moon's geocentric position, referred to the mean equinox of date.
 *
 * @property longitudeDeg ecliptic longitude, [0, 360)
 * @property latitudeDeg ecliptic latitude
 * @property distanceKm distance between the centres of the Earth and Moon
 */
data class MoonPosition(
    val longitudeDeg: Double,
    val latitudeDeg: Double,
    val distanceKm: Double,
)

/**
 * Meeus, *Astronomical Algorithms*, chapter 47: the principal periodic terms of ELP-2000/82.
 * Accurate to about 10″ in longitude and 4″ in latitude, far below what a phone can show.
 */
object MoonEphemeris {

    /** @param jde Julian Ephemeris Day (dynamical time), see [AstroTime.julianEphemerisDay] */
    fun position(jde: Double): MoonPosition {
        val t = AstroTime.julianCenturiesSinceJ2000(jde)
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t

        val meanLongitude = 218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841.0 - t4 / 65194000.0
        val meanElongation = 297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 - t4 / 113065000.0
        val sunAnomaly = 357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0
        val moonAnomaly = 134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 - t4 / 14712000.0
        val latitudeArgument = 93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000.0 + t4 / 863310000.0

        // Venus, Jupiter and the flattening of the Earth perturb the Moon slightly.
        val a1 = Math.toRadians(119.75 + 131.849 * t)
        val a2 = Math.toRadians(53.09 + 479264.290 * t)
        val a3 = Math.toRadians(313.45 + 481266.484 * t)

        // Terms in the Sun's anomaly shrink in step with the Earth's orbital eccentricity.
        val e = 1.0 - 0.002516 * t - 0.0000074 * t2

        val d = Math.toRadians(meanElongation)
        val m = Math.toRadians(sunAnomaly)
        val mp = Math.toRadians(moonAnomaly)
        val f = Math.toRadians(latitudeArgument)
        val lp = Math.toRadians(meanLongitude)

        var sumL = 0.0
        var sumR = 0.0
        var i = 0
        while (i < LONGITUDE_DISTANCE_TERMS.size) {
            val row = LONGITUDE_DISTANCE_TERMS
            val argument = row[i] * d + row[i + 1] * m + row[i + 2] * mp + row[i + 3] * f
            val factor = eccentricityFactor(e, row[i + 1])
            sumL += row[i + 4] * factor * sin(argument)
            sumR += row[i + 5] * factor * cos(argument)
            i += 6
        }

        var sumB = 0.0
        i = 0
        while (i < LATITUDE_TERMS.size) {
            val row = LATITUDE_TERMS
            val argument = row[i] * d + row[i + 1] * m + row[i + 2] * mp + row[i + 3] * f
            sumB += row[i + 4] * eccentricityFactor(e, row[i + 1]) * sin(argument)
            i += 5
        }

        sumL += 3958 * sin(a1) + 1962 * sin(lp - f) + 318 * sin(a2)
        sumB += -2235 * sin(lp) + 382 * sin(a3) + 175 * sin(a1 - f) + 175 * sin(a1 + f) +
            127 * sin(lp - mp) - 115 * sin(lp + mp)

        return MoonPosition(
            longitudeDeg = AstroTime.normalizeDegrees(meanLongitude + sumL / 1e6),
            latitudeDeg = sumB / 1e6,
            distanceKm = 385000.56 + sumR / 1e3,
        )
    }

    private fun eccentricityFactor(e: Double, sunAnomalyMultiple: Int): Double =
        when (abs(sunAnomalyMultiple)) {
            0 -> 1.0
            1 -> e
            else -> e.pow(2)
        }

    // Meeus table 47.A. Per row: multiples of D, M, M', F, then Σl (1e-6°, sine) and Σr (1e-3 km, cosine).
    private val LONGITUDE_DISTANCE_TERMS = intArrayOf(
        0, 0, 1, 0, 6288774, -20905355,
        2, 0, -1, 0, 1274027, -3699111,
        2, 0, 0, 0, 658314, -2955968,
        0, 0, 2, 0, 213618, -569925,
        0, 1, 0, 0, -185116, 48888,
        0, 0, 0, 2, -114332, -3149,
        2, 0, -2, 0, 58793, 246158,
        2, -1, -1, 0, 57066, -152138,
        2, 0, 1, 0, 53322, -170733,
        2, -1, 0, 0, 45758, -204586,
        0, 1, -1, 0, -40923, -129620,
        1, 0, 0, 0, -34720, 108743,
        0, 1, 1, 0, -30383, 104755,
        2, 0, 0, -2, 15327, 10321,
        0, 0, 1, 2, -12528, 0,
        0, 0, 1, -2, 10980, 79661,
        4, 0, -1, 0, 10675, -34782,
        0, 0, 3, 0, 10034, -23210,
        4, 0, -2, 0, 8548, -21636,
        2, 1, -1, 0, -7888, 24208,
        2, 1, 0, 0, -6766, 30824,
        1, 0, -1, 0, -5163, -8379,
        1, 1, 0, 0, 4987, -16675,
        2, -1, 1, 0, 4036, -12831,
        2, 0, 2, 0, 3994, -10445,
        4, 0, 0, 0, 3861, -11650,
        2, 0, -3, 0, 3665, 14403,
        0, 1, -2, 0, -2689, -7003,
        2, 0, -1, 2, -2602, 0,
        2, -1, -2, 0, 2390, 10056,
        1, 0, 1, 0, -2348, 6322,
        2, -2, 0, 0, 2236, -9884,
        0, 1, 2, 0, -2120, 5751,
        0, 2, 0, 0, -2069, 0,
        2, -2, -1, 0, 2048, -4950,
        2, 0, 1, -2, -1773, 4130,
        2, 0, 0, 2, -1595, 0,
        4, -1, -1, 0, 1215, -3958,
        0, 0, 2, 2, -1110, 0,
        3, 0, -1, 0, -892, 3258,
        2, 1, 1, 0, -810, 2616,
        4, -1, -2, 0, 759, -1897,
        0, 2, -1, 0, -713, -2117,
        2, 2, -1, 0, -700, 2354,
        2, 1, -2, 0, 691, 0,
        2, -1, 0, -2, 596, 0,
        4, 0, 1, 0, 549, -1423,
        0, 0, 4, 0, 537, -1117,
        4, -1, 0, 0, 520, -1571,
        1, 0, -2, 0, -487, -1739,
        2, 1, 0, -2, -399, 0,
        0, 0, 2, -2, -381, -4421,
        1, 1, 1, 0, 351, 0,
        3, 0, -2, 0, -340, 0,
        4, 0, -3, 0, 330, 0,
        2, -1, 2, 0, 327, 0,
        0, 2, 1, 0, -323, 1165,
        1, 1, -1, 0, 299, 0,
        2, 0, 3, 0, 294, 0,
        2, 0, -1, -2, 0, 8752,
    )

    // Meeus table 47.B. Per row: multiples of D, M, M', F, then Σb (1e-6°, sine).
    private val LATITUDE_TERMS = intArrayOf(
        0, 0, 0, 1, 5128122,
        0, 0, 1, 1, 280602,
        0, 0, 1, -1, 277693,
        2, 0, 0, -1, 173237,
        2, 0, -1, 1, 55413,
        2, 0, -1, -1, 46271,
        2, 0, 0, 1, 32573,
        0, 0, 2, 1, 17198,
        2, 0, 1, -1, 9266,
        0, 0, 2, -1, 8822,
        2, -1, 0, -1, 8216,
        2, 0, -2, -1, 4324,
        2, 0, 1, 1, 4200,
        2, 1, 0, -1, -3359,
        2, -1, -1, 1, 2463,
        2, -1, 0, 1, 2211,
        2, -1, -1, -1, 2065,
        0, 1, -1, -1, -1870,
        4, 0, -1, -1, 1828,
        0, 1, 0, 1, -1794,
        0, 0, 0, 3, -1749,
        0, 1, -1, 1, -1565,
        1, 0, 0, 1, -1491,
        0, 1, 1, 1, -1475,
        0, 1, 1, -1, -1410,
        0, 1, 0, -1, -1344,
        1, 0, 0, -1, -1335,
        0, 0, 3, 1, 1107,
        4, 0, 0, -1, 1021,
        4, 0, -1, 1, 833,
        0, 0, 1, -3, 777,
        4, 0, -2, 1, 671,
        2, 0, 0, -3, 607,
        2, 0, 2, -1, 596,
        2, -1, 1, -1, 491,
        2, 0, -2, 1, -451,
        0, 0, 3, -1, 439,
        2, 0, 2, 1, 422,
        2, 0, -3, -1, 421,
        2, 1, -1, 1, -366,
        2, 1, 0, 1, -351,
        4, 0, 0, 1, 331,
        2, -1, 1, 1, 315,
        2, -2, 0, -1, 302,
        0, 0, 1, 3, -283,
        2, 1, 1, -1, -229,
        1, 1, 0, -1, 223,
        1, 1, 0, 1, 223,
        0, 1, -2, -1, -220,
        2, 1, -1, -1, -220,
        1, 0, 1, 1, -185,
        2, -1, -2, -1, 181,
        0, 1, 2, 1, -177,
        4, 0, -2, -1, 176,
        4, -1, -1, -1, 166,
        1, 0, 1, -1, -164,
        4, 0, 1, -1, 132,
        1, 0, -1, -1, -119,
        4, -1, 0, -1, 115,
        2, -2, 0, 1, 107,
    )
}
