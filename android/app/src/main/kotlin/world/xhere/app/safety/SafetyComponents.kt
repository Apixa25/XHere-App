package world.xhere.app.safety

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.model.ReportReason
import world.xhere.shared.safety.SafetyContact
import world.xhere.shared.safety.SafetyResources

/** Same cap as `reports.details` in the core schema. */
private const val DETAILS_MAX = 1000

/**
 * Pick a reason, add optional details, send. [showAlsoHide] adds "Also hide it
 * for me", which only makes sense for a drop. Shown inline on the detail screen,
 * right where Report was tapped.
 */
@Composable
fun ReportForm(
    title: String,
    showAlsoHide: Boolean,
    sending: Boolean,
    onSend: (reason: ReportReason, details: String?, alsoHide: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf<ReportReason?>(null) }
    var details by rememberSaveable { mutableStateOf("") }
    var alsoHide by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.REPORT_FORM),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                ReportReason.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = reason == option,
                                onClick = { reason = option },
                                role = Role.RadioButton,
                            )
                            .testTag(TestTags.reportReason(option.name.lowercase())),
                    ) {
                        RadioButton(selected = reason == option, onClick = null)
                        Text(
                            text = reasonLabel(option),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = details,
                onValueChange = { details = it.take(DETAILS_MAX) },
                label = { Text(stringResource(R.string.safety_report_details)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.REPORT_DETAILS),
            )
            if (showAlsoHide) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = alsoHide, onValueChange = { alsoHide = it }, role = Role.Checkbox)
                        .testTag(TestTags.REPORT_ALSO_HIDE),
                ) {
                    Checkbox(checked = alsoHide, onCheckedChange = null)
                    Text(
                        text = stringResource(R.string.safety_report_also_hide),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.safety_cancel))
                }
                TextButton(
                    onClick = { reason?.let { onSend(it, details.trim().ifEmpty { null }, alsoHide) } },
                    enabled = reason != null && !sending,
                    modifier = Modifier.testTag(TestTags.REPORT_SEND),
                ) {
                    Text(stringResource(R.string.safety_report_send))
                }
            }
        }
    }
}

@Composable
fun BlockDialog(handle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.safety_block_title, handle)) },
        text = { Text(stringResource(R.string.safety_block_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(TestTags.BLOCK_CONFIRM)) {
                Text(stringResource(R.string.safety_block_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.safety_cancel))
            }
        },
    )
}

/**
 * The footer every Safety screen shows (CRIME-OPEN-DATA §1): the tip line where
 * one is configured, and 911 everywhere. Buttons open the dialer; nothing is
 * called without the person pressing call.
 */
@Composable
fun SafetyFooter(resources: SafetyResources, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(TestTags.SAFETY_FOOTER),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = resources.footer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                resources.tipLine?.let { tip ->
                    DialButton(tip, stringResource(R.string.safety_tip_call), Modifier.testTag(TestTags.SAFETY_TIP_CALL))
                }
                DialButton(resources.emergency, stringResource(R.string.safety_emergency_call))
            }
        }
    }
}

@Composable
private fun DialButton(contact: SafetyContact, label: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val dial = Intent(Intent.ACTION_DIAL, contact.dialUri.toUri())
            runCatching { context.startActivity(dial) }
        },
        modifier = modifier,
    ) {
        Text(label)
    }
}

@Composable
fun reasonLabel(reason: ReportReason): String = stringResource(
    when (reason) {
        ReportReason.SPAM -> R.string.safety_reason_spam
        ReportReason.HARASSMENT -> R.string.safety_reason_harassment
        ReportReason.HATE -> R.string.safety_reason_hate
        ReportReason.VIOLENCE -> R.string.safety_reason_violence
        ReportReason.SEXUAL -> R.string.safety_reason_sexual
        ReportReason.ILLEGAL -> R.string.safety_reason_illegal
        ReportReason.PERSONAL_INFO -> R.string.safety_reason_personal_info
        ReportReason.MISINFORMATION -> R.string.safety_reason_misinformation
        ReportReason.DANGEROUS -> R.string.safety_reason_dangerous
        ReportReason.OTHER -> R.string.safety_reason_other
    },
)
