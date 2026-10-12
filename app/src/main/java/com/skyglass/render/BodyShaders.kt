package com.skyglass.render

import com.skyglass.catalog.BodyAppearance

/**
 * GLSL ES 3.0 sources for the Sun, Moon, planets and the planets' moons, drawn as screen-aligned quads
 * (see `BodyVertices`) with a shape per body kind: tinted discs for planets, a blazing core and wide
 * glow for the Sun, a shaded sphere for the Moon so that its phase shows, and small faint discs for the
 * planets' moons, faded in only once they clear their planet (`aSeparation`, as
 * `BodyAppearance.satelliteFade`).
 *
 * Size: the vertex shader does `BodyAppearance.drawn`'s sum. The disc is its nominal size or its
 * true size (`aTrueRadius` times `uFocalPx`), whichever is larger, and the halo keeps its nominal
 * profile outside the disc: every halo term is measured in units of the nominal sprite's radius from
 * where the nominal halo would start, pushed out by the disc's growth (and, for a planet, narrowed as
 * its disc grows, `BodyAppearance.haloScale`). With no growth this is exactly the nominal sprite. All
 * edges are in pixels, so a disc hundreds of pixels across still ends crisply.
 *
 * Output is premultiplied. In the enhanced view glows have alpha 0, so they add to what is behind as
 * the stars do; in the camera view (`uCameraMode` 1) their alpha covers their colour instead (see
 * [OverlayBlend]). In the enhanced view the Moon is opaque (alpha 1) only within its *true* angular
 * radius (`vOccluder` of the drawn disc): at the default field of view the drawn disc is several times
 * larger than the real Moon, and a star or planet near the Moon must not vanish behind that
 * exaggeration. In the camera view that does not hold: alpha must cover the colour, so the whole drawn
 * disc is nearly opaque (the lit colour is about 0.93) and does hide what is behind it. Draw with
 * `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`.
 *
 * The Moon's lit side comes from `uMoonLight`, computed on the CPU by [SkyViewMath.spriteLight]
 * where it is unit-tested, in the quad's frame: x right, y up, z toward the viewer.
 */
object BodyShaders {

    const val ATTRIB_POSITION = 0
    const val ATTRIB_CORNER = 1
    const val ATTRIB_SIZE = 2
    const val ATTRIB_TRUE_RADIUS = 3
    const val ATTRIB_COLOR = 4
    const val ATTRIB_KIND = 5
    const val ATTRIB_SEPARATION = 6

    /** Brightness of the Moon's unlit side, as a fraction of the lit side (earthshine). */
    private const val EARTHSHINE = 0.05f

