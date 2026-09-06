package io.github.miniaudiobookshelf.android.ui.connect

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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.android.session.SessionController
import io.github.miniaudiobookshelf.core.ConnectFailure
import io.github.miniaudiobookshelf.core.ConnectStep
import io.github.miniaudiobookshelf.core.ViewLogic

@Composable
fun ServerStep(
    session: SessionController,
    step: ConnectStep.Server,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable(step.url) { mutableStateOf(step.url) }
    var httpAck by rememberSaveable(step.url, step.httpWarning) { mutableStateOf(step.httpAck) }
    val keyboard = LocalSoftwareKeyboardController.current
    val canContinue = !step.busy && url.isNotBlank() && (!step.httpWarning || httpAck)

    fun submit() {
        if (!canContinue) return
        keyboard?.hide()
        session.probe(url, httpAck)
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.connect_blurb),
            style = MaterialTheme.typography.bodyMedium,
        )
        val urlError = step.error
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.server_url)) },
            singleLine = true,
            enabled = !step.busy,
            isError = urlError != null && !step.httpWarning,
            supportingText = if (urlError != null) {
                { Text(urlError) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { submit() }),
        )
        if (step.httpWarning) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = ViewLogic.userMessage(ConnectFailure.HTTP_WARNING),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = httpAck,
                            onCheckedChange = { httpAck = it },
                            enabled = !step.busy,
                        )
                        Text(stringResource(R.string.http_ack))
                    }
                }
            }
        }
        Button(
            onClick = { submit() },
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.continue_action))
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
