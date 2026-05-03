package chat.onym.android

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
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
import chat.onym.android.chats.ChatsScreen
import chat.onym.android.chats.ChatsViewModel
import chat.onym.android.group.CreateGroupViewModel
import chat.onym.android.group.IntroCapability
import chat.onym.android.group.JoinScreen
import chat.onym.android.group.JoinViewModel
import chat.onym.android.group.ShareInviteViewModel
import chat.onym.android.group.creategroup.CreateGroupScreen
import chat.onym.android.group.creategroup.ShareInviteScreen
import chat.onym.android.recovery.RecoveryPhraseBackupScreen
import chat.onym.android.recovery.RecoveryPhraseBackupViewModel
import chat.onym.android.search.SearchScreen
import chat.onym.android.identity.IdentitiesViewModel
import chat.onym.android.identity.IdentityId
import chat.onym.android.settings.AboutOnymScreen
import chat.onym.android.settings.AnchorsNetworkScreen
import chat.onym.android.settings.AnchorsPickerViewModel
import chat.onym.android.settings.AnchorsRootScreen
import chat.onym.android.settings.AnchorsVersionScreen
import chat.onym.android.settings.ContractDetailScreen
import chat.onym.android.settings.DeployContractScreen
import chat.onym.android.settings.IdentitiesScreen
import chat.onym.android.settings.IdentityDetailScreen
import chat.onym.android.settings.PrivacyEncryptionScreen
import chat.onym.android.settings.RelayerSettingsScreen
import chat.onym.android.settings.RelayerSettingsViewModel
import chat.onym.android.settings.RunRelayerScreen
import chat.onym.android.settings.SettingsScreen
import chat.onym.android.settings.UseExistingContractScreen

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
fun RootScreen(
    dependencies: AppDependencies,
    /** Set by [MainActivity] when an `https://onym.chat/join?c=…` or
     *  `onym://join?c=…` intent arrives — cold start or via
     *  `addOnNewIntentListener`. RootScreen navigates to the join
     *  destination when this flips non-null. */
    pendingCapability: IntroCapability? = null,
    /** Called immediately after RootScreen has issued the navigation
     *  for [pendingCapability]. Lets the host clear its slot so a
     *  later back-navigation doesn't re-fire the same capability. */
    onPendingCapabilityHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route

    // Forward an inbound deeplink capability to the join destination.
    // The capability's encoded form is short and URL-safe — embed it
    // as a path arg so the target composable can decode it without
    // touching shared state. Acks the host immediately after the
    // navigate() call (no need to wait for the destination to render).
    LaunchedEffect(pendingCapability) {
        val cap = pendingCapability ?: return@LaunchedEffect
        navController.navigate("join_invite/${cap.encode()}")
        onPendingCapabilityHandled()
    }

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
                            modifier = androidx.compose.ui.Modifier.testTag("nav.tab.${tab.route}"),
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
            startDestination = Tab.Chats.route,
            modifier = androidx.compose.ui.Modifier.padding(padding),
        ) {
            composable(Tab.Chats.route) {
                val vm: ChatsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeChatsViewModel() }
                    },
                )
                ChatsScreen(
                    viewModel = vm,
                    onCreateGroup = { navController.navigate(ROUTE_CREATE_GROUP) },
                )
            }
            composable(Tab.Settings.route) {
                val networkFlow = remember(dependencies) {
                    dependencies.networkPreferenceProvider.flow
                }
                val networkPref by networkFlow.collectAsStateWithLifecycle(
                    initialValue = dependencies.networkPreferenceProvider.current(),
                )
                val coroutineScope = rememberCoroutineScope()
                val identitiesVm: IdentitiesViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeIdentitiesViewModel() }
                    },
                )
                SettingsScreen(
                    identitiesViewModel = identitiesVm,
                    onRelayerClick = { navController.navigate(ROUTE_RELAYER_SETTINGS) },
                    onAnchorsClick = { navController.navigate(ROUTE_ANCHORS_ROOT) },
                    onIdentitiesClick = { navController.navigate(ROUTE_IDENTITIES) },
                    onIdentityDetailClick = { id ->
                        navController.navigate("identity_detail/${id.value}")
                    },
                    onPrivacyClick = { navController.navigate(ROUTE_PRIVACY) },
                    onAboutClick = { navController.navigate(ROUTE_ABOUT) },
                    useMainnet = networkPref == chat.onym.android.chain.AppNetwork.Mainnet,
                    onToggleMainnet = { on ->
                        coroutineScope.launch {
                            dependencies.networkPreferenceProvider.set(
                                if (on) chat.onym.android.chain.AppNetwork.Mainnet
                                else chat.onym.android.chain.AppNetwork.Testnet,
                            )
                        }
                    },
                )
            }
            composable(ROUTE_CREATE_GROUP) {
                val vm: CreateGroupViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeCreateGroupViewModel() }
                    },
                )
                vm.onClose = { navController.popBackStack() }
                vm.onShareInvite = { id ->
                    navController.navigate("share_invite/$id")
                }
                // OnymTheme provides LocalOnymTokens (light or dark
                // bundle picked off `isSystemInDarkTheme()`) so every
                // CreateGroup* screen reads the right surface family.
                // Chats / Settings tabs already adapt via Material's
                // colorScheme and don't need this wrapper.
                chat.onym.android.group.OnymTheme {
                    CreateGroupScreen(viewModel = vm)
                }
            }
            composable("share_invite/{groupId}") { entry ->
                val groupId = entry.arguments?.getString("groupId") ?: return@composable
                val vm: ShareInviteViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeShareInviteViewModel() }
                    },
                )
                ShareInviteScreen(
                    groupId = groupId,
                    viewModel = vm,
                    onDone = { navController.popBackStack() },
                )
            }
            // Inbound deeplink destination. Path arg carries the
            // url-safe-base64-of-JSON capability — re-decoded here
            // (rather than threading the value type through nav args)
            // so the screen survives process death: the system
            // rebuilds the back stack from the saved route strings,
            // and re-decoding from the path arg restores the same
            // capability without round-tripping through a Parcelable.
            composable("join_invite/{capability}") { entry ->
                val encoded = entry.arguments?.getString("capability")
                    ?: return@composable
                val capability = try {
                    IntroCapability.decode(encoded)
                } catch (_: Throwable) {
                    return@composable
                }
                val vm: JoinViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeJoinViewModel(capability) }
                    },
                )
                JoinScreen(
                    viewModel = vm,
                    onBackClick = { navController.popBackStack() },
                    onOpenChat = {
                        // No chat-detail destination yet — for now
                        // landing on Chats with the new group at the
                        // top of the list is the success state. The
                        // future ChatDetailScreen replaces this with
                        // a navigate to chat_detail/{id}.
                        navController.navigate(Tab.Chats.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Tab.Search.route) {
                SearchScreen()
            }
            composable(ROUTE_IDENTITIES) {
                val vm: IdentitiesViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeIdentitiesViewModel() }
                    },
                )
                IdentitiesScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onIdentityClick = { id ->
                        navController.navigate("identity_detail/${id.value}")
                    },
                )
            }
            composable("identity_detail/{identityId}") { entry ->
                val raw = entry.arguments?.getString("identityId") ?: return@composable
                val vm: IdentitiesViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeIdentitiesViewModel() }
                    },
                )
                IdentityDetailScreen(
                    viewModel = vm,
                    identityId = IdentityId(raw),
                    onBack = { navController.popBackStack() },
                    onBackup = { navController.navigate(ROUTE_RECOVERY_BACKUP) },
                )
            }
            composable(ROUTE_PRIVACY) {
                PrivacyEncryptionScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_ABOUT) {
                AboutOnymScreen(
                    onBack = { navController.popBackStack() },
                )
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
                    onRunYourOwnClick = { navController.navigate(ROUTE_RUN_RELAYER) },
                )
            }
            composable(ROUTE_RUN_RELAYER) {
                RunRelayerScreen(onBack = { navController.popBackStack() })
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
                    onContractDetailClick = { version, contractId ->
                        navController.navigate(
                            "contract_detail/${network.wireValue}/${type.wireValue}/${version}?addr=${contractId ?: ""}"
                        )
                    },
                    onUseExistingClick = {
                        navController.navigate("use_contract/${network.wireValue}/${type.wireValue}")
                    },
                    onDeployClick = {
                        navController.navigate("deploy_contract/${network.wireValue}/${type.wireValue}")
                    },
                )
            }
            composable("contract_detail/{network}/{type}/{version}?addr={addr}") { entry ->
                val net = ContractNetwork.fromWire(entry.arguments?.getString("network") ?: "")
                    ?: return@composable
                val gov = GovernanceType.fromWire(entry.arguments?.getString("type") ?: "")
                    ?: return@composable
                val ver = entry.arguments?.getString("version") ?: return@composable
                val addr = entry.arguments?.getString("addr")?.takeIf { it.isNotBlank() }
                ContractDetailScreen(
                    network = net,
                    type = gov,
                    version = ver,
                    contractAddress = addr,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("use_contract/{network}/{type}") { entry ->
                val net = ContractNetwork.fromWire(entry.arguments?.getString("network") ?: "")
                    ?: return@composable
                val gov = GovernanceType.fromWire(entry.arguments?.getString("type") ?: "")
                    ?: return@composable
                UseExistingContractScreen(
                    network = net,
                    type = gov,
                    onBack = { navController.popBackStack() },
                    onUseContract = { _, _ -> navController.popBackStack() },
                )
            }
            composable("deploy_contract/{network}/{type}") { entry ->
                val net = ContractNetwork.fromWire(entry.arguments?.getString("network") ?: "")
                    ?: return@composable
                val gov = GovernanceType.fromWire(entry.arguments?.getString("type") ?: "")
                    ?: return@composable
                DeployContractScreen(
                    network = net,
                    type = gov,
                    onBack = { navController.popBackStack() },
                    onUseDeployed = { navController.popBackStack() },
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
    // Chats is the leftmost + default tab post-PR-30.
    Chats("chats", R.string.chats_tab),
    Settings("settings", R.string.settings),
    Search("search", R.string.search);

    @Composable
    fun icon() = when (this) {
        Chats -> Icons.Filled.Forum
        Settings -> Icons.Filled.Settings
        Search -> Icons.Filled.Search
    }
}

private const val ROUTE_RECOVERY_BACKUP = "recovery_backup"
private const val ROUTE_IDENTITIES = "identities"
private const val ROUTE_PRIVACY = "privacy"
private const val ROUTE_ABOUT = "about_onym"
private const val ROUTE_RELAYER_SETTINGS = "relayer_settings"
private const val ROUTE_RUN_RELAYER = "run_relayer"
private const val ROUTE_ANCHORS_ROOT = "anchors_root"
private const val ROUTE_CREATE_GROUP = "create_group"
private val TAB_ROUTES = setOf(Tab.Chats.route, Tab.Settings.route, Tab.Search.route)
