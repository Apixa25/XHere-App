package world.xhere.app.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags

/** Confirm, listen, or hear the Here Card. Stays off the map until the mic is used. */
@Composable
fun VoiceSheet(
    state: VoiceUiState,
    onPost: () -> Unit,
    onCancelCountdown: () -> Unit,
    onRecord: () -> Unit,
    onStopClip: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.phase == VoicePhase.IDLE) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.VOICE_SHEET),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(titleFor(state)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag(TestTags.VOICE_TITLE),
            )
            when (state.phase) {
                VoicePhase.LISTENING -> Text(stringResource(R.string.voice_listening), style = MaterialTheme.typography.bodyLarge)
                VoicePhase.DENIED -> {
                    Text(stringResource(R.string.voice_denied), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(TestTags.VOICE_DENIED))
                    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().testTag(TestTags.VOICE_SETTINGS)) {
                        Text(stringResource(R.string.voice_open_settings))
                    }
                }
                VoicePhase.CONFIRM -> ConfirmBody(state, onPost, onCancelCountdown, onRecord, onStopClip)
                VoicePhase.SPEAKING -> Text(state.spoken, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(TestTags.VOICE_SPOKEN))
                VoicePhase.POSTED -> Text(stringResource(R.string.voice_posted), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(TestTags.VOICE_POSTED))
                VoicePhase.ERROR -> Text(errorText(state.error), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.VOICE_ERROR))
                VoicePhase.IDLE -> Unit
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.VOICE_DISMISS)) {
                Text(stringResource(R.string.voice_close))
            }
        }
    }
}

@Composable
private fun ConfirmBody(
    state: VoiceUiState,
    onPost: () -> Unit,
    onCancelCountdown: () -> Unit,
    onRecord: () -> Unit,
    onStopClip: () -> Unit,
) {
    Text(state.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag(TestTags.VOICE_DROP_TITLE))
    Text(state.categorySlug, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    if (state.body.isNotBlank()) {
        Text(state.body, style = MaterialTheme.typography.bodyMedium)
    }
    state.secondsLeft?.let { left ->
        Text(
            text = stringResource(R.string.voice_countdown, left),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(TestTags.VOICE_COUNTDOWN),
        )
        OutlinedButton(onClick = onCancelCountdown, modifier = Modifier.fillMaxWidth().testTag(TestTags.VOICE_CANCEL_COUNTDOWN)) {
            Text(stringResource(R.string.voice_cancel_countdown))
        }
    }
    state.clipSeconds?.let { seconds ->
        Text(stringResource(R.string.voice_clip_ready, seconds.toInt()), modifier = Modifier.testTag(TestTags.VOICE_CLIP))
    }
    state.error?.let { code ->
        Text(errorText(code), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.VOICE_ERROR))
    }
    Button(
        onClick = onPost,
        enabled = !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.VOICE_POST),
    ) {
        Text(stringResource(R.string.voice_post), style = MaterialTheme.typography.titleLarge)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.recording) {
            OutlinedButton(onClick = onStopClip, modifier = Modifier.testTag(TestTags.VOICE_CLIP_STOP)) {
                Text(stringResource(R.string.voice_clip_stop))
            }
        } else {
            OutlinedButton(onClick = onRecord, modifier = Modifier.testTag(TestTags.VOICE_CLIP_ADD)) {
                Text(stringResource(R.string.voice_clip_add))
            }
        }
    }
}

@Composable
private fun titleFor(state: VoiceUiState): Int = when (state.phase) {
    VoicePhase.LISTENING -> R.string.voice_listening_title
    VoicePhase.DENIED -> R.string.voice_denied_title
    VoicePhase.CONFIRM -> R.string.voice_confirm_title
    VoicePhase.SPEAKING -> R.string.voice_speaking_title
    VoicePhase.POSTED -> R.string.voice_posted_title
    else -> R.string.voice_title
}

@Composable
private fun errorText(code: String?): String = when (code) {
    "voice_empty" -> stringResource(R.string.voice_empty)
    "voice_unparsed" -> stringResource(R.string.voice_unparsed)
    "voice_location" -> stringResource(R.string.voice_location)
    "voice_clip" -> stringResource(R.string.voice_clip_failed)
    "voice_too_long" -> stringResource(R.string.voice_too_long)
    "voice_too_large" -> stringResource(R.string.voice_too_large)
    "invalid_attributes" -> stringResource(R.string.voice_failed)
    else -> stringResource(R.string.voice_failed)
}
