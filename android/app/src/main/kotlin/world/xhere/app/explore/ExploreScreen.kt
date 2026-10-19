package world.xhere.app.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Clock
import org.koin.androidx.compose.koinViewModel
import world.xhere.app.R
import world.xhere.app.map.CampaignCard
import world.xhere.app.map.QuestCard
import world.xhere.app.map.WalkCard
import world.xhere.app.map.formatMeters
import world.xhere.app.ui.TestTags
import world.xhere.shared.explore.Experience
import world.xhere.shared.explore.ExperienceKind
import world.xhere.shared.explore.ExperienceSort
import world.xhere.shared.explore.Experiences

/**
 * Every experience the current map can offer, including ones hidden from the map.
 * Sections stay in a fixed order so ten more kinds do not turn the list into a pile.
 */
@Composable
fun ExploreScreen(
    onOpenMap: (ExperienceAction) -> Unit,
    viewModel: ExploreViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize().testTag(TestTags.SCREEN_EXPLORE)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.explore_heading),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.explore_intro),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SortRow(selected = state.sort, onSelect = viewModel::setSort)
            FilterRow(selected = state.filter, onSelect = viewModel::setFilter)
            val sections = sections(state)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { section ->
                    item(key = "header-${section.filter.name}") {
                        Text(
                            text = stringResource(section.title),
                            modifier = Modifier
                                .padding(start = 20.dp, end = 20.dp, top = 12.dp)
                                .testTag(section.tag),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (section.items.isEmpty()) {
                        item(key = "empty-${section.filter.name}") {
                            Text(
                                text = stringResource(
                                    if (section.filter == ExperienceFilter.TOWN_HALL) {
                                        R.string.explore_town_hall_empty
                                    } else {
                                        R.string.explore_live_empty
                                    },
                                ),
                                modifier = Modifier.padding(horizontal = 20.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(section.items, key = { it.id }) { item ->
                        ExperienceRow(
                            item = item,
                            state = state,
                            onOpen = {
                                if (item.cta != world.xhere.shared.explore.ExperienceCta.NONE) onOpenMap(item.toAction())
                            },
                            onDismiss = { viewModel.dismiss(item) },
                            onDismissQuest = { id -> viewModel.dismissQuest(id) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        }
        ExperienceUndoBar(
            pending = state.local.pendingUndo,
            onUndo = viewModel::undoDismiss,
            onDismissed = viewModel::clearUndo,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp, start = 12.dp, end = 12.dp),
        )
    }
}

private data class Section(
    val filter: ExperienceFilter,
    val title: Int,
    val tag: String,
    val items: List<Experience>,
)

private fun sections(state: ExploreUiState): List<Section> {
    val sorted = Experiences.sort(state.experiences, state.sort)
    val wanted = if (state.filter == ExperienceFilter.ALL) {
        listOf(
            ExperienceFilter.LIVE,
            ExperienceFilter.WALKS,
            ExperienceFilter.QUESTS,
            ExperienceFilter.CAMPAIGNS,
            ExperienceFilter.TIME,
            ExperienceFilter.THINGS,
            ExperienceFilter.CAPSULES,
            ExperienceFilter.TOWN_HALL,
        )
    } else {
        listOf(state.filter)
    }
    return wanted.map { filter ->
        Section(
            filter = filter,
            title = filterTitle(filter),
            tag = filterTag(filter),
            items = sorted.filter { it.matches(filter) },
        )
    }.filter { section ->
        section.items.isNotEmpty() ||
            section.filter == ExperienceFilter.LIVE ||
            section.filter == ExperienceFilter.TOWN_HALL
    }
}

@Composable
private fun SortRow(selected: ExperienceSort, onSelect: (ExperienceSort) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == ExperienceSort.RELEVANCE,
            onClick = { onSelect(ExperienceSort.RELEVANCE) },
            label = { Text(stringResource(R.string.explore_sort_relevance)) },
            modifier = Modifier.testTag(TestTags.EXPLORE_SORT_RELEVANCE),
        )
        FilterChip(
            selected = selected == ExperienceSort.DISTANCE,
            onClick = { onSelect(ExperienceSort.DISTANCE) },
            label = { Text(stringResource(R.string.explore_sort_distance)) },
            modifier = Modifier.testTag(TestTags.EXPLORE_SORT_DISTANCE),
        )
    }
}

@Composable
private fun FilterRow(selected: ExperienceFilter, onSelect: (ExperienceFilter) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExperienceFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(filterTitle(filter))) },
                modifier = Modifier.testTag(TestTags.exploreFilter(filter.name.lowercase())),
            )
        }
    }
}

