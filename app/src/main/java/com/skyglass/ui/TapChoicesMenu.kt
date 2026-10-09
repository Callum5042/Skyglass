package com.skyglass.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.skyglass.R
import com.skyglass.catalog.SearchResult
import kotlin.math.roundToInt

/** Space between the finger and the menu, so the menu does not sit on the spot it is about. */
private val MenuGap = 12.dp

/**
 * "What's here?": the things under the finger when a tap lands on several at once (see
 * `TapResolver`), one row each, as the search panel shows them. Anchored at the tap (below it, or above
 * when there is no room), it is a popup: Back or a tap outside closes it, and that tap does nothing else.
 *
 * Place it in a box that fills the sky view, so the tap's coordinates are the box's.
 */
@Composable
fun TapChoicesMenu(
    choices: TapChoices,
    onChoose: (SearchResult) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.offset { IntOffset(choices.x.roundToInt(), choices.y.roundToInt()) }.size(0.dp)) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismiss,
            offset = DpOffset(0.dp, MenuGap),
            shape = RoundedCornerShape(12.dp),
            containerColor = CardBackground,
            shadowElevation = 0.dp,
            modifier = Modifier.widthIn(min = 240.dp, max = 340.dp),
        ) {
            Text(
                text = stringResource(R.string.tap_choices_title),
                color = CardSecondaryTextColor,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            for (result in choices.results) {
                ResultRow(result, onClick = { onChoose(result) })
            }
        }
    }
}
