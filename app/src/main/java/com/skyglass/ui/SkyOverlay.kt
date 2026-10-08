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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.skyglass.astro.BodyPosition
import com.skyglass.astro.CompassPoint
import com.skyglass.astro.SkyBody
import com.skyglass.catalog.BodyAppearance
import com.skyglass.catalog.ConstellationDescription
import com.skyglass.catalog.ConstellationLabel
import com.skyglass.catalog.DeepSkyAppearance
import com.skyglass.catalog.NavigationTarget
import com.skyglass.catalog.SearchKind
import com.skyglass.render.CompassLabels
import com.skyglass.render.EdgeArrow
import com.skyglass.render.NavInsets
import com.skyglass.render.ScreenPoint
import com.skyglass.render.SkyNavigation
import com.skyglass.render.SkyProjection
import com.skyglass.render.SkyRenderInputs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.tan
import kotlin.math.roundToInt
import kotlin.math.sin

/** Dim, slightly blue so labels sit behind the stars without spoiling dark adaptation. */
private val ConstellationLabelColor = Color(0xFF7B8FB8)

/**
 * A selected constellation: its name and the rings round the stars of its figure, in the pale blue of
 * its lines in the sky (`LineShaders.HIGHLIGHT_*`), clearly brighter than the other names.
 */
private val ConstellationHighlightColor = Color(0xFFA9C6FF)
private const val HIGHLIGHT_STAR_RING_BASE_DP = 5f
private const val HIGHLIGHT_STAR_RING_PER_MAGNITUDE_DP = 0.9f

/** Warmer than the constellation labels, so the Sun, Moon and planets read as a different kind of object. */
private val BodyLabelColor = Color(0xFFD2BE94)
private val SelectionRingColor = Color(0xFFB8C4DC)

/** Dim lilac, a different hue from the constellation names and the bodies', for the names of deep-sky objects. */
private val DeepSkyLabelColor = Color(0xFFA79BD0)

/** A deep-sky label sits below an object up to this big, and in the middle of a bigger one (the Magellanic Clouds, the Andromeda Galaxy). */
private const val DEEP_SKY_LABEL_INSIDE_RADIUS_DP = 60f
private const val DEEP_SKY_LABEL_GAP_DP = 4f

/** The ring round a selected deep-sky object follows its extent, but never grows beyond this. */
private const val DEEP_SKY_RING_MAX_RADIUS_DP = 140f
private const val DEEP_SKY_RING_GAP_DP = 6f
private const val TARGET_DEEP_SKY_MAX_RADIUS_DP = 160f

/**
 * The compass letters take the horizon line's teal-green (see `LineShaders.HORIZON_*`): N, E, S, W
 * brighter and bold, the four in between dimmer and smaller. A dark shadow keeps them legible over a
 * bright camera image.
 */
private val CardinalLabelColor = Color(0xFF8FD6C2)
private val IntercardinalLabelColor = Color(0xFF63998B)
private val CompassLabelShadow = Shadow(color = Color(0xCC000000), blurRadius = 6f)

/** A compass letter is still drawn this far outside the screen, so it slides off the edge instead of vanishing at it. */
private const val COMPASS_LABEL_EDGE_MARGIN_DP = 24f

/** Amber, so the search target stands out from the blue-grey selection ring and the labels without being bright. */
internal val NavigationColor = Color(0xFFE8B84A)
private val NavigationOutlineColor = Color(0x99000000)
internal val CardBackground = Color(0xCC0B0D12)
internal val CardTextColor = Color(0xFFCBD2E0)
internal val CardSecondaryTextColor = Color(0xFF8A93A6)

/** Radius of the ring drawn round the selected star; a body's ring is never smaller than its disc plus a gap. */
private const val RING_RADIUS_DP = 16f

/** Space between the edge of a body's disc and its label, and between the disc and its ring. */
private const val BODY_LABEL_GAP_DP = 4f
private const val BODY_RING_GAP_DP = 6f

/**
 * Radius of the marker round a search target that is in view: outside the selection ring of a star
 * and of a body's disc, much larger round a constellation, whose "position" is the middle of its figure.
 */
