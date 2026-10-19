package world.xhere.app.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.drop.AttributeForm
import world.xhere.app.ui.TestTags
import world.xhere.shared.form.AttributeField

@Composable
fun OwnerOverflow(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(
        onClick = { open = true },
        modifier = Modifier.testTag(TestTags.DETAIL_OWNER_MENU),
    ) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.detail_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.detail_edit)) },
            onClick = {
                open = false
                onEdit()
            },
            modifier = Modifier.testTag(TestTags.DETAIL_EDIT),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.detail_delete), color = MaterialTheme.colorScheme.error) },
            onClick = {
                open = false
                onDelete()
            },
            modifier = Modifier.testTag(TestTags.DETAIL_DELETE),
        )
    }
}

@Composable
fun EditDropDialog(
    title: String,
    body: String,
    alt: String,
    fields: List<AttributeField>,
    values: Map<String, String>,
    saving: Boolean,
    failed: Boolean,
    onTitle: (String) -> Unit,
    onBody: (String) -> Unit,
    onAlt: (String) -> Unit,
    onField: (String, String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.detail_edit_title), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitle,
                    label = { Text(stringResource(R.string.detail_edit_title_field)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.DETAIL_EDIT_TITLE),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = onBody,
                    label = { Text(stringResource(R.string.detail_edit_body_field)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.DETAIL_EDIT_BODY),
                    maxLines = 4,
                )
                if (fields.isNotEmpty()) {
                    AttributeForm(fields = fields, values = values, onChange = onField)
                }
                OutlinedTextField(
                    value = alt,
                    onValueChange = onAlt,
                    label = { Text(stringResource(R.string.detail_edit_alt_field)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.DETAIL_EDIT_ALT),
                    singleLine = true,
                )
        if (failed) {
            Text(
                text = stringResource(R.string.detail_edit_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = onSave,
            enabled = !saving && title.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.DETAIL_EDIT_SAVE),
        ) {
            Text(stringResource(R.string.detail_edit_save))
        }
        TextButton(onClick = onDismiss, enabled = !saving) {
            Text(stringResource(R.string.detail_edit_cancel))
        }
    }
}

@Composable
fun DeleteDropDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_delete_title)) },
        text = { Text(stringResource(R.string.detail_delete_body)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag(TestTags.DETAIL_DELETE_CONFIRM),
            ) {
                Text(stringResource(R.string.detail_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.detail_edit_cancel))
            }
        },
    )
}
