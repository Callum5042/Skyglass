package com.skyglass.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.skyglass.astro.BodyPosition
import com.skyglass.astro.SkyBody
import com.skyglass.catalog.BodyAppearance
import com.skyglass.catalog.ConstellationLabel
import com.skyglass.render.SkyProjection
import com.skyglass.render.SkyRenderInputs

/** Dim, slightly blue so labels sit behind the stars without spoiling dark adaptation. */
private val ConstellationLabelColor = Color(0xFF7B8FB8)

/** Warmer than the constellation labels, so the Sun, Moon and planets read as a different kind of object. */
private val BodyLabelColor = Color(0xFFD2BE94)
private val SelectionRingColor = Color(0xFFB8C4DC)
internal val CardBackground = Color(0xCC0B0D12)
internal val CardTextColor = Color(0xFFCBD2E0)
internal val CardSecondaryTextColor = Color(0xFF8A93A6)

/** Radius of the ring drawn round the selected star; a body's ring is never smaller than its disc plus a gap. */
private const val RING_RADIUS_DP = 16f

/** Space between the edge of a body's disc and its label, and between the disc and its ring. */
private const val BODY_LABEL_GAP_DP = 4f
private const val BODY_RING_GAP_DP = 6f

/**
 * Transparent layer over the GL view that draws constellation names, the names of the Sun, Moon
 * and planets, and a ring round the selected star or body, all placed with the MVP of the latest
 * GL frame, and reports taps.
 *
 * It redraws on every display frame; the draw lambda only reads state, so nothing recomposes.
 */
@Composable
fun SkyOverlay(
    renderInputs: SkyRenderInputs,
    labels: List<ConstellationLabel>,
    bodies: List<BodyPosition>,
    selectedStar: SelectedStar?,
    selectedBody: BodyPosition?,
    onTap: (x: Float, y: Float, widthPx: Int, heightPx: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val measuredLabels = remember(labels, labelStyle) { labels.map { textMeasurer.measure(it.name, labelStyle) } }
    val bodyLabelStyle = MaterialTheme.typography.labelLarge
    val bodyNames = SkyBody.entries.associateWith { stringResource(it.nameRes()) }
    val measuredBodyNames = remember(bodyNames, bodyLabelStyle) {
        bodyNames.mapValues { (_, name) -> textMeasurer.measure(name, bodyLabelStyle) }
    }
    val ringRadiusPx = with(LocalDensity.current) { RING_RADIUS_DP.dp.toPx() }
    val currentOnTap by rememberUpdatedState(onTap)

    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frameTick = it }
    }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset: Offset ->
                currentOnTap(offset.x, offset.y, size.width, size.height)
            }
        },
    ) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick // read so the canvas is redrawn each frame
        val mvp = renderInputs.lastMvp ?: return@Canvas
        val width = size.width.toInt()
        val height = size.height.toInt()

        labels.forEachIndexed { i, label ->
            val point = SkyProjection.project(mvp, label.direction, width, height) ?: return@forEachIndexed
            if (!SkyProjection.isOnScreen(point, width, height)) return@forEachIndexed
            val layout = measuredLabels[i]
            drawText(
                layout,
                color = ConstellationLabelColor,
                topLeft = Offset(
                    point.x.toFloat() - layout.size.width / 2f,
                    point.y.toFloat() - layout.size.height / 2f,
                ),
            )
        }

        // Named just below each disc, so the label never covers the body itself.
        for (body in bodies) {
            val point = SkyProjection.project(mvp, body.direction, width, height) ?: continue
            if (!SkyProjection.isOnScreen(point, width, height)) continue
            val layout = measuredBodyNames.getValue(body.body)
            drawText(
                layout,
                color = BodyLabelColor,
                topLeft = Offset(
                    point.x.toFloat() - layout.size.width / 2f,
                    point.y.toFloat() + (BodyAppearance.discRadiusDp(body) + BODY_LABEL_GAP_DP).dp.toPx(),
                ),
            )
        }

        val selectedDirection = selectedStar?.direction ?: selectedBody?.direction
        if (selectedDirection != null) {
            val point = SkyProjection.project(mvp, selectedDirection, width, height)
            val radiusPx = if (selectedBody != null) {
                maxOf(ringRadiusPx, (BodyAppearance.discRadiusDp(selectedBody) + BODY_RING_GAP_DP).dp.toPx())
            } else {
                ringRadiusPx
            }
            if (point != null && SkyProjection.isOnScreen(point, width, height, marginPx = radiusPx.toDouble())) {
                drawCircle(
                    SelectionRingColor,
                    radius = radiusPx,
                    center = Offset(point.x.toFloat(), point.y.toFloat()),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}
