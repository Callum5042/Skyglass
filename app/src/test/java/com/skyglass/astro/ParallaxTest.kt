package com.skyglass.astro

import com.skyglass.assertAngleDegEquals
import com.skyglass.assertVecEquals
import com.skyglass.dmsToDeg
import com.skyglass.hmsToDeg
import com.skyglass.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

class ParallaxTest {

    private fun rad(deg: Double) = Math.toRadians(deg)

    // Palomar Observatory, Meeus examples 11.a and 40.a.
    private val palomarLat = rad(dmsToDeg(+1, 33, 21, 22.0))
    private val palomarHeight = 1706.0

    @Test
    fun meeusExample11a_observerFactorsAtPalomar() {
        // ρ sin φ′ = +0.546861 and ρ cos φ′ = +0.836339 for φ = 33°21′22″, 1706 m.
        val observer = Parallax.observerPosition(palomarLat, lstRad = 0.0, heightM = palomarHeight)
        assertEquals(0.546861, observer.z, 2e-6)
        assertEquals(0.836339, hypot(observer.x, observer.y), 2e-6)
    }

    @Test
    fun meeusExample40a_marsFromPalomar() {
        // Mars on 2003 August 28 at 3h17m UT: α = 22h38m07.25s, δ = −15°46′15.9″, Δ = 0.37276 AU,
        // local hour angle H = 288.7958°. Topocentric: α′ = 22h38m08.54s, δ′ = −15°46′30.0″.
        val alpha = rad(hmsToDeg(22, 38, 7.25))
        val delta = rad(dmsToDeg(-1, 15, 46, 15.9))
        val distanceKm = 0.37276 * SolarSystem.KM_PER_AU
        val lst = alpha + rad(288.7958)

        val observer = Parallax.observerPosition(palomarLat, lst, palomarHeight)
        val topocentric = Parallax.topocentricDirection(
            Coordinates.raDecToUnit(Equatorial(alpha, delta)), distanceKm, observer,
        )
        val eq = Coordinates.unitToRaDec(topocentric)
        assertAngleDegEquals(hmsToDeg(22, 38, 8.54), Math.toDegrees(eq.raRad), 0.3 / 3600)
        assertEquals(dmsToDeg(-1, 15, 46, 30.0), Math.toDegrees(eq.decRad), 0.3 / 3600)
    }

    @Test
    fun agreesWithMeeusChapter40Formulae_forRandomMoonsAndObservers() {
        val random = Random(40)
        repeat(200) {
            val alpha = random.nextDouble(0.0, 2 * Math.PI)
            val delta = rad(random.nextDouble(-70.0, 70.0))
            val distanceKm = random.nextDouble(356_000.0, 407_000.0)
            val lat = rad(random.nextDouble(-80.0, 80.0))
            val height = random.nextDouble(0.0, 3000.0)
            val lst = random.nextDouble(0.0, 2 * Math.PI)

            val observer = Parallax.observerPosition(lat, lst, height)
            val ours = Coordinates.unitToRaDec(
                Parallax.topocentricDirection(Coordinates.raDecToUnit(Equatorial(alpha, delta)), distanceKm, observer),
            )

            // Meeus eqs 40.2 and 40.3, written out independently of the vector method.
            val sinPi = Parallax.EARTH_EQUATORIAL_RADIUS_KM / distanceKm
            val u = atan(0.99664719 * tan(lat))
            val rhoSin = 0.99664719 * sin(u) + height / 6378140.0 * sin(lat)
            val rhoCos = cos(u) + height / 6378140.0 * cos(lat)
            val hourAngle = lst - alpha
            val deltaAlpha = atan2(-rhoCos * sinPi * sin(hourAngle), cos(delta) - rhoCos * sinPi * cos(hourAngle))
            val deltaPrime = atan2(
                (sin(delta) - rhoSin * sinPi) * cos(deltaAlpha),
                cos(delta) - rhoCos * sinPi * cos(hourAngle),
            )

            assertAngleDegEquals(Math.toDegrees(alpha + deltaAlpha), Math.toDegrees(ours.raRad), 1e-8)
            assertEquals(Math.toDegrees(deltaPrime), Math.toDegrees(ours.decRad), 1e-8)
        }
    }

    @Test
    fun aMoonOnTheHorizon_isLoweredByAboutItsHorizontalParallax() {
        // Moon due south on the horizon from (geocentric-ish) 45° N: it is pulled down by π ≈ 0.95°.
        val lat = rad(45.0)
        val lst = rad(100.0)
        val geocentricAlt = rad(0.0)
        val enu = Coordinates.horizontalToEnu(Horizontal(geocentricAlt, rad(180.0)))
        val equatorial = Coordinates.equatorialToEnuMatrix(lst, lat).transpose() * enu

        val distanceKm = 384_400.0
        val topocentric = Parallax.topocentricDirection(equatorial, distanceKm, Parallax.observerPosition(lat, lst))
        val altitude = Coordinates.enuToHorizontal(Coordinates.equatorialToEnuMatrix(lst, lat) * topocentric).altRad

        val horizontalParallaxDeg = Math.toDegrees(asin(Parallax.EARTH_EQUATORIAL_RADIUS_KM / distanceKm))
        assertEquals(-horizontalParallaxDeg, Math.toDegrees(altitude), 0.2) // geodetic vs geocentric zenith: ≤ 0.19°
        assertTrue("lowered, not raised", altitude < 0)
    }

    @Test
    fun aMoonAtTheZenith_isNotMovedMuchByParallax() {
        val lat = rad(30.0)
        val lst = rad(10.0)
        val zenith = Coordinates.equatorialToEnuMatrix(lst, lat).transpose() * Vector3(0.0, 0.0, 1.0)
        val topocentric = Parallax.topocentricDirection(zenith, 384_400.0, Parallax.observerPosition(lat, lst))
        // Only the 0.14° gap between the geodetic and geocentric vertical remains, times 1/60.
        assertTrue(Math.toDegrees(acos((zenith dot topocentric).coerceIn(-1.0, 1.0))) < 0.2)
    }

    @Test
    fun aDistantBody_hasNoParallax() {
        val direction = Coordinates.raDecToUnit(Equatorial(rad(120.0), rad(-20.0)))
        val observer = Parallax.observerPosition(rad(51.5), rad(33.0))
        assertVecEquals(direction, Parallax.topocentricDirection(direction, 1e15, observer), 1e-8)
    }
}
