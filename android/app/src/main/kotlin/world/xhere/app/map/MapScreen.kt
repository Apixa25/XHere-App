package world.xhere.app.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import world.xhere.app.R
import world.xhere.app.access.AccessSurveySheet
import world.xhere.app.explore.DismissibleCard
import world.xhere.app.explore.ExperienceFocus
import world.xhere.app.explore.ExperienceUndoBar
import world.xhere.shared.explore.Experiences
import world.xhere.app.detail.IncomingLinks
import world.xhere.app.location.ForegroundLocation
import world.xhere.app.ui.TestTags
import world.xhere.app.voice.ThingsViewModel
import world.xhere.app.voice.VoicePermissions
import world.xhere.app.voice.VoiceSession
import world.xhere.app.voice.VoiceSurface
import world.xhere.app.voice.NfcWriteEffect
import world.xhere.app.voice.SpeakingBanner
import world.xhere.app.voice.ThingStoryCard
import world.xhere.app.voice.ThingsAroundSheet
import world.xhere.app.ui.hapticClick
import world.xhere.app.ui.openAppSettings
import world.xhere.app.voice.VoiceSheet
import world.xhere.app.voice.VoiceViewModel
import world.xhere.shared.access.AccessSurvey
import world.xhere.shared.access.SurveyDraft
import world.xhere.shared.model.Taxonomy
import world.xhere.shared.walks.WalkGuide

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

/** Straight-line walk route, and the guide to the next stop when one is underway. */
internal val WalkRouteSemantics = SemanticsPropertyKey<String>("WalkRoute")

