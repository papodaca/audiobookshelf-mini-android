package io.github.miniaudiobookshelf.android.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import io.github.miniaudiobookshelf.android.player.IntendedState
import io.github.miniaudiobookshelf.android.player.PlayerState
import io.github.miniaudiobookshelf.core.PlaybackStarter

@Composable
fun NowPlayingBar(
    state: PlayerState,
    coverUrl: String,
    playback: PlaybackStarter,
    queueAvailable: Boolean,
    onOpenPlayer: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val title = state.chapter?.title?.ifEmpty { state.title } ?: state.title
    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (state.syncUnhealthy) {
            Text(
                text = stringResource(R.string.progress_not_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (state.stalled || state.retryAvailable) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clickable(onClick = onOpenPlayer)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .crossfade(true)
                    .size(128)
                    .build(),
                contentDescription = title,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = title.ifEmpty { stringResource(R.string.app_name) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (queueAvailable) {
                IconButton(onClick = onOpenQueue) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.up_next))
                }
            }
            IconButton(onClick = { playback.playPause() }) {
                if (state.intended == IntendedState.PLAYING) {
                    Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.pause))
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play))
                }
            }
        }
    }
}
