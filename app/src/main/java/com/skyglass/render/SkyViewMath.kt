package com.skyglass.render

import com.skyglass.astro.Coordinates
import com.skyglass.math.Matrix3
import com.skyglass.math.Matrix4
import com.skyglass.math.Quaternion

/**
 * Builds the single matrix that takes static equatorial star vectors to clip space:
 *
 *     clip = P · S(displayRotation) · R(q)ᵀ · M(true→magnetic) · H(LST, φ) · v_eq
 *
 * In natural (portrait) orientation the device frame (+X right, +Y up, +Z out of the screen)
 * coincides with the OpenGL eye frame, so looking out the back of the phone is −Z.
 */
object SkyViewMath {

    const val NEAR = 0.01
    const val FAR = 10.0

    /** Equatorial-of-date → screen-aligned eye coordinates. */
    fun viewMatrix(
        deviceToMagneticEnu: Quaternion,
        declinationDeg: Double,
        lstDeg: Double,
        latitudeDeg: Double,
        displayRotation: Int,
    ): Matrix3 {
        val equatorialToTrueEnu = Coordinates.equatorialToEnuMatrix(Math.toRadians(lstDeg), Math.toRadians(latitudeDeg))
        val trueToMagneticEnu = Coordinates.magneticToTrueEnu(Math.toRadians(declinationDeg)).transpose()
        val magneticEnuToDevice = deviceToMagneticEnu.toMatrix3().transpose()
        return deviceToScreen(displayRotation) * magneticEnuToDevice * trueToMagneticEnu * equatorialToTrueEnu
    }

    fun modelViewProjection(
        deviceToMagneticEnu: Quaternion,
        declinationDeg: Double,
        lstDeg: Double,
        latitudeDeg: Double,
        displayRotation: Int,
        fovYDeg: Double,
        aspect: Double,
    ): Matrix4 {
        val view = viewMatrix(deviceToMagneticEnu, declinationDeg, lstDeg, latitudeDeg, displayRotation)
        return Matrix4.perspective(fovYDeg, aspect, NEAR, FAR) * Matrix4.fromMatrix3(view)
    }

    /**
     * Re-expresses device coordinates in the axes of the drawn screen. [displayRotation] is a
     * `Surface.ROTATION_*` value (0–3). For ROTATION_90 the device has been turned 90°
     * counter-clockwise, so the device's +X edge now points up the screen and its +Y edge left.
     */
    fun deviceToScreen(displayRotation: Int): Matrix3 =
        Matrix3.rotationZ(Math.toRadians(90.0 * (displayRotation and 3)))
}
