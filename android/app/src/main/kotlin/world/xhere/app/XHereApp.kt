package world.xhere.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import world.xhere.app.auth.AuthScreen
import world.xhere.app.onboarding.OnboardingScreen
import world.xhere.app.session.Gate
import world.xhere.app.session.SessionViewModel
import world.xhere.app.shell.MainShell

@Composable
fun XHereApp(session: SessionViewModel = koinViewModel()) {
    val gate by session.gate.collectAsStateWithLifecycle()
    when (gate) {
        Gate.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.loading), style = MaterialTheme.typography.titleMedium)
        }
        Gate.SignedOut -> AuthScreen()
        Gate.NeedsOnboarding -> OnboardingScreen(onFinished = session::completeOnboarding)
        Gate.Ready -> MainShell()
    }
}
