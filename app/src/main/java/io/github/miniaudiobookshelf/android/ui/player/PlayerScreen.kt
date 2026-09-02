package io.github.miniaudiobookshelf.android.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.android.player.IntendedState
import io.github.miniaudiobookshelf.android.player.PlayerState
import io.github.miniaudiobookshelf.core.PlaybackStarter
import io.github.miniaudiobookshelf.core.ViewLogic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerState,
    coverUrl: String,
    playback: PlaybackStarter,
    queueAvailable: Boolean,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var speedOpen by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val duration = state.duration.coerceAtLeast(0.0)
    val displayed = (scrub ?: state.position.toFloat()).toDouble().coerceIn(0.0, duration.coerceAtLeast(0.0))

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {},
                actions = {
                    if (queueAvailable) {
                        IconButton(onClick = onOpenQueue) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.up_next))
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .crossfade(true)
                    .size(800)
                    .build(),
                contentDescription = state.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.height(20.dp))
            Text(state.title.ifEmpty { stringResource(R.string.app_name) }, style = MaterialTheme.typography.headlineSmall)
            if (state.author.isNotEmpty()) {
                Text(
                    text = state.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.chapter != null) {
                Text(
                    text = state.chapter.title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.syncUnhealthy) {
                Text(
                    text = stringResource(R.string.progress_not_saved),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.stalled || state.retryAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.statusMessage.ifEmpty { stringResource(R.string.playback_stalled) },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.retryAvailable) {
                        TextButton(onClick = { playback.retry() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Slider(
                value = displayed.toFloat(),
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    val target = scrub
                    scrub = null
                    if (target != null) playback.seekTo(target.toDouble())
                },
                valueRange = 0f..(duration.toFloat().takeIf { it > 0f } ?: 1f),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(ViewLogic.formatClock(displayed), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(ViewLogic.formatClock(duration), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.hasChapters) {
                    IconButton(onClick = { playback.previousChapter() }) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.previous_chapter))
                    }
                }
                TextButton(onClick = { playback.skip(-30.0) }) {
                    Text(stringResource(R.string.skip_back_30))
                }
                IconButton(onClick = { playback.playPause() }) {
                    if (state.intended == IntendedState.PLAYING) {
                        Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.pause), modifier = Modifier.height(40.dp))
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play), modifier = Modifier.height(40.dp))
                    }
                }
                TextButton(onClick = { playback.skip(30.0) }) {
                    Text(stringResource(R.string.skip_forward_30))
                }
                if (state.hasChapters) {
                    IconButton(onClick = { playback.nextChapter() }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.next_chapter))
                    }
                }
            }
            Row {
                TextButton(onClick = { speedOpen = true }) {
                    Text(ViewLogic.formatRate(state.rate))
                }
                DropdownMenu(expanded = speedOpen, onDismissRequest = { speedOpen = false }) {
                    ViewLogic.speedPresets.forEach { rate ->
                        DropdownMenuItem(
                            text = { Text(ViewLogic.formatRate(rate)) },
                            onClick = {
                                playback.setRate(rate)
                                speedOpen = false
                            },
                        )
                    }
                }
            }
            if (state.hasChapters) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.chapters),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.timeline?.chapterList()?.forEach { chapter ->
                    val current = state.chapter?.id == chapter.id
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { playback.seekTo(chapter.start) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            text = ViewLogic.formatClock(chapter.start),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}
