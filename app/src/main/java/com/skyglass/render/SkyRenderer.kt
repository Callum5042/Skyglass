package com.skyglass.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.skyglass.astro.AstroTime
import com.skyglass.catalog.BodyAppearance
import com.skyglass.catalog.BodyVertices
import com.skyglass.catalog.ArtImage
import com.skyglass.catalog.ConstellationArtAppearance
import com.skyglass.catalog.ConstellationArtMesh
import com.skyglass.catalog.ConstellationSky
import com.skyglass.catalog.DeepSkyAppearance
import com.skyglass.catalog.DeepSkyVertices
import com.skyglass.catalog.MilkyWayAppearance
import com.skyglass.catalog.MilkyWayMesh
import com.skyglass.catalog.StarVertices
import com.skyglass.math.Matrix4
import com.skyglass.math.Quaternion
import com.skyglass.math.Vector3
import com.skyglass.sensors.OrientationFilter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min
import kotlin.math.tan

/**
 * Draws the Milky Way band, the constellation illustrations, the horizon and reference grid, the
 * constellation lines, the deep-sky objects, the star field, then the Sun, Moon and planets. Stars,
 * lines and bodies live in VBOs in the equatorial frame of date; each frame only the MVP matrix (and,
 * while zooming, the field of view in it) changes, built by [SkyViewMath]. The horizon guides are
 * fixed to the observer, so they live in true ENU and are drawn with the same view minus the sky's rotation
 * ([SkyViewMath.horizonModelViewProjection]). The star, line and guide buffers are static; the body
 * buffer is replaced every few seconds as the bodies move, and re-uploaded when its array instance
 * changes.
 *
 * @param orientation latest raw device→magnetic-ENU rotation; smoothed here, once per frame
 * @param displayRotation current `Surface.ROTATION_*` value
 * @param pointScale pixels per dp
 */
