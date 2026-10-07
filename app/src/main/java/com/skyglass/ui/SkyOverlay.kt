package com.skyglass.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.skyglass.R
import com.skyglass.catalog.ConstellationLabel
import com.skyglass.catalog.StarDescription
import com.skyglass.render.SkyProjection
import com.skyglass.render.SkyRenderInputs

/** Dim, slightly blue so labels sit behind the stars without spoiling dark adaptation. */
private val ConstellationLabelColor = Color(0xFF7B8FB8)
private val SelectionRingColor = Color(0xFFB8C4DC)
private val CardBackground = Color(0xCC0B0D12)
private val CardTextColor = Color(0xFFCBD2E0)
private val CardSecondaryTextColor = Color(0xFF8A93A6)

/** Radius of the ring drawn round the selected star. */
private const val RING_RADIUS_DP = 16f

/**
 * Transparent layer over the GL view that draws constellation names and a ring round the
 * selected star, both placed with the MVP of the latest GL frame, and reports taps.
 *
 * It redraws on every display frame; the draw lambda only reads state, so nothing recomposes.
 */
@Composable
fun SkyOverlay(
    renderInputs: SkyRenderInputs,
    labels: List<ConstellationLabel>,
    selectedStar: SelectedStar?,
    onTap: (x: Float, y: Float, widthPx: Int, heightPx: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val measuredLabels = remember(labels, labelStyle) { labels.map { textMeasurer.measure(it.name, labelStyle) } }
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

        if (selectedStar != null) {
            val point = SkyProjection.project(mvp, selectedStar.direction, width, height)
            if (point != null && SkyProjection.isOnScreen(point, width, height, marginPx = ringRadiusPx.toDouble())) {
                drawCircle(
                    SelectionRingColor,
                    radius = ringRadiusPx,
                    center = Offset(point.x.toFloat(), point.y.toFloat()),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

/** Name and magnitude of the tapped star. */
@Composable
fun StarInfoCard(description: StarDescription, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(CardBackground, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = description.title,
            color = CardTextColor,
            style = MaterialTheme.typography.titleMedium,
        )
        if (description.designation != null) {
            Text(
                text = description.designation,
                color = CardSecondaryTextColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(R.string.star_magnitude, description.vmag),
            color = CardTextColor,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.star_hr, description.hr),
            color = CardSecondaryTextColor,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
