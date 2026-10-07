package com.skyglass.render

/** GLSL ES 3.0 sources for constellation lines: positions only, one flat colour. */
object LineShaders {

    const val ATTRIB_POSITION = 0

    /** Faint blue-grey, added to the black sky; stars drawn afterwards stay clearly brighter. */
    const val LINE_RED = 0.13f
    const val LINE_GREEN = 0.22f
    const val LINE_BLUE = 0.38f

    val VERTEX = """
        #version 300 es
        uniform mat4 uMvp;

        layout(location = $ATTRIB_POSITION) in vec3 aPosition;

        void main() {
            gl_Position = uMvp * vec4(aPosition, 1.0);
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform vec3 uColor;
        out vec4 fragColor;

        void main() {
            fragColor = vec4(uColor, 1.0);
        }
    """.trimIndent()
}
