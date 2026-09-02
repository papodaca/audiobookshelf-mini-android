package io.github.miniaudiobookshelf.android.ui.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.android.download.DownloadController
import io.github.miniaudiobookshelf.core.DownloadRecord
import io.github.miniaudiobookshelf.core.DownloadStatus
import io.github.miniaudiobookshelf.core.LibraryItem

@Composable
fun DownloadsScreen(
    records: List<DownloadRecord>,
    downloads: DownloadController,
    onOpen: (DownloadRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    var allowMetered by remember { mutableStateOf(downloads.allowMetered()) }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.allow_metered_downloads),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = allowMetered,
                onCheckedChange = { checked ->
                    allowMetered = checked
                    downloads.setAllowMetered(checked)
                },
            )
        }
        if (records.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.downloads_empty), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.downloads_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            ) {
                items(records, key = { it.itemId + ":" + it.episodeId }) { row ->
                    DownloadRow(row = row, downloads = downloads, onOpen = onOpen)
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    row: DownloadRecord,
    downloads: DownloadController,
    onOpen: (DownloadRecord) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(row) }
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(row.title.ifEmpty { row.itemId }, style = MaterialTheme.typography.titleMedium)
        if (row.author.isNotEmpty()) {
            Text(row.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(statusLabel(row), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (row.status == DownloadStatus.RUNNING || row.status == DownloadStatus.QUEUED) {
            val progress = if (row.bytesTotal > 0) row.bytesDownloaded.toFloat() / row.bytesTotal.toFloat() else 0f
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (row.status) {
                DownloadStatus.QUEUED, DownloadStatus.RUNNING -> {
                    TextButton(onClick = { downloads.pause(row.itemId, row.episodeId) }) {
                        Text(stringResource(R.string.pause))
                    }
                    TextButton(onClick = { downloads.cancel(row.itemId, row.episodeId) }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                    TextButton(onClick = { downloads.retry(row.itemId, row.episodeId) }) {
                        Text(stringResource(R.string.retry))
                    }
                    TextButton(onClick = { downloads.delete(row.itemId, row.episodeId) }) {
                        Text(stringResource(R.string.delete_download))
                    }
                }
                DownloadStatus.COMPLETE -> {
                    TextButton(onClick = { downloads.delete(row.itemId, row.episodeId) }) {
                        Text(stringResource(R.string.delete_download))
                    }
                }
            }
        }
        Text(byteLabel(row), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun statusLabel(row: DownloadRecord): String {
    return when {
        row.waitingUnmetered -> stringResource(R.string.download_waiting_wifi)
        row.status == DownloadStatus.QUEUED -> stringResource(R.string.download_queued)
        row.status == DownloadStatus.RUNNING -> stringResource(R.string.download_running)
        row.status == DownloadStatus.PAUSED -> stringResource(R.string.download_paused)
        row.status == DownloadStatus.FAILED -> row.error.ifEmpty { stringResource(R.string.download_failed) }
        row.status == DownloadStatus.COMPLETE -> stringResource(R.string.downloaded)
        else -> ""
    }
}

private fun byteLabel(row: DownloadRecord): String {
    val done = formatBytes(row.bytesDownloaded)
    val total = formatBytes(row.bytesTotal)
    return if (row.bytesTotal > 0) "$done / $total" else done
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

fun DownloadRecord.toLibraryItem(): LibraryItem = LibraryItem(
    id = itemId,
    episodeId = episodeId,
    title = title,
    author = author,
    numTracks = if (complete) 1 else 0,
)
