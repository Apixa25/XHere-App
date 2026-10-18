package world.xhere.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.koin.androidx.compose.koinViewModel
import world.xhere.app.R
import world.xhere.app.profile.ProfileViewModel
import world.xhere.app.settings.SettingsScreen
import world.xhere.app.ui.MapMark
import world.xhere.app.ui.TestTags

private object Routes {
    const val MAP = "map"
    const val DROP = "drop"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route != Routes.SETTINGS
    Scaffold(
        topBar = {
            if (showBar) {
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
            composable(Routes.MAP) { PlaceholderPage(TestTags.SCREEN_MAP, R.string.map_title, R.string.map_body) }
            composable(Routes.DROP) { PlaceholderPage(TestTags.SCREEN_DROP, R.string.drop_title, R.string.drop_body) }
            composable(Routes.PROFILE) { ProfilePage(onSettings = { nav.navigate(Routes.SETTINGS) }) }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
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
private fun ProfilePage(onSettings: () -> Unit, viewModel: ProfileViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
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
    }
}
