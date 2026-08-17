package app.onym.android

// String resources moved to the :strings module — the same-package
// `app.onym.android.R` no longer carries R.string entries under
// android.nonTransitiveRClass.
import app.onym.android.strings.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
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
import androidx.navigation.NavType
import androidx.navigation.navArgument
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
    // First-launch onboarding gate (PR 3): while the completed/
    // grandfathered decision is unresolved hold a blank frame (a
    // single fast DataStore read — flashing the tabs at a user who is
    // about to be onboarded is worse); when onboarding is due, the
    // walk replaces the whole shell, full-screen and back-blocked,
    // until `OnboardingFlow.complete` flips the decision to false.
    val onboarding = dependencies.onboarding
    if (onboarding != null) {
        val shouldOnboard by onboarding.shouldOnboard.collectAsStateWithLifecycle()
        when (shouldOnboard) {
            null -> {
                Box(androidx.compose.ui.Modifier.fillMaxSize())
                return
            }
            true -> {
                OnboardingHost(dependencies = dependencies, onboarding = onboarding)
                return
            }
            false -> Unit // fall through to the tab shell
        }
    }

    // Moderation gate (device-recall profile): after onboarding — the
    // walk's own moderation step handles first-run consent, so the
    // cover must not stack on top of it — the RootGate decides whether
    // the shell renders at all. Banned and CheckRequired replace it
    // full-screen and back-blocked (the same early-return pattern as
    // the onboarding gate above); NeedsConsent hosts the consent
    // surface for users who onboarded before the seat existed (or
    // whose enrollment the backend lost). Null dependencies = the seat
    // is dark, and everything renders as before it existed.
    val moderation = dependencies.moderation
    if (moderation != null) {
        val rootGate by moderation.gate.snapshots.collectAsStateWithLifecycle()
        when (val gate = rootGate) {
            is app.onym.android.moderation.ui.RootGate.Banned -> {
                val context = LocalContext.current
                // The appeal surface is hosted HERE, not in the NavHost
                // below: this branch returns before the shell exists, so
                // a banned user could not reach a nav destination at all
                // — which is why the in-app appeal shipped unreachable
                // for the one person who needs it. Local state, and Back
                // closes it rather than escaping the gate.
                // Saveable, not remembered: this host is hand-rolled
                // rather than a NavHost destination, so nothing else
                // restores it across activity recreation. With plain
                // `remember`, a rotation mid-appeal dropped the banned
                // user back to the ban screen AND discarded the typed
                // statement — CaseAppealScreen's own `rememberSaveable`
                // never restores, because the screen left composition.
                var appealingCaseId by androidx.compose.runtime.saveable.rememberSaveable {
                    androidx.compose.runtime.mutableStateOf<String?>(null)
                }
                BackHandler(enabled = true) { appealingCaseId = null }
                val caseId = appealingCaseId
                if (caseId != null) {
                    val controller = remember(caseId) {
                        moderation.makeCaseAppealController(caseId)
                    }
                    app.onym.android.moderation.ui.CaseAppealScreen(
                        controller = controller,
                        // No gate notice while banned — the case is
                        // closed enough to have produced a verdict — so
                        // the screen renders from the case id alone.
                        notice = null,
                        onBack = { appealingCaseId = null },
                    )
                    return
                }
                app.onym.android.moderation.ui.BannedScreen(
                    state = gate.state,
                    onOpenUrl = { url ->
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url),
                                ),
                            )
                        }
                    },
                    onAppealCase = { id -> appealingCaseId = id },
                )
                return
            }
            is app.onym.android.moderation.ui.RootGate.CheckRequired -> {
                BackHandler(enabled = true) {}
                app.onym.android.moderation.ui.GateCheckRequiredScreen(
                    reason = gate.reason,
                    onRetry = { moderation.gate.tappedRetry() },
                    authorityContact = gate.authorityContact,
                )
                return
            }
            is app.onym.android.moderation.ui.RootGate.NeedsConsent -> {
                BackHandler(enabled = true) {}
                // resumeExistingMandate = canDefer: the never-mandated
                // host resumes a just-persisted consent after rotation;
                // the enrollment-lost host (canDefer=false) must run a
                // FRESH transaction despite the local record.
                val controller = remember(gate.canDefer) {
                    moderation.makeConsentController(gate.canDefer)
                }
                app.onym.android.moderation.ui.ModerationConsentContent(
                    controller = controller,
                    onConsented = { moderation.gate.consentCompleted() },
                    // An unreachable authority (directory up, manifest
                    // fetch or signature verify down) must not lock a
                    // never-mandated user out of the app for the
                    // outage's duration: Continue defers consent for
                    // this process, mirroring the empty-directory
                    // softening, and the next launch asks again. An
                    // ENROLLMENT-LOST user also lands here (re-consent
                    // is their route back), but deferring past a gate
                    // that refused them is not on offer — canDefer is
                    // false and no Continue renders, because a dead
                    // button is worse than none.
                    onUnavailableContinue = if (gate.canDefer) {
                        { moderation.gate.deferConsent() }
                    } else {
                        null
                    },
                    // Same debug-only escape as the onboarding step;
                    // inert when canDefer is false (no deferral
                    // callback to take).
                    debugSkipAllowed = BuildConfig.DEBUG,
                )
                return
            }
            is app.onym.android.moderation.ui.RootGate.Operational -> Unit
        }
    }

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
                    activeBlsPubkeyHex = dependencies.activeBlsPubkeyHex,
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
            composable(
                "chat_thread/{groupId}?scrollTo={scrollTo}",
                arguments = listOf(
                    navArgument("scrollTo") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val groupId = entry.arguments?.getString("groupId") ?: return@composable
                val scrollTo = entry.arguments?.getString("scrollTo")
                    ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
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
                    scrollToMessageId = scrollTo,
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
                val blossomServers by dependencies.blossomServersFlow.collectAsStateWithLifecycle()
                val discoveryState = dependencies.discovery?.stateFlow
                    ?.collectAsStateWithLifecycle()
                // Notices ride on an otherwise-operational gate answer.
                val moderationOpenCases =
                    (dependencies.moderation?.gate?.snapshots?.collectAsStateWithLifecycle()
                        ?.value as? app.onym.android.moderation.ui.RootGate.Operational)
                        ?.openCases
                        .orEmpty()
                SettingsScreen(
                    identitiesViewModel = identitiesVm,
                    onRelayerClick = { navController.navigate(ROUTE_RELAYER_SETTINGS) },
                    onAnchorsClick = { navController.navigate(ROUTE_ANCHORS_ROOT) },
                    onBackup = { navController.navigate(ROUTE_RECOVERY_BACKUP) },
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
                    onBlossomRelaysClick = { navController.navigate(ROUTE_BLOSSOM_RELAYS) },
                    blossomRelaysCount = blossomServers.endpoints.size,
                    onClearMessages = {
                        coroutineScope.launch { dependencies.clearAllMessages() }
                    },
                    // Confirmed restart: RootScreen's top-level gate
                    // reacts to `shouldOnboard` flipping true and
                    // presents the walk immediately — no cold-boot
                    // probe re-run (the restart bit overrides
                    // grandfathering by design).
                    onRestartOnboarding = dependencies.onboarding?.let { onboardingDeps ->
                        { coroutineScope.launch { onboardingDeps.requestRestart() } }
                    },
                    // Null when discovery isn't wired (UI-test
                    // harness) — the DISCOVERY section is omitted.
                    onDiscoveryClick = dependencies.discovery?.let {
                        { navController.navigate(ROUTE_DISCOVERY_SETTINGS) }
                    },
                    discoveryProvidersCount = discoveryState?.value?.sources?.size ?: 0,
                    // Null when the moderation seat is dark — the
                    // MODERATION section is omitted, exactly like the
                    // discovery row above.
                    onModerationClick = dependencies.moderation?.let {
                        { navController.navigate(ROUTE_MODERATION_SETTINGS) }
                    },
                    // Resolved PER IDENTITY (never a raw records
                    // scan — ModerationState.activeMandate's rule);
                    // null while unresolved renders no subtitle
                    // rather than a wrong one.
                    moderationConsent = dependencies.moderation?.let { moderation ->
                        val snapshots =
                            moderation.repository.snapshots.collectAsStateWithLifecycle()
                        androidx.compose.runtime.produceState<
                            app.onym.android.moderation.ModerationRepository.IdentityConsentState?,
                        >(initialValue = null, snapshots.value) {
                            value = runCatching {
                                moderation.repository.identityConsentState()
                            }.getOrNull()
                        }.value
                    },
                    openCases = moderationOpenCases,
                    // One case goes straight to its appeal. Several go
                    // to Settings → Moderation, whose Open cases section
                    // lists every one with its own Appeal row — the
                    // banner used to say "N cases" and then open only
                    // the first, leaving the rest unreachable from here.
                    onReviewCases = {
                        val single = moderationOpenCases.singleOrNull()
                        if (single != null) {
                            navController.navigate(
                                "moderation_case_appeal/${android.net.Uri.encode(single.caseId)}",
                            )
                        } else {
                            navController.navigate(ROUTE_MODERATION_SETTINGS)
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
                app.onym.android.design.OnymTheme {
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
                    onOpenChat = { group ->
                        // Open the chat itself. This used to land on the
                        // Chats tab instead, because no chat-detail
                        // destination existed when the join flow was
                        // written; `chat_thread/{groupId}` arrived later
                        // (PR A5) and the Chats list has been using it
                        // since. Dropping the user on a list one tap
                        // after "You're in" read as a dead button.
                        //
                        // popUpTo start drops join_invite, so Back from
                        // the thread lands on Chats, not back on the
                        // just-completed join screen.
                        navController.navigate("chat_thread/${group.id}") {
                            popUpTo(navController.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Tab.Search.route) {
                val vm: app.onym.android.search.SearchViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeSearchViewModel() }
                    },
                )
                SearchScreen(
                    viewModel = vm,
                    onOpenResult = { groupId, messageId ->
                        navController.navigate("chat_thread/$groupId?scrollTo=$messageId")
                    },
                )
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
            composable(ROUTE_ABOUT) {
                AboutOnymScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_NOSTR_RELAYS) {
                val vm = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeNostrRelaySettingsViewModel(null) }
                    },
                ) as app.onym.android.settings.NostrRelaySettingsViewModel
                val (entries, consents) = rememberSeatCatalog(
                    dependencies = dependencies,
                    seatType = DiscoveryBackedKnownNostrRelaysFetcher.SEAT_TYPE,
                )
                app.onym.android.settings.NostrRelaySettingsScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onRunYourOwn = { navController.navigate(ROUTE_RUN_NOSTR) },
                    catalogEntries = entries,
                    consentByComponentId = consents,
                    onCatalogEntryClick = { entry ->
                        navController.navigate(moduleConsentRoute(entry))
                    },
                )
            }
            composable(ROUTE_RUN_NOSTR) {
                app.onym.android.settings.RunNostrRelayScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_BLOSSOM_RELAYS) {
                val vm = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeBlossomServerSettingsViewModel(null) }
                    },
                ) as app.onym.android.settings.BlossomServerSettingsViewModel
                app.onym.android.settings.BlossomServerSettingsScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onRunYourOwn = { navController.navigate(ROUTE_RUN_BLOSSOM) },
                )
            }
            composable(ROUTE_RUN_BLOSSOM) {
                app.onym.android.settings.RunBlossomServerScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_RELAYER_SETTINGS) {
                val vm: RelayerSettingsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { dependencies.makeRelayerSettingsViewModel(null) }
                    },
                )
                val (entries, consents) = rememberSeatCatalog(
                    dependencies = dependencies,
                    seatType = DiscoveryBackedKnownRelayersFetcher.SEAT_TYPE,
                )
                RelayerSettingsScreen(
                    viewModel = vm,
                    onBackClick = { navController.popBackStack() },
                    onRunYourOwnClick = { navController.navigate(ROUTE_RUN_RELAYER) },
                    catalogEntries = entries,
                    consentByComponentId = consents,
                    onCatalogEntryClick = { entry ->
                        navController.navigate(moduleConsentRoute(entry))
                    },
                )
            }
            composable(ROUTE_RUN_RELAYER) {
                RunRelayerScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_DISCOVERY_SETTINGS) {
                // Reachable only through the Settings row, which is
                // hidden when discovery isn't wired — the guard keeps
                // a stale deep link from crashing.
                val discovery = dependencies.discovery ?: return@composable
                val vm: app.onym.android.settings.DiscoverySettingsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { discovery.makeDiscoverySettingsViewModel(null) }
                    },
                )
                app.onym.android.settings.DiscoverySettingsScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onAddProvider = { navController.navigate(ROUTE_ADD_DISCOVERY_PROVIDER) },
                )
            }
            composable(ROUTE_MODERATION_SETTINGS) {
                val moderation = dependencies.moderation ?: return@composable
                // The open-case notices ride along on an operational
                // gate answer — a case never blocks the app, so this
                // screen is where one becomes visible and appealable.
                val gateState by moderation.gate.snapshots.collectAsStateWithLifecycle()
                app.onym.android.settings.ModerationSettingsScreen(
                    repository = moderation.repository,
                    onRetryRegistration = moderation.retryRegistration,
                    onSwitchAuthority = {
                        navController.navigate(ROUTE_MODERATION_SWITCH_CONSENT)
                    },
                    onViewTerms = { navController.navigate(ROUTE_MODERATION_TERMS) },
                    openCases =
                        (gateState as? app.onym.android.moderation.ui.RootGate.Operational)
                            ?.openCases
                            .orEmpty(),
                    onAppealCase = { caseId ->
                        // Encoded, because a case id is opaque
                        // authority-issued text: a `/` in one would
                        // mismatch the `{caseId}` pattern and the screen
                        // would simply never open. Navigation decodes
                        // path arguments on read (`NavDeepLink` runs
                        // `Uri.decode`), so the destination — and the
                        // authority client below it — still sees the
                        // original id. Same reason
                        // `OkHttpAuthorityClient` percent-encodes the
                        // path segment.
                        navController.navigate(
                            "moderation_case_appeal/${android.net.Uri.encode(caseId)}",
                        )
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable("moderation_case_appeal/{caseId}") { entry ->
                val moderation = dependencies.moderation ?: return@composable
                val caseId = entry.arguments?.getString("caseId") ?: return@composable
                // Keyed on the case: one controller carries one case's
                // submit state, and reusing it across cases would show
                // one case's outcome on another's screen.
                val controller = remember(caseId) {
                    moderation.makeCaseAppealController(caseId)
                }
                val gateState by moderation.gate.snapshots.collectAsStateWithLifecycle()
                app.onym.android.moderation.ui.CaseAppealScreen(
                    controller = controller,
                    // The notice is context (class, deadlines), not a
                    // precondition — a case the gate has since stopped
                    // reporting is still appealable by id.
                    notice = (gateState as? app.onym.android.moderation.ui.RootGate.Operational)
                        ?.openCases
                        ?.firstOrNull { it.caseId == caseId },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_MODERATION_TERMS) {
                val moderation = dependencies.moderation ?: return@composable
                app.onym.android.settings.ConsentedTermsScreen(
                    repository = moderation.repository,
                    documents = moderation.documents,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(ROUTE_MODERATION_SWITCH_CONSENT) {
                val moderation = dependencies.moderation ?: return@composable
                // resumeExistingMandate = false: switching is ALWAYS a
                // fresh one-snapshot review + fresh mandate — an
                // existing healthy record must not short-circuit to
                // Consented (same contract as the enrollment-lost
                // re-consent host).
                val controller = remember {
                    moderation.makeConsentController(false)
                }
                app.onym.android.moderation.ui.ModerationConsentContent(
                    controller = controller,
                    onConsented = {
                        moderation.gate.consentCompleted()
                        navController.popBackStack()
                    },
                    // Settings can be walked away from — no deferral
                    // affordance needed; Back is the exit.
                    onUnavailableContinue = null,
                    standalone = true,
                )
            }
            composable(ROUTE_ADD_DISCOVERY_PROVIDER) {
                val discovery = dependencies.discovery ?: return@composable
                // Fresh ViewModel instance per visit: the add flow's
                // phase state is local to this destination, while the
                // pinned outcome propagates through the repository.
                val vm: app.onym.android.settings.DiscoverySettingsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { discovery.makeDiscoverySettingsViewModel(null) }
                    },
                )
                app.onym.android.settings.AddDiscoveryProviderScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                )
            }
            // Module consent for one catalog entry. The path carries
            // seat type + short component id (not the value type) so
            // the destination survives process death; the entry is
            // re-looked-up from the live aggregate by the factory.
            composable("module_consent/{seatType}/{componentShortId}") { entry ->
                val discovery = dependencies.discovery ?: return@composable
                val seatType = entry.arguments?.getString("seatType") ?: return@composable
                val shortId = entry.arguments?.getString("componentShortId") ?: return@composable
                val vm: app.onym.android.settings.ModuleConsentViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { discovery.makeModuleConsentViewModel(seatType, shortId) }
                    },
                )
                app.onym.android.settings.ModuleConsentScreen(
                    viewModel = vm,
                    onClose = { navController.popBackStack() },
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
                    onVersionClick = { net, type ->
                        navController.navigate("anchors_version/${net.wireValue}/${type.wireValue}")
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

/** Route into the module-consent flow for one catalog entry. The
 *  `onym:component:` prefix is stripped so the path arg stays a plain
 *  `[a-z0-9-]` token (component ids can't collide after stripping —
 *  the prefix is fixed). */
private fun moduleConsentRoute(
    entry: app.onym.android.discovery.AttributedCatalogEntry,
): String {
    val shortId = entry.entry.componentId.removePrefix("onym:component:")
    return "module_consent/${entry.entry.seatType}/$shortId"
}

/**
 * Discovery entries for one seat type + the active pinned consent per
 * componentId, for a picker's "From catalog" section. Both empty when
 * discovery isn't wired. Consents reload whenever the entry list
 * changes or the destination re-enters composition (returning from
 * the consent flow re-runs [produceState]).
 */
@Composable
internal fun rememberSeatCatalog(
    dependencies: AppDependencies,
    seatType: String,
): Pair<
    List<app.onym.android.discovery.AttributedCatalogEntry>,
    Map<String, app.onym.android.foundation.PinnedConsentRecord>,
> {
    val discovery = dependencies.discovery
        ?: return emptyList<app.onym.android.discovery.AttributedCatalogEntry>() to emptyMap()
    val state by discovery.stateFlow.collectAsStateWithLifecycle()
    val entries = remember(state.entries, seatType) {
        state.entries.filter { it.entry.seatType == seatType }
    }
    val consents by androidx.compose.runtime.produceState(
        initialValue = emptyMap<String, app.onym.android.foundation.PinnedConsentRecord>(),
        entries,
    ) {
        value = entries.mapNotNull { entry ->
            discovery.activeConsent(entry.entry.componentId)
                ?.let { entry.entry.componentId to it }
        }.toMap()
    }
    return entries to consents
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
private const val ROUTE_ABOUT = "about_onym"
private const val ROUTE_RELAYER_SETTINGS = "relayer_settings"
private const val ROUTE_DISCOVERY_SETTINGS = "discovery_settings"
private const val ROUTE_MODERATION_SETTINGS = "moderation_settings"
private const val ROUTE_MODERATION_SWITCH_CONSENT = "moderation_switch_consent"
private const val ROUTE_MODERATION_TERMS = "moderation_terms"
private const val ROUTE_ADD_DISCOVERY_PROVIDER = "add_discovery_provider"
private const val ROUTE_NOSTR_RELAYS = "nostr_relays"
private const val ROUTE_BLOSSOM_RELAYS = "blossom_relays"
private const val ROUTE_RUN_NOSTR = "run_nostr_relay"
private const val ROUTE_RUN_BLOSSOM = "run_blossom_server"
private const val ROUTE_RUN_RELAYER = "run_relayer"
private const val ROUTE_ANCHORS_ROOT = "anchors_root"
private const val ROUTE_CREATE_GROUP = "create_group"
private const val ROUTE_PENDING_INVITES = "pending_invites"
private const val ROUTE_SCAN_JOIN = "scan_join"
private val TAB_ROUTES = setOf(Tab.Chats.route, Tab.Settings.route, Tab.Search.route)
