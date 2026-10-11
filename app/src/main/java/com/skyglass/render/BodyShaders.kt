package com.skyglass.render

import com.skyglass.catalog.BodyAppearance

/**
 * GLSL ES 3.0 sources for the Sun, Moon and planets, drawn as point sprites like the stars but
 * with a shape per body kind: tinted discs for planets, a blazing core and wide glow for the Sun,
 * and a shaded sphere for the Moon so that its phase shows.
 *
 * Output is premultiplied. In the enhanced view glows have alpha 0, so they add to what is behind as
 * the stars do; in the camera view (`uCameraMode` 1) their alpha covers their colour instead (see
 * [OverlayBlend]). In the enhanced view the Moon is opaque (alpha 1) only within its *true* angular
 * radius, `uMoonOccluder` of the drawn disc: the drawn disc is several times larger than the real
 * Moon, and a star or planet near the Moon must not vanish behind that exaggeration. In the camera
 * view that does not hold: alpha must cover the colour, so the whole drawn disc is nearly opaque
 * (the lit colour is about 0.93) and does hide what is behind it. Draw with
 * `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`.
 *
 * The Moon's lit side comes from `uMoonLight`, computed on the CPU by [SkyViewMath.spriteLight]
 * where it is unit-tested. The shader's only part in it is the sprite frame: x right, y up, z
 * toward the viewer, which needs `gl_PointCoord`'s top-left origin flipped to y-up.
 */
object BodyShaders {

    const val ATTRIB_POSITION = 0
    const val ATTRIB_SIZE = 1
    const val ATTRIB_COLOR = 2
    const val ATTRIB_KIND = 3

    /** Brightness of the Moon's unlit side, as a fraction of the lit side (earthshine). */
    private const val EARTHSHINE = 0.05f

    val VERTEX = """
        #version 300 es
        uniform mat4 uMvp;
        uniform float uPointScale;   // dp → px
        uniform float uMaxPointSize;

        layout(location = $ATTRIB_POSITION) in vec3 aPosition;       // unit, equatorial of date
        layout(location = $ATTRIB_SIZE) in float aSizeDp;
        layout(location = $ATTRIB_COLOR) in vec3 aColor;
        layout(location = $ATTRIB_KIND) in float aKind;

        out vec3 vColor;
        flat out float vKind;

        void main() {
            gl_Position = uMvp * vec4(aPosition, 1.0);
            gl_PointSize = min(aSizeDp * uPointScale, uMaxPointSize);
            vColor = aColor;
            vKind = aKind;
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform vec3 uMoonLight;       // unit, sprite frame (x right, y up, z toward the viewer)
        uniform float uMoonOccluder;   // true lunar radius as a fraction of the drawn disc's radius
        uniform float uCameraMode;     // 0 enhanced view, 1 camera view: see OverlayBlend
        ${NightMode.GLSL_UNIFORM}

        in vec3 vColor;
        flat in float vKind;
        out vec4 fragColor;

        const float MOON_DISC = ${BodyAppearance.MOON_DISC_FRACTION};
        const float SUN_DISC = ${BodyAppearance.SUN_DISC_FRACTION};
        const float EARTHSHINE = $EARTHSHINE;

        void main() {
            // gl_PointCoord has its origin at the top left; flip y so +y is up, as on screen.
            vec2 p = vec2(gl_PointCoord.x * 2.0 - 1.0, 1.0 - gl_PointCoord.y * 2.0);
            float r2 = dot(p, p);
            if (r2 > 1.0) discard;
            float r = sqrt(r2);

            if (vKind > 1.5) {
                // Moon: a sphere lit from uMoonLight, inside a halo that fades with the phase.
                if (r < MOON_DISC) {
                    vec2 q = p / MOON_DISC;
                    float qLength = length(q);
                    vec3 normal = vec3(q, sqrt(max(1.0 - dot(q, q), 0.0)));
                    float lit = smoothstep(-0.02, 0.05, dot(normal, uMoonLight));
                    float edge = 1.0 - smoothstep(0.94, 1.0, qLength);
                    float shade = EARTHSHINE + (1.0 - EARTHSHINE) * lit;
                    float opaque = 1.0 - smoothstep(0.9 * uMoonOccluder, uMoonOccluder, qLength);
                    fragColor = vec4(vColor * shade * edge, min(edge, opaque));
                } else {
                    float illuminated = 0.5 * (1.0 + uMoonLight.z);
                    float halo = 0.25 * illuminated * exp(-9.0 * (r - MOON_DISC));
                    fragColor = vec4(vColor * halo, 0.0);
                }
            } else if (vKind > 0.5) {
                // Sun: white-hot core and a wide glow.
                float core = 1.0 - smoothstep(0.8 * SUN_DISC, SUN_DISC, r);
                float glow = 0.75 * exp(-10.0 * r2) + 0.25 * exp(-3.0 * r2);
                fragColor = vec4(vColor * (core + glow), 0.0);
            } else {
                // Planet: a small tinted disc in a soft halo, steadier than a star's pinpoint.
                float core = 1.0 - smoothstep(0.12, 0.28, r);
                float glow = exp(-5.0 * r2);
                fragColor = vec4(vColor * (0.75 * glow + 0.45 * core), 0.0);
            }

            // Over a camera image the colour must be covered by alpha (premultiplied). The glows'
            // alpha 0 is only right on the opaque black of the enhanced view.
            fragColor.a = max(fragColor.a, uCameraMode * min(1.0, max(max(fragColor.r, fragColor.g), fragColor.b)));
            ${NightMode.GLSL_APPLY}
        }
    """.trimIndent()
}
