package com.skyglass.render

/**
 * GLSL ES 3.0 sources for the constellation illustrations: a triangle mesh per image in the equatorial
 * frame of date (see `ConstellationArtMesh`), textured with the image's brightness (a one-channel
 * texture) times `uColor`, the tint times the strength (`ConstellationArtAppearance`). The art is
 * drawn on black, so black adds nothing; the outer `uEdgeFade` of the image fades to nothing so art
 * that runs to the edge has no hard cut. In the camera view alpha covers the colour as for the stars
 * (see [OverlayBlend]). Draw with `OverlayBlend.stars`.
 */
object ConstellationArtShaders {

    const val ATTRIB_POSITION = 0
    const val ATTRIB_TEXCOORD = 1

    val VERTEX = """
        #version 300 es
        uniform mat4 uMvp;

        layout(location = $ATTRIB_POSITION) in vec3 aPosition;   // unit, equatorial of date
        layout(location = $ATTRIB_TEXCOORD) in vec2 aTexCoord;   // (0, 0) top-left of the image

        out vec2 vTexCoord;

        void main() {
            gl_Position = uMvp * vec4(aPosition, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform sampler2D uArt;
        uniform vec3 uColor;         // tint times strength
        uniform float uEdgeFade;     // fraction of the image over which its edges fade out
        uniform float uCameraMode;   // 0 enhanced view, 1 camera view: see OverlayBlend

        in vec2 vTexCoord;
        out vec4 fragColor;

        void main() {
            vec2 t = vTexCoord;
            float edge = min(min(t.x, 1.0 - t.x), min(t.y, 1.0 - t.y));
            float lum = texture(uArt, t).r * smoothstep(0.0, uEdgeFade, edge);
            vec3 c = uColor * lum;
            float alpha = mix(1.0, min(1.0, max(max(c.r, c.g), c.b)), uCameraMode);
            fragColor = vec4(c, alpha);
        }
    """.trimIndent()
}
