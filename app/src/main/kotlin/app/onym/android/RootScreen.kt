package app.onym.android

import androidx.compose.foundation.layout.consumeWindowInsets
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
import app.onym.android.chain.ContractNetwork
import app.onym.android.chain.GovernanceType
import app.onym.android.chats.ChatsScreen
import app.onym.android.chats.ChatsViewModel
import app.onym.android.group.CreateGroupViewModel
import app.onym.android.group.IntroCapability
import app.onym.android.group.JoinScreen
import app.onym.android.group.JoinViewModel
import app.onym.android.group.ScanToJoinScreen
import app.onym.android.group.ShareInviteViewModel
import app.onym.android.group.creategroup.CreateGroupScreen
import app.onym.android.group.creategroup.ShareInviteScreen
import app.onym.android.recovery.RecoveryPhraseBackupScreen
import app.onym.android.recovery.RecoveryPhraseBackupViewModel
import app.onym.android.search.SearchScreen
import app.onym.android.identity.IdentitiesViewModel
import app.onym.android.identity.IdentityId
import app.onym.android.settings.AboutOnymScreen
import app.onym.android.settings.AnchorsNetworkScreen
import app.onym.android.settings.AnchorsPickerViewModel
import app.onym.android.settings.AnchorsRootScreen
import app.onym.android.settings.AnchorsVersionScreen
import app.onym.android.settings.ContractDetailScreen
import app.onym.android.settings.DeployContractScreen
import app.onym.android.settings.IdentitiesScreen
import app.onym.android.settings.IdentityDetailScreen
import app.onym.android.settings.PrivacyEncryptionScreen
import app.onym.android.settings.RelayerSettingsScreen
import app.onym.android.settings.RelayerSettingsViewModel
import app.onym.android.settings.RunRelayerScreen
import app.onym.android.settings.SettingsScreen
import app.onym.android.settings.UseExistingContractScreen

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
    /** Set by [MainActivity] when an `https://onym.app/join?c=…` or
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
            // `consumeWindowInsets(padding)` is load-bearing, not
            // decorative: several destinations host their own
            // `Scaffold` (ChatThreadScreen, SettingsScreen, …). The
            // outer Scaffold here applies the system-bar insets and
            // hands them back as `padding`, but does NOT consume them
            // from the inset tree. Without the consume, a nested
            // Scaffold reads the full `systemBars` again and re-pads
            // the bottom navigation-bar inset a second time. On a
            // scrolling list that doubled inset just looks like extra
            // bottom padding; on the chat thread's bottom-pinned
            // input panel it's the visible gap + keyboard-overlap in
            // #154. Consuming here makes the inner Scaffolds see the
            // already-applied insets as spent, so `imePadding()`
            // downstream lands the panel flush on the keyboard.
            modifier = androidx.compose.ui.Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
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
                    approveRequestsViewModel = dependencies.approveRequestsViewModel,
                    onOpenApproveRequests = { navController.navigate(ROUTE_APPROVE_REQUESTS) },
                    pendingInvitesViewModel = dependencies.pendingInvitesViewModel,
                    onOpenInvitations = { navController.navigate(ROUTE_PENDING_INVITES) },
                    onOpenChat = { groupId ->
                        // PR A5: chats list now opens the thread,
                        // not the members roster. Members move one
                        // tap deeper behind the thread's info button.
                        navController.navigate("chat_thread/$groupId")
                    },
                    onScanToJoin = { navController.navigate(ROUTE_SCAN_JOIN) },
                )
            }
            composable(ROUTE_SCAN_JOIN) {
                ScanToJoinScreen(
                    onCapability = { capability ->
                        // Route a scanned invite through the exact join
                        // destination the tapped-link path uses; drop the
                        // scanner from the back stack so Back from Join
                        // returns to Chats, not the live camera.
                        navController.navigate("join_invite/${capability.encode()}") {
                            popUpTo(ROUTE_SCAN_JOIN) { inclusive = true }
                        }
                    },
                    onCancel = { navController.popBackStack() },
                )
            }
            composable("chat_thread/{groupId}") { entry ->
                val groupId = entry.arguments?.getString("groupId") ?: return@composable
                val vm: app.onym.android.chats.ChatThreadViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeChatThreadViewModel(groupId) }
                    },
                )
                app.onym.android.chats.ChatThreadScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onShowMembers = {
                        navController.navigate("chat_members/$groupId")
                    },
                )
            }
            composable("chat_members/{groupId}") { entry ->
                val groupId = entry.arguments?.getString("groupId") ?: return@composable
                val chatsVm: ChatsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeChatsViewModel() }
                    },
                )
                val identitiesVm: IdentitiesViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeIdentitiesViewModel() }
                    },
                )
                app.onym.android.chats.ChatMembersScreen(
                    groupId = groupId,
                    chatsViewModel = chatsVm,
                    identityViewModel = identitiesVm,
                    onBack = { navController.popBackStack() },
                    onShareInviteClick = {
                        // Reuse the existing share-invite surface —
                        // ShareInviteScreen + ShareInviteViewModel
                        // already know how to mint a fresh
                        // IntroCapability + render the deeplink URL.
                        navController.navigate("share_invite/$groupId")
                    },
                )
            }
            composable(ROUTE_APPROVE_REQUESTS) {
                app.onym.android.group.ApproveRequestsScreen(
                    viewModel = dependencies.approveRequestsViewModel,
                    onClose = { navController.popBackStack() },
                )
            }
            composable(ROUTE_PENDING_INVITES) {
                app.onym.android.inbox.PendingInvitesScreen(
                    viewModel = dependencies.pendingInvitesViewModel,
                    onClose = { navController.popBackStack() },
                )
            }
            composable(Tab.Settings.route) {
                val networkFlow = remember(dependencies) {
                    dependencies.networkPreferenceProvider.flow
                }
                val networkPref by networkFlow.collectAsStateWithLifecycle(
                    initialValue = dependencies.networkPreferenceProvider.current(),
                )
                val readReceiptsFlow = remember(dependencies) {
                    dependencies.readReceiptsPreferenceProvider.flow
                }
                val sendReadReceipts by readReceiptsFlow.collectAsStateWithLifecycle(
                    initialValue = dependencies.readReceiptsPreferenceProvider.current(),
                )
                val coroutineScope = rememberCoroutineScope()
                val identitiesVm: IdentitiesViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeIdentitiesViewModel() }
                    },
                )
                val nostrRelays by dependencies.nostrRelaysFlow.collectAsStateWithLifecycle()
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
                    useMainnet = networkPref == app.onym.android.chain.AppNetwork.Mainnet,
                    onToggleMainnet = { on ->
                        coroutineScope.launch {
                            dependencies.networkPreferenceProvider.set(
                                if (on) app.onym.android.chain.AppNetwork.Mainnet
                                else app.onym.android.chain.AppNetwork.Testnet,
                            )
                        }
                    },
                    sendReadReceipts = sendReadReceipts,
                    onToggleReadReceipts = { on ->
                        coroutineScope.launch {
                            dependencies.readReceiptsPreferenceProvider.set(on)
                        }
                    },
                    onNostrRelaysClick = { navController.navigate(ROUTE_NOSTR_RELAYS) },
                    nostrRelaysCount = nostrRelays.endpoints.size,
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
                app.onym.android.group.OnymTheme {
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
            composable(ROUTE_NOSTR_RELAYS) {
                val vm = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeNostrRelaySettingsViewModel() }
                    },
                ) as app.onym.android.settings.NostrRelaySettingsViewModel
                app.onym.android.settings.NostrRelaySettingsScreen(
                    viewModel = vm,
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
private const val ROUTE_NOSTR_RELAYS = "nostr_relays"
private const val ROUTE_RUN_RELAYER = "run_relayer"
private const val ROUTE_ANCHORS_ROOT = "anchors_root"
private const val ROUTE_CREATE_GROUP = "create_group"
private const val ROUTE_APPROVE_REQUESTS = "approve_requests"
private const val ROUTE_PENDING_INVITES = "pending_invites"
private const val ROUTE_SCAN_JOIN = "scan_join"
private val TAB_ROUTES = setOf(Tab.Chats.route, Tab.Settings.route, Tab.Search.route)