class SkyRenderer(
    private val inputs: SkyRenderInputs,
    private val orientation: () -> Quaternion?,
    private val displayRotation: () -> Int,
    private val pointScale: Float,
) : GLSurfaceView.Renderer {

    private val filter = OrientationFilter()
    private val mvpFloats = FloatArray(16)
    private val horizonMvpFloats = FloatArray(16)

    private lateinit var program: ShaderProgram
    private var uMvp = -1
    private var uPointScale = -1
    private var uMaxPointSize = -1
    private var uCameraMode = -1
    private var uNightMode = -1
    private var uLimitMag = -1

    private var vbo = 0
    private var uploadedVertices: FloatArray? = null
    private var starCount = 0

    private lateinit var lineProgram: ShaderProgram
    private var uLineMvp = -1
    private var uLineColor = -1
    private var uLineCameraMode = -1
    private var uLineNightMode = -1
    private var lineVbo = 0
    private var uploadedLines: FloatArray? = null
    private var lineVertexCount = 0

    // Horizon line and reference grid, in one static buffer: the grid first, then the horizon.
    private var guideVbo = 0
    private var guidesUploaded = false
    private var gridVertexCount = 0
    private var horizonVertexCount = 0

    private lateinit var bodyProgram: ShaderProgram
    private var uBodyMvp = -1
    private var uBodyPointScale = -1
    private var uBodyFocalPx = -1
    private var uBodyViewportPx = -1
    private var uBodyMoonLight = -1
    private var uBodyCameraMode = -1
    private var uBodyNightMode = -1
    private var bodyVbo = 0
    private var uploadedBodies: FloatArray? = null
    private var bodyCount = 0
    private var moonDirection: Vector3? = null
    private var moonSunDirection: Vector3? = null

    // Deep-sky objects: two triangles each, built in the vertex shader (see DeepSkyShaders).
    private lateinit var deepSkyProgram: ShaderProgram
    private var uDeepSkyMvp = -1
    private var uDeepSkyFocalPx = -1
    private var uDeepSkyMinRadiusPx = -1
    private var uDeepSkyCameraMode = -1
    private var uDeepSkyNightMode = -1
    private var deepSkyVbo = 0
    private var uploadedDeepSky: FloatArray? = null
    private var deepSkyVertexCount = 0

    // The Milky Way band: an indexed triangle mesh.
    private lateinit var milkyWayProgram: ShaderProgram
    private var uMilkyWayMvp = -1
    private var uMilkyWayGain = -1
    private var uMilkyWayCameraMode = -1
    private var uMilkyWayNightMode = -1
    private var milkyWayVbo = 0
    private var milkyWayIbo = 0
    private var uploadedMilkyWay: MilkyWayMesh? = null
    private var milkyWayIndexCount = 0

    // Constellation illustrations: one indexed mesh, one single-channel texture per image.
    private lateinit var artProgram: ShaderProgram
    private var uArtMvp = -1
    private var uArtColor = -1
    private var uArtEdgeFade = -1
    private var uArtCameraMode = -1
    private var uArtNightMode = -1
    private var uArtSampler = -1
    private var artVbo = 0
    private var artIbo = 0
    private var uploadedArtMesh: ConstellationArtMesh? = null
    private var uploadedArtImages: List<ArtImage>? = null
    private var artTextures = IntArray(0)

    /** Textures go up a few per frame (see [ART_TEXTURES_PER_FRAME]); images from this index on are not uploaded yet. */
    private var artTexturesReady = 0

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var aspect = 1.0
    private var maxPointSize = 64f
    private var lastFrameNanos = 0L

    /** Every fragment shader's `uNightMode` for the frame being drawn (see [NightMode]). */
    private var nightModeUniform = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)

        program = ShaderProgram(StarShaders.VERTEX, StarShaders.FRAGMENT)
        uMvp = program.uniformLocation("uMvp")
        uPointScale = program.uniformLocation("uPointScale")
        uMaxPointSize = program.uniformLocation("uMaxPointSize")
        uCameraMode = program.uniformLocation("uCameraMode")
        uNightMode = program.uniformLocation("uNightMode")
        uLimitMag = program.uniformLocation("uLimitMag")

        lineProgram = ShaderProgram(LineShaders.VERTEX, LineShaders.FRAGMENT)
        uLineMvp = lineProgram.uniformLocation("uMvp")
        uLineColor = lineProgram.uniformLocation("uColor")
        uLineCameraMode = lineProgram.uniformLocation("uCameraMode")
        uLineNightMode = lineProgram.uniformLocation("uNightMode")

        bodyProgram = ShaderProgram(BodyShaders.VERTEX, BodyShaders.FRAGMENT)
        uBodyMvp = bodyProgram.uniformLocation("uMvp")
        uBodyPointScale = bodyProgram.uniformLocation("uPointScale")
        uBodyFocalPx = bodyProgram.uniformLocation("uFocalPx")
        uBodyViewportPx = bodyProgram.uniformLocation("uViewportPx")
        uBodyMoonLight = bodyProgram.uniformLocation("uMoonLight")
        uBodyCameraMode = bodyProgram.uniformLocation("uCameraMode")
        uBodyNightMode = bodyProgram.uniformLocation("uNightMode")

        deepSkyProgram = ShaderProgram(DeepSkyShaders.VERTEX, DeepSkyShaders.FRAGMENT)
        uDeepSkyMvp = deepSkyProgram.uniformLocation("uMvp")
        uDeepSkyFocalPx = deepSkyProgram.uniformLocation("uFocalPx")
        uDeepSkyMinRadiusPx = deepSkyProgram.uniformLocation("uMinRadiusPx")
        uDeepSkyCameraMode = deepSkyProgram.uniformLocation("uCameraMode")
        uDeepSkyNightMode = deepSkyProgram.uniformLocation("uNightMode")

        milkyWayProgram = ShaderProgram(MilkyWayShaders.VERTEX, MilkyWayShaders.FRAGMENT)
        uMilkyWayMvp = milkyWayProgram.uniformLocation("uMvp")
        uMilkyWayGain = milkyWayProgram.uniformLocation("uGain")
        uMilkyWayCameraMode = milkyWayProgram.uniformLocation("uCameraMode")
        uMilkyWayNightMode = milkyWayProgram.uniformLocation("uNightMode")

        artProgram = ShaderProgram(ConstellationArtShaders.VERTEX, ConstellationArtShaders.FRAGMENT)
        uArtMvp = artProgram.uniformLocation("uMvp")
        uArtColor = artProgram.uniformLocation("uColor")
        uArtEdgeFade = artProgram.uniformLocation("uEdgeFade")
        uArtCameraMode = artProgram.uniformLocation("uCameraMode")
        uArtNightMode = artProgram.uniformLocation("uNightMode")
        uArtSampler = artProgram.uniformLocation("uArt")

        val ids = IntArray(9)
        GLES30.glGenBuffers(9, ids, 0)
        vbo = ids[0]
        lineVbo = ids[1]
        bodyVbo = ids[2]
        guideVbo = ids[3]
        deepSkyVbo = ids[4]
        milkyWayVbo = ids[5]
        milkyWayIbo = ids[6]
        artVbo = ids[7]
        artIbo = ids[8]
        // A new context has empty buffers and no textures, even if the data was uploaded to a previous one.
        guidesUploaded = false
        uploadedArtMesh = null
        uploadedArtImages = null
        artTextures = IntArray(0)
        artTexturesReady = 0
        uploadedDeepSky = null
        uploadedMilkyWay = null
        uploadedVertices = null
        uploadedLines = null
        uploadedBodies = null

        val range = FloatArray(2)
        GLES30.glGetFloatv(GLES30.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
        maxPointSize = range[1]

        filter.reset()
        lastFrameNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        aspect = if (height > 0) width.toDouble() / height else 1.0
    }

    /** Applies the factors chosen by [OverlayBlend], which is where the reasoning for each mode lives. */
    private fun setBlending(factors: OverlayBlend.Factors) {
        GLES30.glBlendFuncSeparate(
            glFactor(factors.srcRgb), glFactor(factors.dstRgb), glFactor(factors.srcAlpha), glFactor(factors.dstAlpha),
        )
    }

    private fun glFactor(factor: OverlayBlend.Factor): Int = when (factor) {
        OverlayBlend.Factor.ZERO -> GLES30.GL_ZERO
        OverlayBlend.Factor.ONE -> GLES30.GL_ONE
        OverlayBlend.Factor.ONE_MINUS_SRC_ALPHA -> GLES30.GL_ONE_MINUS_SRC_ALPHA
    }

    override fun onDrawFrame(gl: GL10?) {
        // Opaque black for the enhanced view; fully transparent over a camera image, where what is
        // drawn is premultiplied colour with a real alpha, composited over the image (OverlayBlend).
        // In night mode the camera image is dimmed by clearing to translucent black (see NightMode).
        val cameraMode = inputs.cameraMode
        val nightMode = inputs.nightMode
        val clear = OverlayBlend.clearColor(cameraMode, nightMode)
        GLES30.glClearColor(clear.r.toFloat(), clear.g.toFloat(), clear.b.toFloat(), clear.a.toFloat())
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val cameraModeUniform = if (cameraMode) 1f else 0f
        nightModeUniform = if (nightMode) 1f else 0f
        // Each pass enables the vertex arrays it uses; none may inherit another pass's, whose buffer may be smaller.
        for (attribute in 0..MAX_VERTEX_ATTRIBUTE) GLES30.glDisableVertexAttribArray(attribute)
        setBlending(OverlayBlend.stars(cameraMode))
        // Over the camera the sky must keep up with the image, which is not smoothed at any zoom.
        // On black, zooming in magnifies hand shake, so the filter smooths more (see SkyZoom).
        val fovYDeg = inputs.fovYDeg
        filter.timeConstantSeconds = if (cameraMode) {
            OrientationFilter.CAMERA_TIME_CONSTANT_SECONDS
        } else {
            SkyZoom.smoothingSeconds(fovYDeg, OrientationFilter.DEFAULT_TIME_CONSTANT_SECONDS)
        }

        val now = System.nanoTime()
        val dtSeconds = if (lastFrameNanos == 0L) 0.0 else (now - lastFrameNanos) / 1e9
        lastFrameNanos = now

        val vertices = inputs.starVertices ?: return
        val observer = inputs.observer ?: return
        val rawOrientation = orientation() ?: return

        if (vertices !== uploadedVertices) upload(vertices)

        val smoothed = filter.update(rawOrientation, dtSeconds)
        val lstDeg = AstroTime.localSiderealTimeDeg(
            AstroTime.julianDate(System.currentTimeMillis()), observer.longitudeDeg,
        )
        val mvp = SkyViewMath.modelViewProjection(
            deviceToMagneticEnu = smoothed,
            declinationDeg = observer.declinationDeg,
            lstDeg = lstDeg,
            latitudeDeg = observer.latitudeDeg,
            displayRotation = displayRotation(),
            fovYDeg = fovYDeg,
            aspect = aspect,
        )

        inputs.lastMvp = mvp
        mvp.toColumnMajorFloatArray(mvpFloats)

        // The same orientation, for things fixed to the horizon: no sidereal time, no latitude.
        val horizonMvp = SkyViewMath.horizonModelViewProjection(
            deviceToMagneticEnu = smoothed,
            declinationDeg = observer.declinationDeg,
            displayRotation = displayRotation(),
            fovYDeg = fovYDeg,
            aspect = aspect,
        )
        inputs.lastHorizonMvp = horizonMvp
        horizonMvp.toColumnMajorFloatArray(horizonMvpFloats)

        // The Milky Way is the faintest and widest thing on the sky: first, under everything else.
        if (inputs.showMilkyWay) inputs.milkyWayMesh?.let { drawMilkyWay(it, cameraMode, cameraModeUniform) }

        // The constellation illustrations: a backdrop, under every line and point.
        val artMesh = inputs.constellationArtMesh
        val artImages = inputs.constellationArtImages
        if (artMesh != null && artImages != null) drawConstellationArt(artMesh, artImages, fovYDeg, cameraMode, cameraModeUniform)

        // The horizon and grid next.
        drawGuides(cameraModeUniform, inputs.showHorizon, inputs.showGrid)

        // Constellation lines next, so stars are drawn over them. A selected constellation is lit
        // even when the lines are switched off, since the user has just asked for that one.
        inputs.constellationLines?.let { lines ->
            if (lines !== uploadedLines) uploadLines(lines)
            drawLines(cameraModeUniform, inputs.showConstellations, inputs.highlightedConstellation)
        }

        // Deep-sky objects over the lines and under the stars, which are what the eye goes to.
        if (inputs.showDeepSky) inputs.deepSkyVertices?.let { drawDeepSky(it, fovYDeg, cameraModeUniform) }

        program.use()
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvpFloats, 0)
        GLES30.glUniform1f(uPointScale, pointScale)
        GLES30.glUniform1f(uMaxPointSize, maxPointSize)
        GLES30.glUniform1f(uCameraMode, cameraModeUniform)
        GLES30.glUniform1f(uNightMode, nightModeUniform)
        GLES30.glUniform1f(uLimitMag, inputs.limitingMagnitude.toFloat())

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        bindAttribute(StarShaders.ATTRIB_POSITION, 3, StarVertices.POSITION_OFFSET)
        bindAttribute(StarShaders.ATTRIB_SIZE, 1, StarVertices.SIZE_OFFSET)
        bindAttribute(StarShaders.ATTRIB_COLOR, 3, StarVertices.COLOR_OFFSET)
        bindAttribute(StarShaders.ATTRIB_VMAG, 1, StarVertices.VMAG_OFFSET)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, starCount)

        inputs.bodyVertices?.let { bodies ->
            if (bodies !== uploadedBodies) uploadBodies(bodies)
            drawBodies(mvp, fovYDeg, cameraModeUniform)
        }
    }

    /**
     * The Milky Way band, with the blending of the stars. Over a camera image it is drawn at
     * [MilkyWayAppearance.CAMERA_GAIN] of its strength so the image stays readable.
     */
    private fun drawMilkyWay(mesh: MilkyWayMesh, cameraMode: Boolean, cameraModeUniform: Float) {
        if (mesh !== uploadedMilkyWay) uploadMilkyWay(mesh)
        milkyWayProgram.use()
        GLES30.glUniformMatrix4fv(uMilkyWayMvp, 1, false, mvpFloats, 0)
        GLES30.glUniform1f(uMilkyWayGain, if (cameraMode) MilkyWayAppearance.CAMERA_GAIN else 1f)
        GLES30.glUniform1f(uMilkyWayCameraMode, cameraModeUniform)
        GLES30.glUniform1f(uMilkyWayNightMode, nightModeUniform)
        val stride = MilkyWayMesh.FLOATS_PER_VERTEX * Float.SIZE_BYTES
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, milkyWayVbo)
        GLES30.glEnableVertexAttribArray(MilkyWayShaders.ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(
            MilkyWayShaders.ATTRIB_POSITION, 3, GLES30.GL_FLOAT, false, stride, MilkyWayMesh.POSITION_OFFSET * Float.SIZE_BYTES,
        )
        GLES30.glEnableVertexAttribArray(MilkyWayShaders.ATTRIB_COLOR)
        GLES30.glVertexAttribPointer(
            MilkyWayShaders.ATTRIB_COLOR, 3, GLES30.GL_FLOAT, false, stride, MilkyWayMesh.COLOR_OFFSET * Float.SIZE_BYTES,
        )
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, milkyWayIbo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, milkyWayIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        // Later passes use this slot with an array of their own, or none.
        GLES30.glDisableVertexAttribArray(MilkyWayShaders.ATTRIB_COLOR)
    }

    private fun uploadMilkyWay(mesh: MilkyWayMesh) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, milkyWayVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertices.size * Float.SIZE_BYTES, toBuffer(mesh.vertices), GLES30.GL_STATIC_DRAW)
        val indexBuffer = ByteBuffer.allocateDirect(mesh.indices.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(mesh.indices)
            .also { it.position(0) }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, milkyWayIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * Short.SIZE_BYTES, indexBuffer, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        uploadedMilkyWay = mesh
        milkyWayIndexCount = mesh.indices.size
    }

    /**
     * The illustrations (at [ConstellationArtAppearance.GAIN], none while switched off) and the selected
     * constellation's (at [ConstellationArtAppearance.HIGHLIGHT_GAIN], whatever the switch says), with
     * the blending of the stars, faded out as the view zooms in past what their resolution allows
     * ([ConstellationArtAppearance.zoomFade]). Textures go up a few per frame, so the art fills in over
     * the first frames rather than holding up one.
     */
    private fun drawConstellationArt(
        mesh: ConstellationArtMesh,
        images: List<ArtImage>,
        fovYDeg: Double,
        cameraMode: Boolean,
        cameraModeUniform: Float,
    ) {
        val showAll = inputs.showConstellationArt
        val highlight = inputs.highlightedArtImage
        if (!showAll && highlight < 0) return
        val zoomFade = ConstellationArtAppearance.zoomFade(fovYDeg)
        if (zoomFade <= 0f) return
        if (mesh !== uploadedArtMesh) uploadArtMesh(mesh)
        if (images !== uploadedArtImages) replaceArtTextures(images)
        uploadArtTextures(images)
        val count = min(artTexturesReady, mesh.art.size)
        if (count == 0) return

        artProgram.use()
        GLES30.glUniformMatrix4fv(uArtMvp, 1, false, mvpFloats, 0)
        GLES30.glUniform1f(uArtEdgeFade, ConstellationArtAppearance.EDGE_FADE)
        GLES30.glUniform1f(uArtCameraMode, cameraModeUniform)
        GLES30.glUniform1f(uArtNightMode, nightModeUniform)
        GLES30.glUniform1i(uArtSampler, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val stride = ConstellationArtMesh.FLOATS_PER_VERTEX * Float.SIZE_BYTES
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, artVbo)
        GLES30.glEnableVertexAttribArray(ConstellationArtShaders.ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(
            ConstellationArtShaders.ATTRIB_POSITION, 3, GLES30.GL_FLOAT, false, stride,
            ConstellationArtMesh.POSITION_OFFSET * Float.SIZE_BYTES,
        )
        GLES30.glEnableVertexAttribArray(ConstellationArtShaders.ATTRIB_TEXCOORD)
        GLES30.glVertexAttribPointer(
            ConstellationArtShaders.ATTRIB_TEXCOORD, 2, GLES30.GL_FLOAT, false, stride,
            ConstellationArtMesh.TEXCOORD_OFFSET * Float.SIZE_BYTES,
        )
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, artIbo)
        val tint = ConstellationArtAppearance.TINT
        val viewGain = zoomFade * if (cameraMode) ConstellationArtAppearance.CAMERA_GAIN else 1f
        for (i in 0 until count) {
            val lit = i == highlight
            if (!showAll && !lit) continue
            val gain = viewGain * if (lit) ConstellationArtAppearance.HIGHLIGHT_GAIN else ConstellationArtAppearance.GAIN
            GLES30.glUniform3f(uArtColor, tint[0] * gain, tint[1] * gain, tint[2] * gain)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, artTextures[i])
            GLES30.glDrawElements(
                GLES30.GL_TRIANGLES, ConstellationArtMesh.INDICES_PER_IMAGE, GLES30.GL_UNSIGNED_SHORT,
                i * ConstellationArtMesh.INDICES_PER_IMAGE * Short.SIZE_BYTES,
            )
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        // Later passes use this slot with an array of their own, or none.
        GLES30.glDisableVertexAttribArray(ConstellationArtShaders.ATTRIB_TEXCOORD)
    }

    private fun uploadArtMesh(mesh: ConstellationArtMesh) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, artVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertices.size * Float.SIZE_BYTES, toBuffer(mesh.vertices), GLES30.GL_STATIC_DRAW)
        val indexBuffer = ByteBuffer.allocateDirect(mesh.indices.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(mesh.indices)
            .also { it.position(0) }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, artIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * Short.SIZE_BYTES, indexBuffer, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        uploadedArtMesh = mesh
    }

    /** Frees the previous image list's textures (in this context) and names new, still empty ones. */
    private fun replaceArtTextures(images: List<ArtImage>) {
        if (artTextures.isNotEmpty()) GLES30.glDeleteTextures(artTextures.size, artTextures, 0)
        artTextures = IntArray(images.size)
        if (images.isNotEmpty()) GLES30.glGenTextures(images.size, artTextures, 0)
        artTexturesReady = 0
        uploadedArtImages = images
    }

    /** Uploads the next few images as single-channel, mipmapped textures. */
    private fun uploadArtTextures(images: List<ArtImage>) {
        if (artTexturesReady >= images.size) return
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val end = min(images.size, artTexturesReady + ART_TEXTURES_PER_FRAME)
        for (i in artTexturesReady until end) {
            val image = images[i]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, artTextures[i])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, image.width, image.height, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(image.luminance),
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        artTexturesReady = end
    }

    /** Deep-sky symbols, with the blending of the stars; the vertex shader sizes them from the focal length in pixels. */
    private fun drawDeepSky(vertices: FloatArray, fovYDeg: Double, cameraModeUniform: Float) {
        if (vertices !== uploadedDeepSky) uploadDeepSky(vertices)
        if (deepSkyVertexCount == 0) return
        deepSkyProgram.use()
        GLES30.glUniformMatrix4fv(uDeepSkyMvp, 1, false, mvpFloats, 0)
        GLES30.glUniform1f(uDeepSkyFocalPx, ((viewportHeight / 2.0) / tan(Math.toRadians(fovYDeg / 2))).toFloat())
        GLES30.glUniform1f(uDeepSkyMinRadiusPx, DeepSkyAppearance.MIN_RADIUS_DP * pointScale)
        GLES30.glUniform1f(uDeepSkyCameraMode, cameraModeUniform)
        GLES30.glUniform1f(uDeepSkyNightMode, nightModeUniform)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, deepSkyVbo)
        bindDeepSkyAttribute(DeepSkyShaders.ATTRIB_POSITION, 3, DeepSkyVertices.POSITION_OFFSET)
        bindDeepSkyAttribute(DeepSkyShaders.ATTRIB_CORNER, 2, DeepSkyVertices.CORNER_OFFSET)
        bindDeepSkyAttribute(DeepSkyShaders.ATTRIB_SEMI_MAJOR, 1, DeepSkyVertices.SEMI_MAJOR_OFFSET)
        bindDeepSkyAttribute(DeepSkyShaders.ATTRIB_SEMI_MINOR, 1, DeepSkyVertices.SEMI_MINOR_OFFSET)
        bindDeepSkyAttribute(DeepSkyShaders.ATTRIB_POSITION_ANGLE, 1, DeepSkyVertices.POSITION_ANGLE_OFFSET)
        bindDeepSkyAttribute(DeepSkyShaders.ATTRIB_COLOR, 3, DeepSkyVertices.COLOR_OFFSET)
        bindDeepSkyAttribute(DeepSkyShaders.ATTRIB_GLYPH, 1, DeepSkyVertices.GLYPH_OFFSET)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, deepSkyVertexCount)
        // Later passes must not be left with enabled arrays that run past the end of this small buffer.
        for (attribute in DeepSkyShaders.ATTRIB_CORNER..DeepSkyShaders.ATTRIB_GLYPH) {
            GLES30.glDisableVertexAttribArray(attribute)
        }
    }

    private fun uploadDeepSky(vertices: FloatArray) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, deepSkyVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * Float.SIZE_BYTES, toBuffer(vertices), GLES30.GL_STATIC_DRAW)
        uploadedDeepSky = vertices
        deepSkyVertexCount = vertices.size / DeepSkyVertices.FLOATS_PER_VERTEX
    }

    private fun bindDeepSkyAttribute(location: Int, size: Int, offsetFloats: Int) {
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(
            location, size, GLES30.GL_FLOAT, false,
            DeepSkyVertices.FLOATS_PER_VERTEX * Float.SIZE_BYTES, offsetFloats * Float.SIZE_BYTES,
        )
    }

    /**
     * The reference grid (faint) and the horizon (strong, 2 px where the GPU draws wide lines), with
     * the line program the constellation lines use, so the camera-view alpha follows the same rule.
     * Their blending is that of the lines, set by the caller.
     */
    private fun drawGuides(cameraModeUniform: Float, showHorizon: Boolean, showGrid: Boolean) {
        if (!showHorizon && !showGrid) return
        if (!guidesUploaded) uploadGuides()
        lineProgram.use()
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, horizonMvpFloats, 0)
        GLES30.glUniform1f(uLineCameraMode, cameraModeUniform)
        GLES30.glUniform1f(uLineNightMode, nightModeUniform)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, guideVbo)
        GLES30.glEnableVertexAttribArray(LineShaders.ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(
            LineShaders.ATTRIB_POSITION, HorizonGuides.FLOATS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, 0,
        )
        if (showGrid && gridVertexCount > 0) {
            GLES30.glUniform3f(uLineColor, LineShaders.GRID_RED, LineShaders.GRID_GREEN, LineShaders.GRID_BLUE)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, gridVertexCount)
        }
        if (showHorizon && horizonVertexCount > 0) {
            GLES30.glLineWidth(HORIZON_LINE_WIDTH_PX) // clamped by the GPU; many only draw 1 px
            GLES30.glUniform3f(uLineColor, LineShaders.HORIZON_RED, LineShaders.HORIZON_GREEN, LineShaders.HORIZON_BLUE)
            GLES30.glDrawArrays(GLES30.GL_LINES, gridVertexCount, horizonVertexCount)
            GLES30.glLineWidth(1f)
        }
    }

    private fun uploadGuides() {
        val geometry = HorizonGuides.build()
        val all = geometry.grid + geometry.horizon
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, guideVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, all.size * Float.SIZE_BYTES, toBuffer(all), GLES30.GL_STATIC_DRAW)
        gridVertexCount = geometry.grid.size / HorizonGuides.FLOATS_PER_VERTEX
        horizonVertexCount = geometry.horizon.size / HorizonGuides.FLOATS_PER_VERTEX
        guidesUploaded = true
    }

    /**
     * Sun, Moon, planets and the planets' moons over the stars, as quads the vertex shader sizes from the
     * focal length: nominal sprites at the default field of view, true-size discs when zoomed in, moons
     * faded in once clear of their planet (see [BodyShaders]).
     */
    private fun drawBodies(mvp: Matrix4, fovYDeg: Double, cameraModeUniform: Float) {
        if (bodyCount == 0) return
        bodyProgram.use()
        GLES30.glUniformMatrix4fv(uBodyMvp, 1, false, mvpFloats, 0)
        GLES30.glUniform1f(uBodyPointScale, pointScale)
        GLES30.glUniform1f(uBodyFocalPx, ((viewportHeight / 2.0) / tan(Math.toRadians(fovYDeg / 2))).toFloat())
        GLES30.glUniform2f(uBodyViewportPx, viewportWidth.toFloat(), viewportHeight.toFloat())
        GLES30.glUniform1f(uBodyCameraMode, cameraModeUniform)
        GLES30.glUniform1f(uBodyNightMode, nightModeUniform)
        setMoonLight(mvp)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bodyVbo)
        bindBodyAttribute(BodyShaders.ATTRIB_POSITION, 3, BodyVertices.POSITION_OFFSET)
        bindBodyAttribute(BodyShaders.ATTRIB_CORNER, 2, BodyVertices.CORNER_OFFSET)
        bindBodyAttribute(BodyShaders.ATTRIB_SIZE, 1, BodyVertices.SIZE_OFFSET)
        bindBodyAttribute(BodyShaders.ATTRIB_TRUE_RADIUS, 1, BodyVertices.TRUE_RADIUS_OFFSET)
        bindBodyAttribute(BodyShaders.ATTRIB_COLOR, 3, BodyVertices.COLOR_OFFSET)
        bindBodyAttribute(BodyShaders.ATTRIB_KIND, 1, BodyVertices.KIND_OFFSET)
        bindBodyAttribute(BodyShaders.ATTRIB_SEPARATION, 1, BodyVertices.SEPARATION_OFFSET)

        // Premultiplied "over": in the enhanced view glows (alpha 0) still add, while the Moon's
        // disc (alpha 1) hides the stars behind it.
        setBlending(OverlayBlend.bodies())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, bodyCount * BodyVertices.VERTICES_PER_BODY)

        // These arrays point into the small body buffer. The line pass of the next frame does not
        // read them, and must not be left with enabled arrays that run past the end of their buffer.
        for (attribute in BodyShaders.ATTRIB_POSITION..BodyShaders.ATTRIB_SEPARATION) {
            GLES30.glDisableVertexAttribArray(attribute)
        }
    }

    /** The Moon's lit side in its quad's frame (screen right, up, toward the viewer). */
    private fun setMoonLight(mvp: Matrix4) {
        val moon = moonDirection
        val sun = moonSunDirection
        // Out of view or unknown: any value will do, since nothing of the Moon is drawn then.
        val light = (if (moon != null && sun != null) {
            SkyViewMath.spriteLight(mvp, moon, sun, viewportWidth, viewportHeight)
        } else {
            null
        }) ?: Vector3(0.0, 0.0, 1.0)
        GLES30.glUniform3f(uBodyMoonLight, light.x.toFloat(), light.y.toFloat(), light.z.toFloat())
    }

    private fun uploadBodies(bodies: FloatArray) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bodyVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bodies.size * Float.SIZE_BYTES, toBuffer(bodies), GLES30.GL_DYNAMIC_DRAW)
        uploadedBodies = bodies
        bodyCount = bodies.size / BodyVertices.FLOATS_PER_BODY
        moonDirection = null
        moonSunDirection = null
        for (i in 0 until bodyCount) {
            val base = i * BodyVertices.FLOATS_PER_BODY // the body's first vertex; all six carry the same values
            if (bodies[base + BodyVertices.KIND_OFFSET] == BodyAppearance.KIND_MOON) {
                moonDirection = readVector(bodies, base + BodyVertices.POSITION_OFFSET)
                moonSunDirection = readVector(bodies, base + BodyVertices.SUN_DIRECTION_OFFSET)
            }
        }
    }

    private fun readVector(data: FloatArray, offset: Int) =
        Vector3(data[offset].toDouble(), data[offset + 1].toDouble(), data[offset + 2].toDouble())

    private fun bindBodyAttribute(location: Int, size: Int, offsetFloats: Int) {
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(
            location, size, GLES30.GL_FLOAT, false,
            BodyVertices.FLOATS_PER_VERTEX * Float.SIZE_BYTES, offsetFloats * Float.SIZE_BYTES,
        )
    }

    /**
     * The constellation lines ([showAll]) and then, over them, the [highlight] ranges (vertex ranges
     * of the selected constellation, see `SkyRenderInputs.highlightedConstellation`) in the highlight
     * colour, 2 px wide where the GPU allows wide lines.
     */
    private fun drawLines(cameraModeUniform: Float, showAll: Boolean, highlight: IntArray?) {
        if (lineVertexCount == 0 || (!showAll && (highlight == null || highlight.isEmpty()))) return
        lineProgram.use()
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvpFloats, 0)
        GLES30.glUniform1f(uLineCameraMode, cameraModeUniform)
        GLES30.glUniform1f(uLineNightMode, nightModeUniform)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
        GLES30.glEnableVertexAttribArray(LineShaders.ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(
            LineShaders.ATTRIB_POSITION, ConstellationSky.FLOATS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, 0,
        )
        if (showAll) {
            GLES30.glUniform3f(uLineColor, LineShaders.LINE_RED, LineShaders.LINE_GREEN, LineShaders.LINE_BLUE)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, lineVertexCount)
        }
        if (highlight != null && highlight.isNotEmpty()) {
            GLES30.glLineWidth(HIGHLIGHT_LINE_WIDTH_PX) // clamped by the GPU; many only draw 1 px
            GLES30.glUniform3f(uLineColor, LineShaders.HIGHLIGHT_RED, LineShaders.HIGHLIGHT_GREEN, LineShaders.HIGHLIGHT_BLUE)
            for (i in 0 until highlight.size / 2) {
                val first = highlight[2 * i]
                val count = min(highlight[2 * i + 1], lineVertexCount - first)
                if (first >= 0 && count > 0) GLES30.glDrawArrays(GLES30.GL_LINES, first, count)
            }
            GLES30.glLineWidth(1f)
        }
    }

    private fun uploadLines(lines: FloatArray) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lines.size * Float.SIZE_BYTES, toBuffer(lines), GLES30.GL_STATIC_DRAW)
        uploadedLines = lines
        lineVertexCount = lines.size / ConstellationSky.FLOATS_PER_VERTEX
    }

    private fun toBuffer(data: FloatArray) = ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(data)
        .also { it.position(0) }

    private fun upload(vertices: FloatArray) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * Float.SIZE_BYTES, toBuffer(vertices), GLES30.GL_STATIC_DRAW)
        uploadedVertices = vertices
        starCount = vertices.size / StarVertices.FLOATS_PER_STAR
    }

    private fun bindAttribute(location: Int, size: Int, offsetFloats: Int) {
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(
            location, size, GLES30.GL_FLOAT, false,
            StarVertices.FLOATS_PER_STAR * Float.SIZE_BYTES, offsetFloats * Float.SIZE_BYTES,
        )
    }

    private companion object {
        /** The highest vertex attribute slot any pass uses (the deep-sky and body passes use 0 to 6). */
        const val MAX_VERTEX_ATTRIBUTE = 6

        /** Illustrations uploaded per frame: 8 of at most 512 x 512 bytes is 2 MB, a few milliseconds. */
        const val ART_TEXTURES_PER_FRAME = 8

        /** Asked of the GPU for a selected constellation's lines; as for the horizon, 1 px is what many devices give. */
        const val HIGHLIGHT_LINE_WIDTH_PX = 2f

        /** Asked of the GPU for the horizon; devices without wide lines draw 1 px and rely on its brightness. */
        const val HORIZON_LINE_WIDTH_PX = 2f
    }
}
