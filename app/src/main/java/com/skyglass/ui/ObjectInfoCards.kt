package com.skyglass.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.skyglass.catalog.ConstellationChart
import com.skyglass.catalog.ConstellationDescription
import com.skyglass.catalog.ConstellationOrigin
import com.skyglass.catalog.DeepSkyMeasure
import com.skyglass.catalog.DeepSkySize
import com.skyglass.catalog.MoonImage
import com.skyglass.catalog.ObjectImage
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

/** The constellation card carries a few sentences of story as well as the rows, so its details get more room. */
private val ConstellationDetailsMaxHeight = 220.dp

/** The constellation picture in the card header. */
private val ChartWidth = 112.dp
private val ChartHeight = 96.dp
private val ChartPadding = 8.dp
private val ChartBackground = Color(0xFF05070C)
private val ChartBorder = Color(0xFF1F2A3D)
private val ChartLineColor = Color(0xFF6F9BE8)
private val ChartFigureStarColor = Color(0xFFEAF0FF)
private val ChartContextStarColor = Color(0xFF6B7488)
private val LabelWidth = 112.dp

/** The photos: beside a card's header, in a planet's gallery of moons, and the widest full-size view. */
private val PhotoThumbnailSize = 96.dp
private val MoonThumbnailSize = 64.dp
private val PhotoDialogMaxWidth = 600.dp
private val PhotoDialogBackground = Color(0xF20B0D12)

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
 * Details of the tapped deep-sky object: name, other designations, type and magnitude and where it
 * is now, then (when [expanded]) its type, constellation, apparent size, coordinates and rise and
 * set times. A dark nebula has no magnitude, so that is left out.
 */
