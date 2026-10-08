package com.skyglass.astro

import com.skyglass.math.Matrix3
import com.skyglass.math.Vector3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Galactic longitude and latitude in degrees: l in [0, 360), b in [-90, 90]. */
data class GalacticCoordinates(val lDeg: Double, val bDeg: Double)

/**
 * The IAU galactic coordinate system, expressed in the J2000 (ICRS) equatorial frame: the north
 * galactic pole at RA 192.85948°, Dec +27.12825°, and the galactic longitude of the north celestial
 * pole 122.93192° (the values the Hipparcos catalogue adopted). Longitude is measured from the
 * direction of the galactic centre (RA 266.405°, Dec −28.936°), toward +Y.
 *
 * Everything here is in the J2000 equatorial frame; carry a vector to the mean equator of date with
 * `Precession.matrixFromJ2000`, as the stars are.
 */
object Galactic {

    const val NORTH_POLE_RA_DEG = 192.85948
    const val NORTH_POLE_DEC_DEG = 27.12825
    const val NORTH_CELESTIAL_POLE_LONGITUDE_DEG = 122.93192

    /** Columns are the galactic X (to the centre), Y (toward l = 90°) and Z (north galactic pole) axes in the J2000 equatorial frame. */
    private val galacticToEquatorial: Matrix3 = run {
        val ra = Math.toRadians(NORTH_POLE_RA_DEG)
        val dec = Math.toRadians(NORTH_POLE_DEC_DEG)
        val z = Vector3(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
        // The celestial pole, with its part along z removed, lies in the galactic XY plane at
        // longitude l_NCP: P = X cos(l_NCP) + Y sin(l_NCP), so X = P cos(l_NCP) − (z × P) sin(l_NCP).
        val pole = Vector3(0.0, 0.0, 1.0)
        val p = (pole - z * (pole dot z)).normalized()
        val lNcp = Math.toRadians(NORTH_CELESTIAL_POLE_LONGITUDE_DEG)
        val x = p * cos(lNcp) - (z cross p) * sin(lNcp)
        val y = z cross x
        Matrix3.fromColumns(x, y, z)
    }

    private val equatorialToGalactic: Matrix3 = galacticToEquatorial.transpose()

    /** Unit vector in the J2000 equatorial frame for galactic longitude [lDeg] and latitude [bDeg]. */
    fun toEquatorialUnit(lDeg: Double, bDeg: Double): Vector3 {
        val l = Math.toRadians(lDeg)
        val b = Math.toRadians(bDeg)
        return galacticToEquatorial * Vector3(cos(b) * cos(l), cos(b) * sin(l), sin(b))
    }

    /** J2000 right ascension and declination of a galactic position. */
    fun toEquatorial(lDeg: Double, bDeg: Double): Equatorial = Coordinates.unitToRaDec(toEquatorialUnit(lDeg, bDeg))

    /** Galactic longitude and latitude of a J2000 equatorial unit vector. */
    fun fromEquatorialUnit(v: Vector3): GalacticCoordinates {
        val g = equatorialToGalactic * v
        return GalacticCoordinates(
            lDeg = AstroTime.normalizeDegrees(Math.toDegrees(atan2(g.y, g.x))),
            bDeg = Math.toDegrees(asin(g.z.coerceIn(-1.0, 1.0))),
        )
    }

    /** Galactic position of a J2000 right ascension and declination. */
    fun fromEquatorial(position: Equatorial): GalacticCoordinates = fromEquatorialUnit(Coordinates.raDecToUnit(position))
}