    val VERTEX = """
        #version 300 es
        uniform mat4 uMvp;
        uniform float uPointScale;   // dp → px
        uniform float uFocalPx;      // focal length of the projection in pixels
        uniform vec2 uViewportPx;

        layout(location = $ATTRIB_POSITION) in vec3 aPosition;          // unit, equatorial of date
        layout(location = $ATTRIB_CORNER) in vec2 aCorner;              // (±1, ±1), x right, y up on screen
        layout(location = $ATTRIB_SIZE) in float aSizeDp;               // nominal sprite diameter
        layout(location = $ATTRIB_TRUE_RADIUS) in float aTrueRadius;    // tan of the true angular radius
        layout(location = $ATTRIB_COLOR) in vec3 aColor;
        layout(location = $ATTRIB_KIND) in float aKind;
        layout(location = $ATTRIB_SEPARATION) in float aSeparation;  // a moon's: tan of its distance from its planet; else < 0

        out vec2 vPx;                // pixels from the body's centre, x right, y up
        out vec3 vColor;
        flat out float vKind;
        flat out float vNominalPx;   // the nominal sprite's radius
        flat out float vGrowthPx;    // how far the disc, and the halo with it, has grown past nominal
        flat out float vHalo;        // the halo's width as a fraction of nominal (narrower for a grown planet)
        flat out float vRadiusPx;    // the whole quad's radius
        flat out float vOccluder;    // Moon: its true radius as a fraction of the drawn disc's
        flat out float vFade;        // a planet's moon: 0 against its planet, 1 once clear of it; else 1

        const float MOON_DISC = ${BodyAppearance.MOON_DISC_FRACTION};
        const float SUN_DISC = ${BodyAppearance.SUN_DISC_FRACTION};
        const float PLANET_DISC = ${BodyAppearance.PLANET_DISC_FRACTION};
        const float MIN_OCCLUDER = ${BodyAppearance.MIN_MOON_OCCLUDER};
        const float MIN_HALO = ${BodyAppearance.PLANET_MIN_HALO_SCALE};
        const float FADE_START_DP = ${BodyAppearance.SATELLITE_FADE_START_DP};
        const float FADE_FULL_DP = ${BodyAppearance.SATELLITE_FADE_FULL_DP};

        void main() {
            // Kinds: 0 planet, 1 Sun, 2 Moon, 3 a planet's moon (drawn like a small planet).
            float fraction = (aKind > 1.5 && aKind < 2.5) ? MOON_DISC : ((aKind > 0.5 && aKind < 1.5) ? SUN_DISC : PLANET_DISC);
            float nominal = 0.5 * aSizeDp * uPointScale;
            float trueRadius = aTrueRadius * uFocalPx;
            float disc = max(fraction * nominal, trueRadius);
            float growth = disc - fraction * nominal;
            bool sunOrMoon = aKind > 0.5 && aKind < 2.5;
            float halo = sunOrMoon ? 1.0 : clamp(1.0 - growth / ((1.0 - fraction) * nominal), MIN_HALO, 1.0);
            float radius = disc + (1.0 - fraction) * nominal * halo;

            // Spread the corner on the screen: an offset in NDC, scaled by w so it survives the divide.
            vec4 clip = uMvp * vec4(aPosition, 1.0);
            clip.xy += aCorner * radius * 2.0 / uViewportPx * clip.w;
            gl_Position = clip;
            vPx = aCorner * radius;
            vColor = aColor;
            vKind = aKind;
            vNominalPx = nominal;
            vGrowthPx = growth;
            vHalo = halo;
            vRadiusPx = radius;
            vOccluder = clamp(trueRadius / disc, MIN_OCCLUDER, 1.0);
            vFade = aSeparation < 0.0 ? 1.0 : smoothstep(FADE_START_DP, FADE_FULL_DP, aSeparation * uFocalPx / uPointScale);
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision highp float;

        uniform vec3 uMoonLight;       // unit, quad frame (x right, y up, z toward the viewer)
        uniform float uCameraMode;     // 0 enhanced view, 1 camera view: see OverlayBlend
        ${NightMode.GLSL_UNIFORM}

        in vec2 vPx;
        in vec3 vColor;
        flat in float vKind;
        flat in float vNominalPx;
        flat in float vGrowthPx;
        flat in float vHalo;
        flat in float vRadiusPx;
        flat in float vOccluder;
        flat in float vFade;
        out vec4 fragColor;

        const float MOON_DISC = ${BodyAppearance.MOON_DISC_FRACTION};
        const float SUN_DISC = ${BodyAppearance.SUN_DISC_FRACTION};
        const float PLANET_DISC = ${BodyAppearance.PLANET_DISC_FRACTION};
        const float EARTHSHINE = $EARTHSHINE;

        void main() {
            float rPx = length(vPx);
            if (rPx > vRadiusPx) discard;
            // Radius in the nominal sprite, for the halo: 0 at the centre, 1 at the edge, pushed out by the growth.
            float rn = max(rPx - vGrowthPx, 0.0) / vNominalPx;

            if (vFade <= 0.0) discard;
            if (vKind > 1.5 && vKind < 2.5) {
                // Moon: a sphere lit from uMoonLight, inside a halo that fades with the phase.
                float disc = MOON_DISC * vNominalPx + vGrowthPx;
                if (rPx < disc) {
                    vec2 q = vPx / disc;
                    vec3 normal = vec3(q, sqrt(max(1.0 - dot(q, q), 0.0)));
                    // The terminator's softness is set for the nominal disc; on a bigger one it is
                    // narrowed (to a quarter at most), or a screen-filling Moon would look out of focus.
                    float sharpen = clamp(MOON_DISC * vNominalPx / disc, 0.25, 1.0);
                    float lit = smoothstep(-0.02 * sharpen, 0.05 * sharpen, dot(normal, uMoonLight));
                    float edge = 1.0 - smoothstep(disc - 0.06 * MOON_DISC * vNominalPx, disc, rPx);
                    float shade = EARTHSHINE + (1.0 - EARTHSHINE) * lit;
                    float occluderPx = vOccluder * disc;
                    float opaque = 1.0 - smoothstep(occluderPx - 0.1 * min(occluderPx, MOON_DISC * vNominalPx), occluderPx, rPx);
                    fragColor = vec4(vColor * shade * edge, min(edge, opaque));
                } else {
                    float illuminated = 0.5 * (1.0 + uMoonLight.z);
                    float halo = 0.25 * illuminated * exp(-9.0 * (rn - MOON_DISC));
                    fragColor = vec4(vColor * halo, 0.0);
                }
            } else if (vKind > 0.5 && vKind < 1.5) {
                // Sun: white-hot core and a wide glow.
                float disc = SUN_DISC * vNominalPx + vGrowthPx;
                float core = 1.0 - smoothstep(disc - 0.2 * SUN_DISC * vNominalPx, disc, rPx);
                float glow = 0.75 * exp(-10.0 * rn * rn) + 0.25 * exp(-3.0 * rn * rn);
                fragColor = vec4(vColor * (core + glow), 0.0);
            } else {
                // Planet, or a planet's moon: a small tinted disc in a soft halo, steadier than a star's pinpoint.
                float disc = PLANET_DISC * vNominalPx + vGrowthPx;
                float soft = 0.4 * PLANET_DISC * vNominalPx;
                float core = 1.0 - smoothstep(disc - soft, disc + soft, rPx);
                // Outside the disc the halo is the nominal one, narrowed by vHalo.
                float rh = rPx < disc ? rn : PLANET_DISC + (rPx - disc) / (vNominalPx * vHalo);
                float glow = exp(-5.0 * rh * rh);
                fragColor = vec4(vColor * (0.75 * glow + 0.45 * core) * vFade, 0.0);
            }

            // Over a camera image the colour must be covered by alpha (premultiplied). The glows'
            // alpha 0 is only right on the opaque black of the enhanced view.
            fragColor.a = max(fragColor.a, uCameraMode * min(1.0, max(max(fragColor.r, fragColor.g), fragColor.b)));
            ${NightMode.GLSL_APPLY}
        }
    """.trimIndent()
}
