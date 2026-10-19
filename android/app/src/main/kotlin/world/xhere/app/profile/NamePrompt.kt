package world.xhere.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags

/** Asks for a display name before a generated handle is what people see. */
@Composable
fun NamePrompt(
    displayName: String,
    handle: String,
    saving: Boolean,
    failed: Boolean,
    onDisplayName: (String) -> Unit,
    onHandle: (String) -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.profile_name_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.profile_name_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayName,
                label = { Text(stringResource(R.string.profile_name_display)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.PROFILE_NAME),
                singleLine = true,
            )
            OutlinedTextField(
                value = handle,
                onValueChange = onHandle,
                label = { Text(stringResource(R.string.profile_name_handle)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.PROFILE_HANDLE),
                singleLine = true,
            )
            if (failed) {
                Text(
                    text = stringResource(R.string.profile_name_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onSave,
                enabled = !saving && displayName.isNotBlank(),
                modifier = Modifier.testTag(TestTags.PROFILE_NAME_SAVE),
            ) {
                Text(stringResource(R.string.profile_name_save))
            }
        }
    }
}
