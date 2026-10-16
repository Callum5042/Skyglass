package com.skyglass.ui

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skyglass.R
import com.skyglass.astro.CompassPoint
import com.skyglass.astro.MoonPhase
import com.skyglass.astro.RiseSetTimes
import com.skyglass.astro.SkyPlacement
import com.skyglass.catalog.Bortle
import com.skyglass.catalog.Darkness
import com.skyglass.catalog.SearchTarget
import com.skyglass.catalog.SkyHeight
import com.skyglass.catalog.Tonight
import com.skyglass.catalog.TonightMoon
import com.skyglass.catalog.TonightShower
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Leaves room for the guidance banner and status lines under it on a small phone. */
private val TonightListMaxHeight = 440.dp
private val MinTouchTarget = 48.dp

/** One line of the list: what it is, a line about it, and where to look. */
private class TonightRow(val target: SearchTarget, val title: String, val detail: String, val where: String)

/**
 * What is worth seeing tonight (see [Tonight]): when it is dark, then the Moon, the planets that are
 * up, any meteor shower and bright deep-sky objects, each with where to look. Choosing a row guides to
 * it ([onSelect]), as choosing a search result does.
 *
 * @param plan null while it is being worked out
 * @param locationKnown false until the first fix, which the plan needs
 * @param skyBrightness the light-pollution map's sky brightness at the observer (mag/arcsec²), shown
 *   as a Bortle class under the darkness times; null hides the line
 */
@Composable
fun TonightPanel(
    plan: Tonight.Plan?,
    locationKnown: Boolean,
    skyBrightness: Double?,
    onSelect: (SearchTarget) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeDescription = stringResource(R.string.tonight_close)
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            // Taps on the panel are the panel's: without this they would fall through to the sky underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .background(CardBackground, RoundedCornerShape(12.dp)),
    ) {
        Row(modifier = Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.tonight_title),
                color = CardTextColor,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(MinTouchTarget)
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = closeDescription },
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", color = CardSecondaryTextColor, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (plan == null) {
            Text(
                text = stringResource(if (locationKnown) R.string.tonight_working_out else R.string.status_waiting_for_location),
                color = CardSecondaryTextColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            return@Column
        }
        DarknessLines(plan)
        skyBrightness?.let { sqm ->
            Text(
                text = stringResource(R.string.sky_brightness_here, Bortle.classForSkyBrightness(sqm), sqm),
                color = CardSecondaryTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
        val moon = moonRow(plan.moon, plan.nowJd)
        val planets = plan.planets.map { planet ->
            TonightRow(
                SearchTarget.BodyTarget(planet.position.body),
                stringResource(planet.position.body.nameRes()),
                planet.setJd?.let { stringResource(R.string.tonight_sets, eventTimeText(it, plan.nowJd)) } ?: stringResource(R.string.tonight_up_all_night),
                whereText(planet.placement),
            )
        }
        val showers = plan.showers.map { shower ->
            TonightRow(SearchTarget.RadiantTarget(shower.shower.code), shower.shower.name, showerDetail(shower), whereText(shower.placement))
        }
        val deepSky = plan.deepSky.map { item ->
            TonightRow(SearchTarget.DeepSkyTarget(item.obj.id), item.obj.title, stringResource(item.obj.type.nameRes()), whereText(item.placement))
        }
        LazyColumn(modifier = Modifier.heightIn(max = TonightListMaxHeight).padding(bottom = 8.dp)) {
            rows(listOf(moon), onSelect)
            section(R.string.tonight_section_planets, planets, onSelect)
            section(R.string.tonight_section_showers, showers, onSelect)
            section(R.string.tonight_section_deep_sky, deepSky, onSelect)
        }
    }
}

private fun LazyListScope.section(@StringRes heading: Int, rows: List<TonightRow>, onSelect: (SearchTarget) -> Unit) {
    if (rows.isEmpty()) return
    item {
        Text(
            text = stringResource(heading),
            color = NavigationColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
    }
    rows(rows, onSelect)
}

private fun LazyListScope.rows(rows: List<TonightRow>, onSelect: (SearchTarget) -> Unit) {
    items(rows) { row -> RowItem(row, onClick = { onSelect(row.target) }) }
}

@Composable
private fun RowItem(row: TonightRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.title, color = CardTextColor, style = MaterialTheme.typography.bodyLarge)
            Text(text = row.detail, color = CardSecondaryTextColor, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = row.where,
            color = CardTextColor,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 120.dp),
        )
    }
}

