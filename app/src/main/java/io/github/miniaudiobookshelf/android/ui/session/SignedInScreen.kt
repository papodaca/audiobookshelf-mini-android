package io.github.miniaudiobookshelf.android.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.android.session.SessionController
import io.github.miniaudiobookshelf.android.session.SessionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignedInScreen(
    session: SessionController,
    state: SessionState.SignedIn,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(state.toast) {
        val toast = state.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(toast)
        session.dismissToast()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { confirmSignOut = true }) {
                        Text(stringResource(R.string.sign_out))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.signed_in),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = state.origin,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (confirmSignOut) {
        val body = if (state.apiKey) {
            stringResource(R.string.sign_out_api_key_body)
        } else {
            stringResource(R.string.sign_out_body)
        }
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out)) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        session.confirmSignOut()
                    },
                ) {
                    Text(stringResource(R.string.sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