private const val TARGET_STAR_RADIUS_DP = 28f
private const val TARGET_BODY_GAP_DP = 18f
private const val TARGET_CONSTELLATION_RADIUS_DP = 64f
private const val TARGET_TICK_LENGTH_DP = 8f

/** The edge arrow: its size, and how far the distance label sits from it toward the middle of the view. */
private const val ARROW_SIZE_DP = 18f
private const val ARROW_LABEL_OFFSET_DP = 34f

/**
 * Transparent layer over the GL view that draws constellation names, the names of the Sun, Moon
 * and planets, the names of the notable deep-sky objects, the compass letters on the horizon and a ring round the selected star or body, all
 * placed with the MVP of the latest GL frame, and reports taps. (The compass letters use the
 * frame's horizon MVP, `SkyRenderInputs.lastHorizonMvp`, since they are fixed to the horizon and
 * not to the stars; see `CompassLabels`.)
 *
 * While [navigation] is set it also guides the user to the searched-for target (see [SkyNavigation]):
 * an arrow at the edge of the usable view, labelled with the angle to turn, while the target is out
 * of view, and a marker round it once it is on screen. Both follow the same MVP as everything else,
 * so they hold in the camera view too.
 *
 * It redraws on every display frame; the draw lambda only reads state, so nothing recomposes.
 *
 * @param navigationInsets space in pixels the arrow keeps clear at each edge (toggles, cards, panels)
 */
