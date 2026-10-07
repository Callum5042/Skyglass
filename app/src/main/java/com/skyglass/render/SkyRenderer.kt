package com.skyglass.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.skyglass.astro.AstroTime
import com.skyglass.catalog.BodyAppearance
import com.skyglass.catalog.BodyVertices
import com.skyglass.catalog.ConstellationSky
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
 * Draws the constellation lines, the star field, then the Sun, Moon and planets. All vectors live
 * in VBOs in the equatorial frame of date; each frame only the MVP matrix changes, built by
 * [SkyViewMath]. The star and line buffers are static; the body buffer is replaced every few
 * seconds as the bodies move, and re-uploaded when its array instance changes.
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

    private lateinit var program: ShaderProgram
    private var uMvp = -1
    private var uPointScale = -1
    private var uMaxPointSize = -1

    private var vbo = 0
    private var uploadedVertices: FloatArray? = null
    private var starCount = 0

    private lateinit var lineProgram: ShaderProgram
    private var uLineMvp = -1
    private var uLineColor = -1
    private var lineVbo = 0
    private var uploadedLines: FloatArray? = null
    private var lineVertexCount = 0

    private lateinit var bodyProgram: ShaderProgram
    private var uBodyMvp = -1
    private var uBodyPointScale = -1
    private var uBodyMaxPointSize = -1
    private var uBodyMoonLight = -1
    private var uBodyMoonOccluder = -1
    private var bodyVbo = 0
    private var uploadedBodies: FloatArray? = null
    private var bodyCount = 0
    private var moonDirection: Vector3? = null
    private var moonSunDirection: Vector3? = null

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var aspect = 1.0
    private var maxPointSize = 64f
    private var lastFrameNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE) // additive: overlapping glows brighten

        program = ShaderProgram(StarShaders.VERTEX, StarShaders.FRAGMENT)
        uMvp = program.uniformLocation("uMvp")
        uPointScale = program.uniformLocation("uPointScale")
        uMaxPointSize = program.uniformLocation("uMaxPointSize")

        lineProgram = ShaderProgram(LineShaders.VERTEX, LineShaders.FRAGMENT)
        uLineMvp = lineProgram.uniformLocation("uMvp")
        uLineColor = lineProgram.uniformLocation("uColor")

        bodyProgram = ShaderProgram(BodyShaders.VERTEX, BodyShaders.FRAGMENT)
        uBodyMvp = bodyProgram.uniformLocation("uMvp")
        uBodyPointScale = bodyProgram.uniformLocation("uPointScale")
        uBodyMaxPointSize = bodyProgram.uniformLocation("uMaxPointSize")
        uBodyMoonLight = bodyProgram.uniformLocation("uMoonLight")
        uBodyMoonOccluder = bodyProgram.uniformLocation("uMoonOccluder")

        val ids = IntArray(3)
        GLES30.glGenBuffers(3, ids, 0)
        vbo = ids[0]
        lineVbo = ids[1]
        bodyVbo = ids[2]
        // A new context has empty buffers, even if the data was uploaded to a previous one.
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

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

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
            fovYDeg = FOV_Y_DEG,
            aspect = aspect,
        )

        inputs.lastMvp = mvp
        mvp.toColumnMajorFloatArray(mvpFloats)

        // Lines first, so stars are drawn over them.
        inputs.constellationLines?.let { lines ->
            if (lines !== uploadedLines) uploadLines(lines)
            drawLines()
        }

        program.use()
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvpFloats, 0)
        GLES30.glUniform1f(uPointScale, pointScale)
        GLES30.glUniform1f(uMaxPointSize, maxPointSize)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        bindAttribute(StarShaders.ATTRIB_POSITION, 3, StarVertices.POSITION_OFFSET)
        bindAttribute(StarShaders.ATTRIB_SIZE, 1, StarVertices.SIZE_OFFSET)
        bindAttribute(StarShaders.ATTRIB_COLOR, 3, StarVertices.COLOR_OFFSET)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, starCount)

        inputs.bodyVertices?.let { bodies ->
            if (bodies !== uploadedBodies) uploadBodies(bodies)
            drawBodies(mvp)
        }
    }

    /** Sun, Moon and planets over the stars. */
    private fun drawBodies(mvp: Matrix4) {
        if (bodyCount == 0) return
        bodyProgram.use()
        GLES30.glUniformMatrix4fv(uBodyMvp, 1, false, mvpFloats, 0)
        GLES30.glUniform1f(uBodyPointScale, pointScale)
        GLES30.glUniform1f(uBodyMaxPointSize, maxPointSize)
        setMoonUniforms(mvp)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bodyVbo)
        bindBodyAttribute(BodyShaders.ATTRIB_POSITION, 3, BodyVertices.POSITION_OFFSET)
        bindBodyAttribute(BodyShaders.ATTRIB_SIZE, 1, BodyVertices.SIZE_OFFSET)
        bindBodyAttribute(BodyShaders.ATTRIB_COLOR, 3, BodyVertices.COLOR_OFFSET)
        bindBodyAttribute(BodyShaders.ATTRIB_KIND, 1, BodyVertices.KIND_OFFSET)

        // Premultiplied "over": glows (alpha 0) still add, while the Moon's disc (alpha 1)
        // hides the stars behind it.
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, bodyCount)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)

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
    private fun setMoonUniforms(mvp: Matrix4) {
        val moon = moonDirection
        val sun = moonSunDirection
        // Out of view or unknown: any value will do, since nothing of the Moon is drawn then.
        val light = (if (moon != null && sun != null) {
            SkyViewMath.spriteLight(mvp, moon, sun, viewportWidth, viewportHeight)
        } else {
            null
        }) ?: Vector3(0.0, 0.0, 1.0)
        GLES30.glUniform3f(uBodyMoonLight, light.x.toFloat(), light.y.toFloat(), light.z.toFloat())

        val focalPx = (viewportHeight / 2.0) / tan(Math.toRadians(FOV_Y_DEG / 2))
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

    private fun drawLines() {
        if (lineVertexCount == 0) return
        lineProgram.use()
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvpFloats, 0)
        GLES30.glUniform3f(uLineColor, LineShaders.LINE_RED, LineShaders.LINE_GREEN, LineShaders.LINE_BLUE)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
        GLES30.glEnableVertexAttribArray(LineShaders.ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(
            LineShaders.ATTRIB_POSITION, ConstellationSky.FLOATS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, 0,
        )
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, lineVertexCount)
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
        const val FOV_Y_DEG = 60.0

        /** Keeps the shader's smoothstep well defined if the Moon would be vanishingly small. */
        const val MIN_MOON_OCCLUDER = 0.05
    }
}
