package com.skyglass.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.skyglass.astro.AstroTime
import com.skyglass.catalog.ConstellationSky
import com.skyglass.catalog.StarVertices
import com.skyglass.math.Quaternion
import com.skyglass.sensors.OrientationFilter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the constellation lines and the star field. All vectors live in static VBOs in the
 * equatorial frame of date; each frame only the MVP matrix changes, built by [SkyViewMath].
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

        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        lineVbo = ids[1]
        // A new context has empty buffers, even if the data was uploaded to a previous one.
        uploadedVertices = null
        uploadedLines = null

        val range = FloatArray(2)
        GLES30.glGetFloatv(GLES30.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
        maxPointSize = range[1]

        filter.reset()
        lastFrameNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
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
    }
}
