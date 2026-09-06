package io.github.miniaudiobookshelf.android.ui.connect

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.android.session.SessionController
import io.github.miniaudiobookshelf.core.ConnectFailure
import io.github.miniaudiobookshelf.core.PinPrompt
import io.github.miniaudiobookshelf.core.ViewLogic

@Composable
fun TrustDialog(
    session: SessionController,
    pin: PinPrompt,
) {
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
