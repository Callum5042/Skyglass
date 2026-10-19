package com.skyglass.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skyglass.R
import com.skyglass.catalog.CloserLook
import com.skyglass.catalog.Instrument
import com.skyglass.catalog.Viewing
import com.skyglass.catalog.ViewingDetail

private val SignRowGlyphSize = 18.dp
private val ListGlyphSize = 16.dp

/** Instruments that can't show the object, dimmed beside the lit ones that can. */
private val UnusableColor = CardSecondaryTextColor.copy(alpha = 0.35f)

/** The instrument that shows more ([CloserLook]). */
private val CloserLookColor = Color(0xFF8FC1FF)

/** The Sun's warning; night mode turns it red with everything else. */
private val DangerColor = Color(0xFFFFB74D)

/**
 * The viewing signs on a details card: the four instruments, lit from the least one that shows the
 * object (from the observer's sky) upward, the one that shows more in blue, and the least one's name.
 * For the Sun, a warning instead.
 */
@Composable
fun ViewingSignRow(viewing: Viewing, modifier: Modifier = Modifier) {
    when (viewing) {
        Viewing.SunDanger -> {
            val warning = stringResource(R.string.viewing_sun_danger)
            Row(
                modifier = modifier.padding(vertical = 2.dp).clearAndSetSemantics { contentDescription = warning },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(SignRowGlyphSize)) { drawWarning(DangerColor) }
                Spacer(Modifier.width(8.dp))
                Text(text = warning, color = DangerColor, style = MaterialTheme.typography.bodySmall)
            }
        }
        is Viewing.Needs -> {
            val description = stringResource(viewing.least.needsRes())
            Row(
                modifier = modifier.padding(vertical = 2.dp).clearAndSetSemantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (instrument in Instrument.entries) {
                    val color = when {
                        instrument == viewing.closerLook?.instrument -> CloserLookColor
                        instrument >= viewing.least -> CardTextColor
                        else -> UnusableColor
                    }
                    InstrumentGlyph(instrument, color, SignRowGlyphSize)
                }
                Text(
                    text = stringResource(viewing.least.nameRes()),
                    color = CardSecondaryTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        Viewing.BeyondReach -> {
            val description = stringResource(R.string.viewing_needs_beyond)
            Row(
                modifier = modifier.padding(vertical = 2.dp).clearAndSetSemantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (instrument in Instrument.entries) InstrumentGlyph(instrument, UnusableColor, SignRowGlyphSize)
                Text(
                    text = stringResource(R.string.viewing_beyond),
                    color = CardSecondaryTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

/** "A small telescope shows its rings.", or nothing when a bigger instrument adds nothing worth saying. */
@Composable
fun CloserLookText(viewing: Viewing) {
    val closerLook = (viewing as? Viewing.Needs)?.closerLook ?: return
    Text(
        text = stringResource(closerLook.instrument.closerRes(), stringResource(closerLook.detail.textRes())),
        color = CardTextColor,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** The least instrument that shows an object, small enough for a list row, described for accessibility. */
@Composable
fun ViewingGlyph(viewing: Viewing, modifier: Modifier = Modifier) {
    when (viewing) {
        Viewing.SunDanger -> {
            val description = stringResource(R.string.viewing_sun_danger)
            Canvas(modifier.size(ListGlyphSize).semantics { contentDescription = description }) { drawWarning(DangerColor) }
        }
        is Viewing.Needs -> {
            val description = stringResource(viewing.least.needsRes())
            InstrumentGlyph(viewing.least, CardSecondaryTextColor, ListGlyphSize, modifier.semantics { contentDescription = description })
        }
        Viewing.BeyondReach -> {
            val description = stringResource(R.string.viewing_needs_beyond)
            InstrumentGlyph(Instrument.LARGE_TELESCOPE, UnusableColor, ListGlyphSize, modifier.semantics { contentDescription = description })
        }
    }
}

/** An instrument drawn on a 24-unit grid with round 2-unit strokes, as the top bar's glyphs are. */
@Composable
private fun InstrumentGlyph(instrument: Instrument, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        when (instrument) {
            Instrument.EYE -> drawEye(color, u)
            Instrument.BINOCULARS -> drawBinoculars(color, u)
            Instrument.SMALL_TELESCOPE -> drawSmallTelescope(color, u)
            Instrument.LARGE_TELESCOPE -> drawLargeTelescope(color, u)
        }
    }
}

private fun stroke(u: Float) = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** An almond with a pupil. */
private fun DrawScope.drawEye(color: Color, u: Float) {
    val outline = Path().apply {
        moveTo(2f * u, 12f * u)
        quadraticTo(12f * u, 2f * u, 22f * u, 12f * u)
        quadraticTo(12f * u, 22f * u, 2f * u, 12f * u)
        close()
    }
    drawPath(outline, color, style = stroke(u))
    drawCircle(color, radius = 3.25f * u, center = Offset(12f * u, 12f * u))
}

/** Two barrels joined by a bridge, with their big lenses at the bottom. */
private fun DrawScope.drawBinoculars(color: Color, u: Float) {
    val s = stroke(u)
    for (left in listOf(2.5f, 14.5f)) {
        drawRoundRect(color, topLeft = Offset(left * u, 5f * u), size = Size(7f * u, 15f * u), cornerRadius = CornerRadius(2.5f * u), style = s)
        drawLine(color, Offset(left * u, 15f * u), Offset((left + 7f) * u, 15f * u), strokeWidth = s.width)
    }
    drawLine(color, Offset(9.5f * u, 10f * u), Offset(14.5f * u, 10f * u), strokeWidth = s.width)
}

/** A slim tube tilted up on a tripod. */
private fun DrawScope.drawSmallTelescope(color: Color, u: Float) {
    drawLine(color, Offset(4f * u, 12.5f * u), Offset(20f * u, 5f * u), strokeWidth = 3.5f * u, cap = StrokeCap.Round)
    drawTripod(color, u, Offset(12f * u, 10f * u))
}

/** The same telescope with a fat tube, so the two read as one family. */
private fun DrawScope.drawLargeTelescope(color: Color, u: Float) {
    drawLine(color, Offset(3.5f * u, 13f * u), Offset(20.5f * u, 4.5f * u), strokeWidth = 7f * u, cap = StrokeCap.Butt)
    drawTripod(color, u, Offset(12f * u, 12f * u))
}

private fun DrawScope.drawTripod(color: Color, u: Float, mount: Offset) {
    for (foot in listOf(5.5f, 12f, 18.5f)) {
        drawLine(color, mount, Offset(foot * u, 21.5f * u), strokeWidth = 2f * u, cap = StrokeCap.Round)
    }
}

/** A warning triangle with an exclamation mark. */
private fun DrawScope.drawWarning(color: Color) {
    val u = size.minDimension / 24f
    val triangle = Path().apply {
        moveTo(12f * u, 3f * u)
        lineTo(22f * u, 20.5f * u)
        lineTo(2f * u, 20.5f * u)
        close()
    }
    drawPath(triangle, color, style = stroke(u))
    drawLine(color, Offset(12f * u, 9.5f * u), Offset(12f * u, 14f * u), strokeWidth = 2f * u, cap = StrokeCap.Round)
    drawCircle(color, radius = 1.2f * u, center = Offset(12f * u, 17.25f * u))
}

@StringRes
private fun Instrument.nameRes(): Int = when (this) {
    Instrument.EYE -> R.string.viewing_eye
    Instrument.BINOCULARS -> R.string.viewing_binoculars
    Instrument.SMALL_TELESCOPE -> R.string.viewing_small_telescope
    Instrument.LARGE_TELESCOPE -> R.string.viewing_large_telescope
}

@StringRes
private fun Instrument.needsRes(): Int = when (this) {
    Instrument.EYE -> R.string.viewing_needs_eye
    Instrument.BINOCULARS -> R.string.viewing_needs_binoculars
    Instrument.SMALL_TELESCOPE -> R.string.viewing_needs_small_telescope
    Instrument.LARGE_TELESCOPE -> R.string.viewing_needs_large_telescope
}

/** The sentence a closer look goes into; the eye is never one. */
@StringRes
private fun Instrument.closerRes(): Int = when (this) {
    Instrument.EYE, Instrument.BINOCULARS -> R.string.viewing_closer_binoculars
    Instrument.SMALL_TELESCOPE -> R.string.viewing_closer_small_telescope
    Instrument.LARGE_TELESCOPE -> R.string.viewing_closer_large_telescope
}

@StringRes
private fun ViewingDetail.textRes(): Int = when (this) {
    ViewingDetail.PHASE -> R.string.viewing_detail_phase
    ViewingDetail.CRATERS -> R.string.viewing_detail_craters
    ViewingDetail.SURFACE_MARKINGS -> R.string.viewing_detail_surface_markings
    ViewingDetail.MOONS -> R.string.viewing_detail_moons
    ViewingDetail.RINGS -> R.string.viewing_detail_rings
    ViewingDetail.DISC -> R.string.viewing_detail_disc
    ViewingDetail.RESOLVED_STARS -> R.string.viewing_detail_resolved_stars
    ViewingDetail.RING_OR_DISC -> R.string.viewing_detail_ring_or_disc
    ViewingDetail.STRUCTURE -> R.string.viewing_detail_structure
}
