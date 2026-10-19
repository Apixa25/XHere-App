package world.xhere.app.shell

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Clock
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import world.xhere.app.R
import world.xhere.app.ar.ArScreen
import world.xhere.app.detail.DropDetailScreen
import world.xhere.app.detail.IncomingLinks
import world.xhere.app.drop.DropScreen
import world.xhere.app.drop.PendingDropSpot
import world.xhere.app.explore.ExperienceFocus
import world.xhere.app.map.SheetSignals
import world.xhere.app.explore.ExploreScreen
import world.xhere.app.map.CampaignCard
import world.xhere.app.map.MapScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import world.xhere.app.detail.DeleteDropDialog
import world.xhere.app.profile.MyDropsList
import world.xhere.app.profile.NamePrompt
import world.xhere.app.profile.ProfileViewModel
import world.xhere.app.reputation.LevelUpSheet
import world.xhere.app.reputation.TierChip
import world.xhere.shared.model.ImpactNotice
import world.xhere.shared.profile.AuthorNames
import world.xhere.app.safety.GuidelinesScreen
import world.xhere.app.safety.ModerationScreen
import world.xhere.app.safety.SafetyListsScreen
import world.xhere.app.settings.SettingsScreen
import world.xhere.app.ui.MapMark
import world.xhere.app.ui.TestTags

private object Routes {
    const val MAP = "map"
    const val EXPLORE = "explore"
    const val DROP = "drop"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val GUIDELINES = "guidelines"
    const val LISTS = "blocked"
    const val MODERATION = "moderation"
    const val DETAIL = "detail/{shortId}?edit={edit}"
    const val EDIT_PROFILE = "profile/edit"
    const val PERSON = "person/{handle}"
    const val AR = "ar"

    /** Full-screen pages with their own back arrow and no bottom bar. */
    val fullScreen = setOf(SETTINGS, GUIDELINES, LISTS, MODERATION, DETAIL, AR, EDIT_PROFILE, PERSON)

    fun person(handle: String): String = "person/${android.net.Uri.encode(handle)}"

