package com.skyglass.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.skyglass.astro.AstroTime
import com.skyglass.catalog.BodyAppearance
import com.skyglass.catalog.BodyVertices
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
 * Draws the Milky Way band, the horizon and reference grid, the constellation lines, the deep-sky
 * objects, the star field, then the Sun, Moon and planets. Stars, lines and bodies live in VBOs in the equatorial frame of date; each frame only
 * the MVP matrix changes, built by [SkyViewMath]. The horizon guides are fixed to the observer, so
 * they live in true ENU and are drawn with the same view minus the sky's rotation
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
    private var uLimitMag = -1

    private var vbo = 0
    private var uploadedVertices: FloatArray? = null
    private var starCount = 0

    private lateinit var lineProgram: ShaderProgram
    private var uLineMvp = -1
    private var uLineColor = -1
    private var uLineCameraMode = -1
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
    private var uBodyMaxPointSize = -1
    private var uBodyMoonLight = -1
    private var uBodyMoonOccluder = -1
    private var uBodyCameraMode = -1
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
    private var deepSkyVbo = 0
    private var uploadedDeepSky: FloatArray? = null
    private var deepSkyVertexCount = 0

    // The Milky Way band: an indexed triangle mesh.
    private lateinit var milkyWayProgram: ShaderProgram
    private var uMilkyWayMvp = -1
    private var uMilkyWayGain = -1
    private var uMilkyWayCameraMode = -1
    private var milkyWayVbo = 0
    private var milkyWayIbo = 0
    private var uploadedMilkyWay: MilkyWayMesh? = null
    private var milkyWayIndexCount = 0

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var aspect = 1.0
    private var maxPointSize = 64f
    private var lastFrameNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)

        program = ShaderProgram(StarShaders.VERTEX, StarShaders.FRAGMENT)
        uMvp = program.uniformLocation("uMvp")
        uPointScale = program.uniformLocation("uPointScale")
        uMaxPointSize = program.uniformLocation("uMaxPointSize")
        uCameraMode = program.uniformLocation("uCameraMode")
        uLimitMag = program.uniformLocation("uLimitMag")

        lineProgram = ShaderProgram(LineShaders.VERTEX, LineShaders.FRAGMENT)
        uLineMvp = lineProgram.uniformLocation("uMvp")
        uLineColor = lineProgram.uniformLocation("uColor")
        uLineCameraMode = lineProgram.uniformLocation("uCameraMode")

        bodyProgram = ShaderProgram(BodyShaders.VERTEX, BodyShaders.FRAGMENT)
        uBodyMvp = bodyProgram.uniformLocation("uMvp")
        uBodyPointScale = bodyProgram.uniformLocation("uPointScale")
        uBodyMaxPointSize = bodyProgram.uniformLocation("uMaxPointSize")
        uBodyMoonLight = bodyProgram.uniformLocation("uMoonLight")
        uBodyMoonOccluder = bodyProgram.uniformLocation("uMoonOccluder")
        uBodyCameraMode = bodyProgram.uniformLocation("uCameraMode")

        deepSkyProgram = ShaderProgram(DeepSkyShaders.VERTEX, DeepSkyShaders.FRAGMENT)
        uDeepSkyMvp = deepSkyProgram.uniformLocation("uMvp")
        uDeepSkyFocalPx = deepSkyProgram.uniformLocation("uFocalPx")
        uDeepSkyMinRadiusPx = deepSkyProgram.uniformLocation("uMinRadiusPx")
        uDeepSkyCameraMode = deepSkyProgram.uniformLocation("uCameraMode")

        milkyWayProgram = ShaderProgram(MilkyWayShaders.VERTEX, MilkyWayShaders.FRAGMENT)
        uMilkyWayMvp = milkyWayProgram.uniformLocation("uMvp")
        uMilkyWayGain = milkyWayProgram.uniformLocation("uGain")
        uMilkyWayCameraMode = milkyWayProgram.uniformLocation("uCameraMode")

        val ids = IntArray(7)
        GLES30.glGenBuffers(7, ids, 0)
        vbo = ids[0]
        lineVbo = ids[1]
        bodyVbo = ids[2]
        guideVbo = ids[3]
        deepSkyVbo = ids[4]
        milkyWayVbo = ids[5]
        milkyWayIbo = ids[6]
        // A new context has empty buffers, even if the data was uploaded to a previous one.
        guidesUploaded = false
        uploadedDeepSky = null
        uploadedMilkyWay = null
        uploadedVertices = null
        uploadedLines = null
        uploadedBodies = null

        val range = FloatArray(2)
        GLES30.glGetFloatv(GLES30.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
        maxPointSize = range[1]
        inputs.maxPointSizePx = maxPointSize // lets taps use the sprite size actually drawn

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
        val cameraMode = inputs.cameraMode
        GLES30.glClearColor(0f, 0f, 0f, if (cameraMode) 0f else 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val cameraModeUniform = if (cameraMode) 1f else 0f
        // Each pass enables the vertex arrays it uses; none may inherit another pass's, whose buffer may be smaller.
        for (attribute in 0..MAX_VERTEX_ATTRIBUTE) GLES30.glDisableVertexAttribArray(attribute)
        setBlending(OverlayBlend.stars(cameraMode))
        filter.timeConstantSeconds = if (cameraMode) {
            OrientationFilter.CAMERA_TIME_CONSTANT_SECONDS
        } else {
            OrientationFilter.DEFAULT_TIME_CONSTANT_SECONDS
        }

        val now = System.nanoTime()
        val dtSeconds = if (lastFrameNanos == 0L) 0.0 else (now - lastFrameNanos) / 1e9
        lastFrameNanos = now

        val vertices = inputs.starVertices ?: return
        val observer = inputs.observer ?: return
        val rawOrientation = orientation() ?: return

        if (vertices !== uploadedVertices) upload(vertices)

        val fovYDeg = inputs.fovYDeg
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

    /** Deep-sky symbols, with the blending of the stars; the vertex shader sizes them from the focal length in pixels. */
    private fun drawDeepSky(vertices: FloatArray, fovYDeg: Double, cameraModeUniform: Float) {
        if (vertices !== uploadedDeepSky) uploadDeepSky(vertices)
        if (deepSkyVertexCount == 0) return
        deepSkyProgram.use()
        GLES30.glUniformMatrix4fv(uDeepSkyMvp, 1, false, mvpFloats, 0)
        GLES30.glUniform1f(uDeepSkyFocalPx, ((viewportHeight / 2.0) / tan(Math.toRadians(fovYDeg / 2))).toFloat())
        GLES30.glUniform1f(uDeepSkyMinRadiusPx, DeepSkyAppearance.MIN_RADIUS_DP * pointScale)
        GLES30.glUniform1f(uDeepSkyCameraMode, cameraModeUniform)
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

    /** Sun, Moon and planets over the stars. */
    private fun drawBodies(mvp: Matrix4, fovYDeg: Double, cameraModeUniform: Float) {
        if (bodyCount == 0) return
        bodyProgram.use()
        GLES30.glUniformMatrix4fv(uBodyMvp, 1, false, mvpFloats, 0)
        GLES30.glUniform1f(uBodyPointScale, pointScale)
        GLES30.glUniform1f(uBodyMaxPointSize, maxPointSize)
        GLES30.glUniform1f(uBodyCameraMode, cameraModeUniform)
        setMoonUniforms(mvp, fovYDeg)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bodyVbo)
        bindBodyAttribute(BodyShaders.ATTRIB_POSITION, 3, BodyVertices.POSITION_OFFSET)
        bindBodyAttribute(BodyShaders.ATTRIB_SIZE, 1, BodyVertices.SIZE_OFFSET)
        bindBodyAttribute(BodyShaders.ATTRIB_COLOR, 3, BodyVertices.COLOR_OFFSET)
        bindBodyAttribute(BodyShaders.ATTRIB_KIND, 1, BodyVertices.KIND_OFFSET)

        // Premultiplied "over": in the enhanced view glows (alpha 0) still add, while the Moon's
        // disc (alpha 1) hides the stars behind it.
        setBlending(OverlayBlend.bodies())
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, bodyCount)

        // These arrays point into the small body buffer. The line pass of the next frame does not
        // read them, and must not be left with enabled arrays that run past the end of their buffer.
        for (attribute in BodyShaders.ATTRIB_POSITION..BodyShaders.ATTRIB_KIND) {
            GLES30.glDisableVertexAttribArray(attribute)
        }
    }

    /**
     * The Moon's lit side in its sprite frame, and how much of its drawn disc is opaque. The
     * drawn disc is enlarged; only the true angular radius (a few dp) may hide what is behind.
     */
    private fun setMoonUniforms(mvp: Matrix4, fovYDeg: Double) {
        val moon = moonDirection
        val sun = moonSunDirection
        // Out of view or unknown: any value will do, since nothing of the Moon is drawn then.
        val light = (if (moon != null && sun != null) {
            SkyViewMath.spriteLight(mvp, moon, sun, viewportWidth, viewportHeight)
        } else {
            null
        }) ?: Vector3(0.0, 0.0, 1.0)
        GLES30.glUniform3f(uBodyMoonLight, light.x.toFloat(), light.y.toFloat(), light.z.toFloat())

        val focalPx = (viewportHeight / 2.0) / tan(Math.toRadians(fovYDeg / 2))
        val trueRadiusPx = tan(Math.toRadians(BodyAppearance.MOON_TRUE_RADIUS_DEG)) * focalPx
        val drawnSpritePx = min(BodyAppearance.MOON_SPRITE_DP * pointScale, maxPointSize)
        val drawnDiscRadiusPx = drawnSpritePx / 2.0 * BodyAppearance.MOON_DISC_FRACTION
        val occluder = (trueRadiusPx / drawnDiscRadiusPx).coerceIn(MIN_MOON_OCCLUDER, 1.0)
        GLES30.glUniform1f(uBodyMoonOccluder, occluder.toFloat())
    }

    private fun uploadBodies(bodies: FloatArray) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bodyVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, bodies.size * Float.SIZE_BYTES, toBuffer(bodies), GLES30.GL_DYNAMIC_DRAW)
        uploadedBodies = bodies
        bodyCount = bodies.size / BodyVertices.FLOATS_PER_BODY
        moonDirection = null
        moonSunDirection = null
        for (i in 0 until bodyCount) {
            val base = i * BodyVertices.FLOATS_PER_BODY
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
            BodyVertices.FLOATS_PER_BODY * Float.SIZE_BYTES, offsetFloats * Float.SIZE_BYTES,
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
        /** The highest vertex attribute slot any pass uses (the deep-sky pass uses 0 to 6). */
        const val MAX_VERTEX_ATTRIBUTE = 6

        /** Keeps the shader's smoothstep well defined if the Moon would be vanishingly small. */
        const val MIN_MOON_OCCLUDER = 0.05

        /** Asked of the GPU for a selected constellation's lines; as for the horizon, 1 px is what many devices give. */
        const val HIGHLIGHT_LINE_WIDTH_PX = 2f

        /** Asked of the GPU for the horizon; devices without wide lines draw 1 px and rely on its brightness. */
        const val HORIZON_LINE_WIDTH_PX = 2f
    }
}
