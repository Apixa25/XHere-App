package world.xhere.app.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.access.AccessAnswer
import world.xhere.shared.access.AccessRollup
import world.xhere.shared.access.AccessStatus
import world.xhere.shared.access.AccessSummary
import world.xhere.shared.access.AccessSurvey
import world.xhere.shared.access.SurveyDraft
import world.xhere.shared.access.SurveyQuestion

/** One line on the Here Card or a drop. The check button opens the 3-tap survey. */
@Composable
fun AccessSummaryLine(
    summary: AccessSummary?,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
    showCheck: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth().testTag(TestTags.ACCESS_SUMMARY), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.access_title), style = MaterialTheme.typography.titleMedium)
        if (summary == null) {
            Text(stringResource(R.string.access_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(TestTags.ACCESS_NONE))
        } else {
            val color = colorFor(summary.status)
            Text(
                text = "♿  ${statusLabel(summary.status)}",
                color = color,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(TestTags.ACCESS_STATUS),
            )
            Text(
                text = stringResource(
                    R.string.access_line,
                    answerLabel(summary.stepFree),
                    answerLabel(summary.ramp),
                    answerLabel(summary.restroom),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            val confidence = (summary.confidence * 100.0).toInt().coerceIn(0, 100)
            val day = summary.lastConfirmedAt?.toString()?.take(10) ?: stringResource(R.string.access_never)
            Text(
                text = stringResource(R.string.access_confidence, confidence, summary.sampleSize, day),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(TestTags.ACCESS_CONFIDENCE),
            )
            Text(summary.attribution, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            summary.myNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                Text(stringResource(R.string.access_my_notes, notes), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (showCheck) {
            TextButton(onClick = onCheck, modifier = Modifier.testTag(TestTags.ACCESS_CHECK)) {
                Text(stringResource(R.string.access_check))
            }
        }
    }
}

/** One line for the Here Card header. Stays short so the drop list keeps its place. */
@Composable
fun AccessStatusChip(summary: AccessSummary, modifier: Modifier = Modifier) {
    val confidence = (summary.confidence * 100.0).toInt().coerceIn(0, 100)
    Text(
        text = "♿  ${statusLabel(summary.status)} · $confidence%",
        color = colorFor(summary.status),
        style = MaterialTheme.typography.titleMedium,
        // The sheet surface is one big click target, which folds plain text into
        // itself. A merge boundary keeps this status its own node for TalkBack and tests.
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                testTag = TestTags.ACCESS_STATUS
            },
    )
}

/** Yes, No, Not sure. Three taps, then an optional note and Save. */
@Composable
fun AccessSurveySheet(
    draft: SurveyDraft,
    busy: Boolean,
    onAnswer: (AccessAnswer) -> Unit,
    onNotes: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(TestTags.ACCESS_SURVEY),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(questionLabel(draft.question), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(TestTags.ACCESS_QUESTION))
        if (draft.question != SurveyQuestion.NOTES) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                AnswerButton(R.string.access_yes, TestTags.ACCESS_YES, busy) { onAnswer(AccessAnswer.YES) }
                AnswerButton(R.string.access_no, TestTags.ACCESS_NO, busy) { onAnswer(AccessAnswer.NO) }
                AnswerButton(R.string.access_unknown, TestTags.ACCESS_UNKNOWN, busy) { onAnswer(AccessAnswer.UNKNOWN) }
            }
        } else {
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { onNotes(it.take(AccessSurvey.NOTE_MAX)) },
                modifier = Modifier.fillMaxWidth().testTag(TestTags.ACCESS_NOTES),
                label = { Text(stringResource(R.string.access_notes)) },
                minLines = 2,
            )
            Button(
                onClick = onSave,
                enabled = draft.ready && !busy,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.ACCESS_SAVE),
            ) {
                Text(stringResource(R.string.access_save))
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.testTag(TestTags.ACCESS_CANCEL)) {
            Text(stringResource(R.string.voice_close))
        }
    }
}

@Composable
private fun AnswerButton(label: Int, tag: String, busy: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !busy, modifier = Modifier.testTag(tag)) {
        Text(stringResource(label))
    }
}

@Composable
private fun questionLabel(question: SurveyQuestion): String = when (question) {
    SurveyQuestion.STEP_FREE -> stringResource(R.string.access_q_step)
    SurveyQuestion.RAMP -> stringResource(R.string.access_q_ramp)
    SurveyQuestion.RESTROOM -> stringResource(R.string.access_q_restroom)
    SurveyQuestion.NOTES -> stringResource(R.string.access_q_notes)
}

@Composable
private fun statusLabel(status: AccessStatus): String = when (status) {
    AccessStatus.ACCESSIBLE -> stringResource(R.string.access_status_yes)
    AccessStatus.PARTIAL -> stringResource(R.string.access_status_partial)
    AccessStatus.NOT_ACCESSIBLE -> stringResource(R.string.access_status_no)
    AccessStatus.UNKNOWN -> stringResource(R.string.access_status_unknown)
}

@Composable
private fun answerLabel(answer: AccessAnswer): String = when (answer) {
    AccessAnswer.YES -> stringResource(R.string.access_yes)
    AccessAnswer.NO -> stringResource(R.string.access_no)
    AccessAnswer.UNKNOWN -> stringResource(R.string.access_unknown)
}

private fun colorFor(status: AccessStatus): Color {
    val raw = AccessRollup.colorHex(status).removePrefix("#")
    val value = raw.toLongOrNull(16) ?: return Color.Gray
    return Color(0xFF000000L or value)
}
