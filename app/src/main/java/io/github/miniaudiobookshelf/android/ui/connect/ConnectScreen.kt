package io.github.miniaudiobookshelf.android.ui.connect

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.miniaudiobookshelf.android.session.SessionController
import io.github.miniaudiobookshelf.core.ConnectStep

@Composable
fun ConnectScreen(
    session: SessionController,
    step: ConnectStep,
    modifier: Modifier = Modifier,
) {
    when (step) {
        is ConnectStep.Server -> ServerStep(session = session, step = step, modifier = modifier)
        is ConnectStep.Trust -> {
            ServerStep(session = session, step = step.server, modifier = modifier)
            TrustDialog(session = session, pin = step.pin)
        }
        is ConnectStep.Credentials -> CredentialsStep(session = session, step = step, modifier = modifier)
    }
}
