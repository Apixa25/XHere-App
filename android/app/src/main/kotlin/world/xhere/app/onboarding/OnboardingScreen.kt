package world.xhere.app.onboarding

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.location.ForegroundLocation
import world.xhere.app.ui.MapMark
import world.xhere.app.ui.TestTags

/**
 * The rationale is this screen. The system dialog, if the person continues,
 * asks only for foreground location. "Not now" finishes onboarding without a request.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onFinished() }
    OnboardingContent(
        onAllow = {
            val activity = context as? Activity
            if (activity == null || ForegroundLocation.isGranted(activity)) {
                onFinished()
            } else {
                launcher.launch(ForegroundLocation.permissions)
            }
        },
        onSkip = onFinished,
    )
}

@Composable
internal fun OnboardingContent(
    onAllow: () -> Unit,
    onSkip: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MapMark()
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.onboarding_body),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.onboarding_background),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.onboarding_settings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f, fill = false))
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onAllow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag(TestTags.ONBOARDING_ALLOW)
                    .align(Alignment.CenterHorizontally),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(stringResource(R.string.onboarding_allow))
            }
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.ONBOARDING_SKIP),
            ) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }
    }
}
