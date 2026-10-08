package com.skyglass.render

/** GLSL ES 3.0 sources for constellation lines: positions only, one flat colour. */
object LineShaders {

    const val ATTRIB_POSITION = 0

    /** Faint blue-grey, added to the black sky; stars drawn afterwards stay clearly brighter. */
    const val LINE_RED = 0.13f
    const val LINE_GREEN = 0.22f
    const val LINE_BLUE = 0.38f

    /**
     * A selected constellation's own lines, drawn over the faint ones: the same blue-grey hue turned
     * up to a pale, clear blue so it reads as "the same lines, lit". (Alpha follows the camera-mode
     * rule like every line, so over the camera image it is about as opaque as the horizon at most.)
     */
    const val HIGHLIGHT_RED = 0.42f
    const val HIGHLIGHT_GREEN = 0.62f
    const val HIGHLIGHT_BLUE = 1.00f

    /**
     * The horizon: a muted teal-green, a different hue from the blue-grey constellation lines so the
     * two never read as the same kind of thing, and clearly brighter than them. (Drawn 2 px wide
     * where the GPU allows wide lines; see `SkyRenderer`.)
     */
    const val HORIZON_RED = 0.20f
    const val HORIZON_GREEN = 0.55f
    const val HORIZON_BLUE = 0.42f

    /** The altitude/azimuth grid: the horizon's hue at about a fifth of its strength, fainter than the constellation lines. */
    const val GRID_RED = 0.05f
    const val GRID_GREEN = 0.13f
    const val GRID_BLUE = 0.10f

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
        uniform float uCameraMode;   // 0 enhanced view, 1 camera view: see OverlayBlend
        out vec4 fragColor;

        void main() {
            float alpha = mix(1.0, min(1.0, max(max(uColor.r, uColor.g), uColor.b)), uCameraMode);
            fragColor = vec4(uColor, alpha);
        }
    """.trimIndent()
}