/** `yes` when the AR button is shown. `no` when the phone or the spot cannot do Geospatial. */
internal val ArReadySemantics = SemanticsPropertyKey<String>("ArReady")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapScreen(
    onDrop: () -> Unit = {},
    onDropHere: (world.xhere.shared.geo.LatLng, String?) -> Unit = { _, _ -> },
    onOpenDrop: (String) -> Unit = {},
    onOpenAr: () -> Unit = {},
    onExplore: () -> Unit = {},
    viewModel: MapViewModel = koinViewModel(),
    voiceModel: VoiceViewModel = koinViewModel(),
    canvas: MapCanvas = koinInject(),
    things: ThingsViewModel = koinViewModel(),
    voice: VoiceSession = koinInject(),
    links: IncomingLinks = koinInject(),
    focus: ExperienceFocus = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val voiceState by things.state.collectAsStateWithLifecycle()
    val pendingThing by links.pendingThing.collectAsStateWithLifecycle()
    val pendingFocus by focus.requests.collectAsStateWithLifecycle()
    val talk by voiceModel.state.collectAsStateWithLifecycle()
    var accessDraft by remember { mutableStateOf(SurveyDraft()) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshGuide()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(voice) {
        voice.watch(VoiceSurface.MAP)
        onDispose { voice.unwatch(VoiceSurface.MAP) }
    }
    androidx.compose.runtime.LaunchedEffect(pendingThing) {
        val id = pendingThing ?: return@LaunchedEffect
        things.openFromLink(id)
        links.consumeThing()
    }
    androidx.compose.runtime.LaunchedEffect(pendingFocus) {
        val action = pendingFocus ?: return@LaunchedEffect
        viewModel.focus(action)
        focus.consume()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) viewModel.goToMyLocation() else viewModel.locationDenied()
    }
    val hearPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        things.openAround()
    }
    val speakPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) things.speak()
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voiceModel.onPermission(granted)
    }
    val walk = state.walk?.takeIf { walk -> walk.stops.any { it.available } }
    val quest = state.quests.firstOrNull()
    val walkCard = walkOnMap(state)
    val campaignCard = campaignOnMap(state)
    val questCard = questOnMap(state)
    val hintCount = exploreHintCount(state)
    val loadingLabel = stringResource(R.string.map_loading)
    val myLocationLabel = stringResource(R.string.map_my_location)
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
                set(WalkRouteSemantics, describeRoute(state))
                set(ArReadySemantics, if (state.arReady) "yes" else "no")
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
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        ) {
            CategoryChipBar(
                groups = state.groups,
                presets = state.presets,
                selection = state.selection,
                onSelectAll = viewModel::selectAll,
                onSelectPreset = viewModel::selectPreset,
                onSelectGroup = viewModel::selectGroup,
                onLongPressGroup = viewModel::showSubcategories,
                onSave = viewModel::showSavePreset,
            )
            if (campaignCard != null && state.alerts.isNotEmpty()) {
                val item = Experiences.campaign(campaignCard)
                DismissibleCard(
                    enabled = true,
                    onDismiss = { viewModel.dismissExperience(item) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MapStatusStack(
                        campaign = campaignCard,
                        alerts = state.alerts,
                        onDismissAlert = viewModel::dismissAlert,
                        onDismissCampaign = { viewModel.dismissExperience(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                if (campaignCard != null) {
                    val item = Experiences.campaign(campaignCard)
                    DismissibleCard(
                        enabled = true,
                        onDismiss = { viewModel.dismissExperience(item) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    ) {
                        CampaignCard(
                            campaign = campaignCard,
                            now = kotlin.time.Clock.System.now(),
                            compact = true,
                            onDismiss = { viewModel.dismissExperience(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (state.alerts.isNotEmpty()) {
                    WeatherAlertBanner(alerts = state.alerts, onDismiss = viewModel::dismissAlert)
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (voiceState.speakingId != null) {
                SpeakingBanner(onStop = things::stopSpeaking)
            }
            questCard?.let { card ->
                QuestCard(quest = card, onDismiss = { viewModel.dismissQuest(card.id) })
            }
            walkCard?.let { card ->
                val item = Experiences.walk(card, state.myLocation)
                val distance = state.myLocation?.let { here -> WalkGuide.distanceToNext(here, card) }
                DismissibleCard(
                    enabled = true,
                    onDismiss = { viewModel.dismissExperience(item) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    WalkCard(
                        walk = card,
                        distanceToNextM = distance,
                        onStart = hapticClick(viewModel::startWalk),
                        onDismiss = { viewModel.dismissExperience(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            state.notice?.let { notice ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.dismissNotice() }
                        .testTag(TestTags.MAP_NOTICE),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = noticeText(notice),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (notice == MapNotice.LOCATION_OFF) {
                            TextButton(
                                onClick = { openAppSettings(context) },
                                modifier = Modifier.testTag(TestTags.MAP_LOCATION_SETTINGS),
                            ) {
                                Text(stringResource(R.string.map_location_settings))
                            }
                        }
                    }
                }
            }
            state.teaser?.let { teaser ->
                TeaserCard(
                    teaser = teaser,
                    onClose = viewModel::dismissTeaser,
                    onOpen = { onOpenDrop(teaser.shortId) },
                )
            }
            if (hintCount > 0 && state.experienceLocal.hintEnabled) {
                Surface(
                    onClick = onExplore,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .testTag(TestTags.EXPLORE_PILL),
                ) {
                    Text(
                        text = stringResource(R.string.explore_pill, hintCount),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = { viewModel.openTimeMachine() },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(TestTags.MAP_TIME),
                ) {
                    Text(
                        text = stringResource(R.string.time_machine),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Surface(
                    onClick = {
                        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (allowed) {
                            voiceModel.listen()
                        } else {
                            // The system prompt is still shown. The sheet explains how to open Settings
                            // when the person has already said no, or the prompt cannot appear.
                            voiceModel.onPermission(false)
                            microphone.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiary,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 56.dp, minHeight = 56.dp)
                        .testTag(TestTags.VOICE_MIC),
                ) {
                    Text(
                        text = "🎤",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Surface(
                    onClick = viewModel::toggleAccessibility,
                    shape = MaterialTheme.shapes.large,
                    color = if (state.accessibilityLayer) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(TestTags.MAP_ACCESS),
                ) {
                    Text(
                        text = stringResource(R.string.access_layer),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Surface(
                    onClick = hapticClick(onDrop),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag(TestTags.MAP_DROP_FAB),
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
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .semantics { contentDescription = loadingLabel },
                        strokeWidth = 2.dp,
                    )
                }
                if (state.arReady) {
                    Surface(
                        onClick = onOpenAr,
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag(TestTags.MAP_AR),
                    ) {
                        Text(
                            text = stringResource(R.string.map_ar),
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Surface(
                    onClick = {
                        val missing = VoicePermissions.missingScan(context)
                        if (missing.isEmpty()) things.openAround() else hearPermission.launch(missing)
                    },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(TestTags.THINGS_AROUND),
                ) {
                    Text(
                        text = stringResource(R.string.things_around),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Surface(
                    onClick = viewModel::openNearMe,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag(TestTags.NEAR_ME),
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
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = myLocationLabel }
                        .testTag(TestTags.MY_LOCATION),
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
        ExperienceUndoBar(
            pending = state.experienceLocal.pendingUndo,
            onUndo = viewModel::undoDismiss,
            onDismissed = viewModel::clearUndo,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp, start = 12.dp, end = 12.dp),
        )
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
                alerts = state.alerts,
                onDropHere = { onDropHere(sheet.spot, sheet.place?.name) },
                onTimeMachine = { viewModel.openTimeMachine(sheet.spot) },
                onAccess = {
                    accessDraft = SurveyDraft()
                    viewModel.openAccessSurvey()
                },
                onDismiss = viewModel::dismissHere,
            )
        }
        if (state.accessSurvey) {
            AccessSurveySheet(
                draft = accessDraft,
                busy = false,
                onAnswer = { accessDraft = AccessSurvey.answer(accessDraft, it) },
                onNotes = { accessDraft = AccessSurvey.notes(accessDraft, it) },
                onSave = {
                    val draft = accessDraft
                    val step = draft.stepFree
                    val ramp = draft.ramp
                    val restroom = draft.restroom
                    if (step != null && ramp != null && restroom != null) {
                        viewModel.submitAccess(step, ramp, restroom, draft.notes)
                    }
                },
                onDismiss = viewModel::dismissAccessSurvey,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
        VoiceSheet(
            state = talk,
            onPost = voiceModel::post,
            onCancelCountdown = voiceModel::cancelCountdown,
            onRecord = voiceModel::recordClip,
            onStopClip = voiceModel::stopClip,
            onOpenSettings = { openAppSettings(context) },
            onDismiss = voiceModel::dismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
        state.timeMachine?.let { time ->
            TimeMachineSheet(
                ui = time,
                onYear = viewModel::selectHistoryYear,
                onSheet = viewModel::selectHistorySheet,
                onOpenViewer = viewModel::openHistoryViewer,
                onOpacity = viewModel::setHistoryOpacity,
                onCloseViewer = viewModel::closeHistoryViewer,
                onDismiss = viewModel::dismissTimeMachine,
            )
        }
        historyLayer(state)?.let { layer ->
            Text(
                text = layer.attribution,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp, start = 12.dp, end = 12.dp)
                    .testTag(TestTags.TIME_MAP_CREDIT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (state.savingPreset) {
            SavePresetDialog(
                onSave = viewModel::savePreset,
                onDismiss = viewModel::dismissSavePreset,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (voiceState.around) {
            ThingsAroundSheet(
                state = voiceState,
                latitude = state.myLocation?.latitude,
                longitude = state.myLocation?.longitude,
                onDismiss = things::closeAround,
                onGive = things::openGive,
                onCloseGive = things::closeGive,
                onCreate = { title, story ->
                    things.create(title, story, state.myLocation?.latitude, state.myLocation?.longitude)
                },
                onSelect = things::select,
                onBind = things::bind,
                onSpeak = {
                    val missing = VoicePermissions.missingAdvertise(context)
                    if (missing.isEmpty()) things.speak() else speakPermission.launch(missing)
                },
                onNfc = { things.waitForNfc(true) },
            )
        }
        if (voiceState.nfcWaiting) {
            NfcWriteEffect(voiceState.created?.voiceId, things::nfcFinished)
        }
        voiceState.selected?.let { card ->
            ThingStoryCard(
                thing = card,
                reporting = voiceState.reporting,
                busy = voiceState.busy,
                onReport = things::startReport,
                onSendReport = things::report,
                onDismissReport = things::dismissReport,
                onDismiss = things::dismissCard,
            )
        }
        if (state.celebrateWalk || state.celebrateQuest) {
            val title = when {
                state.celebrateWalk && walk != null -> stringResource(R.string.walk_celebrate_title, walk.title)
                quest != null -> stringResource(R.string.quest_celebrate_title, quest.title)
                else -> stringResource(R.string.walk_celebrate_body)
            }
            val body = when {
                state.celebrateWalk && state.celebrateQuest ->
                    stringResource(R.string.walk_celebrate_body) + " " + stringResource(R.string.quest_celebrate_body)
                state.celebrateWalk -> stringResource(R.string.walk_celebrate_body)
                else -> stringResource(R.string.quest_celebrate_body)
            }
            AlertDialog(
                onDismissRequest = viewModel::dismissCelebration,
                confirmButton = {
                    TextButton(
                        onClick = viewModel::dismissCelebration,
                        modifier = Modifier.testTag(TestTags.CELEBRATION_OK),
                    ) {
                        Text(stringResource(R.string.guide_celebration_ok))
                    }
                },
                title = {
                    Text(
                        text = title,
                        modifier = Modifier.testTag(
                            if (state.celebrateWalk) TestTags.WALK_CELEBRATION else TestTags.QUEST_CELEBRATION,
                        ),
                    )
                },
                text = { Text(body) },
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
    MapNotice.WALK_FAILED -> stringResource(R.string.walk_failed)
    MapNotice.MOCK_LOCATION -> stringResource(R.string.map_mock_location)
    MapNotice.IMPOSSIBLE_TRAVEL -> stringResource(R.string.map_impossible_travel)
    MapNotice.UNVERIFIED_FIX -> stringResource(R.string.map_unverified_fix)
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
