package world.xhere.app.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import world.xhere.app.R
import world.xhere.app.ui.TestTags

@Composable
fun SafetyListsScreen(onBack: () -> Unit, viewModel: SafetyListsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SubPage(title = stringResource(R.string.lists_title), tag = TestTags.SCREEN_LISTS, onBack = onBack) {
        if (state.loading) {
            CircularProgressIndicator()
            return@SubPage
        }
        if (state.failed) {
            Text(stringResource(R.string.lists_failed), color = MaterialTheme.colorScheme.error)
        }
        Heading(R.string.lists_blocked)
        if (state.blocked.isEmpty()) {
            Empty(R.string.lists_blocked_empty, TestTags.LISTS_BLOCKED_EMPTY)
        }
        state.blocked.forEach { user ->
            ListRow(
                title = "@${user.handle}",
                subtitle = user.displayName,
                action = stringResource(R.string.lists_unblock),
                tag = TestTags.unblock(user.handle),
                onAction = { viewModel.unblock(user) },
            )
        }
        Heading(R.string.lists_hidden)
        if (state.hidden.isEmpty()) {
            Empty(R.string.lists_hidden_empty, TestTags.LISTS_HIDDEN_EMPTY)
        }
        state.hidden.forEach { drop ->
            ListRow(
                title = drop.title,
                subtitle = null,
                action = stringResource(R.string.lists_unhide),
                tag = TestTags.unhide(drop.shortId),
                onAction = { viewModel.unhide(drop) },
            )
        }
    }
}

@Composable
private fun Heading(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Empty(text: Int, tag: String) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun ListRow(title: String, subtitle: String?, action: String, tag: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onAction, modifier = Modifier.testTag(tag)) {
            Text(action)
        }
    }
}
