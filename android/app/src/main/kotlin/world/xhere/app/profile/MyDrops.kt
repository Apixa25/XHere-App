package world.xhere.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import world.xhere.app.R
import world.xhere.app.ui.TestTags
import world.xhere.shared.model.OwnDrop

@Composable
fun MyDropsList(
    drops: List<OwnDrop>,
    loaded: Boolean,
    onEdit: (OwnDrop) -> Unit,
    onDelete: (OwnDrop) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(TestTags.PROFILE_MY_DROPS),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.profile_my_drops), style = MaterialTheme.typography.titleLarge)
        if (loaded && drops.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_my_drops_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        drops.forEach { drop ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = drop.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.myDrop(drop.shortId)),
                )
                var open by remember(drop.id) { mutableStateOf(false) }
                IconButton(onClick = { open = true }, modifier = Modifier.testTag(TestTags.myDropMenu(drop.shortId))) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.detail_more))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.detail_edit)) },
                        onClick = {
                            open = false
                            onEdit(drop)
                        },
                        modifier = Modifier.testTag(TestTags.myDropEdit(drop.shortId)),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.detail_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            open = false
                            onDelete(drop)
                        },
                        modifier = Modifier.testTag(TestTags.myDropDelete(drop.shortId)),
                    )
                }
            }
        }
    }
}
