package world.xhere.app.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.safety.SafetyDirectory

/** The in-app copy of xhere.world/guidelines (`web/src/pages/guidelines.astro`). */
@Composable
fun GuidelinesScreen(onBack: () -> Unit) {
    SubPage(title = stringResource(R.string.guidelines_title), tag = TestTags.SCREEN_GUIDELINES, onBack = onBack) {
        Text(stringResource(R.string.guidelines_intro), style = MaterialTheme.typography.bodyLarge)
        Section(R.string.guidelines_short_title)
        listOf(
            R.string.guidelines_short_1,
            R.string.guidelines_short_2,
            R.string.guidelines_short_3,
            R.string.guidelines_short_4,
            R.string.guidelines_short_5,
        ).forEach { line ->
            Text("• " + stringResource(line), style = MaterialTheme.typography.bodyLarge)
        }
        Section(R.string.guidelines_everyone_title)
        Text(stringResource(R.string.guidelines_everyone_body), style = MaterialTheme.typography.bodyLarge)
        Section(R.string.guidelines_tools_title)
        Text(stringResource(R.string.guidelines_tools_body), style = MaterialTheme.typography.bodyLarge)
        Section(R.string.guidelines_safety_title)
        Text(stringResource(R.string.guidelines_safety_body), style = MaterialTheme.typography.bodyLarge)
        Section(R.string.guidelines_child_title)
        Text(stringResource(R.string.guidelines_child_body), style = MaterialTheme.typography.bodyLarge)
        Section(R.string.guidelines_moderation_title)
        Text(stringResource(R.string.guidelines_moderation_body), style = MaterialTheme.typography.bodyLarge)
        SafetyFooter(SafetyDirectory.chautauqua, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Section(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** A full-screen page with a back arrow, used by the Settings sub-screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubPage(
    title: String,
    tag: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .testTag(tag),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
