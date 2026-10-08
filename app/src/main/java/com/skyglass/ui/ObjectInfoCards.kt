package com.skyglass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skyglass.R
import com.skyglass.astro.AstroTime
import com.skyglass.astro.BodyPosition
import com.skyglass.astro.BodySize
import com.skyglass.astro.CompassPoint
import com.skyglass.astro.MoonPhase
import com.skyglass.astro.RiseSet
import com.skyglass.astro.RiseSetTimes
import com.skyglass.astro.Sexagesimal
import com.skyglass.astro.SkyBody
import com.skyglass.astro.SkyPlacement
import com.skyglass.astro.SolarSystem
import com.skyglass.catalog.StarDistance
import com.skyglass.math.Vector3
import com.skyglass.render.Observer
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import kotlin.math.abs

/** The cards are no wider than this, and the detail rows scroll beyond [DetailRowsMaxHeight], so they never take over the sky. */
private val CardMaxWidth = 360.dp
private val DetailRowsMaxHeight = 140.dp
private val LabelWidth = 112.dp

/** The size of the tap target for the close button and the "more details" row. */
private val MinTouchTarget = 48.dp

/**
 * Details of the tapped star: name, magnitude and where it is now, then (when [expanded]) its
 * constellation, spectral type, colour, distance, coordinates and rise and set times.
 *
 * @param observer where the user is; with [julianDate] it places the star in the sky (null: no position shown)
 */
