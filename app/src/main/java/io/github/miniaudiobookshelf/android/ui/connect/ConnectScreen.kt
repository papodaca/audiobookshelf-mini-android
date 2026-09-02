package io.github.miniaudiobookshelf.android.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.android.session.ConnectUi
import io.github.miniaudiobookshelf.android.session.SessionController
import io.github.miniaudiobookshelf.core.ViewLogic
import io.github.miniaudiobookshelf.core.ConnectFailure

@Composable
fun ConnectScreen(
    session: SessionController,
    ui: ConnectUi,
    modifier: Modifier = Modifier,
) {
    var url by rememberSaveable(ui.url) { mutableStateOf(ui.url) }
    var httpAck by rememberSaveable(ui.httpAck) { mutableStateOf(ui.httpAck) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }

    val offer = ui.offer
    val canTypeSecrets = offer != null && (!ui.showHttpWarning || httpAck)

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
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.server_url)) },
            singleLine = true,
            enabled = !ui.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Button(
            onClick = { session.probe(url, httpAck) },
            enabled = !ui.busy && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.continue_action))
        }

        if (ui.showHttpWarning) {
            Text(
                text = ViewLogic.userMessage(ConnectFailure.HTTP_WARNING),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = httpAck,
                    onCheckedChange = { httpAck = it },
                    enabled = !ui.busy,
                )
                Text(stringResource(R.string.http_ack))
            }
        }

        if (ui.error != null) {
            Text(
                text = ui.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (ui.busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        if (offer != null && canTypeSecrets) {
            if (offer.password) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true,
                    enabled = !ui.busy,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    enabled = !ui.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.api_key)) },
                singleLine = true,
                enabled = !ui.busy,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val key = apiKey.trim()
                    if (key.isNotEmpty()) {
                        session.signInApiKey(key)
                    } else {
                        session.signInPassword(username, password)
                    }
                },
                enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sign_in))
            }
        }
    }

    val pin = ui.pin
    if (pin != null) {
        AlertDialog(
            onDismissRequest = { session.refusePin() },
            title = { Text(stringResource(R.string.untrusted_certificate)) },
            text = {
                Text(
                    ViewLogic.userMessage(ConnectFailure.TLS_UNTRUSTED) +
                        "\n\nSHA-256: " + pin.fingerprint +
                        "\n\nPin this certificate for " + pin.origin + "?",
                )
            },
            confirmButton = {
                TextButton(onClick = { session.pinCertificate() }) {
                    Text(stringResource(R.string.pin_certificate))
                }
            },
            dismissButton = {
                TextButton(onClick = { session.refusePin() }) {
                    Text(stringResource(R.string.refuse))
                }
            },
        )
    }
}
