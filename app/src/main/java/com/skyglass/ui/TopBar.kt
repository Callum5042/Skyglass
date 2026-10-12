package com.skyglass.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.skyglass.R

private val IdleColor = Color(0xFF8A8A8A)
private val ActiveColor = Color(0xFFCBD2E0)
private val ButtonSize = 48.dp
private val IconSize = 24.dp

/** The drawn glyphs of the top bar, on a 24-unit grid like Material's icons. */
enum class TopBarIcon { TONIGHT, SEARCH, DISPLAY, CAMERA }

/**
 * The controls along the top: "Tonight" with its name, because it is where a beginner starts, then
 * Search, Display and the camera view as icon buttons on the right. Four labelled buttons do not fit
 * across a 360 dp screen. Every button is 48 dp tall; an open panel or the camera view lights its button.
 */
@Composable
fun TopBar(
    tonightOpen: Boolean,
    onTonightClick: () -> Unit,
    searchOpen: Boolean,
    onSearchClick: () -> Unit,
    displayOpen: Boolean,
    onDisplayClick: () -> Unit,
    cameraOn: Boolean,
    onCameraChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        TonightButton(open = tonightOpen, onClick = onTonightClick)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                icon = TopBarIcon.SEARCH,
                label = stringResource(R.string.search_toggle_label),
                state = stringResource(if (searchOpen) R.string.search_toggle_open else R.string.search_toggle_closed),
                active = searchOpen,
                onClick = onSearchClick,
            )
            IconButton(
                icon = TopBarIcon.DISPLAY,
                label = stringResource(R.string.star_limit_toggle_label),
                state = stringResource(if (displayOpen) R.string.star_limit_toggle_open else R.string.star_limit_toggle_closed),
                active = displayOpen,
                onClick = onDisplayClick,
            )
            CameraButton(on = cameraOn, onChange = onCameraChange)
        }
    }
}

@Composable
private fun TonightButton(open: Boolean, onClick: () -> Unit) {
    val stateText = stringResource(if (open) R.string.tonight_toggle_open else R.string.tonight_toggle_closed)
    val color = if (open) ActiveColor else IdleColor
    Row(
        modifier = Modifier
            .heightIn(min = ButtonSize)
            .background(CardBackground, RoundedCornerShape(24.dp))
            .activeBorder(open, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = stateText }
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarGlyph(TopBarIcon.TONIGHT, color)
        Spacer(Modifier.width(8.dp))
        Text(text = stringResource(R.string.tonight_toggle_label), color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun IconButton(icon: TopBarIcon, label: String, state: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(ButtonSize)
            .background(CardBackground, CircleShape)
            .activeBorder(active, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        TopBarGlyph(icon, if (active) ActiveColor else IdleColor)
    }
}

/** Switches between the enhanced sky (off) and the live camera image (on); a switch to accessibility. */
@Composable
private fun CameraButton(on: Boolean, onChange: (Boolean) -> Unit) {
    val label = stringResource(R.string.camera_toggle_label)
    Box(
        modifier = Modifier
            .size(ButtonSize)
            .background(CardBackground, CircleShape)
            .activeBorder(on, CircleShape)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        TopBarGlyph(TopBarIcon.CAMERA, if (on) ActiveColor else IdleColor)
    }
}

private fun Modifier.activeBorder(active: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (active) border(1.dp, ActiveColor.copy(alpha = 0.5f), shape) else this

@Composable
private fun TopBarGlyph(icon: TopBarIcon, color: Color) {
    Canvas(Modifier.size(IconSize)) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        when (icon) {
            TopBarIcon.TONIGHT -> drawPath(crescent(u), color)
            TopBarIcon.SEARCH -> {
                drawCircle(color, radius = 6.5f * u, center = p(10f, 10f), style = stroke)
                drawLine(color, p(15f, 15f), p(20.5f, 20.5f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            TopBarIcon.DISPLAY -> {
                // Three sliders with their knobs at different places.
                for ((y, knob) in listOf(6f to 15f, 12f to 8f, 18f to 13f)) {
                    drawLine(color, p(3.5f, y), p(20.5f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                    drawCircle(color, radius = 2.75f * u, center = p(knob, y))
                }
            }
            TopBarIcon.CAMERA -> drawCamera(color, stroke, u)
        }
    }
}

/** A waxing crescent: a disc with an offset disc taken out of it. */
private fun crescent(u: Float): Path {
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(12f * u, 12f * u), 8.5f * u)) }
    val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(7.5f * u, 9f * u), 7.5f * u)) }
    return Path().apply { op(disc, bite, PathOperation.Difference) }
}

private fun DrawScope.drawCamera(color: Color, stroke: Stroke, u: Float) {
    drawRoundRect(color, topLeft = Offset(3f * u, 7f * u), size = Size(18f * u, 13f * u), cornerRadius = CornerRadius(2.5f * u), style = stroke)
    val hump = Path().apply {
        moveTo(8.5f * u, 7f * u)
        lineTo(10f * u, 4.5f * u)
        lineTo(14f * u, 4.5f * u)
        lineTo(15.5f * u, 7f * u)
    }
    drawPath(hump, color, style = stroke)
    drawCircle(color, radius = 3.5f * u, center = Offset(12f * u, 13.5f * u), style = stroke)
}
