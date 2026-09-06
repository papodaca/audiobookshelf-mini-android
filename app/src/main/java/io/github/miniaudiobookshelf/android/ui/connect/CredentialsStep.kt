package io.github.miniaudiobookshelf.android.ui.connect

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.android.session.SessionController
import io.github.miniaudiobookshelf.core.AuthMode
import io.github.miniaudiobookshelf.core.ConnectReason
import io.github.miniaudiobookshelf.core.ConnectStep
import io.github.miniaudiobookshelf.core.ViewLogic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsStep(
    session: SessionController,
    step: ConnectStep.Credentials,
    modifier: Modifier = Modifier,
) {
    val origin = step.server.origin
    var username by rememberSaveable(origin) { mutableStateOf(step.username) }
    var password by remember(origin) { mutableStateOf("") }
    var apiKey by remember(origin) { mutableStateOf("") }
    val passwordFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val bothOffered = step.offer.password && step.offer.apiKey
    val canSubmit = !step.busy && !step.rateLimited &&
        ViewLogic.canSubmit(step.mode, username, password, apiKey)

    fun submit() {
        if (!canSubmit) return
        keyboard?.hide()
        when (step.mode) {
            AuthMode.PASSWORD -> session.signInPassword(username, password)
            AuthMode.API_KEY -> session.signInApiKey(apiKey.trim())
        }
    }

    BackHandler { session.changeServer() }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.server.origin,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = step.version,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { session.changeServer() },
                enabled = !step.busy,
            ) {
                Text(stringResource(R.string.change_server))
            }
        }
        if (step.reason == ConnectReason.SESSION_EXPIRED) {
            Text(
                text = stringResource(R.string.session_expired),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (bothOffered) {
            val modes = listOf(AuthMode.PASSWORD, AuthMode.API_KEY)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = step.mode == mode,
                        onClick = { session.switchMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        enabled = !step.busy,
                        label = {
                            Text(
                                stringResource(
                                    if (mode == AuthMode.PASSWORD) R.string.use_password else R.string.use_api_key,
                                ),
                            )
                        },
                    )
                }
            }
        } else if (!step.offer.password) {
            Text(
                text = stringResource(R.string.sso_only_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val fieldError = step.error
        if (step.mode == AuthMode.PASSWORD) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Username },
                label = { Text(stringResource(R.string.username)) },
                singleLine = true,
                enabled = !step.busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocus)
                    .semantics { contentType = ContentType.Password },
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                enabled = !step.busy,
                isError = fieldError != null,
                supportingText = if (fieldError != null) {
                    { Text(fieldError) }
                } else {
                    null
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            LaunchedEffect(step.error, step.username) {
                if (step.error != null && !step.rateLimited) {
                    password = ""
                    passwordFocus.requestFocus()
                } else if (step.username.isNotEmpty() && step.error == null) {
                    passwordFocus.requestFocus()
                }
            }
        } else {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentType = ContentType.Password },
                label = { Text(stringResource(R.string.api_key)) },
                singleLine = true,
                enabled = !step.busy,
                isError = fieldError != null,
                supportingText = if (fieldError != null) {
                    { Text(fieldError) }
                } else {
                    null
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }
        Button(
            onClick = { submit() },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sign_in))
        }
        if (step.busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(24.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}
