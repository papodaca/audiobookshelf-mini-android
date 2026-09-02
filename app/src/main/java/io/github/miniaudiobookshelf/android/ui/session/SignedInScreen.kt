package io.github.miniaudiobookshelf.android.ui.session

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.miniaudiobookshelf.android.MiniAbsApplication
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.android.browse.BrowseUi
import io.github.miniaudiobookshelf.android.player.PlaybackCommands
import io.github.miniaudiobookshelf.android.session.SessionController
import io.github.miniaudiobookshelf.android.session.SessionState
import io.github.miniaudiobookshelf.android.ui.browse.DetailScreen
import io.github.miniaudiobookshelf.android.ui.browse.LibraryScreen
import io.github.miniaudiobookshelf.android.ui.download.DownloadsScreen
import io.github.miniaudiobookshelf.android.ui.download.toLibraryItem
import io.github.miniaudiobookshelf.android.ui.player.NowPlayingBar
import io.github.miniaudiobookshelf.android.ui.player.PlayerScreen
import io.github.miniaudiobookshelf.android.ui.player.QueueSheet
import io.github.miniaudiobookshelf.core.Library
import io.github.miniaudiobookshelf.core.ViewLogic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignedInScreen(
    session: SessionController,
    state: SessionState.SignedIn,
    playback: PlaybackCommands,
    modifier: Modifier = Modifier,
) {
    val browse = state.browse
    val ui by browse.ui.collectAsStateWithLifecycle()
    val player by playback.state.collectAsStateWithLifecycle()
    val queueEntries by browse.queue.entries.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmSignOut by remember { mutableStateOf(false) }
    var showLibraries by remember { mutableStateOf(false) }
    var playerOpen by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val app = context.applicationContext as MiniAbsApplication
    val downloadRows by app.downloadController.records.collectAsStateWithLifecycle()
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val queueAvailable = player.episodeId.isNotEmpty() || queueEntries.isNotEmpty()
    val coverUrl = if (player.itemId.isNotEmpty()) {
        player.artUrl.ifEmpty { browse.coverUrl(player.itemId) }
    } else {
        ""
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(state.toast) {
        val toast = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(toast)
        session.dismissToast()
    }

    LaunchedEffect(ui.notice) {
        val notice = ui.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        browse.dismissNotice()
    }

    LaunchedEffect(player.itemId) {
        if (player.itemId.isEmpty()) {
            playerOpen = false
        }
    }

    BackHandler(enabled = playerOpen) {
        playerOpen = false
    }
    BackHandler(enabled = !playerOpen && ui.detail != null) {
        browse.closeDetail()
    }
    BackHandler(enabled = !playerOpen && ui.detail == null && ui.searchOpen) {
        browse.closeSearch()
    }
    BackHandler(enabled = !playerOpen && ui.detail == null && !ui.searchOpen && showDownloads) {
        showDownloads = false
    }

    if (playerOpen && player.itemId.isNotEmpty()) {
        Box(modifier.fillMaxSize()) {
            PlayerScreen(
                state = player,
                coverUrl = coverUrl,
                playback = playback,
                queueAvailable = queueAvailable,
                onBack = { playerOpen = false },
                onOpenQueue = { showQueue = true },
                modifier = Modifier.fillMaxSize(),
            )
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    } else {
        Scaffold(
            modifier = modifier,
            topBar = {
                if (ui.detail != null) {
                    DetailTopBar(
                        onBack = { browse.closeDetail() },
                        onSignOut = { confirmSignOut = true },
                    )
                } else if (ui.searchOpen) {
                    SearchTopBar(
                        query = ui.query,
                        onQuery = browse::onQuery,
                        onClose = { browse.closeSearch() },
                    )
                } else if (showDownloads) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { showDownloads = false }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                )
                            }
                        },
                        title = { Text(stringResource(R.string.downloads)) },
                        actions = {
                            TextButton(onClick = { confirmSignOut = true }) {
                                Text(stringResource(R.string.sign_out))
                            }
                        },
                    )
                } else {
                    LibraryTopBar(
                        ui = ui,
                        onPickLibrary = { showLibraries = true },
                        onDownloads = { showDownloads = true },
                        onSignOut = { confirmSignOut = true },
                    )
                }
            },
            bottomBar = {
                if (player.itemId.isNotEmpty()) {
                    NowPlayingBar(
                        state = player,
                        coverUrl = coverUrl,
                        playback = playback,
                        queueAvailable = queueAvailable,
                        onOpenPlayer = { playerOpen = true },
                        onOpenQueue = { showQueue = true },
                    )
                }
            },
            floatingActionButton = {
                if (ui.detail == null && !ui.searchOpen && !showDownloads) {
                    FloatingActionButton(onClick = { browse.openSearch() }) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { innerPadding ->
            if (ui.detail != null) {
                DetailScreen(
                    browse = browse,
                    ui = ui,
                    canDownload = state.canDownload,
                    modifier = Modifier.padding(innerPadding),
                )
            } else if (showDownloads) {
                DownloadsScreen(
                    records = downloadRows,
                    downloads = app.downloadController,
                    coverUrl = { row ->
                        app.downloadController.coverUri(row.itemId, row.episodeId)
                            .ifEmpty { browse.coverUrl(row.itemId) }
                    },
                    onOpen = { row ->
                        showDownloads = false
                        browse.openDetail(row.toLibraryItem(), row.episodeId.ifEmpty { null })
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            } else {
                LibraryScreen(
                    browse = browse,
                    ui = ui,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }

    if (showQueue) {
        QueueSheet(
            entries = queueEntries,
            onDismiss = { showQueue = false },
            onPlay = { index ->
                browse.playQueued(index)
                showQueue = false
            },
            onRemove = browse::removeQueued,
            onMove = browse::moveQueued,
            onClear = browse::clearQueue,
        )
    }

    if (showLibraries) {
        ModalBottomSheet(
            onDismissRequest = { showLibraries = false },
            sheetState = sheetState,
        ) {
            Text(
                text = stringResource(R.string.libraries),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ui.libraries.forEach { library ->
                LibraryRow(
                    library = library,
                    selected = library.id == ui.selected?.id,
                    onClick = {
                        browse.selectLibrary(library)
                        showLibraries = false
                    },
                )
            }
        }
    }

    if (confirmSignOut) {
        val hasDownloads = downloadRows.isNotEmpty()
        val body = when {
            hasDownloads -> stringResource(R.string.sign_out_downloads_body)
            state.apiKey -> stringResource(R.string.sign_out_api_key_body)
            else -> stringResource(R.string.sign_out_body)
        }
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out)) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        session.confirmSignOut(deleteDownloads = false)
                    },
                ) {
                    Text(if (hasDownloads) stringResource(R.string.keep_downloads) else stringResource(R.string.sign_out))
                }
            },
            dismissButton = {
                Row {
                    if (hasDownloads) {
                        TextButton(
                            onClick = {
                                confirmSignOut = false
                                session.confirmSignOut(deleteDownloads = true)
                            },
                        ) {
                            Text(stringResource(R.string.delete_downloads))
                        }
                    }
                    TextButton(onClick = { confirmSignOut = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
    }

    val conflict = player.conflict
    if (conflict != null) {
        AlertDialog(
            onDismissRequest = { playback.resolveConflict(keepLocal = true) },
            title = { Text(stringResource(R.string.progress_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.progress_conflict_body,
                        ViewLogic.formatClock(conflict.localPosition),
                        ViewLogic.formatClock(conflict.serverPosition),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { playback.resolveConflict(keepLocal = true) }) {
                    Text(stringResource(R.string.keep_this_device))
                }
            },
            dismissButton = {
                TextButton(onClick = { playback.resolveConflict(keepLocal = false) }) {
                    Text(stringResource(R.string.take_other_device))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    ui: BrowseUi,
    onPickLibrary: () -> Unit,
    onDownloads: () -> Unit,
    onSignOut: () -> Unit,
) {
    val name = ui.selected?.name?.ifEmpty { ui.selected?.id.orEmpty() }.orEmpty()
    val canSwitch = ui.libraries.size > 1
    TopAppBar(
        title = {
            Row(
                modifier = Modifier.clickable(enabled = canSwitch, onClick = onPickLibrary),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name.ifEmpty { stringResource(R.string.app_name) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canSwitch) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(R.string.switch_library),
                    )
                }
            }
        },
        actions = {
            TextButton(onClick = onDownloads) {
                Text(stringResource(R.string.downloads))
            }
            TextButton(onClick = onSignOut) {
                Text(stringResource(R.string.sign_out))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.close_search),
                )
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                placeholder = { Text(stringResource(R.string.search)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.clear_search),
                            )
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(
    onBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        title = {},
        actions = {
            TextButton(onClick = onSignOut) {
                Text(stringResource(R.string.sign_out))
            }
        },
    )
}

@Composable
private fun LibraryRow(
    library: Library,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val kind = if (ViewLogic.isPodcastLibrary(library)) {
        stringResource(R.string.library_podcasts)
    } else {
        stringResource(R.string.library_books)
    }
    ListItem(
        headlineContent = { Text(library.name.ifEmpty { library.id }) },
        supportingContent = { Text(kind) },
        leadingContent = {
            RadioButton(selected = selected, onClick = null)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}
