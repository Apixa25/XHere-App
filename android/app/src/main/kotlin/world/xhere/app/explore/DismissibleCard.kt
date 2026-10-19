package world.xhere.app.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.explore.ExperienceDismissal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DismissibleCard(
    enabled: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    if (!enabled) {
        androidx.compose.foundation.layout.Row(modifier) { content() }
        return
    }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.Settled) {
                false
            } else {
                onDismiss()
                true
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = stringResource(R.string.explore_hide),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        content = content,
    )
}

@Composable
internal fun ExperienceCloseButton(onClick: () -> Unit, tag: String) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.explore_close),
        )
    }
}

@Composable
internal fun ExperienceUndoBar(
    pending: ExperienceDismissal?,
    onUndo: () -> Unit,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = remember { SnackbarHostState() }
    val message = stringResource(R.string.explore_undo_message)
    val action = stringResource(R.string.explore_undo)
    LaunchedEffect(pending?.id, pending?.revision) {
        if (pending == null) return@LaunchedEffect
        val result = host.showSnackbar(
            message = message,
            actionLabel = action,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo() else onDismissed()
    }
    SnackbarHost(hostState = host, modifier = modifier) { data ->
        Snackbar(
            action = {
                TextButton(
                    onClick = { data.performAction() },
                    modifier = Modifier.testTag(TestTags.EXPLORE_UNDO),
                ) {
                    Text(data.visuals.actionLabel.orEmpty())
                }
            },
        ) {
            Text(data.visuals.message)
        }
    }
}
