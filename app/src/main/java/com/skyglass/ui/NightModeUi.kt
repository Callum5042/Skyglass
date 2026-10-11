package com.skyglass.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.skyglass.render.NightMode
import com.skyglass.render.Rgba

/**
 * Whether night mode is on, for the popups and dialogs: they are windows of their own, outside the
 * filtered layer of the sky screen, so each applies [nightMode] to its own content.
 */
val LocalNightMode = compositionLocalOf { false }

private val NightModePaint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix(NightMode.UI_COLOR_MATRIX)) }

/**
 * Draws this element and everything in it through [NightMode.UI_COLOR_MATRIX] when [on], as one
 * offscreen layer. Never put a `SurfaceView` (the GL sky, the camera preview) inside it: the hole a
 * surface view punches would only clear the layer, not the window, and hide the surface.
 */
fun Modifier.nightMode(on: Boolean): Modifier = if (!on) this else drawWithContent {
    drawIntoCanvas { canvas ->
        canvas.saveLayer(size.toRect(), NightModePaint)
        drawContent()
        canvas.restore()
    }
}

/** This colour as [nightMode] would draw it, for colours painted outside the filtered content (a popup's container). */
fun Color.inNightMode(on: Boolean): Color {
    if (!on) return this
    val red = NightMode.ui(Rgba(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble()))
    return Color(red.r.toFloat(), 0f, 0f, red.a.toFloat())
}
