package chat.onym.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import chat.onym.android.chain.BearerAuthInterceptor
import chat.onym.android.chain.ContractsRepository
import chat.onym.android.chain.DataStoreNetworkPreferenceProvider
import chat.onym.android.chain.OkHttpSepContractTransport
import chat.onym.android.chain.DataStorePreferencesAnchorSelectionStore
import chat.onym.android.chain.DataStorePreferencesRelayerSelectionStore
import chat.onym.android.chain.GitHubReleasesContractsManifestFetcher
import chat.onym.android.chain.GitHubReleasesKnownRelayersFetcher
import chat.onym.android.chain.RelayerRepository
import chat.onym.android.chain.relayerFetchErrorMessageResolver
import chat.onym.android.group.CreateGroupInteractor
import chat.onym.android.group.CreateGroupViewModel
import chat.onym.android.group.GroupDatabase
import chat.onym.android.group.GroupDatabaseMigrations
import chat.onym.android.group.GroupRepository
import chat.onym.android.group.RoomGroupStore
import chat.onym.android.identity.IdentityRepository
import chat.onym.android.identity.IdentitySecretStore
import chat.onym.android.identity.OnymNostrSignerProvider
import chat.onym.android.persistence.StorageEncryption
import chat.onym.android.recovery.AndroidBiometricAuthenticator
import chat.onym.android.recovery.AndroidClipboardWriter
import chat.onym.android.recovery.AndroidStringProvider
import chat.onym.android.recovery.RecoveryPhraseBackupViewModel
import chat.onym.android.settings.AnchorsPickerViewModel
import chat.onym.android.settings.RelayerSettingsViewModel
import chat.onym.android.transport.nostr.NostrInboxTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.lang.ref.WeakReference
import java.security.Security

/**
 * DataStore Preferences for the relayer URL selection + cached
 * known-relayer list. The `preferencesDataStore` delegate
 * guarantees one instance per `Context` per filename across the
 * process — safe to call from anywhere; we only read it from
 * [OnymApplication.onCreate].
 *
 * Identity material continues to live in EncryptedSharedPreferences
 * via [IdentitySecretStore]; URLs aren't secret so DataStore is fine.
 */
private val Context.relayerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chat.onym.android.relayer_prefs",
)

/**
 * DataStore Preferences for the anchor (contract version)
 * selections + cached `contracts-manifest.json`. Separate file from
 * [relayerDataStore] so each domain's storage layer has its own
 * blob — easier to reason about + selectively wipe in tests.
 */
private val Context.contractsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chat.onym.android.contracts_prefs",
)

/**
 * DataStore Preferences for app-wide network selection
 * (`onym.useMainnet` boolean — same key iOS uses via
 * `@AppStorage("onym.useMainnet")`). Separate file so the Settings
 * toggle doesn't touch the contracts cache or the relayer list.
 */
private val Context.networkPreferenceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chat.onym.android.network_prefs",
)

/**
 * DataStore Preferences for Nostr relay configuration. Separate
 * from [relayerDataStore] (which holds chain-relayer URLs) — different
 * domain, different lifecycle.
 */
private val Context.nostrRelaysDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "chat.onym.android.nostr_relays_prefs",
)

/**
 * Composition root. Two responsibilities:
 *
 *  - Register the BouncyCastle JCE provider once at process start
 *    (we use BC for Curve25519 raw-key APIs the JDK doesn't expose
 *    — see [chat.onym.android.identity.IdentityRepository]). BC
 *    must be installed BEFORE any provider lookup that picks the
 *    first match (some JCA APIs cache providers per-thread); doing
 *    it in [Application.onCreate] guarantees ordering.
 *  - Build [AppDependencies] — the single seam between the
 *    repository / FFI layer and the View / ViewModel layer.
 *    Repositories and I/O affordances live as captures inside the
 *    factory closures; nothing above this point holds a reference
 *    to [IdentityRepository] or to `chat.onym.sdk.*`.
 */
class OnymApplication : Application() {

    /** Lazy-built so instrumented tests can swap [UITestRegistry]
     *  fakes in BEFORE the first read (Application.onCreate runs
     *  once at process start, well before any JUnit `@Rule` body
     *  executes — building deps eagerly there reads an empty
     *  registry on every test). MainActivity is the first reader. */
    @Volatile
    private var depsLazy: Lazy<AppDependencies> = lazy { buildDependencies() }

