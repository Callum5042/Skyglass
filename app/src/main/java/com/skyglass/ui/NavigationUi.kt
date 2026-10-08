package com.skyglass.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.skyglass.R
import com.skyglass.astro.BodyPosition
import com.skyglass.astro.SkyPlacement
import com.skyglass.catalog.NavigationTarget
import com.skyglass.catalog.SearchResult
import com.skyglass.catalog.StarVisibility
import com.skyglass.astro.CompassPoint
import com.skyglass.render.Observer

private val SearchTextColor = Color(0xFF8A8A8A)
private val SearchActiveTextColor = Color(0xFFCBD2E0)

/** About six rows; beyond it the results scroll, so the panel never covers much of the sky or sits under the keyboard. */
private val ResultsMaxHeight = 288.dp
private val MinTouchTarget = 48.dp

/** Opens and closes the [SearchPanel]. At least 48 dp tall, like the other toggles. */
@Composable
fun SearchToggle(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val stateText = stringResource(if (expanded) R.string.search_toggle_open else R.string.search_toggle_closed)
    Box(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .background(CardBackground, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = stateText }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.search_toggle_label),
            color = if (expanded) SearchActiveTextColor else SearchTextColor,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * A text field with the best matches under it. Choosing one is [onSelect]'s business (it starts the
 * guidance and closes the panel); the keyboard's search key chooses the first.
 *
 * @param search the view model's search; called on every keystroke, which is cheap (a few ms over the whole catalogue)
 * @param catalogueReady false while the stars are still loading, when [search] has nothing to look in
 */
@Composable
fun SearchPanel(
    search: (String) -> List<SearchResult>,
    catalogueReady: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (SearchResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val results = remember(query, catalogueReady) { search(query) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    val hint = stringResource(R.string.search_hint)
    val closeDescription = stringResource(R.string.search_close)

    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            // Taps on the panel are the panel's: without this they would fall through to the sky underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .background(CardBackground, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier.heightIn(min = MinTouchTarget).padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(text = hint, color = CardSecondaryTextColor, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = CardTextColor),
                    cursorBrush = SolidColor(NavigationColor),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = {
                        val first = results.firstOrNull()
                        if (first != null) onSelect(first) else keyboard?.hide()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = hint },
                )
            }
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
        when {
            !catalogueReady -> PanelMessage(stringResource(R.string.status_loading_stars))
            results.isEmpty() -> PanelMessage(stringResource(R.string.search_no_matches))
            else -> LazyColumn(modifier = Modifier.heightIn(max = ResultsMaxHeight)) {
                items(results) { result -> ResultRow(result, onClick = { onSelect(result) }) }
            }
        }
    }
}

@Composable
private fun PanelMessage(text: String) {
    Text(
        text = text,
        color = CardSecondaryTextColor,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    )
}

@Composable
private fun ResultRow(result: SearchResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = result.title, color = CardTextColor, style = MaterialTheme.typography.bodyLarge)
            result.detail?.let {
                Text(text = it, color = CardSecondaryTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            text = stringResource(result.kind.labelRes()),
            color = CardSecondaryTextColor,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * What the user is being guided to, with a button to stop, and the reasons the target may not be
 * where they would look for it: below the horizon, or a star that the faintest-stars slider hides.
 * The arrow and marker themselves are drawn over the sky by `SkyOverlay`.
 */
@Composable
fun NavigationBanner(
    navigation: NavigationTarget,
    bodies: List<BodyPosition>,
    observer: Observer?,
    julianDate: Double?,
    limitingMagnitude: Double,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = navigation.result.title
    val direction = navigation.directionIn(bodies)
    val placement = remember(direction, observer, julianDate) {
        if (direction != null && observer != null && julianDate != null) {
            SkyPlacement.of(direction, julianDate, observer.latitudeDeg, observer.longitudeDeg)
        } else {
            null
        }
    }
    val stopDescription = stringResource(R.string.navigation_stop_description, title)
    Row(
        modifier = modifier
            .background(CardBackground, RoundedCornerShape(24.dp))
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.navigation_title, title),
                color = NavigationColor,
                style = MaterialTheme.typography.labelLarge,
            )
            if (placement != null && placement.altitudeDeg < 0) {
                Text(
                    text = stringResource(
                        R.string.navigation_below_horizon,
                        stringResource(CompassPoint.of(placement.azimuthDeg).nameRes()),
                    ),
                    color = CardSecondaryTextColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val vmag = navigation.result.vmag
            if (vmag != null && !StarVisibility.isTappable(vmag, limitingMagnitude)) {
                Text(
                    text = stringResource(R.string.navigation_hidden_by_limit),
                    color = CardSecondaryTextColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Box(
            modifier = Modifier
                .heightIn(min = MinTouchTarget)
                .widthIn(min = MinTouchTarget)
                .clickable(role = Role.Button, onClick = onStop)
                .semantics { contentDescription = stopDescription }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.navigation_stop),
                color = SearchActiveTextColor,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
