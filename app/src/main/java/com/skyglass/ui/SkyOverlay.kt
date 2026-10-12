package com.skyglass.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.skyglass.R
import com.skyglass.astro.BodyPosition
import com.skyglass.astro.CompassPoint
import com.skyglass.astro.PlanetMoon
import com.skyglass.astro.PlanetMoonPosition
import com.skyglass.astro.SkyBody
import com.skyglass.catalog.BodyAppearance
import com.skyglass.catalog.ConstellationDescription
import com.skyglass.catalog.ConstellationLabel
import com.skyglass.catalog.DeepSkyAppearance
import com.skyglass.catalog.NavigationTarget
import com.skyglass.catalog.SearchKind
import com.skyglass.catalog.SearchTarget
import com.skyglass.render.CompassLabels
import com.skyglass.render.EdgeArrow
import com.skyglass.render.NavInsets
import com.skyglass.render.ScreenPoint
import com.skyglass.render.SkyNavigation
import com.skyglass.render.SkyProjection
import com.skyglass.render.SkyRenderInputs
import com.skyglass.render.SkyViewMath
import kotlin.math.cos
import kotlin.math.min
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

/** The planets' moons: the bodies' hue, dimmer, in a smaller type, so the planet's own name leads. */
private val MoonLabelColor = Color(0xFFA8987A)

