package io.github.miniaudiobookshelf.android.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.core.QueueEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    entries: List<QueueEntry>,
    onDismiss: () -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onClear: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.up_next), style = MaterialTheme.typography.titleMedium)
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.clear_queue))
                }
            }
        }
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.queue_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn {
                itemsIndexed(entries, key = { _, entry -> "${entry.itemId}:${entry.episodeId}" }) { index, entry ->
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { Text(entry.showTitle) },
                        leadingContent = {
                            Row {
                                IconButton(
                                    onClick = { onMove(index, index - 1) },
                                    enabled = index > 0,
                                ) {
                                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
                                }
                                IconButton(
                                    onClick = { onMove(index, index + 1) },
                                    enabled = index < entries.lastIndex,
                                ) {
                                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
                                }
                            }
                        },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { onPlay(index) }) {
                                    Text(stringResource(R.string.play))
                                }
                                IconButton(onClick = { onRemove(index) }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove_from_queue))
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
