package com.skyglass.render

import com.skyglass.astro.Coordinates
import com.skyglass.math.Matrix3
import com.skyglass.math.Matrix4
import com.skyglass.math.Quaternion
import com.skyglass.math.Vector3
import kotlin.math.hypot

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

    /**
     * Direction of the sunlight in a body's own sprite frame, for shading the Moon's phase: x to
     * the right and y up on screen, z toward the viewer. With elongation ψ between the body and
     * the Sun, the component along the line of sight is −cos ψ (+1 at full Moon, −1 at new) and
     * the component across it has length sin ψ and points toward the Sun *as drawn*; the latter
     * is found by projecting a small step from the body toward the Sun through [mvp], so it
     * follows the screen orientation, display rotation included. The result has unit length.
     *
     * Returns null if the body, or the step toward the Sun, is behind the viewer.
     *
     * @param bodyDirection unit vector toward the body, equatorial of date
     * @param sunDirection unit vector toward the Sun, same frame
     */
    fun spriteLight(mvp: Matrix4, bodyDirection: Vector3, sunDirection: Vector3, widthPx: Int, heightPx: Int): Vector3? {
        val cosPsi = bodyDirection dot sunDirection
        val toward = sunDirection - bodyDirection * cosPsi
        val sinPsi = toward.length()
        if (sinPsi < 1e-9) return Vector3(0.0, 0.0, -cosPsi) // Sun dead ahead or dead behind
        val origin = SkyProjection.project(mvp, bodyDirection, widthPx, heightPx) ?: return null
        val stepped = SkyProjection.project(mvp, bodyDirection + toward * (SPRITE_LIGHT_STEP / sinPsi), widthPx, heightPx)
            ?: return null
        val dx = stepped.x - origin.x
        val dy = origin.y - stepped.y // screen pixels grow downward; the sprite frame's y is up
        val length = hypot(dx, dy)
        if (length < 1e-12) return Vector3(0.0, 0.0, -cosPsi)
        return Vector3(dx / length * sinPsi, dy / length * sinPsi, -cosPsi)
    }

    /** Size of the probe step, in unit-vector length (about 0.6°), used by [spriteLight]. */
    private const val SPRITE_LIGHT_STEP = 0.01
}
