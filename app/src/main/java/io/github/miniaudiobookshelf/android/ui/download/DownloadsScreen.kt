package io.github.miniaudiobookshelf.android.ui.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.android.download.DownloadController
import io.github.miniaudiobookshelf.core.DownloadRecord
import io.github.miniaudiobookshelf.core.DownloadStatus
import io.github.miniaudiobookshelf.core.LibraryItem
import io.github.miniaudiobookshelf.core.MediaProgress
import io.github.miniaudiobookshelf.core.ViewLogic

@Composable
fun DownloadsScreen(
    records: List<DownloadRecord>,
    downloads: DownloadController,
    coverUrl: (DownloadRecord) -> String,
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
        val completeCount = records.count { it.complete }
        if (completeCount > 0) {
            Text(
                text = stringResource(
                    R.string.downloads_total,
                    completeCount,
                    ViewLogic.formatBytes(ViewLogic.completedDownloadBytes(records)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
                    DownloadRow(
                        row = row,
                        coverUrl = coverUrl(row),
                        downloads = downloads,
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    row: DownloadRecord,
    coverUrl: String,
    downloads: DownloadController,
    onOpen: (DownloadRecord) -> Unit,
) {
    val context = LocalContext.current
    val title = row.title.ifEmpty { row.itemId }
    val headline = if (row.author.isNotEmpty()) "$title - ${row.author}" else title
    val status = "${statusLabel(row)} - ${byteLabel(row)}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(row) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (coverUrl.isNotEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .crossfade(true)
                    .size(128)
                    .build(),
                contentDescription = title,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {}
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = headline,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.status == DownloadStatus.RUNNING || row.status == DownloadStatus.QUEUED) {
                val progress = if (row.bytesTotal > 0) row.bytesDownloaded.toFloat() / row.bytesTotal.toFloat() else 0f
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
        when (row.status) {
            DownloadStatus.QUEUED, DownloadStatus.RUNNING -> {
                IconButton(onClick = { downloads.pause(row.itemId, row.episodeId) }) {
                    Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.pause))
                }
                IconButton(onClick = { downloads.cancel(row.itemId, row.episodeId) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_download))
                }
            }
            DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                IconButton(onClick = { downloads.retry(row.itemId, row.episodeId) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.retry))
                }
                IconButton(onClick = { downloads.delete(row.itemId, row.episodeId) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_download))
                }
            }
            DownloadStatus.COMPLETE -> {
                IconButton(onClick = { downloads.delete(row.itemId, row.episodeId) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_download))
                }
            }
        }
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
    val done = ViewLogic.formatBytes(row.bytesDownloaded)
    val total = ViewLogic.formatBytes(row.bytesTotal)
    return if (row.bytesTotal > 0) "$done / $total" else done
}

fun DownloadRecord.toLibraryItem(): LibraryItem {
    val progress = if (currentTime > 0 || isFinished) {
        MediaProgress(
            libraryItemId = itemId,
            episodeId = episodeId,
            duration = duration,
            currentTime = currentTime,
            isFinished = isFinished,
            progress = if (duration > 0) (currentTime / duration).coerceIn(0.0, 1.0) else 0.0,
        )
    } else {
        null
    }
    return LibraryItem(
        id = itemId,
        episodeId = episodeId,
        title = title,
        author = author,
        numTracks = if (complete) 1 else 0,
        duration = duration,
        progress = progress,
    )
}
