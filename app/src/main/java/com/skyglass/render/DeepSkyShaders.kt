package com.skyglass.render

import com.skyglass.catalog.DeepSkyAppearance

/**
 * GLSL ES 3.0 sources for deep-sky objects. Each object is a quad on the sky (see `DeepSkyVertices`),
 * built in the vertex shader in the tangent plane at the object: its long axis along the position
 * angle (from north through east, in the equatorial frame of date, where north is +Z and east is
 * z × direction), its semi-axes in tangent-plane units, enlarged until the long semi-axis is at least
 * `uMinRadiusPx` pixels (`DeepSkyAppearance.drawnSemiAxes` does the same sum on the CPU for taps and
 * labels). The fragment shader draws a symbol per `glyph` in the object's own frame, where the
 * ellipse's edge is at distance 1: a soft glow with a nucleus for galaxies, a dense ball for globular
 * clusters, a dotted ring for open clusters, a soft disc for nebulae, a ring round a centre for
 * planetary nebulae, a thin ring for supernova remnants and a faint dashed outline for dark nebulae.
 *
 * Output is premultiplied colour; in the camera view (`uCameraMode` 1) alpha covers the colour as for
 * the stars (see [OverlayBlend]), otherwise alpha is 1 on the opaque black surface. Draw with
 * `OverlayBlend.stars`.
 */
object DeepSkyShaders {

    const val ATTRIB_POSITION = 0
    const val ATTRIB_CORNER = 1
    const val ATTRIB_SEMI_MAJOR = 2
    const val ATTRIB_SEMI_MINOR = 3
    const val ATTRIB_POSITION_ANGLE = 4
    const val ATTRIB_COLOR = 5
    const val ATTRIB_GLYPH = 6

    val VERTEX = """
        #version 300 es
        uniform mat4 uMvp;
        uniform float uFocalPx;       // focal length of the projection in pixels
        uniform float uMinRadiusPx;   // smallest drawn radius of the long axis

        layout(location = $ATTRIB_POSITION) in vec3 aPosition;       // unit, equatorial of date
        layout(location = $ATTRIB_CORNER) in vec2 aCorner;           // (±1, ±1); x along the long axis
        layout(location = $ATTRIB_SEMI_MAJOR) in float aSemiMajor;   // tangent-plane units (radians)
        layout(location = $ATTRIB_SEMI_MINOR) in float aSemiMinor;
        layout(location = $ATTRIB_POSITION_ANGLE) in float aAngle;   // from north through east
        layout(location = $ATTRIB_COLOR) in vec3 aColor;
        layout(location = $ATTRIB_GLYPH) in float aGlyph;

        out vec2 vUv;                 // position in units of the semi-axes
        out vec3 vColor;
        out float vRingWidth;         // keeps thin rings at least about a pixel wide
        flat out float vGlyph;

        const float EXTENT = ${DeepSkyAppearance.QUAD_EXTENT};

        void main() {
            float scale = max(1.0, (uMinRadiusPx / uFocalPx) / aSemiMajor);
            float a = aSemiMajor * scale;
            float b = aSemiMinor * scale;
            vec3 east = normalize(vec3(-aPosition.y, aPosition.x, 0.0));
            vec3 north = cross(aPosition, east);
            vec3 axisMajor = north * cos(aAngle) + east * sin(aAngle);
            vec3 axisMinor = east * cos(aAngle) - north * sin(aAngle);
            vec3 p = aPosition + EXTENT * (aCorner.x * a * axisMajor + aCorner.y * b * axisMinor);
            gl_Position = uMvp * vec4(p, 1.0);
            vUv = aCorner * EXTENT;
            vColor = aColor;
            vRingWidth = max(0.06, 1.3 / (a * uFocalPx));
            vGlyph = aGlyph;
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform float uCameraMode;   // 0 enhanced view, 1 camera view: see OverlayBlend
        ${NightMode.GLSL_UNIFORM}

        in vec2 vUv;
        in vec3 vColor;
        in float vRingWidth;
        flat in float vGlyph;
        out vec4 fragColor;

        const float EXTENT = ${DeepSkyAppearance.QUAD_EXTENT};

        // 1 on a ring of radius `radius`, about `width` thick, fading out smoothly on both sides.
        float ring(float r, float radius, float width) {
            float d = (r - radius) / width;
            return exp(-d * d);
        }

        // Alternating dashes round the ring, so clusters read differently from nebulae and remnants.
        // (atan(0, 0) is undefined in GLSL, hence the nudge: p is exactly zero at the centre pixel.)
        float dashes(vec2 p, float count) {
            return smoothstep(-0.3, 0.3, sin(atan(p.y, p.x + 1e-6) * count));
        }

        void main() {
            float r2 = dot(vUv, vUv);
            float r = sqrt(r2);
            if (r > EXTENT) discard;

            float f;
            if (vGlyph < 0.5) {
                // Galaxy: a glow with a brighter nucleus.
                f = 0.70 * exp(-3.5 * r2) + 0.55 * exp(-22.0 * r2);
            } else if (vGlyph < 1.5) {
                // Globular cluster: a dense ball, brighter toward the middle, in a faint halo.
                f = 0.95 * exp(-9.0 * r2) + 0.30 * exp(-2.5 * r2);
            } else if (vGlyph < 2.5) {
                // Open cluster, asterism: a dotted ring over a faint disc.
                f = 0.85 * ring(r, 0.92, vRingWidth) * dashes(vUv, 10.0) + 0.10 * (1.0 - smoothstep(0.7, 1.0, r));
            } else if (vGlyph < 3.5) {
                // Nebula, star cloud: a soft-edged disc, brighter inside.
                f = 0.65 * (1.0 - smoothstep(0.15, 1.05, r)) + 0.20 * (1.0 - smoothstep(0.0, 0.5, r));
            } else if (vGlyph < 4.5) {
                // Planetary nebula: a ring round a bright centre.
                f = 0.85 * ring(r, 0.65, max(vRingWidth, 0.18)) + 0.55 * exp(-30.0 * r2);
            } else if (vGlyph < 5.5) {
                // Supernova remnant: a thin continuous ring.
                f = 0.85 * ring(r, 0.88, vRingWidth) + 0.10 * (1.0 - smoothstep(0.6, 1.0, r));
            } else {
                // Dark nebula: a faint dashed outline.
                f = 0.80 * ring(r, 0.95, vRingWidth) * dashes(vUv, 6.0);
            }

            vec3 c = vColor * f;
            // Camera view: alpha covers the colour (premultiplied), so the symbol only veils the image
            // in proportion to its own strength.
            float alpha = mix(1.0, min(1.0, max(max(c.r, c.g), c.b)), uCameraMode);
            fragColor = vec4(c, alpha);
            ${NightMode.GLSL_APPLY}
        }
    """.trimIndent()
}
