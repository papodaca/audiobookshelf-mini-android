package io.github.miniaudiobookshelf.android.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.miniaudiobookshelf.android.MiniAbsApplication
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.android.browse.BrowseController
import io.github.miniaudiobookshelf.android.browse.BrowseUi
import io.github.miniaudiobookshelf.core.DownloadPolicy
import io.github.miniaudiobookshelf.core.DownloadRecord
import io.github.miniaudiobookshelf.core.DownloadStatus
import io.github.miniaudiobookshelf.core.LibraryItem
import io.github.miniaudiobookshelf.core.ViewLogic

@Composable
fun DetailScreen(
    browse: BrowseController,
    ui: BrowseUi,
    canDownload: Boolean,
    modifier: Modifier = Modifier,
) {
    val item = ui.detail ?: return
    when {
        ui.error != null && !ui.loading -> StatusPane(
            title = stringResource(R.string.detail_error),
            body = ui.error,
            onRetry = browse::retry,
            modifier = modifier,
        )
        ui.loading && item.title.isEmpty() -> Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        else -> DetailBody(browse = browse, ui = ui, item = item, canDownload = canDownload, modifier = modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailBody(
    browse: BrowseController,
    ui: BrowseUi,
    item: LibraryItem,
    canDownload: Boolean,
    modifier: Modifier = Modifier,
) {
    val episodeId = ui.detailEpisodeId
    val episode = if (episodeId.isNotEmpty()) item.episodeById(episodeId) else null
    val isEpisode = episodeId.isNotEmpty()
    val isShow = !isEpisode && item.mediaType == "podcast"
    val title = when {
        isEpisode -> ViewLogic.episodeLabel(episode ?: item.copy(episodeId = episodeId))
        else -> item.title
    }
    val subtitle = when {
        isEpisode -> item.title.ifEmpty { episode?.author.orEmpty() }
        else -> item.author
    }
    val playTarget = when {
        isEpisode -> episode ?: item.copy(episodeId = episodeId)
        isShow -> ViewLogic.firstUnfinishedEpisode(item)
        else -> item
    }
    val decision = playTarget?.let { ViewLogic.playDecision(it) }
    val progressTarget = if (isShow) null else (episode ?: item)
    val context = LocalContext.current
    val coverUrl = remember(item.id) { browse.coverUrl(item.id) }
    val app = context.applicationContext as MiniAbsApplication
    val records by app.downloadController.records.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isShow) {
                        Button(
                            onClick = { playTarget?.let { browse.play(it) } },
                            enabled = playTarget != null,
                        ) {
                            Text(stringResource(R.string.play))
                        }
                    } else {
                        val playable = decision != null && decision != ViewLogic.PlayDecision.NOT_PLAYABLE
                        Button(
                            onClick = {
                                if (playTarget != null && decision != null) {
                                    browse.play(playTarget, startOver = decision == ViewLogic.PlayDecision.START_OVER)
                                }
                            },
                            enabled = playable,
                        ) {
                            Text(decision?.let { ViewLogic.playLabel(it) } ?: stringResource(R.string.play))
                        }
                    }
                    if (isEpisode && decision != ViewLogic.PlayDecision.NOT_PLAYABLE) {
                        OutlinedButton(onClick = { browse.enqueue(playTarget ?: item, item) }) {
                            Text(stringResource(R.string.add_to_queue))
                        }
                    }
                }
                val downloadTarget = if (isShow) null else (episode ?: item)
                if (downloadTarget != null) {
                    DownloadButtons(
                        item = item,
                        episodeId = downloadTarget.episodeId,
                        target = downloadTarget,
                        canDownload = canDownload,
                        record = records.firstOrNull { it.itemId == item.id && it.episodeId == downloadTarget.episodeId },
                    )
                }
                if (decision == ViewLogic.PlayDecision.NOT_PLAYABLE || (isShow && playTarget == null)) {
                    Text(
                        text = ViewLogic.unplayableExplanation(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (progressTarget != null) {
                    val summary = ViewLogic.progressSummary(progressTarget)
                    if (summary != null) {
                        LinearProgressIndicator(
                            progress = { ViewLogic.progressFraction(progressTarget).toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .crossfade(true)
                    .size(320)
                    .build(),
                contentDescription = title,
                modifier = Modifier
                    .width(160.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
        }

        if (isShow && item.hasEpisodes) {
            EpisodeList(browse = browse, ui = ui, show = item)
        } else if (!isEpisode && item.hasChapters) {
            item.chapters.forEach { chapter ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { browse.onChapter(chapter) }
                        .padding(vertical = 8.dp),
                ) {
                    Text(chapter.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = ViewLogic.formatClock(chapter.start),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeList(
    browse: BrowseController,
    ui: BrowseUi,
    show: LibraryItem,
) {
    var expanded by remember { mutableStateOf(false) }
    val sortLabel = if (ui.newestFirst) {
        stringResource(R.string.newest_first)
    } else {
        stringResource(R.string.oldest_first)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.episodes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = sortLabel,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.newest_first)) },
                    onClick = {
                        if (!ui.newestFirst) browse.toggleEpisodeSort()
                        expanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.oldest_first)) },
                    onClick = {
                        if (ui.newestFirst) browse.toggleEpisodeSort()
                        expanded = false
                    },
                )
            }
        }
    }
    ViewLogic.sortedEpisodes(show.episodes, ui.newestFirst).forEach { episode ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = episode.playable) { browse.play(episode) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ViewLogic.episodeLabel(episode), style = MaterialTheme.typography.bodyLarge)
                val sub = ViewLogic.episodeSubtitle(episode)
                if (sub.isNotEmpty()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (episode.playable) {
                OutlinedButton(onClick = { browse.enqueue(episode, show) }) {
                    Text(stringResource(R.string.add_to_queue))
                }
            }
        }
    }
}

@Composable
private fun DownloadButtons(
    item: LibraryItem,
    episodeId: String,
    target: LibraryItem,
    canDownload: Boolean,
    record: DownloadRecord?,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MiniAbsApplication
    val hasFiles = target.tracks.any { it.ino.isNotEmpty() }
    if (!DownloadPolicy.canDownload(canDownload, hasFiles) && record == null) return
    val controller = app.downloadController
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            record == null || record.status == DownloadStatus.FAILED && record.bytesDownloaded == 0L -> {
                if (DownloadPolicy.canDownload(canDownload, hasFiles)) {
                    OutlinedButton(onClick = { controller.enqueue(item, episodeId) }) {
                        Text(stringResource(R.string.download))
                    }
                }
            }
            record.status == DownloadStatus.QUEUED || record.status == DownloadStatus.RUNNING -> {
                val percent = if (record.bytesTotal > 0) {
                    ((record.bytesDownloaded * 100) / record.bytesTotal).toInt()
                } else {
                    0
                }
                Text(
                    text = if (record.waitingUnmetered) {
                        stringResource(R.string.download_waiting_wifi)
                    } else {
                        stringResource(R.string.download_percent, percent)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { controller.pause(item.id, episodeId) }) {
                        Text(stringResource(R.string.pause))
                    }
                    TextButton(onClick = { controller.cancel(item.id, episodeId) }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
            record.status == DownloadStatus.PAUSED || record.status == DownloadStatus.FAILED -> {
                Text(
                    text = record.error.ifEmpty { stringResource(R.string.download_paused) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { controller.retry(item.id, episodeId) }) {
                        Text(stringResource(R.string.retry))
                    }
                    TextButton(onClick = { controller.delete(item.id, episodeId) }) {
                        Text(stringResource(R.string.delete_download))
                    }
                }
            }
            record.status == DownloadStatus.COMPLETE -> {
                Text(stringResource(R.string.downloaded), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { controller.delete(item.id, episodeId) }) {
                    Text(stringResource(R.string.delete_download))
                }
            }
        }
    }
}
