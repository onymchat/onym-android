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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import chat.onym.android.chain.ContractNetwork
import chat.onym.android.chain.GovernanceType
import chat.onym.android.recovery.RecoveryPhraseBackupScreen
import chat.onym.android.recovery.RecoveryPhraseBackupViewModel
import chat.onym.android.search.SearchScreen
import chat.onym.android.settings.AnchorsNetworkScreen
import chat.onym.android.settings.AnchorsPickerViewModel
import chat.onym.android.settings.AnchorsRootScreen
import chat.onym.android.settings.AnchorsVersionScreen
import chat.onym.android.settings.RelayerSettingsScreen
import chat.onym.android.settings.RelayerSettingsViewModel
import chat.onym.android.settings.SettingsScreen

/**
 * App shell. `Scaffold` + Material 3 [NavigationBar] across the bottom,
 * a [androidx.navigation.compose.NavHost] for the content slot. Two tabs (Settings, Search) plus a
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
 *
 * Receives [AppDependencies] (built once in [OnymApplication]) and
 * uses its factory closures to construct per-flow ViewModels — no
 * composable in this graph holds a reference to a repository.
 */
@Composable
fun RootScreen(dependencies: AppDependencies) {
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
                    onRelayerClick = { navController.navigate(ROUTE_RELAYER_SETTINGS) },
                    onAnchorsClick = { navController.navigate(ROUTE_ANCHORS_ROOT) },
                )
            }
            composable(Tab.Search.route) {
                SearchScreen()
            }
            composable(ROUTE_RELAYER_SETTINGS) {
                val vm: RelayerSettingsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeRelayerSettingsViewModel() }
                    },
                )
                RelayerSettingsScreen(
                    viewModel = vm,
                    onBackClick = { navController.popBackStack() },
                )
            }
            composable(ROUTE_ANCHORS_ROOT) {
                val vm: AnchorsPickerViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeAnchorsPickerViewModel() }
                    },
                )
                AnchorsRootScreen(
                    viewModel = vm,
                    onNetworkClick = { net ->
                        navController.navigate("anchors_network/${net.wireValue}")
                    },
                    onBackClick = { navController.popBackStack() },
                )
            }
            composable("anchors_network/{network}") { entry ->
                val networkArg = entry.arguments?.getString("network") ?: return@composable
                val network = ContractNetwork.fromWire(networkArg) ?: return@composable
                val vm: AnchorsPickerViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeAnchorsPickerViewModel() }
                    },
                )
                AnchorsNetworkScreen(
                    viewModel = vm,
                    network = network,
                    onTypeClick = { type ->
                        navController.navigate("anchors_version/${network.wireValue}/${type.wireValue}")
                    },
                    onBackClick = { navController.popBackStack() },
                )
            }
            composable("anchors_version/{network}/{type}") { entry ->
                val networkArg = entry.arguments?.getString("network") ?: return@composable
                val typeArg = entry.arguments?.getString("type") ?: return@composable
                val network = ContractNetwork.fromWire(networkArg) ?: return@composable
                val type = GovernanceType.fromWire(typeArg) ?: return@composable
                val vm: AnchorsPickerViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeAnchorsPickerViewModel() }
                    },
                )
                AnchorsVersionScreen(
                    viewModel = vm,
                    network = network,
                    type = type,
                    onBackClick = { navController.popBackStack() },
                )
            }
            composable(ROUTE_RECOVERY_BACKUP) {
                // Resolve the host Activity at render time. AppDependencies
                // can't capture the Activity at app start (it doesn't exist
                // yet), so the factory takes a thunk; OnymApplication
                // tracks the currently-resumed Activity via lifecycle
                // callbacks and hands it back here.
                val app = LocalContext.current.applicationContext as OnymApplication
                val activityProvider = remember(app) { { app.requireCurrentFragmentActivity() } }
                val viewModel: RecoveryPhraseBackupViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            dependencies.makeRecoveryPhraseBackupViewModel(activityProvider)
                        }
                    },
                )
                RecoveryPhraseBackupScreen(
                    viewModel = viewModel,
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
private const val ROUTE_RELAYER_SETTINGS = "relayer_settings"
private const val ROUTE_ANCHORS_ROOT = "anchors_root"
private val TAB_ROUTES = setOf(Tab.Settings.route, Tab.Search.route)
