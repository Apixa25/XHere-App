package world.xhere.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import world.xhere.app.R
import world.xhere.app.ui.MapMark
import world.xhere.app.ui.TestTags

@Composable
fun AuthScreen(viewModel: AuthViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    AuthContent(
        state = state,
        banner = banner,
        onEmail = viewModel::onEmail,
        onCode = viewModel::onCode,
        onSend = viewModel::sendCode,
        onVerify = viewModel::verifyCode,
        onGoogle = viewModel::signInWithGoogle,
        onSwitchEmail = viewModel::useDifferentEmail,
        onPreview = viewModel::previewJamestown,
    )
}

@Composable
internal fun AuthContent(
    state: AuthUiState,
    banner: String?,
    onEmail: (String) -> Unit,
    onCode: (String) -> Unit,
    onSend: () -> Unit,
    onVerify: () -> Unit,
    onGoogle: () -> Unit,
    onSwitchEmail: () -> Unit,
    onPreview: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            MapMark()
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            if (state.showOfflineNotice) {
                Text(
                    text = stringResource(R.string.auth_offline_notice),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.AUTH_OFFLINE_NOTICE),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onPreview,
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag(TestTags.PREVIEW_JAMESTOWN),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(stringResource(R.string.auth_preview_jamestown))
                }
            }
            if (!banner.isNullOrBlank()) {
                Text(
                    text = banner,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.SIGNED_OUT_NOTICE),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!state.codeSent) {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = onEmail,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.AUTH_EMAIL),
                    label = { Text(stringResource(R.string.auth_email_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSend() }),
                    shape = MaterialTheme.shapes.medium,
                )
                Button(
                    onClick = onSend,
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag(TestTags.AUTH_SEND_CODE),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(stringResource(R.string.auth_send_code))
                }
            } else {
                Text(
                    text = state.email.trim(),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = state.code,
                    onValueChange = onCode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.AUTH_CODE),
                    label = { Text(stringResource(R.string.auth_code_label)) },
                    placeholder = { Text(stringResource(R.string.auth_code_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onVerify() }),
                    shape = MaterialTheme.shapes.medium,
                )
                Button(
                    onClick = onVerify,
                    enabled = !state.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag(TestTags.AUTH_VERIFY),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(stringResource(R.string.auth_verify))
                }
                TextButton(
                    onClick = onSwitchEmail,
                    modifier = Modifier.testTag(TestTags.AUTH_SWITCH_EMAIL),
                ) {
                    Text(stringResource(R.string.auth_switch_email))
                }
            }
            state.error?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.AUTH_ERROR),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Text(
                text = stringResource(R.string.auth_or),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onGoogle,
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag(TestTags.AUTH_GOOGLE),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(stringResource(R.string.auth_google))
            }
        }
    }
}