@Composable
fun SkyOverlay(
    renderInputs: SkyRenderInputs,
    labels: List<ConstellationLabel>,
    bodies: List<BodyPosition>,
    selectedStar: SelectedStar?,
    selectedBody: BodyPosition?,
    deepSkyLabels: List<DeepSkyLabel>,
    selectedDeepSky: SelectedDeepSky?,
    showDeepSky: Boolean,
    selectedConstellation: ConstellationDescription?,
    showConstellations: Boolean,
    navigation: NavigationTarget?,
    navigationInsets: NavInsets,
    showHorizon: Boolean,
    onTap: (x: Float, y: Float, widthPx: Int, heightPx: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val measuredLabels = remember(labels, labelStyle) { labels.map { textMeasurer.measure(it.name, labelStyle) } }
    val measuredDeepSkyLabels = remember(deepSkyLabels, labelStyle) { deepSkyLabels.map { textMeasurer.measure(it.text, labelStyle) } }
    val bodyLabelStyle = MaterialTheme.typography.labelLarge
    val bodyNames = SkyBody.entries.associateWith { stringResource(it.nameRes()) }
    val measuredBodyNames = remember(bodyNames, bodyLabelStyle) {
        bodyNames.mapValues { (_, name) -> textMeasurer.measure(name, bodyLabelStyle) }
    }
    val compassStyles = mapOf(
        true to MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        false to MaterialTheme.typography.labelMedium,
    )
    val compassNames = CompassPoint.entries.associateWith { stringResource(it.nameRes()) }
    val measuredCompass = remember(compassNames, compassStyles) {
        compassNames.mapValues { (point, name) -> textMeasurer.measure(name, compassStyles.getValue(point.isCardinal)) }
    }
    val ringRadiusPx = with(LocalDensity.current) { RING_RADIUS_DP.dp.toPx() }
    val density = LocalDensity.current.density
    val currentOnTap by rememberUpdatedState(onTap)
    val arrowSizePx = with(LocalDensity.current) { ARROW_SIZE_DP.dp.toPx() }
    val arrowPath = remember(arrowSizePx) {
        Path().apply { // pointing right (+x), centred near the origin; rotated to the target's direction when drawn
            moveTo(arrowSizePx, 0f)
            lineTo(-0.7f * arrowSizePx, 0.8f * arrowSizePx)
            lineTo(-0.3f * arrowSizePx, 0f)
            lineTo(-0.7f * arrowSizePx, -0.8f * arrowSizePx)
            close()
        }
    }

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

        if (showHorizon) {
            renderInputs.lastHorizonMvp?.let { horizonMvp ->
                val edgeMarginPx = COMPASS_LABEL_EDGE_MARGIN_DP.dp.toPx().toDouble()
                for (placed in CompassLabels.place(horizonMvp, width, height, edgeMarginPx)) {
                    val layout = measuredCompass.getValue(placed.point)
                    drawText(
                        layout,
                        color = if (placed.point.isCardinal) CardinalLabelColor else IntercardinalLabelColor,
                        topLeft = Offset(
                            placed.position.x.toFloat() - layout.size.width / 2f,
                            placed.position.y.toFloat() - layout.size.height / 2f,
                        ),
                        shadow = CompassLabelShadow,
                    )
                }
            }
        }

        // Names are drawn when the constellations are switched on; a selected constellation is named whatever the switch says.
        val litNames = selectedConstellation?.figureNames.orEmpty()
        labels.forEachIndexed { i, label ->
            val lit = label.name in litNames
            if (!showConstellations && !lit) return@forEachIndexed
            val point = SkyProjection.project(mvp, label.direction, width, height) ?: return@forEachIndexed
            if (!SkyProjection.isOnScreen(point, width, height)) return@forEachIndexed
            val layout = measuredLabels[i]
            drawText(
                layout,
                color = if (lit) ConstellationHighlightColor else ConstellationLabelColor,
                shadow = if (lit) CompassLabelShadow else null,
                topLeft = Offset(
                    point.x.toFloat() - layout.size.width / 2f,
                    point.y.toFloat() - layout.size.height / 2f,
                ),
            )
        }

        // The stars of a selected constellation's figure get a ring each; their lines are lit by the renderer.
        selectedConstellation?.figureStars?.forEach { star ->
            val point = SkyProjection.project(mvp, star.direction, width, height) ?: return@forEach
            val radiusPx = (HIGHLIGHT_STAR_RING_BASE_DP + HIGHLIGHT_STAR_RING_PER_MAGNITUDE_DP * (5.0 - star.vmag).coerceIn(0.0, 6.0).toFloat()).dp.toPx()
            if (!SkyProjection.isOnScreen(point, width, height, marginPx = radiusPx.toDouble())) return@forEach
            drawCircle(
                ConstellationHighlightColor,
                radius = radiusPx,
                center = Offset(point.x.toFloat(), point.y.toFloat()),
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }

        // Deep-sky objects are named below their symbol, or inside it if it is huge. The symbol's
        // extent is worked out as the renderer does, from the field of view the last frame used.
        val focalPx = (height / 2.0) / tan(Math.toRadians(renderInputs.fovYDeg / 2))
        val minDrawnRadiusPx = DeepSkyAppearance.MIN_RADIUS_DP * density.toDouble()
        if (showDeepSky) {
            deepSkyLabels.forEachIndexed { i, label ->
                val point = SkyProjection.project(mvp, label.direction, width, height) ?: return@forEachIndexed
                if (!SkyProjection.isOnScreen(point, width, height)) return@forEachIndexed
                val layout = measuredDeepSkyLabels[i]
                val radiusPx = DeepSkyAppearance.drawnRadiusPx(label.obj, focalPx, minDrawnRadiusPx).toFloat()
                val centreY = if (radiusPx > DEEP_SKY_LABEL_INSIDE_RADIUS_DP.dp.toPx()) {
                    point.y.toFloat()
                } else {
                    point.y.toFloat() + radiusPx + DEEP_SKY_LABEL_GAP_DP.dp.toPx() + layout.size.height / 2f
                }
                drawText(
                    layout,
                    color = DeepSkyLabelColor,
                    topLeft = Offset(point.x.toFloat() - layout.size.width / 2f, centreY - layout.size.height / 2f),
                )
            }
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

        val selectedDirection = selectedStar?.direction ?: selectedBody?.direction ?: selectedDeepSky?.direction
        if (selectedDirection != null) {
            val point = SkyProjection.project(mvp, selectedDirection, width, height)
            val radiusPx = when {
                selectedBody != null -> maxOf(ringRadiusPx, (BodyAppearance.discRadiusDp(selectedBody) + BODY_RING_GAP_DP).dp.toPx())
                selectedDeepSky != null -> maxOf(
                    ringRadiusPx,
                    min(
                        DeepSkyAppearance.drawnRadiusPx(selectedDeepSky.obj, focalPx, minDrawnRadiusPx).toFloat() + DEEP_SKY_RING_GAP_DP.dp.toPx(),
                        DEEP_SKY_RING_MAX_RADIUS_DP.dp.toPx(),
                    ),
                )
                else -> ringRadiusPx
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

        val targetDirection = navigation?.directionIn(bodies)
        if (navigation != null && targetDirection != null) {
            val guidance = SkyNavigation.guide(mvp, targetDirection, width, height, navigationInsets)
            guidance.onScreen?.let { point ->
                val radiusPx = when (navigation.result.kind) {
                    SearchKind.STAR -> TARGET_STAR_RADIUS_DP.dp.toPx()
                    SearchKind.CONSTELLATION -> TARGET_CONSTELLATION_RADIUS_DP.dp.toPx()
                    SearchKind.DEEP_SKY -> {
                        // The tapped or searched-for object is selected, so its extent is to hand.
                        val extentPx = selectedDeepSky?.takeIf { it.direction == targetDirection }
                            ?.let { DeepSkyAppearance.drawnRadiusPx(it.obj, focalPx, minDrawnRadiusPx).toFloat() } ?: 0f
                        min(maxOf(TARGET_STAR_RADIUS_DP.dp.toPx(), extentPx + TARGET_BODY_GAP_DP.dp.toPx()), TARGET_DEEP_SKY_MAX_RADIUS_DP.dp.toPx())
                    }
                    SearchKind.BODY -> {
                        val body = bodies.firstOrNull { it.direction == targetDirection }
                        maxOf(TARGET_STAR_RADIUS_DP, (body?.let(BodyAppearance::discRadiusDp) ?: 0f) + TARGET_BODY_GAP_DP).dp.toPx()
                    }
                }
                drawTargetMarker(point, radiusPx)
            }
            guidance.arrow?.let { arrow ->
                drawGuideArrow(arrow, arrowPath)
                val layout = textMeasurer.measure("${guidance.angularDistanceDeg.roundToInt()}°", bodyLabelStyle)
                val inward = Math.toRadians(arrow.angleDeg + 180.0)
                val offset = ARROW_LABEL_OFFSET_DP.dp.toPx()
                drawText(
                    layout,
                    color = NavigationColor,
                    topLeft = Offset(
                        arrow.position.x.toFloat() + cos(inward).toFloat() * offset - layout.size.width / 2f,
                        arrow.position.y.toFloat() + sin(inward).toFloat() * offset - layout.size.height / 2f,
                    ),
                )
            }
        }
    }
}

/** A ring with four ticks pointing in, round the search target: unlike the plain selection ring it reads as "here". */
private fun DrawScope.drawTargetMarker(center: ScreenPoint, radiusPx: Float) {
    val c = Offset(center.x.toFloat(), center.y.toFloat())
    val tick = TARGET_TICK_LENGTH_DP.dp.toPx()
    drawCircle(NavigationOutlineColor, radius = radiusPx, center = c, style = Stroke(width = 4.dp.toPx()))
    drawCircle(NavigationColor, radius = radiusPx, center = c, style = Stroke(width = 2.dp.toPx()))
    for (quarter in 0 until 4) {
        val angle = Math.toRadians(90.0 * quarter)
        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        drawLine(NavigationColor, c + direction * radiusPx, c + direction * (radiusPx - tick), strokeWidth = 2.dp.toPx())
    }
}

/** [arrowPath] (pointing right) turned to the arrow's direction, with a dark outline so it shows over a bright camera image. */
private fun DrawScope.drawGuideArrow(arrow: EdgeArrow, arrowPath: Path) {
    withTransform({
        translate(arrow.position.x.toFloat(), arrow.position.y.toFloat())
        rotate(arrow.angleDeg.toFloat(), pivot = Offset.Zero) // clockwise, as screen y points down
    }) {
        drawPath(arrowPath, NavigationOutlineColor, style = Stroke(width = 4.dp.toPx()))
        drawPath(arrowPath, NavigationColor)
    }
}