/** A moon is named (and a search for it stops saying "zoom in") once it has faded in this far. */
private const val MOON_LABEL_MIN_FADE = 0.5f
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
 * It redraws on every display frame; the draw lambda only reads state, so nothing recomposes. Sizes
 * that follow the zoom (the Sun, Moon and planets, deep-sky objects, the compass letters' raise) are
 * worked out from the focal length of that same MVP, so they match the frame on screen mid-pinch.
 *
 * A two-finger pinch reports its scale through [onPinch] (above 1 zooms in) and is never taken for a tap.
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
    moons: List<PlanetMoonPosition>,
    selectedMoon: PlanetMoonPosition?,
    deepSkyLabels: List<DeepSkyLabel>,
    selectedDeepSky: SelectedDeepSky?,
    showDeepSky: Boolean,
    selectedConstellation: ConstellationDescription?,
    showConstellations: Boolean,
    navigation: NavigationTarget?,
    navigationInsets: NavInsets,
    showHorizon: Boolean,
    onTap: (x: Float, y: Float, widthPx: Int, heightPx: Int, labelSizesPx: FloatArray) -> Unit,
    onPinch: (scale: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val measuredLabels = remember(labels, labelStyle) { labels.map { textMeasurer.measure(it.name, labelStyle) } }
    // The constellation names' drawn sizes, so a tap is tested against the words as drawn (see ConstellationHitTest.labelAt).
    val labelSizesPx = remember(measuredLabels) {
        FloatArray(2 * measuredLabels.size) { i -> measuredLabels[i / 2].size.let { if (i % 2 == 0) it.width else it.height }.toFloat() }
    }
    val currentLabelSizesPx by rememberUpdatedState(labelSizesPx)
    val measuredDeepSkyLabels = remember(deepSkyLabels, labelStyle) { deepSkyLabels.map { textMeasurer.measure(it.text, labelStyle) } }
    val bodyLabelStyle = MaterialTheme.typography.labelLarge
    val moonLabelStyle = MaterialTheme.typography.labelSmall
    val moonNames = PlanetMoon.entries.associateWith { stringResource(it.nameRes()) }
    val measuredMoonNames = remember(moonNames, moonLabelStyle) {
        moonNames.mapValues { (_, name) -> textMeasurer.measure(name, moonLabelStyle) }
    }
    val zoomHint = stringResource(R.string.moon_zoom_hint)
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
    val currentOnPinch by rememberUpdatedState(onPinch)
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
        modifier = modifier
            .pointerInput(Unit) { detectPinch { scale -> currentOnPinch(scale) } }
            .pointerInput(Unit) {
                detectTapGestures { offset: Offset ->
                    currentOnTap(offset.x, offset.y, size.width, size.height, currentLabelSizesPx)
                }
            },
    ) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick // read so the canvas is redrawn each frame
        val mvp = renderInputs.lastMvp ?: return@Canvas
        val width = size.width.toInt()
        val height = size.height.toInt()
        // The focal length of the frame on screen, for everything whose drawn size follows the zoom.
        val focalPx = SkyViewMath.focalLengthPx(mvp, height)

        if (showHorizon) {
            renderInputs.lastHorizonMvp?.let { horizonMvp ->
                val edgeMarginPx = COMPASS_LABEL_EDGE_MARGIN_DP.dp.toPx().toDouble()
                val raiseDeg = CompassLabels.anchorAltitudeDeg(focalPx, density)
                for (placed in CompassLabels.place(horizonMvp, width, height, edgeMarginPx, raiseDeg)) {
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

        // Named just below each disc, so the label never covers the body itself; zoomed in, the disc is
        // drawn at its true size and the label moves out with its edge.
        // What a moon's name must not cover: each body's glow and name, and the moons' own dots.
        val takenLabelRects = ArrayList<Rect>()
        for (body in bodies) {
            val point = SkyProjection.project(mvp, body.direction, width, height) ?: continue
            val drawn = BodyAppearance.drawn(body, focalPx, density)
            val discPx = drawn.labelRadiusPx.toFloat()
            if (!SkyProjection.isOnScreen(point, width, height, marginPx = discPx.toDouble())) continue
            val layout = measuredBodyNames.getValue(body.body)
            val topLeft = Offset(point.x.toFloat() - layout.size.width / 2f, point.y.toFloat() + discPx + BODY_LABEL_GAP_DP.dp.toPx())
            drawText(layout, color = BodyLabelColor, topLeft = topLeft)
            takenLabelRects += Rect(topLeft, Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
            takenLabelRects += Rect(Offset(point.x.toFloat(), point.y.toFloat()), discPx)
        }

        // The planets' moons are named once they have faded in clear of their planet, in the same fade.
        // They lie in a line and Saturn's crowd together, so the brighter ones are named first, each below,
        // above, right of or left of its dot, wherever its name overlaps no name already drawn (a planet's
        // included); one with no room is left unnamed, and can still be tapped.
        val labelGapPx = BODY_LABEL_GAP_DP.dp.toPx()
        for (moon in moons) {
            if (moon.hidden || BodyAppearance.satelliteFade(moon.separationTan, focalPx, density) <= 0f) continue
            val point = SkyProjection.project(mvp, moon.direction, width, height) ?: continue
            takenLabelRects += Rect(Offset(point.x.toFloat(), point.y.toFloat()), BodyAppearance.drawn(moon, density).labelRadiusPx.toFloat())
        }
        for (moon in moons.sortedBy { it.vmag }) {
            if (moon.hidden) continue
            val fade = BodyAppearance.satelliteFade(moon.separationTan, focalPx, density)
            if (fade < MOON_LABEL_MIN_FADE) continue
            val point = SkyProjection.project(mvp, moon.direction, width, height) ?: continue
            if (!SkyProjection.isOnScreen(point, width, height)) continue
            val layout = measuredMoonNames.getValue(moon.moon)
            val size = Size(layout.size.width.toFloat(), layout.size.height.toFloat())
            val reach = BodyAppearance.drawn(moon, density).labelRadiusPx.toFloat() + labelGapPx
            val x = point.x.toFloat()
            val y = point.y.toFloat()
            val candidates = listOf(
                Offset(x - size.width / 2, y + reach),
                Offset(x - size.width / 2, y - reach - size.height),
                Offset(x + reach, y - size.height / 2),
                Offset(x - reach - size.width, y - size.height / 2),
            )
            val rect = candidates.map { Rect(it, size) }.firstOrNull { candidate -> takenLabelRects.none { it.overlaps(candidate) } } ?: continue
            takenLabelRects += rect
            drawText(layout, color = MoonLabelColor.copy(alpha = fade), topLeft = rect.topLeft)
        }

        val selectedDirection = selectedStar?.direction ?: selectedBody?.direction ?: selectedDeepSky?.direction ?: selectedMoon?.direction
        if (selectedDirection != null) {
            val point = SkyProjection.project(mvp, selectedDirection, width, height)
            val radiusPx = when {
                selectedBody != null -> maxOf(
                    ringRadiusPx,
                    BodyAppearance.drawn(selectedBody, focalPx, density).labelRadiusPx.toFloat() + BODY_RING_GAP_DP.dp.toPx(),
                )
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

        val targetDirection = navigation?.directionIn(bodies, moons)
        if (navigation != null && targetDirection != null) {
            val guidance = SkyNavigation.guide(mvp, targetDirection, width, height, navigationInsets)
            guidance.onScreen?.let { point ->
                val radiusPx = when (navigation.result.kind) {
                    SearchKind.STAR -> TARGET_STAR_RADIUS_DP.dp.toPx()
                    // A radiant is a patch of sky a few degrees across, not a point.
                    SearchKind.CONSTELLATION, SearchKind.METEOR_SHOWER -> TARGET_CONSTELLATION_RADIUS_DP.dp.toPx()
                    SearchKind.DEEP_SKY -> {
                        // The tapped or searched-for object is selected, so its extent is to hand.
                        val extentPx = selectedDeepSky?.takeIf { it.direction == targetDirection }
                            ?.let { DeepSkyAppearance.drawnRadiusPx(it.obj, focalPx, minDrawnRadiusPx).toFloat() } ?: 0f
                        min(maxOf(TARGET_STAR_RADIUS_DP.dp.toPx(), extentPx + TARGET_BODY_GAP_DP.dp.toPx()), TARGET_DEEP_SKY_MAX_RADIUS_DP.dp.toPx())
                    }
                    SearchKind.BODY -> {
                        val body = bodies.firstOrNull { it.direction == targetDirection }
                        val discPx = body?.let { BodyAppearance.drawn(it, focalPx, density).labelRadiusPx.toFloat() } ?: 0f
                        maxOf(TARGET_STAR_RADIUS_DP.dp.toPx(), discPx + TARGET_BODY_GAP_DP.dp.toPx())
                    }
                }
                drawTargetMarker(point, radiusPx)
                // A planet's moon is not drawn until the view is zoomed in far enough to separate it.
                val targetMoon = (navigation.result.target as? SearchTarget.MoonTarget)?.let { t -> moons.firstOrNull { it.moon == t.moon } }
                if (targetMoon != null && BodyAppearance.satelliteFade(targetMoon.separationTan, focalPx, density) < MOON_LABEL_MIN_FADE) {
                    val layout = textMeasurer.measure(zoomHint, moonLabelStyle)
                    drawText(
                        layout,
                        color = NavigationColor,
                        topLeft = Offset(point.x.toFloat() - layout.size.width / 2f, point.y.toFloat() + radiusPx + BODY_LABEL_GAP_DP.dp.toPx()),
                    )
                }
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

/**
 * Reports each step of a two-finger pinch as the factor the fingers' spread changed by, and consumes
 * those moves, so the tap detector after it cancels instead of taking the gesture for a tap. One
 * finger alone is left to the tap detector.
 */
private suspend fun PointerInputScope.detectPinch(onPinch: (scale: Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) onPinch(zoom)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
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