    val dependencies: AppDependencies get() = depsLazy.value

    /** Test-only — invalidate the cached [AppDependencies] so the
     *  next [dependencies] read rebuilds from the current
     *  [UITestRegistry] state. Called by the registry-setup rule
     *  in instrumented tests; never used in production. */
    @androidx.annotation.VisibleForTesting
    internal fun rebuildDependenciesForTest() {
        depsLazy = lazy { buildDependencies() }
    }

    /** The currently-resumed Activity, used to host the AndroidX
     *  `BiometricPrompt` dialog fragment. `WeakReference` so a
     *  background-and-finish doesn't pin the Activity. */
    private var resumedActivity: WeakReference<Activity>? = null

    /** Application-scoped CoroutineScope for fire-and-forget jobs
     *  that must outlive any Activity (e.g., the boot fetch of the
     *  relayer list). [SupervisorJob] so a single failing child
     *  doesn't cancel the rest. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Insert at position 1 so BC is preferred for algorithms it
        // implements (X25519, Ed25519 raw-key params). The Android
        // platform's Conscrypt provider stays first for everything else.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 2)
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() === activity) resumedActivity = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // Dependency wiring happens lazily on first `dependencies`
        // read (typically MainActivity.onCreate). Bootstrap + start
        // are kicked off as part of `buildDependencies` so each
        // rebuild — including instrumented-test rebuilds — fans
        // bootstrap onto the new repository instances.
    }

    private fun buildDependencies(): AppDependencies {
        // Honour the test-injected per-prefs-file store when set
        // (UI tests get a fresh EncryptedSharedPreferences file per
        // test); fall back to the production default-name store
        // otherwise.
        val identityStore = if (UITestRegistry.enabled) {
            UITestRegistry.identitySecretStore ?: IdentitySecretStore(applicationContext)
        } else {
            IdentitySecretStore(applicationContext)
        }
        val identityRepository = IdentityRepository(
            store = identityStore,
        )
        // Eager bootstrap (PR-28 follow-up). Without this, the first
        // Create Group attempt on a fresh install fails with
        // `MissingIdentity` because the identity is loaded lazily by
        // whichever flow asks for it first — typically the Backup
        // screen, but the user can hit Create Group before opening
        // Backup. Idempotent: a second `bootstrap()` is a no-op.
        // Failure is silent — the next operation that needs identity
        // surfaces a clean error (`IdentityNotLoaded`).
        applicationScope.launch {
            runCatching { identityRepository.bootstrap() }
        }
        val nostrSignerProvider = OnymNostrSignerProvider()
        val clipboard = AndroidClipboardWriter(applicationContext)
        val strings = AndroidStringProvider(applicationContext)

        // Single OkHttpClient with the relayer Bearer auth
        // interceptor installed once. The relayer's `validate_auth`
        // requires the header on every contract-call POST; the
        // interceptor adds it transparently so callers (relayer
        // fetcher, contracts manifest fetcher, SEP contract client)
        // don't have to know about the token.
        //
        // `takeIf { isNotBlank() }` keeps the dev experience honest:
        // if `local.properties` is missing the token, no header is
        // added (relayer 401s with a clear message) instead of
        // `Authorization: Bearer ""` (also 401, but more confusing).
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor(
                token = BuildConfig.RELAYER_AUTH_TOKEN.takeIf { it.isNotBlank() },
            ))
            .build()

        // Relayer wiring (PR #17). Bootstrap loads cached + selection
        // from disk; start() fires the network fetch. Both run on the
        // application scope so launch never blocks on the network.
        // UI-test mode (set by `OnymTestRunner` from androidTest/)
        // swaps in in-memory fakes seeded by the page-object harness;
        // production reads from DataStore + OkHttp.
        val relayerStore = if (UITestRegistry.enabled) {
            UITestRegistry.relayerStore
                ?: error("UITestRegistry.enabled but relayerStore not set")
        } else {
            DataStorePreferencesRelayerSelectionStore(
                dataStore = applicationContext.relayerDataStore,
            )
        }
        val relayerFetcher = if (UITestRegistry.enabled) {
            UITestRegistry.relayerFetcher
                ?: error("UITestRegistry.enabled but relayerFetcher not set")
        } else {
            GitHubReleasesKnownRelayersFetcher(httpClient = httpClient)
        }
        val relayerRepository = RelayerRepository(
            store = relayerStore,
            fetcher = relayerFetcher,
            errorMessageResolver = relayerFetchErrorMessageResolver(applicationContext.resources),
        )
        applicationScope.launch {
            relayerRepository.bootstrap()
            relayerRepository.start()
        }

        // Contracts/anchors wiring — same shape as the relayer block.
        // Separate DataStore file so the two domains' storage layers
        // are independent (one less coupling at audit time).
        val contractsStore = if (UITestRegistry.enabled) {
            UITestRegistry.contractsStore
                ?: error("UITestRegistry.enabled but contractsStore not set")
        } else {
            DataStorePreferencesAnchorSelectionStore(
                dataStore = applicationContext.contractsDataStore,
            )
        }
        val contractsFetcher = if (UITestRegistry.enabled) {
            UITestRegistry.contractsFetcher
                ?: error("UITestRegistry.enabled but contractsFetcher not set")
        } else {
            GitHubReleasesContractsManifestFetcher(httpClient = httpClient)
        }
        val contractsRepository = ContractsRepository(
            store = contractsStore,
            fetcher = contractsFetcher,
        )
        applicationScope.launch {
            contractsRepository.bootstrap()
            contractsRepository.start()
        }

        // Group repository (PR-C). Falls back to an in-memory Room
        // build if the on-disk store can't open — non-fatal for the
        // create-group flow, just means newly-created groups don't
        // survive a relaunch.
        val groupDatabase = try {
            Room.databaseBuilder(
                applicationContext,
                GroupDatabase::class.java,
                "chat.onym.android.groups",
            )
                // PR-C follow-up bumped the schema from v1 → v2
                // (groupTypeRaw Int → String). PR-C only just shipped,
                // so any pre-followup install is a dev install with
                // no production data to preserve — drop the table on
                // the version mismatch instead of authoring a one-shot
                // CASE migration.
                //
                // PR 75 (member-profiles) added a non-destructive v3→v4
                // migration that introduces the nullable
                // `encryptedMemberProfilesJson` column.
                .addMigrations(
                    GroupDatabaseMigrations.MIGRATION_3_4,
                    GroupDatabaseMigrations.MIGRATION_4_5,
                )
                .fallbackToDestructiveMigration()
                .build()
        } catch (e: Throwable) {
            Room.inMemoryDatabaseBuilder(applicationContext, GroupDatabase::class.java).build()
        }
        val storageEncryption = StorageEncryption.fromContext(applicationContext)
        val groupRepository = GroupRepository(
            store = RoomGroupStore(dao = groupDatabase.groupDao(), encryption = storageEncryption),
            identity = identityRepository,
            scope = applicationScope,
        )
        // `start()` wires the per-identity-selection collector that
        // recomputes `snapshots` on every active-id change. `reload()`
        // is no longer strictly necessary (the collector emits an
        // initial value once `currentIdentityId` settles), but keep it
        // for symmetry with the previous boot path.
        groupRepository.start()
        applicationScope.launch { groupRepository.reload() }

        // PR 87: Nostr relays configuration + first-launch seed. The
        // inbox transport reads endpoints once at boot — no live
        // re-connect on Settings changes (a banner explains this).
        // Without this seed the user sees "send: not connected" on
        // every send attempt, since nothing else wires endpoints
        // into NostrInboxTransport at startup.
        val nostrRelaysRepository = chat.onym.android.transport.nostr.NostrRelaysRepository(
            store = chat.onym.android.transport.nostr.DataStoreNostrRelaysSelectionStore(
                dataStore = applicationContext.nostrRelaysDataStore,
            ),
        )
        applicationScope.launch { nostrRelaysRepository.bootstrap() }

        // Inbox transport for invitation send. Constructed once;
        // CreateGroupInteractor.create blocks on `send` (which
        // connects on first use via `NostrInboxTransport`).
        val inboxTransport = NostrInboxTransport(signerProvider = nostrSignerProvider)
        applicationScope.launch {
            // Bootstrap above writes _snapshots; this read happens on
            // the same scope so it observes the bootstrap result.
            nostrRelaysRepository.bootstrap()
            val endpoints = nostrRelaysRepository.currentEndpoints()
            if (endpoints.isNotEmpty()) {
                runCatching {
                    inboxTransport.connect(
                        endpoints.map { endpoint ->
                            chat.onym.android.transport.TransportEndpoint(
                                java.net.URI(endpoint.url),
                            )
                        },
                    )
                }
            }
        }

        // Multi-identity inbox fan-out (PR-4) + per-identity
        // decryption routing (PR-6). Subscribes to every identity's
        // inbox tag concurrently so messages targeted at a
        // non-active identity still land on disk; each persisted
        // record carries its `ownerIdentityId` so the decrypt path
        // routes to the right X25519 private key when the user
        // surfaces the envelope. Resubscribes wholesale when the
        // identities list changes (add / remove).
        val invitationsRepository = chat.onym.android.inbox.IncomingInvitationsRepository(
            store = chat.onym.android.persistence.InMemoryInvitationStore(),
            identity = identityRepository,
            scope = applicationScope,
        )
        invitationsRepository.start()
        // PR 80: receive-side dispatcher routes MemberAnnouncementPayload
        // straight into local group state instead of queueing it as a
        // raw invitation. Falls through to the legacy queue for anything
        // else.
        val incomingDispatcher = chat.onym.android.inbox.IncomingMessageDispatcher(
            envelopeDecrypter = identityRepository,
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
            identitiesFlow = identityRepository.identities,
        )
        val invitationsInteractor = chat.onym.android.inbox.IncomingInvitationsInteractor(
            inboxTransport = inboxTransport,
            repository = invitationsRepository,
            dispatcher = incomingDispatcher,
        )
        applicationScope.launch {
            invitationsInteractor.runFanout(
                identityRepository.identities.map { summaries ->
                    summaries.map { summary ->
                        summary.id to chat.onym.android.transport.TransportInboxId(
                            chat.onym.android.identity.IdentityRepository.inboxTag(summary.inboxPublicKey),
                        )
                    }
                },
            )
        }

        // Intro inbox fan-out (deeplink-invite PR-3). Sender
        // subscribes to every per-invite intro pubkey's tag so
        // joiners' "request to join" envelopes land in the
        // IntroRequestStore. Filtered to the currently-active
        // identity — switching identity drops the prior set,
        // re-subscribes for the new one.
        val introKeyStore: chat.onym.android.group.IntroKeyStore =
            chat.onym.android.group.EncryptedPrefsIntroKeyStore(applicationContext)
        val introRequestStore: chat.onym.android.group.IntroRequestStore =
            chat.onym.android.group.InMemoryIntroRequestStore()
        val introInboxPump = chat.onym.android.group.IntroInboxPump(
            transport = inboxTransport,
            store = introRequestStore,
            inboxTagFor = { introPub ->
                chat.onym.android.transport.TransportInboxId(
                    chat.onym.android.identity.IdentityRepository.inboxTag(introPub),
                )
            },
        )
        // Cascade-delete intro keys when an identity is removed so
        // we don't keep listening on tags whose owner is gone. The
        // removal-listener slot is multi-listener (PR-3 of the
        // deeplink stack flipped it from single → list).
        // GroupRepository's chat-wipe registered first (during its
        // `init`); this hook appends. Order: chats wipe first, then
        // intro keys.
        identityRepository.registerRemovalListener { id ->
            introKeyStore.deleteForOwner(id)
        }
        applicationScope.launch {
            introInboxPump.runFanout(
                kotlinx.coroutines.flow.combine(
                    introKeyStore.entriesFlow,
                    identityRepository.currentIdentityId,
                ) { entries, activeId ->
                    if (activeId == null) emptyList()
                    else entries.filter { it.ownerIdentityId == activeId }
                },
            )
        }

        // Joiner-side ship affordance for the deeplink-tap flow
        // (PR-7). Single shared instance — JoinViewModel-per-tap
        // captures the IntroCapability, the sender stays stateless.
        val joinRequestSender = chat.onym.android.group.JoinRequestSender(
            identity = identityRepository,
            inboxTransport = inboxTransport,
        )

        // Approver-side: turn raw IntroRequests into UI-renderable
        // pending requests + ship sealed GroupInvitationPayloads on
        // user approval. Single instance — the toolbar badge + the
        // modal screen share state via [ApproveRequestsViewModel].
        val joinRequestApprover = chat.onym.android.group.JoinRequestApprover(
            identity = identityRepository,
            introKeyStore = introKeyStore,
            introRequestStore = introRequestStore,
            groupRepository = groupRepository,
            inboxTransport = inboxTransport,
            scope = applicationScope,
        )
        val approveRequestsViewModel = chat.onym.android.group.ApproveRequestsViewModel(
            approver = joinRequestApprover,
        )
        // Kick the collector at app start so requests landing while
        // the chats screen isn't open still drive the badge count.
        approveRequestsViewModel.start()

        // App-wide network preference (PR-C follow-up). Defaults to
        // testnet; the Settings → Network → "Use Mainnet" Switch
        // flips it. CreateGroupInteractor reads `current()` per call
        // for both the contract-binding lookup and the wire payload's
        // top-level `network` field.
        val networkPreference = DataStoreNetworkPreferenceProvider(
            dataStore = applicationContext.networkPreferenceDataStore,
        )

        return AppDependencies(
            nostrSignerProvider = nostrSignerProvider,
            makeRecoveryPhraseBackupViewModel = { activityProvider ->
                RecoveryPhraseBackupViewModel(
                    repository = identityRepository,
                    authenticator = AndroidBiometricAuthenticator(
                        activityProvider = activityProvider,
                    ),
                    clipboard = clipboard,
                    strings = strings,
                )
            },
            makeRelayerSettingsViewModel = {
                RelayerSettingsViewModel(repository = relayerRepository)
            },
            makeAnchorsPickerViewModel = {
                AnchorsPickerViewModel(repository = contractsRepository)
            },
            networkPreferenceProvider = networkPreference,
            makeCreateGroupViewModel = {
                val interactor = CreateGroupInteractor(
                    identity = identityRepository,
                    relayers = relayerRepository,
                    contracts = contractsRepository,
                    groups = groupRepository,
                    networkPreference = networkPreference,
                    // Use the shared httpClient (with the
                    // BearerAuthInterceptor installed) so the
                    // create-group call carries the token. Without
                    // this override the interactor's default builds a
                    // fresh OkHttpClient() per call with no auth
                    // wired in — relayer 401s every request.
                    makeContractTransport = { url ->
                        OkHttpSepContractTransport(
                            httpClient = httpClient,
                            endpointUrl = url,
                        )
                    },
                    inboxTransport = inboxTransport,
                )
                CreateGroupViewModel(
                    createGroup = { name, invitees, groupType, onProgress ->
                        interactor.create(
                            name = name,
                            invitees = invitees,
                            groupType = groupType,
                            onProgress = onProgress,
                        )
                    },
                )
            },
            makeChatsViewModel = {
                chat.onym.android.chats.ChatsViewModel(repository = groupRepository)
            },
            makeIdentitiesViewModel = {
                chat.onym.android.identity.IdentitiesViewModel(identity = identityRepository)
            },
            makeShareInviteViewModel = {
                chat.onym.android.group.ShareInviteViewModel(
                    identity = identityRepository,
                    introducer = chat.onym.android.group.InviteIntroducer(introKeyStore),
                    groupRepository = groupRepository,
                )
            },
            approveRequestsViewModel = approveRequestsViewModel,
            makeNostrRelaySettingsViewModel = {
                chat.onym.android.settings.NostrRelaySettingsViewModel(
                    repository = nostrRelaysRepository,
                )
            },
            nostrRelaysFlow = nostrRelaysRepository.snapshots,
            makeJoinViewModel = { capability ->
                // Suggest the active identity's display name as the
                // initial label. Falls back to a generic "Anonymous"
                // if no identity is selected (the VM will error out
                // on send anyway, but the UI shouldn't show a blank
                // field).
                val activeId = identityRepository.currentIdentityId.value
                val suggested = identityRepository.identities.value
                    .firstOrNull { it.id == activeId }
                    ?.name
                    ?: "Anonymous"
                chat.onym.android.group.JoinViewModel(
                    capability = capability,
                    submitRequest = joinRequestSender::send,
                    groupRepository = groupRepository,
                    suggestedDisplayLabel = suggested,
                )
            },
        )
    }

    /** Resolve the currently-on-top [FragmentActivity] for biometric
     *  prompts. Throws if called when no Activity is resumed — this
     *  should be unreachable in practice because the recovery flow
     *  is reached via UI navigation. */
    internal fun requireCurrentFragmentActivity(): FragmentActivity =
        resumedActivity?.get() as? FragmentActivity
            ?: error("No resumed FragmentActivity to host BiometricPrompt")
}