@Composable
fun StarInfoCard(
    selected: SelectedStar,
    observer: Observer?,
    julianDate: Double?,
    riseSet: RiseSetTimes?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = selected.description
    val placement = placementOf(selected.direction, observer, julianDate)
    ObjectCard(
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        onDismiss = onDismiss,
        modifier = modifier,
        header = {
            Text(text = description.title, color = CardTextColor, style = MaterialTheme.typography.titleMedium)
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
            PositionSummary(placement)
        },
        details = {
            description.constellation?.let { DetailRow(stringResource(R.string.detail_label_constellation), it) }
            description.spectralType?.let { DetailRow(stringResource(R.string.detail_label_spectral_type), it) }
            description.colorName?.let { colorName ->
                val name = stringResource(colorName.nameRes())
                DetailRow(
                    stringResource(R.string.detail_label_color),
                    description.bv?.let { stringResource(R.string.detail_color_with_bv, name, it) } ?: name,
                )
            }
            DetailRow(stringResource(R.string.detail_label_distance), distanceText(description.distance))
            CoordinateRows(placement)
            RiseSetRows(riseSet, julianDate)
            Text(
                text = stringResource(R.string.star_hr, description.hr),
                color = CardSecondaryTextColor,
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}

/**
 * Details of the tapped Sun, Moon or planet: name, phase name, magnitude and where it is now, then
 * (when [expanded]) its illuminated fraction, elongation, distance, apparent size, coordinates and
 * rise and set times.
 */
@Composable
fun BodyInfoCard(
    position: BodyPosition,
    observer: Observer?,
    julianDate: Double?,
    riseSet: RiseSetTimes?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val placement = placementOf(position.direction, observer, julianDate)
    ObjectCard(
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        onDismiss = onDismiss,
        modifier = modifier,
        header = {
            Text(
                text = stringResource(position.body.nameRes()),
                color = CardTextColor,
                style = MaterialTheme.typography.titleMedium,
            )
            position.lunarPhaseDeg?.let { phase ->
                Text(
                    text = stringResource(MoonPhase.of(phase).nameRes()),
                    color = CardSecondaryTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = stringResource(R.string.star_magnitude, position.vmag),
                color = CardTextColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            PositionSummary(placement)
        },
        details = {
            // Only the Moon and the inner planets show a phase worth reporting; the rest are always near full.
            if (position.body == SkyBody.MOON || position.body == SkyBody.MERCURY || position.body == SkyBody.VENUS) {
                DetailRow(
                    stringResource(R.string.detail_label_illuminated),
                    stringResource(R.string.body_illuminated, Math.round(position.illuminatedFraction * 100).toInt()),
                )
            }
            if (position.body != SkyBody.SUN) {
                DetailRow(
                    stringResource(R.string.detail_label_elongation),
                    stringResource(R.string.body_elongation, position.elongationDeg),
                )
            }
            DetailRow(
                stringResource(R.string.detail_label_distance),
                if (position.body == SkyBody.MOON) {
                    stringResource(R.string.body_distance_km, Math.round(position.distanceAu * SolarSystem.KM_PER_AU))
                } else {
                    stringResource(R.string.body_distance_au, position.distanceAu)
                },
            )
            DetailRow(stringResource(R.string.detail_label_size), sizeText(position))
            CoordinateRows(placement)
            RiseSetRows(riseSet, julianDate)
        },
    )
}

/**
 * The frame shared by both cards: [header] and a close button always, then the toggle row, and
 * [details] between them while [expanded]. The details scroll if they are taller than
 * [DetailRowsMaxHeight]. Whether it is expanded is the caller's, so it can persist across taps.
 */
@Composable
private fun ObjectCard(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDismiss: () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    details: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeDescription = stringResource(R.string.detail_close)
    Column(
        modifier = modifier
            .widthIn(max = CardMaxWidth)
            .fillMaxWidth()
            // Taps on the card are the card's: without this they would fall through to the sky underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .background(CardBackground, RoundedCornerShape(12.dp))
            .padding(start = 16.dp, end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f).padding(top = 12.dp), content = header)
            Box(
                modifier = Modifier
                    .size(MinTouchTarget)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = closeDescription },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", color = CardSecondaryTextColor, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .heightIn(max = DetailRowsMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = details,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clickable(role = Role.Button, onClick = onToggleExpanded),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = stringResource(if (expanded) R.string.detail_show_less else R.string.detail_show_more),
                color = CardSecondaryTextColor,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            color = CardSecondaryTextColor,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(min = LabelWidth, max = LabelWidth),
        )
        Text(
            text = value,
            color = CardTextColor,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** "42.3° above the horizon · 135.2° SE"; nothing until the observer's location is known. */
@Composable
private fun PositionSummary(placement: SkyPlacement?) {
    if (placement == null) return
    Text(
        text = stringResource(
            if (placement.altitudeDeg >= 0) R.string.detail_above_horizon else R.string.detail_below_horizon,
            abs(placement.altitudeDeg),
            placement.azimuthDeg,
            stringResource(CompassPoint.of(placement.azimuthDeg).nameRes()),
        ),
        color = CardTextColor,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun CoordinateRows(placement: SkyPlacement?) {
    if (placement == null) return
    val ra = Sexagesimal.ofHours(placement.raDeg / 15.0)
    DetailRow(
        stringResource(R.string.detail_label_ra),
        stringResource(R.string.detail_ra, ra.whole, ra.minutes, ra.seconds),
    )
    val dec = Sexagesimal.of(placement.decDeg)
    DetailRow(
        stringResource(R.string.detail_label_dec),
        stringResource(
            if (dec.negative) R.string.detail_dec_south else R.string.detail_dec_north,
            dec.whole, dec.minutes, dec.seconds,
        ),
    )
}

/** Rising and setting, earlier one first; one line saying so if it never does either. */
@Composable
private fun RiseSetRows(riseSet: RiseSetTimes?, nowJd: Double?) {
    if (riseSet == null || nowJd == null) return
    if (riseSet.riseJd == null && riseSet.setJd == null) {
        DetailRow(
            stringResource(R.string.detail_label_rises),
            stringResource(if (riseSet.alwaysAbove) R.string.detail_always_above else R.string.detail_always_below),
        )
        return
    }
    val rows = listOf(R.string.detail_label_rises to riseSet.riseJd, R.string.detail_label_sets to riseSet.setJd)
    for ((label, jd) in rows.sortedBy { it.second ?: Double.MAX_VALUE }) {
        DetailRow(
            stringResource(label),
            if (jd != null) eventTimeText(jd, nowJd) else stringResource(R.string.detail_no_event, RiseSet.WINDOW_HOURS.toInt()),
        )
    }
}

/**
 * The time in the device's zone, in 12 or 24 hour form as the system setting says, with the weekday
 * in front when it is not today.
 */
@Composable
private fun eventTimeText(jd: Double, nowJd: Double): String {
    val context = LocalContext.current
    val locale = ComposeLocale.current.platformLocale
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(AstroTime.epochMillis(jd)).atZone(zone)
    val today = Instant.ofEpochMilli(AstroTime.epochMillis(nowJd)).atZone(zone).toLocalDate()
    val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
    val clock = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(time)
    if (time.toLocalDate() == today) return clock
    return stringResource(R.string.detail_time_other_day, DateTimeFormatter.ofPattern("EEE", locale).format(time), clock)
}

@Composable
private fun distanceText(distance: StarDistance?): String = when {
    distance == null -> stringResource(R.string.detail_distance_unknown)
    distance.rough -> stringResource(R.string.detail_distance_ly_rough, Math.round(distance.displayLightYears()).toInt())
    distance.lightYears < 10 -> stringResource(R.string.detail_distance_ly, distance.displayLightYears())
    else -> stringResource(R.string.detail_distance_ly_whole, Math.round(distance.displayLightYears()).toInt())
}

/** Arcminutes for the Sun and Moon, arcseconds for the planets. */
@Composable
private fun sizeText(position: BodyPosition): String {
    val arcsec = BodySize.angularDiameterArcsec(position.body, position.distanceAu)
    return if (position.body == SkyBody.SUN || position.body == SkyBody.MOON) {
        stringResource(R.string.detail_size_arcmin, arcsec / 60.0)
    } else {
        stringResource(R.string.detail_size_arcsec, arcsec)
    }
}

private fun placementOf(
    direction: Vector3,
    observer: Observer?,
    julianDate: Double?,
): SkyPlacement? =
    if (observer != null && julianDate != null) {
        SkyPlacement.of(direction, julianDate, observer.latitudeDeg, observer.longitudeDeg)
    } else {
        null
    }
