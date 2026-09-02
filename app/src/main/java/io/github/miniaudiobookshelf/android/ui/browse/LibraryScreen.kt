package io.github.miniaudiobookshelf.android.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import io.github.miniaudiobookshelf.android.browse.BrowseController
import io.github.miniaudiobookshelf.android.browse.BrowseUi
import io.github.miniaudiobookshelf.core.LibraryItem
import io.github.miniaudiobookshelf.core.ViewLogic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    browse: BrowseController,
    ui: BrowseUi,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (ui.libraries.isNotEmpty()) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = ui.selected?.name?.ifEmpty { ui.selected?.id.orEmpty() }.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    label = { Text(stringResource(R.string.library)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    ui.libraries.forEach { library ->
                        DropdownMenuItem(
                            text = { Text(library.name.ifEmpty { library.id }) },
                            onClick = {
                                browse.selectLibrary(library)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = ui.query,
            onValueChange = browse::onQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.search)) },
            singleLine = true,
        )
        if (ui.loading && ui.items.isNotEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        when {
            ui.error != null && ui.detail == null -> StatusPane(
                title = stringResource(R.string.library_error),
                body = ui.error,
                onRetry = browse::retry,
            )
            ui.loading && ui.items.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            ui.empty -> StatusPane(
                title = emptyTitle(ui),
                body = null,
                onRetry = null,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(ui.items, key = { it.id + ":" + it.episodeId }) { item ->
                    CoverTile(
                        item = item,
                        coverUrl = browse.coverUrl(item.id),
                        onClick = { browse.openDetail(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun emptyTitle(ui: BrowseUi): String {
    return when {
        ui.searching || ui.query.isNotBlank() -> stringResource(R.string.empty_search)
        ViewLogic.isPodcastLibrary(ui.selected) -> stringResource(R.string.empty_episodes)
        else -> stringResource(R.string.empty_continue)
    }
}

@Composable
internal fun StatusPane(
    title: String,
    body: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (!body.isNullOrBlank()) {
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
        if (onRetry != null) {
            Button(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun CoverTile(
    item: LibraryItem,
    coverUrl: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val badge = ViewLogic.seriesBadge(item)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .crossfade(true)
                    .size(352)
                    .build(),
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
            )
            if (badge != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = badge,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Text(
            text = if (item.episodeId.isNotEmpty()) ViewLogic.episodeLabel(item) else item.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.author.isNotEmpty()) {
            Text(
                text = item.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!item.playable) {
            Text(
                text = stringResource(R.string.no_audio),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
