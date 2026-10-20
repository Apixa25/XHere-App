package world.xhere.app.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Clock
import org.koin.androidx.compose.koinViewModel
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.integrity.LocationIntegrityFlag
import world.xhere.shared.model.ModerationQueueItem
import world.xhere.shared.model.QueueItemKind

@Composable
fun ModerationScreen(onBack: () -> Unit, viewModel: ModerationViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SubPage(title = stringResource(R.string.mod_title), tag = TestTags.SCREEN_MODERATION, onBack = onBack) {
        when {
            state.loading -> CircularProgressIndicator()
            state.denied -> Text(
                text = stringResource(R.string.mod_not_allowed),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(TestTags.MOD_DENIED),
            )
            else -> {
                state.message?.let { message ->
                    Text(
                        text = stringResource(if (message == ModMessage.DONE) R.string.mod_done else R.string.safety_failed),
                        color = if (message == ModMessage.DONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(TestTags.MOD_MESSAGE),
                    )
                }
                if (state.failed) {
                    Text(stringResource(R.string.mod_failed), color = MaterialTheme.colorScheme.error)
                }
                if (state.claims.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.mod_claims),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(TestTags.CLAIM_QUEUE),
                    )
                    state.claims.forEach { claim ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.mod_claim_row, claim.placeName, claim.kind),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.testTag(TestTags.claimRow(claim.id.toString())),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.approveClaim(claim) },
                                    enabled = !state.busy,
                                    modifier = Modifier.testTag(TestTags.claimApprove(claim.id.toString())),
                                ) {
                                    Text(stringResource(R.string.mod_approve))
                                }
                                OutlinedButton(
                                    onClick = { viewModel.rejectClaim(claim) },
                                    enabled = !state.busy,
                                    modifier = Modifier.testTag(TestTags.claimReject(claim.id.toString())),
                                ) {
                                    Text(stringResource(if (claim.kind == "claim") R.string.mod_reject else R.string.mod_dismiss))
                                }
                            }
                        }
                    }
                }
                if (state.items.isEmpty() && state.flags.isEmpty() && state.claims.isEmpty() && !state.failed) {
                    Text(
                        text = stringResource(R.string.mod_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(TestTags.MOD_EMPTY),
                    )
                }
                if (state.flags.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.mod_integrity),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(TestTags.MOD_INTEGRITY),
                    )
                    state.flags.forEach { flag -> IntegrityRow(flag) }
                }
                state.items.forEach { item ->
                    QueueCard(
                        item = item,
                        actions = viewModel.actionsFor(item),
                        enabled = !state.busy,
                        onAction = { viewModel.act(item, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueCard(
    item: ModerationQueueItem,
    actions: List<ModAction>,
    enabled: Boolean,
    onAction: (ModAction) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.queueItem(item.key)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val labels = buildList {
                if (item.sensitive) add(stringResource(R.string.mod_sensitive))
                if (item.isPending) add(stringResource(R.string.mod_pending))
                if (item.isSubjectSuspended(Clock.System.now())) add(stringResource(R.string.mod_suspended))
            }
            if (labels.isNotEmpty()) {
                Text(
                    text = labels.joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (item.kind == QueueItemKind.DROP || item.kind == QueueItemKind.CONTRIBUTION) {
                Text(item.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                item.body?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                item.subjectHandle?.let {
                    Text(
                        text = stringResource(R.string.mod_by, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(stringResource(R.string.mod_profile, item.subjectHandle.orEmpty()), style = MaterialTheme.typography.titleMedium)
            }
            if (item.openReports > 0) {
                val reasons = item.reasons.map { reasonLabel(it) }.joinToString(", ")
                Text(stringResource(R.string.mod_reported, item.openReports, reasons), style = MaterialTheme.typography.bodyMedium)
            }
            item.latestDetails?.takeIf { it.isNotBlank() }?.let {
                Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action ->
                    OutlinedButton(
                        onClick = { onAction(action) },
                        enabled = enabled,
                        modifier = Modifier.testTag(TestTags.modAction(action.slug, item.key)),
                    ) {
                        Text(stringResource(actionLabel(action)))
                    }
                }
            }
        }
    }
}

@Composable
private fun IntegrityRow(flag: LocationIntegrityFlag) {
    val who = flag.handle ?: flag.userId.toString()
    Text(
        text = stringResource(R.string.mod_integrity_row, who, flag.reasons.joinToString(", ")),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("mod_flag_${flag.action}"),
    )
}

private fun actionLabel(action: ModAction): Int = when (action) {
    ModAction.APPROVE -> R.string.mod_approve
    ModAction.REMOVE -> R.string.mod_remove
    ModAction.RESTORE -> R.string.mod_restore
    ModAction.DISMISS -> R.string.mod_dismiss
    ModAction.ACTION -> R.string.mod_action
    ModAction.SUSPEND -> R.string.mod_suspend
    ModAction.UNSUSPEND -> R.string.mod_unsuspend
}
