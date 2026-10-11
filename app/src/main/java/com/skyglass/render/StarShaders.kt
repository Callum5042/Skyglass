package com.skyglass.render

import com.skyglass.catalog.StarVisibility

/** GLSL ES 3.0 sources for drawing stars as soft round point sprites. */
object StarShaders {

    const val ATTRIB_POSITION = 0
    const val ATTRIB_SIZE = 1
    const val ATTRIB_COLOR = 2
    const val ATTRIB_VMAG = 3

    val VERTEX = """
        #version 300 es
        uniform mat4 uMvp;
        uniform float uPointScale;   // dp → px
        uniform float uMaxPointSize;
        uniform float uLimitMag;     // faintest star wanted (drawn at half strength): see StarVisibility

        layout(location = $ATTRIB_POSITION) in vec3 aPosition;
        layout(location = $ATTRIB_SIZE) in float aSizeDp;
        layout(location = $ATTRIB_COLOR) in vec3 aColor;
        layout(location = $ATTRIB_VMAG) in float aVmag;

        out vec3 vColor;

        void main() {
            // Same expression as StarVisibility.visibility. Colour is premultiplied by intensity
            // and, in the camera view, alpha follows the colour, so dimming it dims both.
            float visibility = 1.0 - smoothstep(uLimitMag - ${StarVisibility.FADE_WIDTH / 2}, uLimitMag + ${StarVisibility.FADE_WIDTH / 2}, aVmag);
            if (visibility <= 0.0) {
                gl_Position = vec4(2.0, 2.0, 2.0, 1.0);   // outside the clip volume: not drawn
                gl_PointSize = 1.0;
                vColor = vec3(0.0);
                return;
            }
            gl_Position = uMvp * vec4(aPosition, 1.0);
            gl_PointSize = min(aSizeDp * uPointScale, uMaxPointSize);
            vColor = aColor * visibility;
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
            vec2 p = gl_PointCoord * 2.0 - 1.0;
            float r2 = dot(p, p);
            if (r2 > 1.0) discard;
            // Gaussian-like falloff: bright core, soft edge. Colour is premultiplied by intensity.
            float falloff = exp(-4.0 * r2);
            vec3 c = vColor * falloff;
            // Enhanced view: alpha 1 on an opaque black surface. Camera view: the surface is
            // composited over the camera image, so alpha must cover the colour (premultiplied).
            float alpha = mix(1.0, min(1.0, max(max(c.r, c.g), c.b)), uCameraMode);
            fragColor = vec4(c, alpha);
            ${NightMode.GLSL_APPLY}
        }
    """.trimIndent()
}
