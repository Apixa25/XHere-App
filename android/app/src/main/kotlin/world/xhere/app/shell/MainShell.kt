package world.xhere.app.shell

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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import world.xhere.app.detail.DropDetailScreen
import world.xhere.app.detail.IncomingLinks
import world.xhere.app.drop.DropScreen
import world.xhere.app.map.MapScreen
import world.xhere.app.profile.ProfileViewModel
import world.xhere.shared.model.ImpactNotice
import world.xhere.app.safety.GuidelinesScreen
import world.xhere.app.safety.ModerationScreen
import world.xhere.app.safety.SafetyListsScreen
import world.xhere.app.settings.SettingsScreen
import world.xhere.app.ui.MapMark
import world.xhere.app.ui.TestTags

private object Routes {
    const val MAP = "map"
    const val DROP = "drop"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val GUIDELINES = "guidelines"
    const val LISTS = "blocked"
    const val MODERATION = "moderation"
    const val DETAIL = "detail/{shortId}"

    /** Full-screen pages with their own back arrow and no bottom bar. */
    val fullScreen = setOf(SETTINGS, GUIDELINES, LISTS, MODERATION, DETAIL)

    fun detail(shortId: String): String = "detail/$shortId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell() {
    val nav = rememberNavController()
    val links: IncomingLinks = koinInject()
    val pending by links.pending.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route !in Routes.fullScreen
    val showTopBar = showBar && route != Routes.MAP
    LaunchedEffect(pending) {
        val id = pending ?: return@LaunchedEffect
        nav.navigate(Routes.detail(id)) { launchSingleTop = true }
        links.consume()
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
                        nav.navigate(Routes.DROP) { launchSingleTop = true }
                    },
                    onOpenDrop = { shortId ->
                        nav.navigate(Routes.detail(shortId)) { launchSingleTop = true }
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
                arguments = listOf(navArgument("shortId") { type = NavType.StringType }),
            ) { entry ->
                DropDetailScreen(
                    shortId = entry.arguments?.getString("shortId").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}

private enum class Tab(val route: String, val label: Int, val tag: String, val icon: ImageVector) {
    Map(Routes.MAP, R.string.nav_map, TestTags.NAV_MAP, Icons.Filled.Place),
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
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(TestTags.SCREEN_PROFILE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = state.displayName.ifBlank { stringResource(R.string.profile_signed_in) },
            style = MaterialTheme.typography.headlineSmall,
        )
        if (state.handle.isNotBlank()) {
            Text("@${state.handle}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (state.tierLabel.isNotBlank()) {
            Text(state.tierLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            onOpen = { notice -> viewModel.openNotice(notice, onOpenDrop) },
        )
    }
}

@Composable
private fun ColumnScope.ImpactInbox(
    notices: List<ImpactNotice>,
    showEmpty: Boolean,
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
                Text(
                    text = notice.noticeDay,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
