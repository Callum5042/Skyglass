package com.skyglass.render

/** GLSL ES 3.0 sources for drawing stars as soft round point sprites. */
object StarShaders {

    const val ATTRIB_POSITION = 0
    const val ATTRIB_SIZE = 1
    const val ATTRIB_COLOR = 2

    val VERTEX = """
        #version 300 es
        uniform mat4 uMvp;
        uniform float uPointScale;   // dp → px
        uniform float uMaxPointSize;

        layout(location = $ATTRIB_POSITION) in vec3 aPosition;
        layout(location = $ATTRIB_SIZE) in float aSizeDp;
        layout(location = $ATTRIB_COLOR) in vec3 aColor;

        out vec3 vColor;

        void main() {
            gl_Position = uMvp * vec4(aPosition, 1.0);
            gl_PointSize = min(aSizeDp * uPointScale, uMaxPointSize);
            vColor = aColor;
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision mediump float;

        in vec3 vColor;
        out vec4 fragColor;

        void main() {
            vec2 p = gl_PointCoord * 2.0 - 1.0;
            float r2 = dot(p, p);
            if (r2 > 1.0) discard;
            // Gaussian-like falloff: bright core, soft edge. Colour is premultiplied by intensity.
            float falloff = exp(-4.0 * r2);
            fragColor = vec4(vColor * falloff, 1.0);
        }
    """.trimIndent()
}
