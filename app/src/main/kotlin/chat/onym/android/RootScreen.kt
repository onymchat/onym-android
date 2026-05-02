package chat.onym.android

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import chat.onym.android.recovery.RecoveryPhraseBackupScreen
import chat.onym.android.recovery.RecoveryPhraseBackupViewModel
import chat.onym.android.search.SearchScreen
import chat.onym.android.settings.SettingsScreen

/**
 * App shell. `Scaffold` + Material 3 [NavigationBar] across the bottom,
 * a [NavHost] for the content slot. Two tabs (Settings, Search) plus a
 * full-screen `recovery_backup` destination pushed from the Settings
 * row.
 *
 * Mirrors `RootView.swift` from onym-ios PR #6 in shape:
 *
 *   - Settings tab is the entry point for the recovery-phrase backup
 *     flow (gated behind a Backup row tap).
 *   - Search tab is a placeholder (real search lands later).
 *
 * **Diverges from iOS** in two ways the brief called out:
 *
 *   - No `.search` role / bottom-right floating tab (Material 3 has no
 *     equivalent; would create cross-platform inconsistency without
 *     buying anything Android users expect). Both items live in the
 *     same nav bar with equal weight — same as Gmail / Calendar /
 *     Drive / Files.
 *   - Backup flow is a navigation destination (not a `ModalBottomSheet`).
 *     Android-idiomatic for a multi-step flow; supports system back
 *     gesture without ceremony.
 */
@Composable
fun RootScreen(recoveryViewModel: RecoveryPhraseBackupViewModel) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // Hide the bottom bar on the recovery-backup destination so
            // the multi-step flow has full vertical real-estate (the
            // back gesture / TopAppBar back arrow is the way out).
            if (currentRoute in TAB_ROUTES) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            icon = { Icon(tab.icon(), contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    // Keep a single instance of each tab destination
                                    // and re-use the existing one when the user
                                    // re-taps; pop intermediate destinations off
                                    // the back stack so a tab tap is always a
                                    // "go back to root of this tab" move.
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Settings.route,
            modifier = androidx.compose.ui.Modifier.padding(padding),
        ) {
            composable(Tab.Settings.route) {
                SettingsScreen(
                    onBackupClick = { navController.navigate(ROUTE_RECOVERY_BACKUP) },
                )
            }
            composable(Tab.Search.route) {
                SearchScreen()
            }
            composable(ROUTE_RECOVERY_BACKUP) {
                RecoveryPhraseBackupScreen(
                    viewModel = recoveryViewModel,
                    onBackClick = { navController.popBackStack() },
                )
            }
        }
    }
}

private enum class Tab(val route: String, val labelRes: Int) {
    Settings("settings", R.string.settings),
    Search("search", R.string.search);

    @Composable
    fun icon() = when (this) {
        Settings -> Icons.Filled.Settings
        Search -> Icons.Filled.Search
    }
}

private const val ROUTE_RECOVERY_BACKUP = "recovery_backup"
private val TAB_ROUTES = setOf(Tab.Settings.route, Tab.Search.route)
