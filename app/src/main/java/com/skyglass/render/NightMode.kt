package com.skyglass.render

/**
 * Night mode: everything drawn in red only, so the eyes stay dark-adapted (the rods that see faint
 * stars hardly respond to long-wavelength light). Everything is mapped the same way: the Rec. 601
 * luma goes into red, green and blue go to zero, alpha is kept, so white stays full red and things
 * keep the brightness relative to each other that they have in colour. (Taking the brightest channel
 * instead was tried in an offline render: it lifted the dim blue constellation lines to nearly the
 * stars' strength. The star colours are desaturated, so luma costs a blue-white star only ~15%.)
 * Three parts, all switched together:
 *
 * - **The sky (GL).** Each fragment shader ends with [GLSL_APPLY], which [sky] mirrors. Luma is at
 *   most the largest channel, which is at most alpha for valid premultiplied colour, so the camera
 *   view's rule (see [OverlayBlend]) still holds; alpha is not lowered to match, so in the camera view
 *   a coloured line or glow veils the (already dimmed) image slightly more than its red would need.
 * - **The Compose UI** (labels, cards, photos, charts): filtered through [UI_COLOR_MATRIX], the same
 *   mapping as a colour matrix.
 * - **The camera image**, which is a separate surface under the GL one and cannot be recoloured from
 *   above (the compositor has a single alpha, not one per channel). Instead the GL surface is cleared
 *   to black with alpha [CAMERA_IMAGE_COVER], dimming the image; see [OverlayBlend.clearColor].
 */
object NightMode {

    /** Declares the switch, 0 or 1; goes with the other uniforms of every fragment shader. */
    const val GLSL_UNIFORM = "uniform float uNightMode;   // 0 normal colours, 1 night mode: see NightMode"

    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    /** The last statement of every fragment shader: GLSL of [sky]. */
    const val GLSL_APPLY =
        "fragColor = mix(fragColor, vec4(dot(fragColor.rgb, vec3($LUMA_R, $LUMA_G, $LUMA_B)), 0.0, 0.0, fragColor.a), uNightMode);"

    /** CPU reference of [GLSL_APPLY] with the switch on, on premultiplied colour (the mapping is linear, so either works). */
    fun sky(c: Rgba): Rgba = ui(c)

    /**
     * Android/Compose colour matrix, 4x5 row-major on 0..255 channels (offsets in the fifth column):
     * red = luma, green = blue = 0, alpha unchanged.
     */
    val UI_COLOR_MATRIX: FloatArray
        get() = floatArrayOf(
            LUMA_R, LUMA_G, LUMA_B, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

    /** CPU reference of [UI_COLOR_MATRIX], on straight (not premultiplied) colour in 0..1. */
    fun ui(c: Rgba): Rgba = Rgba(LUMA_R * c.r + LUMA_G * c.g + LUMA_B * c.b, 0.0, 0.0, c.a)

    /**
     * How much of the camera image the cleared GL surface covers in night mode: the image shows at a
     * quarter of its brightness. Untuned: judge on a device at night, where auto-exposure already
     * lifts a dark scene a long way.
     */
    const val CAMERA_IMAGE_COVER = 0.75
}
