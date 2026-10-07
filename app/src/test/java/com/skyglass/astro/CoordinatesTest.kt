package com.skyglass.astro

import com.skyglass.assertAngleDegEquals
import com.skyglass.assertMatrixEquals
import com.skyglass.assertVecEquals
import com.skyglass.dmsToDeg
import com.skyglass.epochMillis
import com.skyglass.hmsToDeg
import com.skyglass.math.Matrix3
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class CoordinatesTest {

    private fun rad(deg: Double) = Math.toRadians(deg)
    private fun deg(rad: Double) = Math.toDegrees(rad)

    private fun horizontalViaMatrix(position: Equatorial, lstRad: Double, latRad: Double): Horizontal =
        Coordinates.enuToHorizontal(
            Coordinates.equatorialToEnuMatrix(lstRad, latRad) * Coordinates.raDecToUnit(position)
        )

    @Test
    fun meeusExample13b_venusFromWashington() {
        // Venus, 1987 Apr 10 19:21 UT: α = 23h09m16.641s, δ = −6°43′11.61″,
        // observer at 38°55′17″ N, 77°03′56″ W. Meeus gives A = 68.0337° measured from *south*
        // (= 248.0337° from north) and h = 15.1249°. Meeus uses apparent sidereal time; the mean
        // time used here differs by 0.24 s, i.e. ~0.001°.
        val venus = Equatorial(rad(hmsToDeg(23, 9, 16.641)), rad(dmsToDeg(-1, 6, 43, 11.61)))
        val lat = rad(dmsToDeg(+1, 38, 55, 17.0))
        val lon = dmsToDeg(-1, 77, 3, 56.0)
        val jd = AstroTime.julianDate(epochMillis("1987-04-10T19:21:00Z"))
        val lst = rad(AstroTime.localSiderealTimeDeg(jd, lon))

        for (h in listOf(
            Coordinates.equatorialToHorizontal(venus, lst, lat),
            horizontalViaMatrix(venus, lst, lat),
        )) {
            assertEquals(15.1249, deg(h.altRad), 0.003)
            assertAngleDegEquals(248.0337, deg(h.azRad), 0.003)
        }
    }

    @Test
    fun celestialPole_altitudeEqualsLatitude_dueNorth() {
        for (latDeg in listOf(5.0, 34.0, 51.5, 78.0)) {
            for (lstDeg in listOf(0.0, 97.0, 233.0)) {
                val h = horizontalViaMatrix(Equatorial(0.0, rad(90.0)), rad(lstDeg), rad(latDeg))
                assertEquals(latDeg, deg(h.altRad), 1e-9)
                assertAngleDegEquals(0.0, deg(h.azRad), 1e-9)
            }
        }
    }

    @Test
    fun southernObserver_seesSouthPoleDueSouth() {
        val h = horizontalViaMatrix(Equatorial(0.0, rad(-90.0)), rad(42.0), rad(-33.9))
        assertEquals(33.9, deg(h.altRad), 1e-9)
        assertAngleDegEquals(180.0, deg(h.azRad), 1e-9)
    }

    @Test
    fun starWithDecEqualToLatitude_onMeridian_isAtZenith() {
        val lat = rad(51.5)
        val lst = rad(123.0)
        val h = horizontalViaMatrix(Equatorial(lst, lat), lst, lat)
        assertEquals(90.0, deg(h.altRad), 1e-9)
    }

    @Test
    fun celestialEquatorStar_setsDueWestAndRisesDueEast() {
        val lat = rad(40.0)
        val lst = rad(200.0)
        // Hour angle +6h: west horizon.
        val setting = Coordinates.equatorialToHorizontal(Equatorial(lst - rad(90.0), 0.0), lst, lat)
        assertEquals(0.0, deg(setting.altRad), 1e-9)
        assertAngleDegEquals(270.0, deg(setting.azRad), 1e-9)
        // Hour angle −6h: east horizon.
        val rising = horizontalViaMatrix(Equatorial(lst + rad(90.0), 0.0), lst, lat)
        assertEquals(0.0, deg(rising.altRad), 1e-9)
        assertAngleDegEquals(90.0, deg(rising.azRad), 1e-9)
    }

    @Test
    fun matrixForm_matchesTrigonometricForm_forRandomInputs() {
        val random = Random(42)
        var checked = 0
        repeat(2000) {
            val position = Equatorial(rad(random.nextDouble(0.0, 360.0)), rad(random.nextDouble(-85.0, 85.0)))
            val lst = rad(random.nextDouble(0.0, 360.0))
            val lat = rad(random.nextDouble(-80.0, 80.0))
            val direct = Coordinates.equatorialToHorizontal(position, lst, lat)
            if (abs(deg(direct.altRad)) > 89.0) return@repeat // azimuth is ill-defined near the zenith

            val viaMatrix = horizontalViaMatrix(position, lst, lat)
            assertEquals(deg(direct.altRad), deg(viaMatrix.altRad), 1e-9)
            assertAngleDegEquals(deg(direct.azRad), deg(viaMatrix.azRad), 1e-7)
            checked++
        }
        assert(checked > 1900)
    }

    @Test
    fun equatorialToEnuMatrix_isARotation() {
        val m = Coordinates.equatorialToEnuMatrix(rad(77.0), rad(-21.0))
        assertMatrixEquals(Matrix3.IDENTITY, m * m.transpose(), 1e-12)
    }

    @Test
    fun raDec_roundTrips() {
        val original = Equatorial(rad(311.25), rad(-47.5))
        val back = Coordinates.unitToRaDec(Coordinates.raDecToUnit(original))
        assertEquals(original.raRad, back.raRad, 1e-12)
        assertEquals(original.decRad, back.decRad, 1e-12)
    }

    @Test
    fun horizontal_roundTripsAndUsesEnuAxes() {
        assertVecEquals(Vector3(0.0, 1.0, 0.0), Coordinates.horizontalToEnu(Horizontal(0.0, 0.0)), 1e-12, "north")
        assertVecEquals(Vector3(1.0, 0.0, 0.0), Coordinates.horizontalToEnu(Horizontal(0.0, rad(90.0))), 1e-12, "east")
        assertVecEquals(Vector3(0.0, 0.0, 1.0), Coordinates.horizontalToEnu(Horizontal(rad(90.0), 0.0)), 1e-12, "up")

        val original = Horizontal(rad(23.0), rad(301.0))
        val back = Coordinates.enuToHorizontal(Coordinates.horizontalToEnu(original))
        assertEquals(original.altRad, back.altRad, 1e-12)
        assertEquals(original.azRad, back.azRad, 1e-12)
    }

    @Test
    fun magneticToTrue_eastDeclination_magneticNorthIsEastOfTrueNorth() {
        val magneticNorth = Vector3(0.0, 1.0, 0.0)
        val inTrueFrame = Coordinates.magneticToTrueEnu(rad(10.0)) * magneticNorth
        assertAngleDegEquals(10.0, deg(Coordinates.enuToHorizontal(inTrueFrame).azRad), 1e-9)
    }

    @Test
    fun magneticToTrue_westDeclination_magneticNorthIsWestOfTrueNorth() {
        val magneticNorth = Vector3(0.0, 1.0, 0.0)
        val inTrueFrame = Coordinates.magneticToTrueEnu(rad(-5.0)) * magneticNorth
        assertAngleDegEquals(355.0, deg(Coordinates.enuToHorizontal(inTrueFrame).azRad), 1e-9)
    }

    @Test
    fun magneticToTrue_leavesUpUnchanged() {
        val up = Vector3(0.0, 0.0, 1.0)
        assertVecEquals(up, Coordinates.magneticToTrueEnu(rad(17.0)) * up, 1e-12)
    }
}