/** When it is dark, and, if that is not now, that the list is for when it gets dark. */
@Composable
private fun DarknessLines(plan: Tonight.Plan) {
    val darkness = when (val d = plan.darkness) {
        is Darkness.Now -> d.endJd?.let { stringResource(R.string.tonight_dark_now, eventTimeText(it, plan.nowJd)) }
            ?: stringResource(R.string.tonight_dark_all_day)
        is Darkness.Later -> d.endJd?.let { stringResource(R.string.tonight_dark_window, eventTimeText(d.startJd, plan.nowJd), eventTimeText(it, plan.nowJd)) }
            ?: stringResource(R.string.tonight_dark_from, eventTimeText(d.startJd, plan.nowJd))
        Darkness.None -> stringResource(R.string.tonight_no_darkness)
    }
    val moment = when {
        plan.isNow -> null
        plan.darkness is Darkness.Later -> stringResource(R.string.tonight_shown_at_dark, eventTimeText(plan.atJd, plan.nowJd))
        else -> stringResource(R.string.tonight_shown_at_darkest, eventTimeText(plan.atJd, plan.nowJd))
    }
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
        Text(text = darkness, color = CardSecondaryTextColor, style = MaterialTheme.typography.bodySmall)
        moment?.let { Text(text = it, color = CardSecondaryTextColor, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Phase and how much is lit, then when it sets if it is up, else when it rises. */
@Composable
private fun moonRow(moon: TonightMoon, nowJd: Double): TonightRow {
    val position = moon.position
    val phase = stringResource(
        R.string.tonight_moon_phase,
        stringResource(MoonPhase.of(position.lunarPhaseDeg ?: 0.0).nameRes()),
        (position.illuminatedFraction * 100).roundToInt(),
    )
    val riseSet = moon.riseSet
    val up = riseSet.isUpAtStart()
    val event = when {
        up && riseSet.setJd != null -> stringResource(R.string.tonight_moon_sets, eventTimeText(riseSet.setJd, nowJd))
        !up && riseSet.riseJd != null -> stringResource(R.string.tonight_moon_rises, eventTimeText(riseSet.riseJd, nowJd))
        up -> stringResource(R.string.tonight_moon_up_all_night)
        else -> stringResource(R.string.tonight_moon_down_all_night)
    }
    val where = if (up && moon.placement.altitudeDeg > 0) whereText(moon.placement) else stringResource(R.string.tonight_where_below)
    return TonightRow(SearchTarget.BodyTarget(position.body), stringResource(R.string.body_moon), stringResource(R.string.tonight_detail_pair, phase, event), where)
}

/**
 * Up when the times were worked out from, by [RiseSetTimes]' own reckoning (which counts the Moon as
 * risen when its upper limb clears the horizon, refraction included): it sets before it next rises.
 */
private fun RiseSetTimes.isUpAtStart(): Boolean =
    alwaysAbove || (setJd != null && (riseJd == null || setJd < riseJd))

/** When the shower peaks and how strong it is then. */
@Composable
private fun showerDetail(item: TonightShower): String {
    val zhr = item.shower.zhr
    return when {
        item.daysFromPeak == 0 -> stringResource(R.string.tonight_shower_peak_tonight, zhr)
        item.daysFromPeak == -1 -> stringResource(R.string.tonight_shower_peak_tomorrow, zhr)
        item.daysFromPeak < 0 -> stringResource(R.string.tonight_shower_peak_on, dayMonthText(item.peakDate), zhr)
        else -> stringResource(R.string.tonight_shower_peaked, dayMonthText(item.peakDate), zhr)
    }
}

/** "Low in the SE", "Overhead", "Below the horizon". */
@Composable
private fun whereText(placement: SkyPlacement): String {
    val direction = stringResource(CompassPoint.of(placement.azimuthDeg).nameRes())
    return when (SkyHeight.of(placement.altitudeDeg)) {
        SkyHeight.BELOW -> stringResource(R.string.tonight_where_below)
        SkyHeight.LOW -> stringResource(R.string.tonight_where_low, direction)
        SkyHeight.HALFWAY -> stringResource(R.string.tonight_where_halfway, direction)
        SkyHeight.HIGH -> stringResource(R.string.tonight_where_high, direction)
        SkyHeight.OVERHEAD -> stringResource(R.string.tonight_where_overhead)
    }
}

/** "13 Aug" or "Aug 13", as the locale writes a day and month. */
@Composable
private fun dayMonthText(date: LocalDate): String {
    val locale = ComposeLocale.current.platformLocale
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(date)
}
