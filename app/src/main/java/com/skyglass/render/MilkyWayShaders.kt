package com.skyglass.render

/**
 * GLSL ES 3.0 sources for the Milky Way band: a triangle mesh in the equatorial frame of date whose
 * vertices carry the band's premultiplied colour (see `MilkyWayMesh`), interpolated across the faces.
 * `uGain` scales it (less over a camera image: `MilkyWayAppearance.CAMERA_GAIN`). The fragment
 * shader adds sub-quantisation noise, because a smooth gradient at an intensity of 0.05 or so spans only
 * a dozen 8-bit levels and would otherwise show as bands. In the camera view alpha covers the
 * colour as for the stars (see [OverlayBlend]). Draw with `OverlayBlend.stars`.
 */
object MilkyWayShaders {

    const val ATTRIB_POSITION = 0
    const val ATTRIB_COLOR = 1

    val VERTEX = """
        #version 300 es
        uniform mat4 uMvp;
        uniform float uGain;

        layout(location = $ATTRIB_POSITION) in vec3 aPosition;   // unit, equatorial of date
        layout(location = $ATTRIB_COLOR) in vec3 aColor;         // premultiplied

        out vec3 vColor;

        void main() {
            gl_Position = uMvp * vec4(aPosition, 1.0);
            vColor = aColor * uGain;
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform float uCameraMode;   // 0 enhanced view, 1 camera view: see OverlayBlend
        ${NightMode.GLSL_UNIFORM}

        in vec3 vColor;
        out vec4 fragColor;

        void main() {
            // Interleaved gradient noise (Jimenez 2014), +-0.5 of an 8-bit step, only where there is band.
            float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5;
            float lit = step(0.0005, max(max(vColor.r, vColor.g), vColor.b));
            vec3 c = max(vColor + vec3(noise / 255.0 * lit), vec3(0.0));
            float alpha = mix(1.0, min(1.0, max(max(c.r, c.g), c.b)), uCameraMode);
            fragColor = vec4(c, alpha);
            ${NightMode.GLSL_APPLY}
        }
    """.trimIndent()
}