    fun detail(shortId: String, edit: Boolean = false): String =
        if (edit) "detail/$shortId?edit=true" else "detail/$shortId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell() {
    val nav = rememberNavController()
    val links: IncomingLinks = koinInject()
    val pendingDrop: PendingDropSpot = koinInject()
    val focus: ExperienceFocus = koinInject()
    val sheets: SheetSignals = koinInject()
    val pending by links.pending.collectAsStateWithLifecycle()
    val pendingThing by links.pendingThing.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route !in Routes.fullScreen
    val showTopBar = showBar && route != Routes.MAP
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        nav.navigate(Routes.detail(id)) { launchSingleTop = true }
        links.consume()
    }
    LaunchedEffect(pendingThing) {
        val id = pendingThing ?: return@LaunchedEffect
        nav.navigate(Routes.MAP) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    Scaffold(
        topBar = {
            if (showTopBar) {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                sheets.requestDismiss()
                                if (route == tab.route) return@NavigationBarItem
                                if (tab.route == Routes.DROP) pendingDrop.clear()
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                            modifier = Modifier.testTag(tab.tag),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.MAP,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.MAP) {
                MapScreen(
                    onDrop = {
                        pendingDrop.clear()
                        nav.navigate(Routes.DROP) { launchSingleTop = true }
                    },
                    onDropHere = { spot, label ->
                        pendingDrop.set(spot, label)
                        nav.navigate(Routes.DROP) { launchSingleTop = true }
                    },
                    onOpenDrop = { shortId ->
                        nav.navigate(Routes.detail(shortId)) { launchSingleTop = true }
                    },
                    onOpenAuthor = { handle ->
                        nav.navigate(Routes.person(handle)) { launchSingleTop = true }
                    },
                    onOpenAr = {
                        nav.navigate(Routes.AR) { launchSingleTop = true }
                    },
                    onExplore = {
                        nav.navigate(Routes.EXPLORE) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Routes.EXPLORE) {
                ExploreScreen(
                    onOpenMap = { action ->
                        focus.request(action)
                        nav.navigate(Routes.MAP) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Routes.AR) {
                ArScreen(
                    onBack = { nav.popBackStack() },
                    onOpenDrop = { shortId ->
                        nav.navigate(Routes.detail(shortId)) { launchSingleTop = true }
                    },
                    onDropHere = { spot, label, signal ->
                        pendingDrop.set(spot, label, signal)
                        nav.navigate(Routes.DROP) { launchSingleTop = true }
                    },
                )
            }
            composable(Routes.DROP) { DropScreen() }
            composable(Routes.PROFILE) {
                ProfilePage(
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenDrop = { shortId ->
                        nav.navigate(Routes.detail(shortId)) { launchSingleTop = true }
                    },
                    onEditDrop = { shortId ->
                        nav.navigate(Routes.detail(shortId, edit = true)) { launchSingleTop = true }
                    },
                    onEditProfile = { nav.navigate(Routes.EDIT_PROFILE) { launchSingleTop = true } },
                )
            }
            composable(Routes.EDIT_PROFILE) {
                world.xhere.app.profile.EditProfileScreen(onBack = { nav.popBackStack() })
            }
            composable(
                route = Routes.PERSON,
                arguments = listOf(navArgument("handle") { type = NavType.StringType }),
            ) { entry ->
                world.xhere.app.profile.PublicProfileScreen(
                    handle = entry.arguments?.getString("handle").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenDrop = { shortId ->
                        nav.navigate(Routes.detail(shortId)) { launchSingleTop = true }
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onGuidelines = { nav.navigate(Routes.GUIDELINES) { launchSingleTop = true } },
                    onBlocked = { nav.navigate(Routes.LISTS) { launchSingleTop = true } },
                    onModeration = { nav.navigate(Routes.MODERATION) { launchSingleTop = true } },
                )
            }
            composable(Routes.GUIDELINES) { GuidelinesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.LISTS) { SafetyListsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MODERATION) { ModerationScreen(onBack = { nav.popBackStack() }) }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(
                    navArgument("shortId") { type = NavType.StringType },
                    navArgument("edit") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                DropDetailScreen(
                    shortId = entry.arguments?.getString("shortId").orEmpty(),
                    startEditing = entry.arguments?.getBoolean("edit") == true,
                    onBack = { nav.popBackStack() },
                    onOpenAuthor = { handle ->
                        nav.navigate(Routes.person(handle)) { launchSingleTop = true }
                    },
                )
            }
        }
    }
}

private enum class Tab(val route: String, val label: Int, val tag: String, val icon: ImageVector) {
    Map(Routes.MAP, R.string.nav_map, TestTags.NAV_MAP, Icons.Filled.Place),
    Explore(Routes.EXPLORE, R.string.nav_explore, TestTags.NAV_EXPLORE, Icons.Filled.Search),
    Drop(Routes.DROP, R.string.nav_drop, TestTags.NAV_DROP, Icons.Filled.Add),
    Profile(Routes.PROFILE, R.string.nav_profile, TestTags.NAV_PROFILE, Icons.Filled.Person),
}

@Composable
private fun PlaceholderPage(tag: String, title: Int, body: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MapMark()
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ProfilePage(
    onSettings: () -> Unit,
    onOpenDrop: (String) -> Unit,
    onEditDrop: (String) -> Unit,
    onEditProfile: () -> Unit,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showLevelUp by rememberSaveable { mutableStateOf(false) }
    val sheets: SheetSignals = koinInject()
    LaunchedEffect(sheets) {
        sheets.dismissals.collect { showLevelUp = false }
    }
    BackHandler(enabled = showLevelUp) { showLevelUp = false }
    var deleting by remember { mutableStateOf<world.xhere.shared.model.OwnDrop?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val deletedMessage = stringResource(R.string.detail_deleted)
    val undoLabel = stringResource(R.string.detail_undo)
    LaunchedEffect(state.undoToken) {
        if (state.undoToken == 0) return@LaunchedEffect
        val dismiss = launch {
            delay(5_000)
            snackbarHost.currentSnackbarData?.dismiss()
        }
        val result = snackbarHost.showSnackbar(
            message = deletedMessage,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        dismiss.cancel()
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.expireUndo()
    }
    deleting?.let { drop ->
        DeleteDropDialog(
            onConfirm = {
                deleting = null
                viewModel.deleteDrop(drop)
            },
            onDismiss = { deleting = null },
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(24.dp)
            .testTag(TestTags.SCREEN_PROFILE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            world.xhere.app.profile.ProfileAvatar(
                url = state.avatarUrl,
                label = state.displayName.ifBlank { stringResource(R.string.profile_signed_in) },
                size = 72.dp,
                tag = TestTags.PROFILE_AVATAR,
            )
            Text(
                text = state.displayName.ifBlank { stringResource(R.string.profile_signed_in) },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .testTag(TestTags.PROFILE_HEADLINE),
            )
        }
        if (state.bio.isNotBlank()) {
            Text(state.bio, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(TestTags.PROFILE_BIO))
        }
        if (state.homeArea.isNotBlank()) {
            Text(state.homeArea, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.website.isNotBlank()) {
            Text(state.website, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.material3.OutlinedButton(
            onClick = onEditProfile,
            modifier = Modifier.testTag(TestTags.PROFILE_EDIT),
        ) {
            Text(stringResource(R.string.profile_edit))
        }
        if (state.needsName) {
            NamePrompt(
                displayName = state.nameDraft,
                handle = state.handleDraft,
                saving = state.savingName,
                failed = state.nameFailed,
                onDisplayName = viewModel::onNameDraft,
                onHandle = viewModel::onHandleDraft,
                onSave = viewModel::saveName,
            )
        } else if (AuthorNames.label(null, state.handle) != null) {
            Text("@${state.handle}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (state.tierLabel.isNotBlank()) {
            TierChip(
                label = state.tierLabel,
                onClick = { showLevelUp = true },
            )
        }
        if (state.badges.isNotEmpty()) {
            Text(
                text = stringResource(R.string.reputation_badges),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (state.impactLoaded) {
            Text(
                text = stringResource(R.string.profile_badges_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag(TestTags.PROFILE_BADGES_EMPTY),
            )
        }
        state.badges.forEach { badge ->
            val where = badge.regionName?.takeIf { it.isNotBlank() }
            Text(
                text = if (where == null) "${badge.icon} ${badge.name}" else "${badge.icon} ${badge.name} · $where",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag(TestTags.PROFILE_BADGE),
            )
        }
        state.campaigns.forEach { campaign ->
            CampaignCard(campaign = campaign, now = Clock.System.now(), compact = false)
        }
        MyDropsList(
            drops = state.myDrops,
            loaded = state.dropsLoaded,
            onEdit = { drop -> onEditDrop(drop.shortId) },
            onDelete = { drop -> deleting = drop },
        )
        if (state.canUndoDelete) {
            androidx.compose.material3.OutlinedButton(
                onClick = viewModel::undoDelete,
                modifier = Modifier.testTag(TestTags.DETAIL_UNDO),
            ) {
                Text(stringResource(R.string.detail_undo))
            }
        }
        state.email?.let { email ->
            Text(email, style = MaterialTheme.typography.bodyLarge)
        }
        Button(
            onClick = onSettings,
            modifier = Modifier
                .padding(top = 16.dp)
                .testTag(TestTags.OPEN_SETTINGS),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Text(stringResource(R.string.profile_settings), modifier = Modifier.padding(start = 8.dp))
        }
        ImpactInbox(
            notices = if (state.impactLoaded) state.notices else emptyList(),
            showEmpty = state.impactLoaded && state.notices.isEmpty(),
            authorName = state.displayName,
            avatarUrl = state.avatarUrl,
            onOpen = { notice -> viewModel.openNotice(notice, onOpenDrop) },
        )
        if (state.civicNotices.isNotEmpty()) {
            CivicInbox(
                notices = state.civicNotices,
                onOpen = { notice -> viewModel.markCivicRead(notice.id) },
            )
        }
    }
    }
    val standing = state.standing
    if (showLevelUp && standing != null) {
        LevelUpSheet(standing = standing, onDismiss = { showLevelUp = false })
    }
}

@Composable
private fun ColumnScope.CivicInbox(
    notices: List<world.xhere.shared.civic.CivicNotice>,
    onOpen: (world.xhere.shared.civic.CivicNotice) -> Unit,
) {
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.civic_inbox_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .padding(top = 20.dp)
            .testTag(TestTags.CIVIC_INBOX),
    )
    notices.forEach { notice ->
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (notice.unread) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable {
                    onOpen(notice)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(notice.sourceUrl)))
                }
                .testTag(TestTags.civicNotice(notice.id.toString())),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(notice.title, style = MaterialTheme.typography.titleMedium)
                notice.addressText?.let { address ->
                    Text(address, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ImpactInbox(
    notices: List<ImpactNotice>,
    showEmpty: Boolean,
    authorName: String,
    avatarUrl: String?,
    onOpen: (ImpactNotice) -> Unit,
) {
    Text(
        text = stringResource(R.string.impact_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier
            .padding(top = 20.dp)
            .testTag(TestTags.IMPACT_INBOX),
    )
    if (showEmpty) {
        Text(
            text = stringResource(R.string.impact_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TestTags.IMPACT_EMPTY),
        )
        return
    }
    notices.forEach { notice ->
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (notice.unread) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable { onOpen(notice) }
                .testTag(TestTags.impactRow(notice.shortId)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (authorName.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        world.xhere.app.profile.ProfileAvatar(url = avatarUrl, label = authorName, size = 28.dp)
                        Text(
                            text = authorName,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .testTag(TestTags.IMPACT_AUTHOR),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(notice.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (notice.unread) {
                        Text(
                            text = stringResource(R.string.impact_new),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.detail_impact, notice.viewEvents, notice.confirmEvents),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (notice.capsuleOpens > 0) {
                    Text(
                        text = stringResource(R.string.impact_capsule_opened, notice.capsuleOpens),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag(TestTags.impactCapsule(notice.shortId)),
                    )
                }
                Text(
                    text = notice.noticeDay,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
