package world.xhere.app.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import world.xhere.app.R
import world.xhere.app.location.ForegroundLocation
import world.xhere.app.ui.TestTags
import world.xhere.shared.model.Taxonomy

/** Carries the last `drops_in_view` arguments so a UI test can read them without a second filter. */
internal val ViewportQuerySemantics = SemanticsPropertyKey<String>("ViewportQuery")

/** Carries the markers the repository returned for that query. */
internal val MapFeaturesSemantics = SemanticsPropertyKey<String>("MapFeatures")

/** Carries the `drops_nearby` rows, nearest first. */
internal val NearMeOrderSemantics = SemanticsPropertyKey<String>("NearMeOrder")

/** The chip selection currently held by the screen, before the next fetch returns. */
internal val FilterSemantics = SemanticsPropertyKey<String>("FilterSelection")

/** `none` until `drops_nearby` returns, then the row count. */
internal val NearCountSemantics = SemanticsPropertyKey<String>("NearCount")

/** Group slug while the sub-category sheet is open. */
internal val SubcategorySemantics = SemanticsPropertyKey<String>("Subcategory")

/** Here Card contents once the place read has finished. `loading` until then. */
internal val HereSemantics = SemanticsPropertyKey<String>("HereCard")

@Composable
fun MapScreen(
    onDrop: () -> Unit = {},
    onDropHere: (world.xhere.shared.geo.LatLng, String?) -> Unit = { _, _ -> },
    onOpenDrop: (String) -> Unit = {},
    viewModel: MapViewModel = koinViewModel(),
    canvas: MapCanvas = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) viewModel.goToMyLocation()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TestTags.SCREEN_MAP)
            .semantics {
                state.lastQuery?.let { set(ViewportQuerySemantics, it.describe()) }
                set(MapFeaturesSemantics, describeFeatures(state.features))
                set(FilterSemantics, state.selection.slugs?.joinToString(",") ?: "*")
                set(NearCountSemantics, state.nearMe?.size?.toString() ?: "none")
                set(SubcategorySemantics, state.subcategoryGroup ?: "none")
            },
    ) {
        canvas.Content(
            state = state,
            onCameraIdle = viewModel::onCameraIdle,
            onUserPanned = viewModel::onUserPanned,
            onFeatureClick = viewModel::onFeature,
            onMapClick = viewModel::onMapSpot,
            onMapLongClick = viewModel::onMapLongPress,
            modifier = Modifier.fillMaxSize(),
        )
        CategoryChipBar(
            groups = state.groups,
            presets = state.presets,
            selection = state.selection,
            onSelectAll = viewModel::selectAll,
            onSelectPreset = viewModel::selectPreset,
            onSelectGroup = viewModel::selectGroup,
            onLongPressGroup = viewModel::showSubcategories,
            onSave = viewModel::showSavePreset,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.notice?.let { notice ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.dismissNotice() }
                        .testTag(TestTags.MAP_NOTICE),
                ) {
                    Text(
                        text = noticeText(notice),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            state.teaser?.let { teaser ->
                TeaserCard(
                    teaser = teaser,
                    onClose = viewModel::dismissTeaser,
                    onOpen = { onOpenDrop(teaser.shortId) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onDrop,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 4.dp,
                    modifier = Modifier.testTag(TestTags.MAP_DROP_FAB),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.drop_fab),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Surface(
                    onClick = viewModel::openNearMe,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(TestTags.NEAR_ME),
                ) {
                    Text(
                        text = stringResource(R.string.map_near_me),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Surface(
                    onClick = {
                        if (ForegroundLocation.isGranted(context)) {
                            viewModel.goToMyLocation()
                        } else {
                            permission.launch(ForegroundLocation.permissions)
                        }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                    modifier = Modifier.testTag(TestTags.MY_LOCATION),
                ) {
                    Text(
                        text = "◎",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            MapAttribution()
        }
        val groupSlug = state.subcategoryGroup
        if (groupSlug != null) {
            val taxonomy = state.taxonomy
            SubcategorySheet(
                groupLabel = taxonomy?.group(groupSlug)?.label ?: groupSlug,
                categories = taxonomy?.categoriesIn(groupSlug).orEmpty(),
                initiallySelected = initialSubcategories(state, groupSlug),
                onApply = { selected -> viewModel.applySubcategories(groupSlug, selected) },
                onDismiss = viewModel::dismissSubcategories,
            )
        }
        state.nearMe?.let { cards ->
            NearMeSheet(
                cards = cards,
                onOpen = { card -> onOpenDrop(card.shortId) },
                onDismiss = viewModel::closeNearMe,
            )
        }
        state.here?.let { sheet ->
            HereCardSheet(
                sheet = sheet,
                taxonomy = state.taxonomy,
                onDropHere = { onDropHere(sheet.spot, sheet.place?.name) },
                onDismiss = viewModel::dismissHere,
            )
        }
        if (state.savingPreset) {
            SavePresetDialog(
                onSave = viewModel::savePreset,
                onDismiss = viewModel::dismissSavePreset,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun noticeText(notice: MapNotice): String = when (notice) {
    MapNotice.LOAD_FAILED -> stringResource(R.string.map_load_failed)
    MapNotice.TEASER_FAILED -> stringResource(R.string.map_teaser_failed)
    MapNotice.NEAR_FAILED -> stringResource(R.string.map_near_failed)
    MapNotice.LOCATION_OFF -> stringResource(R.string.map_location_off)
    MapNotice.PRESET_NAME -> stringResource(R.string.map_preset_rejected)
}

@Composable
internal fun MapAttribution() {
    Text(
        text = stringResource(R.string.map_attribution),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(TestTags.MAP_ATTRIBUTION),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp, lineHeight = 14.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private fun initialSubcategories(state: MapUiState, groupSlug: String): Set<String> {
    val taxonomy: Taxonomy = state.taxonomy ?: return emptySet()
    val inGroup = taxonomy.categoriesIn(groupSlug).map { it.slug }.toSet()
    val selected = state.selection.slugs
    if (selected == null || selected == listOf(groupSlug)) return inGroup
    return selected.filter { it in inGroup }.toSet().ifEmpty { inGroup }
}