@Composable
fun DeepSkyInfoCard(
    selected: SelectedDeepSky,
    image: ObjectImage?,
    observer: Observer?,
    julianDate: Double?,
    riseSet: RiseSetTimes?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val obj = selected.obj
    val placement = placementOf(selected.direction, observer, julianDate)
    val typeName = stringResource(obj.type.nameRes())
    // The title is the common name or else the first designation, which the second line must not repeat.
    val designations = (if (obj.name != null) obj.designations else obj.designations.drop(1)).joinToString(" · ")
    var photoOpen by rememberSaveable(obj.id) { mutableStateOf(false) }
    if (photoOpen && image != null) {
        ObjectPhotoDialog(title = obj.title, image = image, onDismiss = { photoOpen = false })
    }
    ObjectCard(
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        onDismiss = onDismiss,
        modifier = modifier,
        header = {
            HeaderWithPhoto(image = image, title = obj.title, onOpenPhoto = { photoOpen = true }) {
                Text(text = obj.title, color = CardTextColor, style = MaterialTheme.typography.titleMedium)
                if (designations.isNotEmpty()) {
                    Text(text = designations, color = CardSecondaryTextColor, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = obj.vmag?.let { stringResource(R.string.dso_type_and_magnitude, typeName, it) } ?: typeName,
                    color = CardTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
                PositionSummary(placement)
            }
        },
        details = {
            DetailRow(stringResource(R.string.detail_label_type), typeName)
            DetailRow(stringResource(R.string.detail_label_constellation), obj.constellation)
            DetailRow(stringResource(R.string.detail_label_size), deepSkySizeText(DeepSkySize.of(obj)))
            CoordinateRows(placement)
            RiseSetRows(riseSet, julianDate)
            image?.let { ImageCreditRow(it) }
        },
    )
}

/**
 * Details of the tapped (or searched-for) constellation: its name, what it depicts and where the
 * middle of its figure is now, with a picture of the figure beside them; then (when [expanded]) its
 * origin, genitive, brightest stars and the story, the myth for an ancient constellation and the
 * history of the name for a modern one. The same constellation is lit up in the sky.
 */
@Composable
fun ConstellationInfoCard(
    description: ConstellationDescription,
    observer: Observer?,
    julianDate: Double?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val placement = placementOf(description.direction, observer, julianDate)
    val lore = description.lore
    ObjectCard(
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        onDismiss = onDismiss,
        modifier = modifier,
        detailsMaxHeight = ConstellationDetailsMaxHeight,
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = description.name, color = CardTextColor, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (lore != null) {
                            stringResource(R.string.constellation_subtitle, lore.figure, description.abbreviation)
                        } else {
                            description.abbreviation
                        },
                        color = CardSecondaryTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PositionSummary(
                        placement,
                        aboveRes = R.string.constellation_position_above,
                        belowRes = R.string.constellation_position_below,
                    )
                }
                description.chart?.let { chart ->
                    ConstellationChartImage(
                        chart = chart,
                        description = stringResource(R.string.constellation_chart_description, description.name),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        },
        details = {
            if (lore != null) {
                val originRes = if (lore.origin == ConstellationOrigin.ANCIENT) {
                    R.string.constellation_origin_ancient
                } else {
                    R.string.constellation_origin_modern
                }
                DetailRow(stringResource(R.string.constellation_label_origin), stringResource(originRes, lore.tradition))
            }
            DetailRow(stringResource(R.string.constellation_label_genitive), description.genitive)
            if (description.brightStars.isNotEmpty()) {
                DetailRow(
                    stringResource(R.string.constellation_label_bright_stars),
                    description.brightStars.map { stringResource(R.string.constellation_bright_star, it.title, it.vmag) }.joinToString(", "),
                )
            }
            if (lore != null) {
                Text(
                    text = stringResource(
                        if (lore.origin == ConstellationOrigin.ANCIENT) R.string.constellation_story_myth else R.string.constellation_story_history,
                    ),
                    color = CardSecondaryTextColor,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(text = lore.story, color = CardTextColor, style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

/**
 * The picture of a constellation: its stick figure and stars (and its brighter neighbours, dimmer),
 * north up and east to the left as in the sky, fitted into a small box. Drawn from [chart], which is
 * made from the catalogue positions (see `ConstellationCharts`), so it is not artwork.
 */
@Composable
private fun ConstellationChartImage(chart: ConstellationChart, description: String, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .size(ChartWidth, ChartHeight)
            .background(ChartBackground, RoundedCornerShape(8.dp))
            .border(1.dp, ChartBorder, RoundedCornerShape(8.dp))
            .semantics { contentDescription = description },
    ) {
        val padding = ChartPadding.toPx()
        val scale = minOf((size.width - 2 * padding) / chart.width, (size.height - 2 * padding) / chart.height).toFloat()
        val left = (size.width - chart.width.toFloat() * scale) / 2f
        val top = (size.height - chart.height.toFloat() * scale) / 2f
        fun at(x: Double, y: Double) = Offset(left + x.toFloat() * scale, top + y.toFloat() * scale)

        for (star in chart.stars) {
            if (!star.inFigure) {
                drawCircle(ChartContextStarColor, radius = chartStarRadius(star.vmag, inFigure = false).toPx(), center = at(star.x, star.y))
            }
        }
        for (segment in chart.segments) {
            drawLine(ChartLineColor, at(segment.x1, segment.y1), at(segment.x2, segment.y2), strokeWidth = 1.2.dp.toPx())
        }
        for (star in chart.stars) {
            if (star.inFigure) {
                drawCircle(ChartFigureStarColor, radius = chartStarRadius(star.vmag, inFigure = true).toPx(), center = at(star.x, star.y))
            }
        }
    }
}

/** A dot for a star: bigger for brighter ones, with a floor so the faint stars of a figure stay visible. */
private fun chartStarRadius(vmag: Double, inFigure: Boolean): Dp {
    val brightness = (5.0 - vmag).coerceIn(0.0, 6.0).toFloat() // 0 at magnitude 5, 6 at magnitude -1
    return if (inFigure) (1.3f + 0.45f * brightness).dp else (0.6f + 0.1f * brightness).dp
}

/**
 * Details of the tapped Sun, Moon or planet: name, phase name, magnitude and where it is now, then
 * (when [expanded]) its illuminated fraction, elongation, distance, apparent size, coordinates and
 * rise and set times.
 */
@Composable
fun BodyInfoCard(
    position: BodyPosition,
    image: ObjectImage?,
    moons: List<MoonImage>,
    observer: Observer?,
    julianDate: Double?,
    riseSet: RiseSetTimes?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val placement = placementOf(position.direction, observer, julianDate)
    val name = stringResource(position.body.nameRes())
    // The photo open full size: the body's own (PHOTO_OF_BODY) or one of its moons (by index).
    var openPhoto by rememberSaveable(position.body) { mutableStateOf<Int?>(null) }
    openPhoto?.let { index ->
        val moon = moons.getOrNull(index)
        val photo = if (index == PHOTO_OF_BODY) image else moon?.image
        if (photo != null) {
            ObjectPhotoDialog(title = moon?.name ?: name, image = photo, onDismiss = { openPhoto = null })
        }
    }
    ObjectCard(
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        onDismiss = onDismiss,
        modifier = modifier,
        header = {
            HeaderWithPhoto(image = image, title = name, onOpenPhoto = { openPhoto = PHOTO_OF_BODY }) {
                Text(text = name, color = CardTextColor, style = MaterialTheme.typography.titleMedium)
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
            }
        },
        details = {
            if (moons.isNotEmpty()) MoonGallery(moons, onOpen = { openPhoto = it })
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
            image?.let { ImageCreditRow(it) }
        },
    )
}

/** [BodyInfoCard]'s open photo is the body's own rather than one of its moons'. */
private const val PHOTO_OF_BODY = -1

/** The header's text with, when there is one, a photo of the object beside it that opens full size when tapped. */
@Composable
private fun HeaderWithPhoto(image: ObjectImage?, title: String, onOpenPhoto: () -> Unit, text: @Composable ColumnScope.() -> Unit) {
    if (image == null) {
        Column(content = text)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f), content = text)
        ObjectPhotoThumbnail(
            image = image,
            description = stringResource(R.string.object_photo_description, title),
            size = PhotoThumbnailSize,
            onClick = onOpenPhoto,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

/**
 * A photo fitted into a square of [size] (not cropped, so Saturn keeps its rings), on the chart's
 * dark background and border, which also keep the card from jumping while it loads.
 */
@Composable
private fun ObjectPhotoThumbnail(image: ObjectImage, description: String, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bitmap = rememberObjectImage(image, minSidePx = with(LocalDensity.current) { size.roundToPx() })
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(ChartBackground)
            .border(1.dp, ChartBorder, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
        }
    }
}

/** A planet's moons in a row that scrolls sideways, each with its name; tapping one opens it full size. */
@Composable
private fun MoonGallery(moons: List<MoonImage>, onOpen: (Int) -> Unit) {
    Text(
        text = stringResource(R.string.detail_label_moons),
        color = CardSecondaryTextColor,
        style = MaterialTheme.typography.bodySmall,
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        moons.forEachIndexed { index, moon ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(MoonThumbnailSize)) {
                ObjectPhotoThumbnail(
                    image = moon.image,
                    description = stringResource(R.string.object_photo_description, moon.name),
                    size = MoonThumbnailSize,
                    onClick = { onOpen(index) },
                )
                Text(
                    text = moon.name,
                    color = CardTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ImageCreditRow(image: ObjectImage) {
    DetailRow(stringResource(R.string.detail_label_image), stringResource(R.string.object_photo_credit, image.credit, image.licence))
}

/**
 * The photo as large as the screen allows, with its title and credit; tapping the photo, the close
 * button, outside it or Back closes it.
 */
@Composable
private fun ObjectPhotoDialog(title: String, image: ObjectImage, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val bitmap = rememberObjectImage(image, minSidePx = minOf(image.width, image.height))
        val closeDescription = stringResource(R.string.object_photo_close)
        Column(
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = PhotoDialogMaxWidth)
                .fillMaxWidth()
                .background(PhotoDialogBackground, RoundedCornerShape(12.dp))
                .padding(start = 16.dp, end = 4.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = CardTextColor,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
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
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .fillMaxWidth()
                    .aspectRatio(image.width.toFloat() / image.height)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChartBackground)
                    .clickable(onClick = onDismiss),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.object_photo_description, title),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            Text(
                text = stringResource(R.string.object_photo_credit, image.credit, image.licence),
                color = CardSecondaryTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, end = 12.dp),
            )
        }
    }
}

/**
 * The frame shared by both cards: [header] and a close button always, then the toggle row, and
 * [details] between them while [expanded]. The details scroll if they are taller than
 * [detailsMaxHeight]. Whether it is expanded is the caller's, so it can persist across taps.
 */
@Composable
private fun ObjectCard(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDismiss: () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    details: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    detailsMaxHeight: Dp = DetailRowsMaxHeight,
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
                    .heightIn(max = detailsMaxHeight)
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
private fun PositionSummary(
    placement: SkyPlacement?,
    aboveRes: Int = R.string.detail_above_horizon,
    belowRes: Int = R.string.detail_below_horizon,
) {
    if (placement == null) return
    Text(
        text = stringResource(
            if (placement.altitudeDeg >= 0) aboveRes else belowRes,
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

@Composable
private fun deepSkySizeText(size: DeepSkySize): String {
    val major = measureText(size.major)
    val minor = size.minor ?: return major
    return stringResource(R.string.detail_size_pair, major, measureText(minor))
}

@Composable
private fun measureText(measure: DeepSkyMeasure): String = when (measure.unit) {
    DeepSkyMeasure.Unit.DEGREES -> stringResource(R.string.detail_size_deg, measure.value)
    DeepSkyMeasure.Unit.ARCMINUTES ->
        stringResource(if (measure.value >= 10.0) R.string.detail_size_arcmin_whole else R.string.detail_size_arcmin, measure.value)
    DeepSkyMeasure.Unit.ARCSECONDS -> stringResource(R.string.detail_size_arcsec_whole, measure.value)
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