@Composable
private fun ExperienceRow(
    item: Experience,
    state: ExploreUiState,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onDismissQuest: (kotlin.uuid.Uuid) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hidden = item.hiddenIn(state)
    val dismiss: (() -> Unit)? = if (hidden || item.placeholder || item.kind == ExperienceKind.QUEST) {
        null
    } else {
        onDismiss
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hidden) {
            Text(
                text = stringResource(R.string.explore_hidden),
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .testTag(TestTags.exploreDismissed(item.id)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DismissibleCard(enabled = dismiss != null, onDismiss = { dismiss?.invoke() }) {
            when (item.kind) {
                ExperienceKind.WALK -> {
                    val walk = state.walks.firstOrNull { it.id.toString() == item.id }
                    if (walk != null) {
                        Box(experienceOpener(onOpen)) {
                            WalkCard(
                                walk = walk,
                                distanceToNextM = item.distanceM,
                                onStart = onOpen,
                                onDismiss = dismiss,
                            )
                        }
                    }
                }
                ExperienceKind.QUEST -> {
                    val quest = state.quests.firstOrNull { it.id.toString() == item.id }
                    if (quest != null) {
                        Box(experienceOpener(onOpen)) {
                            QuestCard(quest = quest, onDismiss = { onDismissQuest(quest.id) })
                        }
                    }
                }
                ExperienceKind.CAMPAIGN -> {
                    val campaign = state.campaigns.firstOrNull { it.id.toString() == item.id }
                    if (campaign != null) {
                        Box(experienceOpener(onOpen)) {
                            CampaignCard(
                                campaign = campaign,
                                now = Clock.System.now(),
                                compact = false,
                                onDismiss = dismiss,
                            )
                        }
                    }
                }
                else -> {
                    ExperienceSummaryCard(
                        item = item,
                        onClick = if (item.placeholder) null else onOpen,
                        onDismiss = dismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ExperienceSummaryCard(
    item: Experience,
    onClick: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val click = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (item.placeholder) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier
            .fillMaxWidth()
            .then(click)
            .testTag(TestTags.exploreCard(item.id)),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MetaLine(item)
                }
                if (onDismiss != null) {
                    ExperienceCloseButton(onClick = onDismiss, tag = TestTags.EXPERIENCE_DISMISS)
                }
            }
            if (item.summary.isNotBlank()) {
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val fraction = item.progressFraction
            val progressCurrent = item.progressCurrent
            val progressTotal = item.progressTotal
            if (fraction != null && progressCurrent != null && progressTotal != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text(
                    text = stringResource(R.string.explore_progress, progressCurrent, progressTotal),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.placeholder) {
                Text(
                    text = stringResource(R.string.explore_placeholder),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun MetaLine(item: Experience) {
    val parts = buildList {
        item.distanceM?.let { add(stringResource(R.string.explore_distance, formatMeters(it))) }
        item.durationMin?.let { add(stringResource(R.string.explore_duration, it)) }
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Opens the experience without merging the card's test tags into one node. */
private fun RowScope.experienceOpener(onOpen: () -> Unit): Modifier = Modifier
    .weight(1f)
    .semantics {
        role = Role.Button
        onClick {
            onOpen()
            true
        }
    }
    .pointerInput(onOpen) { detectTapGestures(onTap = { onOpen() }) }

private fun filterTitle(filter: ExperienceFilter): Int = when (filter) {
    ExperienceFilter.ALL -> R.string.explore_filter_all
    ExperienceFilter.WALKS -> R.string.explore_section_walks
    ExperienceFilter.QUESTS -> R.string.explore_section_quests
    ExperienceFilter.CAMPAIGNS -> R.string.explore_section_campaigns
    ExperienceFilter.LIVE -> R.string.explore_section_live
    ExperienceFilter.TIME -> R.string.explore_section_time
    ExperienceFilter.THINGS -> R.string.explore_section_things
    ExperienceFilter.CAPSULES -> R.string.explore_section_capsules
    ExperienceFilter.TOWN_HALL -> R.string.explore_section_town_hall
}

private fun filterTag(filter: ExperienceFilter): String = when (filter) {
    ExperienceFilter.ALL -> TestTags.EXPLORE_SECTION_ALL
    ExperienceFilter.WALKS -> TestTags.EXPLORE_SECTION_WALKS
    ExperienceFilter.QUESTS -> TestTags.EXPLORE_SECTION_QUESTS
    ExperienceFilter.CAMPAIGNS -> TestTags.EXPLORE_SECTION_CAMPAIGNS
    ExperienceFilter.LIVE -> TestTags.EXPLORE_SECTION_LIVE
    ExperienceFilter.TIME -> TestTags.EXPLORE_SECTION_TIME
    ExperienceFilter.THINGS -> TestTags.EXPLORE_SECTION_THINGS
    ExperienceFilter.CAPSULES -> TestTags.EXPLORE_SECTION_CAPSULES
    ExperienceFilter.TOWN_HALL -> TestTags.EXPLORE_SECTION_TOWN_HALL
}
