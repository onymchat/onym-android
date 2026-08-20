package app.onym.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.onEach
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import app.onym.android.chain.BearerAuthInterceptor
import app.onym.android.moderation.authorityContactLine
import app.onym.android.chain.ContractsRepository
import app.onym.android.chain.DataStoreNetworkPreferenceProvider
import app.onym.android.chain.OkHttpSepContractTransport
import app.onym.android.chain.DataStorePreferencesAnchorSelectionStore
import app.onym.android.chain.DataStorePreferencesRelayerSelectionStore
import app.onym.android.chain.GitHubReleasesContractsManifestFetcher
import app.onym.android.chain.GitHubReleasesKnownRelayersFetcher
import app.onym.android.chain.RelayerRepository
import app.onym.android.chain.relayerFetchErrorMessageResolver
import app.onym.android.group.CreateGroupInteractor
import app.onym.android.group.CreateGroupViewModel
import app.onym.android.group.GroupDatabase
import app.onym.android.group.GroupDatabaseMigrations
import app.onym.android.group.GroupRepository
import app.onym.android.group.RoomGroupStore
import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.IdentitySecretStore
import app.onym.android.identity.OnymNostrSignerProvider
import app.onym.android.foundation.StorageEncryption
import app.onym.android.recovery.AndroidBiometricAuthenticator
import app.onym.android.recovery.AndroidClipboardWriter
import app.onym.android.recovery.AndroidStringProvider
import app.onym.android.recovery.RecoveryPhraseBackupViewModel
import app.onym.android.settings.AnchorsPickerViewModel
import app.onym.android.settings.RelayerSettingsViewModel
import app.onym.android.transport.nostr.NostrInboxTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    name = "app.onym.android.relayer_prefs",
)

/**
 * DataStore Preferences for the anchor (contract version)
 * selections + cached `contracts-manifest.json`. Separate file from
 * [relayerDataStore] so each domain's storage layer has its own
 * blob — easier to reason about + selectively wipe in tests.
 */
private val Context.contractsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app.onym.android.contracts_prefs",
)

/**
 * DataStore Preferences for app-wide network selection
 * (`onym.useMainnet` boolean — same key iOS uses via
 * `@AppStorage("onym.useMainnet")`). Separate file so the Settings
 * toggle doesn't touch the contracts cache or the relayer list.
 */
private val Context.networkPreferenceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app.onym.android.network_prefs",
)

/**
 * DataStore Preferences for Nostr relay configuration. Separate
 * from [relayerDataStore] (which holds chain-relayer URLs) — different
 * domain, different lifecycle.
 */
private val Context.nostrRelaysDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app.onym.android.nostr_relays_prefs",
)

/**
 * DataStore Preferences for Blossom media-server configuration.
 * Separate domain from Nostr relays — media blob storage vs. Nostr
 * inbox transport.
 */
private val Context.blossomServersDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app.onym.android.blossom_servers_prefs",
)

/**
 * DataStore Preferences for chat settings (the symmetric read-receipt
 * toggle). Separate file so it doesn't touch the network / relay
 * domains.
 */
private val Context.chatPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app.onym.android.chat_prefs",
)

/**
 * DataStore Preferences for the moderation seat: the consented
 * mandate records (mandate + exact manifest bytes — consent records,
 * not secrets) and the persisted last gate result. Separate file per
 * the one-domain-one-blob convention.
 */
private val Context.moderationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app.onym.android.moderation_prefs",
)

/**
 * DataStore Preferences for the Discovery source configuration
 * (pinned provider keys, accepted snapshot chain anchors) + the last
 * verified catalog-entry list. Keys and digests aren't secret
 * material — same reasoning as [relayerDataStore]. Separate file per
 * the one-domain-one-blob convention.
 */
private val Context.discoveryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app.onym.android.discovery_prefs",
)

/**
 * DataStore Preferences for pinned consent records (exact reviewed
 * manifest bytes + acceptance metadata). A new file — not shared with
 * the relayer / discovery stores — so the consent history can be
 * reasoned about (and selectively wiped in tests) on its own.
 */
private val Context.consentDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app.onym.android.consent_prefs",
)

/**
 * DataStore Preferences for the device-backup seat's state
 * (`BackupStateStore`): accepted terms digest, pending operations,
 * pending-payment records. Its own file per the one-domain-one-blob
 * convention — none of this is secret (the sealed snapshot bytes it
 * points at live under the app's files directory, not here).
 */
private val Context.backupDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app.onym.android.backup_prefs",
)

/**
 * DataStore Preferences for the onboarding completion flag
 * (`onboarding.completed`). Its own file per the one-domain-one-blob
 * convention — the test reset path can wipe onboarding without
 * touching any transport configuration. A corrupt file is replaced
 * with empty prefs (flag reads false → the gate falls back through
 * the grandfathering probe) instead of throwing IOException into the
 * gate block.
 */
private val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app.onym.android.onboarding_prefs",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
        androidx.datastore.preferences.core.emptyPreferences()
    },
)

/**
 * DataStore Preferences for intro-request tombstones. Event ids aren't
 * secret, and the set is bounded by the links that still exist.
 */
private val Context.introRequestsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app.onym.android.intro_requests",
)

/**
 * Composition root. Two responsibilities:
 *
 *  - Register the BouncyCastle JCE provider once at process start
 *    (we use BC for Curve25519 raw-key APIs the JDK doesn't expose
 *    — see [app.onym.android.identity.IdentityRepository]). BC
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
        // debugActive, not enabled: swapping the encrypted secret
        // store is a security-weakening seam (see UITestRegistry).
        val identityStore = if (UITestRegistry.debugActive) {
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

        // ONY-001: the relayer bearer token must NEVER leave the relayer
        // origin. This `httpClient` is the *unauthenticated* client — it
        // carries NO `Authorization` header — and is the one shared with
        // every cross-origin consumer: the GitHub release fetchers, the
        // Nostr relay fetcher, and (crucially) the user-configurable
        // Blossom media client. Attaching the relayer credential to any
        // of those would leak it to hosts the user, or an attacker who
        // can configure a Blossom server, controls.
        //
        // The relayer credential is attached only by the per-transport
        // client built in `contractTransportFactory` below, host-scoped
        // to the relayer endpoint.
        val httpClient = OkHttpClient.Builder().build()

        // `takeIf { isNotBlank() }` keeps the dev experience honest:
        // if `local.properties` is missing the token, no header is
        // added (relayer 401s with a clear message) instead of
        // `Authorization: Bearer ""` (also 401, but more confusing).
        val relayerAuthToken = BuildConfig.RELAYER_AUTH_TOKEN.takeIf { it.isNotBlank() }

        // Discovery seat (PR A3). The repository owns the user's
        // provider sources + the verified catalog aggregate; the seat
        // adapters below wrap the three legacy known-list fetchers
        // with discovery-backed ones that fall back to legacy on any
        // failure — discovery can only ever add to what the app can
        // do today, never take away. Under the UI harness the seams
        // come from UITestRegistry; when a slot is null, no
        // repository is constructed at all (same null-means-no-network
        // contract as nostrRelaysFetcher), so existing UI tests stay
        // offline and the legacy fetchers run unwrapped.
        val discoveryFetcher: app.onym.android.discovery.DiscoveryFetching? =
            if (UITestRegistry.enabled) UITestRegistry.discoveryFetcher
            else app.onym.android.discovery.OkHttpDiscoveryFetcher(httpClient = httpClient)
        val discoveryStore: app.onym.android.discovery.DiscoveryStore? =
            if (UITestRegistry.enabled) UITestRegistry.discoveryStore
            else app.onym.android.discovery.DataStorePreferencesDiscoveryStore(
                dataStore = applicationContext.discoveryDataStore,
            )
        // Fixture-era clock (PR 4): instrumented tests inject a fixed
        // instant so the signed discovery fixtures' expiries can't
        // rot the suites; production always runs the real clock.
        // Captured into a local before the null-check: the registry
        // slots are mutable vars, so a re-read after the check would
        // race a concurrent test-runner write (same capture style as
        // the other slots).
        // debugActive, not enabled: a pinned clock weakens the
        // discovery trust layer's signature-expiry checks (see
        // UITestRegistry).
        val registryDiscoveryClock =
            if (UITestRegistry.debugActive) UITestRegistry.discoveryClock else null
        val discoveryClock: () -> java.time.Instant =
            registryDiscoveryClock ?: java.time.Instant::now
        val discoveryRepository: app.onym.android.discovery.DiscoveryRepository? =
            if (discoveryFetcher != null && discoveryStore != null) {
                app.onym.android.discovery.DiscoveryRepository(
                    store = discoveryStore,
                    fetcher = discoveryFetcher,
                    now = discoveryClock,
                    // The Onym-operated default source, UNPINNED —
                    // refresh skips it until the user confirms its
                    // operator-key fingerprint (onboarding's
                    // discovery-confirm step, or Settings →
                    // Discovery). Not seeded under the UI harness so
                    // the discovery UI tests keep their exact source
                    // fixtures.
                    defaultSources = if (UITestRegistry.enabled) {
                        emptyList()
                    } else {
                        listOf(app.onym.android.discovery.DiscoverySource.onymDefault)
                    },
                )
            } else {
                null
            }
        if (discoveryRepository != null) {
            // Same triad launch as the relayer / contracts blocks:
            // hydrate the persisted source set + cached entries from
            // disk, then fire the first refresh in the background.
            applicationScope.launch {
                discoveryRepository.bootstrap()
                discoveryRepository.start()
            }
        }
        // Shared seam the seat adapters fetch catalog entries +
        // destination manifests through. Null exactly when the
        // repository is null — `wrapping(...)` then returns the
        // legacy fetcher unwrapped.
        val discoveryCatalog: DiscoverySeatCatalog? =
            if (discoveryRepository != null && discoveryFetcher != null) {
                DiscoverySeatCatalog(
                    repository = discoveryRepository,
                    fetcher = discoveryFetcher,
                )
            } else {
                null
            }
        // Pinned consent records (module-consent primitives). The
        // composition root owns the consent DataStore file; the
        // DISCOVERY settings surface below consumes it.
        val pinnedConsentStore: app.onym.android.foundation.PinnedConsentStore =
            app.onym.android.foundation.DataStorePreferencesPinnedConsentStore(
                dataStore = applicationContext.consentDataStore,
            )
        // Append-only module-selection history (active = last per
        // seat). Lives in the discovery DataStore file under its own
        // `discovery.`-prefixed key.
        val seatSelectionStore: app.onym.android.discovery.SeatSelectionStore =
            app.onym.android.discovery.DataStorePreferencesSeatSelectionStore(
                dataStore = applicationContext.discoveryDataStore,
            )

        // Onboarding gate state (PR 3). The completion flag lives in
        // its own DataStore file; the pending decision resolves in the
        // gate block below (after the transport stores it probes are
        // constructed) and RootScreen collects it.
        // Registry override (PR 4): a set slot makes the walk
        // drivable from instrumented tests; unset keeps the explicit
        // harness bypass (see the gate block below).
        // Captured into a local (not re-read through `!!`) so the
        // store choice and the driven-by-harness bit below can't
        // disagree if a test-runner write races this block.
        val registryOnboardingStore =
            if (UITestRegistry.enabled) UITestRegistry.onboardingStore else null
        val onboardingStore: app.onym.android.onboarding.OnboardingStore =
            registryOnboardingStore
                ?: app.onym.android.onboarding.DataStorePreferencesOnboardingStore(
                    dataStore = applicationContext.onboardingDataStore,
                )
        // Whether the harness deliberately drives the gate. Captured
        // once so the gate block and its probe agree on the branch.
        val onboardingDrivenByHarness = registryOnboardingStore != null
        val onboardingPending =
            kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
        // Relayer auto-populate policy (#211 seam): suppressed until
        // the gate resolves to "no onboarding" or the walk completes,
        // so a first-launch fetch can't install the whole published
        // list before the notary step offers the choice. Deferred, not
        // cancelled — the fetched list still lands in knownRelayers.
        val relayerAutoPopulateAllowed = java.util.concurrent.atomic.AtomicBoolean(false)

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
        val legacyRelayerFetcher = if (UITestRegistry.enabled) {
            UITestRegistry.relayerFetcher
                ?: error("UITestRegistry.enabled but relayerFetcher not set")
        } else {
            GitHubReleasesKnownRelayersFetcher(httpClient = httpClient)
        }
        // Discovery-backed adapter over the legacy fetcher (identity
        // when there's no discovery catalog — UI-test harness).
        val relayerFetcher = DiscoveryBackedKnownRelayersFetcher.wrapping(
            fallback = legacyRelayerFetcher,
            catalog = discoveryCatalog,
        )
        val relayerRepository = RelayerRepository(
            store = relayerStore,
            fetcher = relayerFetcher,
            errorMessageResolver = relayerFetchErrorMessageResolver(applicationContext.resources),
            autoPopulatePolicy = { relayerAutoPopulateAllowed.get() },
        )
        // bootstrap() + start() run in the onboarding-gate block below:
        // the grandfathering probe must read the persisted relayer
        // configuration BEFORE the first fetch (auto-populate flips its
        // hasUserInteracted bit, which would misread a fresh user as
        // existing), so the two are sequenced in one launch.

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
                "app.onym.android.groups",
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
                    GroupDatabaseMigrations.MIGRATION_5_6,
                    GroupDatabaseMigrations.MIGRATION_6_7,
                    GroupDatabaseMigrations.MIGRATION_7_8,
                    GroupDatabaseMigrations.MIGRATION_8_9,
                )
                .fallbackToDestructiveMigration()
                .build()
        } catch (e: Throwable) {
            Room.inMemoryDatabaseBuilder(applicationContext, GroupDatabase::class.java).build()
        }
        val storageEncryption = StorageEncryption.fromContext(applicationContext)
        // Named (rather than inlined into GroupRepository below) so the
        // device-backup composition root can read/write the same store
        // — see `backupUi` further down.
        val groupStore = RoomGroupStore(dao = groupDatabase.groupDao(), encryption = storageEncryption)
        val groupRepository = GroupRepository(
            store = groupStore,
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

        // Message persistence (PR A2 + A4). Separate @Database from
        // GroupDatabase so a schema bump in either domain doesn't
        // force a wipe of the other — exact mirror of the
        // GroupDatabase comment's prediction. Same in-memory fallback
        // posture as the groups DB: a corrupt on-disk file degrades
        // to ephemeral storage rather than refusing to launch.
        val messageDatabase = try {
            androidx.room.Room.databaseBuilder(
                applicationContext,
                app.onym.android.chats.MessageDatabase::class.java,
                "app.onym.android.messages",
            )
                .addMigrations(
                    app.onym.android.chats.MessageDatabaseMigrations.MIGRATION_1_2,
                    app.onym.android.chats.MessageDatabaseMigrations.MIGRATION_2_3,
                    app.onym.android.chats.MessageDatabaseMigrations.MIGRATION_3_4,
                    app.onym.android.chats.MessageDatabaseMigrations.MIGRATION_4_5,
                    app.onym.android.chats.MessageDatabaseMigrations.MIGRATION_5_6,
                    app.onym.android.chats.MessageDatabaseMigrations.MIGRATION_6_7,
                    app.onym.android.chats.MessageDatabaseMigrations.MIGRATION_7_8,
                    app.onym.android.chats.MessageDatabaseMigrations.MIGRATION_8_9,
                )
                .fallbackToDestructiveMigration()
                .build()
        } catch (_: Throwable) {
            androidx.room.Room.inMemoryDatabaseBuilder(
                applicationContext,
                app.onym.android.chats.MessageDatabase::class.java,
            ).build()
        }
        // Named for the same reason as `groupStore` above — the
        // device-backup composition root reads/writes this store too.
        val messageStore = app.onym.android.chats.RoomMessageStore(
            dao = messageDatabase.messageDao(),
            encryption = storageEncryption,
        )
        val messageRepository = app.onym.android.chats.MessageRepository(
            store = messageStore,
            identity = identityRepository,
            scope = applicationScope,
        )
        messageRepository.start()

        // App-wide network preference (PR-C follow-up). Constructed
        // here (early) so PR-89's chainStateReader can read it.
        val readReceiptsPreference = app.onym.android.chats.DataStoreReadReceiptsPreferenceProvider(
            dataStore = applicationContext.chatPreferencesDataStore,
        )
        val networkPreference = DataStoreNetworkPreferenceProvider(
            dataStore = applicationContext.networkPreferenceDataStore,
        )

        // PR 87: Nostr relays configuration + first-launch seed. The
        // inbox transport reads endpoints once at boot — no live
        // re-connect on Settings changes (a banner explains this).
        // Without this seed the user sees "send: not connected" on
        // every send attempt, since nothing else wires endpoints
        // into NostrInboxTransport at startup.
        // Fetcher pulls the Onym-published default list from GitHub. Under
        // the UI harness it's whatever the test set (null → no network, so
        // the hardcoded seed stays the default).
        val legacyNostrRelaysFetcher: app.onym.android.transport.nostr.KnownNostrRelaysFetcher? =
            if (UITestRegistry.enabled) UITestRegistry.nostrRelaysFetcher
            else app.onym.android.transport.nostr.GitHubReleasesKnownNostrRelaysFetcher(httpClient = httpClient)
        // Discovery-backed adapter over the legacy fetcher (identity
        // when there's no discovery catalog — UI-test harness).
        val nostrRelaysFetcher = DiscoveryBackedKnownNostrRelaysFetcher.wrapping(
            fallback = legacyNostrRelaysFetcher,
            catalog = discoveryCatalog,
        )
        // Held as a local (not inlined) so the onboarding gate's
        // grandfathering probe below can read the persisted config.
        val nostrRelaysStore = app.onym.android.transport.nostr.DataStoreNostrRelaysSelectionStore(
            dataStore = applicationContext.nostrRelaysDataStore,
        )
        val nostrRelaysRepository = app.onym.android.transport.nostr.NostrRelaysRepository(
            store = nostrRelaysStore,
            fetcher = nostrRelaysFetcher,
        )
        // Disk hydrate (seed on first launch) then refresh from GitHub in
        // the background; changes apply on the next launch.
        applicationScope.launch {
            nostrRelaysRepository.bootstrap()
            nostrRelaysRepository.start()
        }

        // User-configurable Blossom media servers (Settings → Transport →
        // Blossom Relays). Bootstrapped synchronously here (a single local
        // DataStore read) so the very first upload/download already sees
        // the persisted configuration. The blob client resolves its base
        // URL per operation from this repository, so later changes apply
        // to the next upload/download — no relaunch needed.
        val legacyBlossomServersFetcher: app.onym.android.transport.blossom.KnownBlossomServersFetcher? =
            if (UITestRegistry.enabled) UITestRegistry.blossomServersFetcher
            else app.onym.android.transport.blossom.GitHubReleasesKnownBlossomServersFetcher(httpClient = httpClient)
        // Discovery-backed adapter over the legacy fetcher (identity
        // when there's no discovery catalog — UI-test harness).
        val blossomServersFetcher = DiscoveryBackedKnownBlossomServersFetcher.wrapping(
            fallback = legacyBlossomServersFetcher,
            catalog = discoveryCatalog,
        )
        val blossomServersRepository = app.onym.android.transport.blossom.BlossomServersRepository(
            store = app.onym.android.transport.blossom.DataStoreBlossomServersSelectionStore(
                dataStore = applicationContext.blossomServersDataStore,
            ),
            fetcher = blossomServersFetcher,
        )
        kotlinx.coroutines.runBlocking { blossomServersRepository.bootstrap() }
        // Background-refresh the published default for the next launch.
        applicationScope.launch { blossomServersRepository.start() }

        // Onboarding gate resolution (PR 3). One launch, strictly
        // ordered: hydrate the relayer config from disk, resolve the
        // completed-flag + grandfathering decision, arm (or keep
        // suppressed) the relayer auto-populate policy, and only THEN
        // fire the relayer's first fetch — auto-populate flips the
        // relayer's hasUserInteracted bit, so letting the fetch race
        // the probe would misread a fresh user as existing. Under the
        // UI-test harness the gate resolves to false immediately
        // (existing instrumented tests never see the walk; PR 4 adds
        // registry slots to drive it deliberately) and the policy
        // arms, preserving current behavior exactly.
        applicationScope.launch {
            runCatching { relayerRepository.bootstrap() }
            // Fail-open resolution (resolvePending): any storage error
            // — corrupt prefs, a throwing probe — resolves to "no
            // onboarding" rather than crashing the launch or leaving
            // the decision null (which RootScreen renders as a
            // permanent blank frame). Worst case a fresh user skips
            // the walk; every surface still works via defaults.
            // Harness contract (PR 4), explicit rather than
            // incidental: with NO onboarding slot registered the gate
            // resolves in UI-test mode — never onboard, the bypass
            // every pre-onboarding instrumented test relies on. With
            // the slot registered, the gate reads the fake store's
            // flag and the grandfathering probe is pinned to "fresh
            // user" so device-persisted DataStore state from earlier
            // runs can't flip the decision (the probe's truth table
            // is unit-tested; the walk test drives the flag).
            val pending = OnboardingGateProbe.resolvePending(
                uiTestMode = UITestRegistry.enabled && !onboardingDrivenByHarness,
                store = onboardingStore,
                isExistingUser = {
                    if (onboardingDrivenByHarness) {
                        false
                    } else {
                        OnboardingGateProbe.isExistingUser(
                            relayer = relayerRepository.snapshots.value.configuration,
                            nostr = nostrRelaysStore.load(),
                            blossom = blossomServersRepository.snapshots.value,
                            // Raw stored names — the fresh-install
                            // auto-bootstrap identity is single and
                            // blank-named, so it doesn't count.
                            identityInteracted = runCatching {
                                identityRepository.hasNamedOrMultipleIdentities()
                            }.getOrDefault(false),
                        )
                    }
                },
            )
            try {
                relayerAutoPopulateAllowed.set(!pending)
                onboardingPending.value = pending
            } finally {
                // The relayer fetch must fire regardless of how the
                // gate resolved — a launch where relayers never fetch
                // is a strictly worse failure than a skipped walk.
                relayerRepository.start()
            }
        }

        // Discovery settings UI (PR A4). One nullable bundle: when
        // the repository is absent (UI-test harness without discovery
        // fakes) `discoveryUi` stays null and every surface renders
        // exactly as before discovery existed.
        val discoveryUi: DiscoveryUiDependencies? =
            if (discoveryRepository != null && discoveryFetcher != null) {
                // Accepting a consented manifest writes its endpoint
                // into the seat's *legacy* configuration — that stays
                // the operational source of truth; the selection
                // record is provenance, not routing.
                val applySeatEndpoint: suspend (app.onym.android.foundation.SignedServiceManifest) -> Unit =
                    { manifest ->
                        val fields = SeatManifestFields(manifest.rawBytes)
                        val name = fields.name?.takeIf { it.isNotEmpty() }
                            ?: manifest.componentId.removePrefix("onym:component:")
                        when (manifest.seat) {
                            DiscoveryBackedKnownRelayersFetcher.SEAT_TYPE ->
                                fields.firstEndpointUri(setOf("https"))?.let { url ->
                                    relayerRepository.addEndpoint(
                                        app.onym.android.chain.RelayerEndpoint(
                                            name = name,
                                            url = url,
                                            networks = fields.networks
                                                ?: DiscoveryBackedKnownRelayersFetcher.DEFAULT_NETWORKS,
                                        ),
                                    )
                                }
                            DiscoveryBackedKnownNostrRelaysFetcher.SEAT_TYPE ->
                                fields.firstEndpointUri(setOf("wss"))?.let { url ->
                                    nostrRelaysRepository.addEndpoint(
                                        app.onym.android.transport.nostr.NostrRelayEndpoint(
                                            url = url,
                                            name = name,
                                            isDefault = false,
                                        ),
                                    )
                                }
                            DiscoveryBackedKnownBlossomServersFetcher.SEAT_TYPE ->
                                fields.firstEndpointUri(setOf("https"))?.let { url ->
                                    // makeActive: uploads/downloads target
                                    // the FIRST configured server, so a
                                    // plain append behind the seeded
                                    // default would leave the consented
                                    // pick silently inert.
                                    blossomServersRepository.addEndpoint(
                                        app.onym.android.transport.blossom.BlossomServerEndpoint(
                                            url = url,
                                            name = name,
                                            isDefault = false,
                                        ),
                                        makeActive = true,
                                    )
                                }
                            // No moderation branch: the moderation
                            // seat has no running code on Android and
                            // its entries are hidden from every
                            // picker.
                        }
                    }
                DiscoveryUiDependencies(
                    stateFlow = discoveryRepository.snapshots,
                    makeDiscoverySettingsViewModel = { writeScope ->
                        app.onym.android.settings.DiscoverySettingsViewModel(
                            repository = discoveryRepository,
                            writeScope = writeScope,
                        )
                    },
                    makeModuleConsentViewModel = { seatType, componentShortId ->
                        val entry = discoveryRepository.snapshots.value.entries.firstOrNull {
                            it.entry.seatType == seatType &&
                                it.entry.componentId.removePrefix("onym:component:") ==
                                componentShortId
                        }
                        app.onym.android.settings.ModuleConsentViewModel(
                            entry = entry,
                            fetchManifestBytes = { uri ->
                                discoveryFetcher.fetch(
                                    uri,
                                    app.onym.android.discovery.DiscoveryProfile
                                        .MAX_DESTINATION_MANIFEST_BYTES,
                                )
                            },
                            consentStore = pinnedConsentStore,
                            selectionStore = seatSelectionStore,
                            applyToConfiguration = applySeatEndpoint,
                            now = discoveryClock,
                        )
                    },
                    activeConsent = { componentId ->
                        pinnedConsentStore.activeRecord(componentId)
                    },
                )
            } else {
                null
            }

        // Inbox transport for invitation send. Constructed once;
        // CreateGroupInteractor.create blocks on `send` (which
        // connects on first use via `NostrInboxTransport`).
        // UI-test seam: a loopback transport + in-memory chain factory
        // (set on UITestRegistry) let two on-device identities exchange
        // messages and a Tyranny group anchor + verify with no network.
        // Production always takes the Nostr / OkHttp branch.
        val inboxTransport: app.onym.android.transport.InboxTransport =
            if (UITestRegistry.enabled && UITestRegistry.inboxTransport != null) {
                UITestRegistry.inboxTransport!!
            } else {
                NostrInboxTransport(signerProvider = nostrSignerProvider)
            }
        val contractTransportFactory: (String) -> app.onym.android.chain.SepContractTransport =
            if (UITestRegistry.enabled && UITestRegistry.contractTransportFactory != null) {
                UITestRegistry.contractTransportFactory!!
            } else {
                { url ->
                    // ONY-001: this is the ONLY place the relayer bearer
                    // token is attached. Derive a client from the shared
                    // (bearer-free) `httpClient` via `newBuilder()` so we
                    // reuse its connection pool + dispatcher, then add a
                    // bearer interceptor scoped to THIS relayer endpoint's
                    // host. A cross-origin redirect therefore can't carry
                    // the token off the relayer (OkHttp also strips
                    // `Authorization` on cross-host redirects).
                    val relayerHost = url.toHttpUrlOrNull()?.host
                    val relayerClient = httpClient.newBuilder()
                        .addInterceptor(
                            BearerAuthInterceptor(
                                token = relayerAuthToken,
                                allowedHost = relayerHost,
                            ),
                        )
                        .build()
                    OkHttpSepContractTransport(httpClient = relayerClient, endpointUrl = url)
                }
            }
        // Blossom media server for image messages + the lazy loaders.
        // Swapped for an in-memory store under the UI-test harness.
        // The base URL resolves PER OPERATION to the first
        // user-configured server (falling back to the Onym default if
        // the list was explicitly cleared), so a server change in
        // Settings — or a pick made during onboarding — applies to the
        // very next upload/download, no relaunch needed.
        val resolveBlossomServerUrl: suspend () -> String = {
            blossomServersRepository.currentEndpoints().firstOrNull()?.url
                ?: DEFAULT_BLOSSOM_SERVER_URL
        }
        val blossomClient: app.onym.android.transport.blossom.BlossomClient =
            if (UITestRegistry.enabled && UITestRegistry.blossomClient != null) {
                UITestRegistry.blossomClient!!
            } else {
                app.onym.android.transport.blossom.DynamicBaseUrlBlossomClient(
                    resolveBaseUrl = resolveBlossomServerUrl,
                    makeClient = { url ->
                        app.onym.android.transport.blossom.OkHttpBlossomClient(
                            baseUrl = url,
                            httpClient = httpClient,
                            signerProvider = nostrSignerProvider,
                        )
                    },
                )
            }
        // The user's configured endpoint URLs — the ONLY servers an
        // attachment's peer-supplied `server` stamp may route a
        // download to (BlossomServerStampPolicy has the rationale).
        val allowedStampServers: suspend () -> List<String> = {
            blossomServersRepository.currentEndpoints().map { it.url }
        }
        val imageLoader = app.onym.android.chats.ChatImageLoader(
            blossomClient = blossomClient,
            cacheDir = java.io.File(applicationContext.cacheDir, "chat_images"),
            allowedStampServers = allowedStampServers,
        )
        val videoLoader = app.onym.android.chats.ChatVideoLoader(
            blossomClient = blossomClient,
            cacheDir = java.io.File(applicationContext.cacheDir, "chat_videos"),
            allowedStampServers = allowedStampServers,
        )
        val voiceLoader = app.onym.android.chats.ChatVoiceLoader(
            blossomClient = blossomClient,
            cacheDir = java.io.File(applicationContext.cacheDir, "chat_voice"),
            allowedStampServers = allowedStampServers,
        )
        // Outbox: persists sealed media blobs so a failed send can be
        // resent (survives restart) by re-uploading the exact bytes.
        val chatOutbox = app.onym.android.chats.ChatOutbox(
            dir = java.io.File(applicationContext.cacheDir, "chat_outbox"),
        )
        // Real video transcoder captures the app context. Under the
        // UI-test harness (which can't transcode a real clip) send a
        // canned encoding so the pipeline still exercises seal → upload
        // → fan-out; the poster comes from a generated test image.
        val appContext = applicationContext
        val encodeVideo: suspend (android.net.Uri) -> app.onym.android.chats.ChatVideoEncoder.Encoded? =
            if (UITestRegistry.enabled) {
                { _ ->
                    app.onym.android.chats.ChatImageEncoder.encode(debugTestImageBytes())?.let { poster ->
                        app.onym.android.chats.ChatVideoEncoder.Encoded(
                            mp4 = "uitest-video-blob".toByteArray(),
                            width = poster.width,
                            height = poster.height,
                            durationSeconds = 3.0,
                            poster = poster,
                        )
                    }
                }
            } else {
                { uri -> app.onym.android.chats.ChatVideoEncoder.encode(appContext, uri) }
            }
        applicationScope.launch {
            // Bootstrap above writes _snapshots; this read happens on
            // the same scope so it observes the bootstrap result.
            nostrRelaysRepository.bootstrap()
            val endpoints = nostrRelaysRepository.currentEndpoints()
            if (endpoints.isNotEmpty()) {
                runCatching {
                    inboxTransport.connect(
                        endpoints.map { endpoint ->
                            app.onym.android.transport.TransportEndpoint(
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
        // Named for the same reason as `groupStore`/`messageStore`
        // above — the device-backup composition root reads/writes
        // this store too. (In-memory today, same as before this
        // extraction — invitations don't yet survive process death
        // independent of this feature.)
        val invitationStore = app.onym.android.persistence.InMemoryInvitationStore()
        val invitationsRepository = app.onym.android.inbox.IncomingInvitationsRepository(
            store = invitationStore,
            identity = identityRepository,
            scope = applicationScope,
        )
        invitationsRepository.start()
        // PR 80: receive-side dispatcher routes MemberAnnouncementPayload
        // straight into local group state instead of queueing it as a
        // raw invitation. Falls through to the legacy queue for anything
        // else.
        // PR 89: live chain-state reader for the Tyranny verifier
        // branches. Reuses the same OkHttp client (with
        // BearerAuthInterceptor) so the relayer's auth token rides
        // along.
        // Cache + retry around the raw relayer reads. Without this, every
        // relay reconnect replays the full inbox and each replayed
        // group-state message fired its own `get_commitment`, storming the
        // relayer into throttling fresh joins. The raw reader stays
        // cache-free; the decorator is the single place trading a little
        // staleness for far fewer relayer calls. See [CachingChainStateReader].
        val chainStateReader = app.onym.android.chain.CachingChainStateReader(
            inner = app.onym.android.chain.SepContractChainStateReader(
                relayers = relayerRepository,
                contracts = contractsRepository,
                networkPreference = networkPreference,
                makeContractTransport = contractTransportFactory,
            ),
        )
        // PR 158: invitee-side push invitations. The dispatcher records
        // decoded GroupInviteOfferPayloads here (never materializing a
        // group); the PendingInvitesViewModel drives the Chats
        // "Invitations" surface and, on explicit Accept, ships a
        // JoinRequestPayload to the offer's intro key via the same
        // JoinRequestSender the deeplink join path uses.
        val pendingInvitesStore = app.onym.android.inbox.PendingInvitesStore()
        // PR 159 verify-at-current: a stale Tyranny snapshot defers here;
        // the verifier asks the admin for the current state and surfaces
        // a "couldn't verify" state if the admin is offline.
        val pendingVerificationStore = app.onym.android.inbox.PendingVerificationStore()
        val groupStateVerifier = app.onym.android.inbox.GroupStateVerifier(
            sealer = identityRepository,
            inboxTransport = inboxTransport,
            groupRepository = groupRepository,
            store = pendingVerificationStore,
            identities = identityRepository.identities,
            activeIdentityId = identityRepository.currentIdentityId,
            scope = applicationScope,
        )
        // Resolve a pending verification the moment its fresh snapshot
        // materializes its group (and cancel the timeout).
        groupStateVerifier.start()
        val chatReceiptSender = app.onym.android.chats.ChatReceiptSender(
            activeIdentity = identityRepository,
            identitiesFlow = identityRepository.identities,
            envelopeSealer = identityRepository,
            inboxTransport = inboxTransport,
        )
        // One recorder for every system row in the app: the dispatcher
        // uses it directly for the member + joiner notices, and the
        // approver reaches the same instance through the
        // GroupSystemEventRecording seam.
        // Active identity's BLS pubkey hex, for the surfaces that gate
        // on "am I this group's admin". Derived once rather than
        // re-hashed per screen.
        val activeBlsPubkeyHex: kotlinx.coroutines.flow.StateFlow<String?> =
            kotlinx.coroutines.flow.combine(
                identityRepository.identities,
                identityRepository.currentIdentityId,
            ) { summaries, currentId ->
                summaries.firstOrNull { it.id == currentId }
                    ?.blsPublicKey
                    ?.joinToString("") { b -> "%02x".format(b) }
            }.stateIn(
                scope = applicationScope,
                started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
                initialValue = null,
            )

        val chatSystemEvents = app.onym.android.chats.ChatSystemEventRecorder(
            messageRepository = messageRepository,
        )
        val incomingDispatcher = app.onym.android.inbox.IncomingMessageDispatcher(
            envelopeDecrypter = identityRepository,
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
            messageRepository = messageRepository,
            identitiesFlow = identityRepository.identities,
            chainState = chainStateReader,
            pendingInvites = pendingInvitesStore,
            groupStateRefresher = groupStateVerifier,
            receiptSender = chatReceiptSender,
            readReceiptsEnabled = { readReceiptsPreference.current() },
            systemEvents = chatSystemEvents,
        )
        // Close the loop for the Retry on a snapshot parked because
        // *this* device couldn't read the chain. The dispatcher owns
        // verification and holds the verifier, so the back-reference is
        // installed here rather than threaded through both constructors.
        groupStateVerifier.setReverify { invitation, owner, signer ->
            // Re-fetch the relayer + contract lists first. The most
            // common reason a joiner can't read the chain is that
            // neither has arrived yet — both are fetched in the
            // background at launch, and a device offline for those few
            // seconds has no endpoint to call and no contract to call it
            // on. Retrying the read alone would fail the same way every
            // time. `refresh`, not `start`: the latter returns as soon
            // as it has spawned the fetch and would race the read.
            runCatching { relayerRepository.refresh() }
            runCatching { contractsRepository.refresh() }
            incomingDispatcher.reverify(invitation, owner, signer)
        }
        // Filter the invites surface to the active identity, and cascade
        // a wipe on identity removal — mirrors the per-identity wiring
        // GroupRepository / IncomingInvitationsRepository already do.
        applicationScope.launch {
            identityRepository.currentIdentityId.collect { id ->
                pendingInvitesStore.setCurrentIdentity(id)
                pendingVerificationStore.setCurrentIdentity(id)
            }
        }
        identityRepository.registerRemovalListener { id ->
            pendingInvitesStore.removeForOwner(id)
            pendingVerificationStore.removeForOwner(id)
        }
        val invitationsInteractor = app.onym.android.inbox.IncomingInvitationsInteractor(
            inboxTransport = inboxTransport,
            repository = invitationsRepository,
            dispatcher = incomingDispatcher,
        )
        applicationScope.launch {
            invitationsInteractor.runFanout(
                identityRepository.identities.map { summaries ->
                    summaries.map { summary ->
                        summary.id to app.onym.android.transport.TransportInboxId(
                            app.onym.android.identity.IdentityRepository.inboxTag(summary.inboxPublicKey),
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
        val introKeyStore: app.onym.android.group.IntroKeyStore =
            app.onym.android.group.EncryptedPrefsIntroKeyStore(applicationContext)
        // Shared minter for both the deeplink share-invite flow and the
        // create-time invite-offer handshake (PR 158). Both persist into
        // the same per-identity IntroKeyStore so the admin's IntroInboxPump
        // listens on every minted intro tag regardless of which path
        // produced it.
        val inviteIntroducer = app.onym.android.group.InviteIntroducer(introKeyStore)
        // Durable sink for inbound "request to join" envelopes. The
        // request now renders as a row inside the founder's chat
        // thread, so it has to survive a relaunch the way any other
        // message does — an in-memory store would silently drop it on
        // process death while the joiner sat on "Waiting for the host
        // to approve…". Falls back to the in-memory store if the Room
        // DAO can't be reached, so a storage failure degrades to the
        // old behaviour instead of blocking launch.
        //
        // Both stores tombstone handled ids in the same durable log: a
        // multi-use link keeps its relay subscription for good, and the
        // REQ carries no `since`, so every reconnect replays requests
        // that were already acted on. The fallback needs it too — its
        // rows are gone after a restart, so the tombstones are all that
        // stop the replay refilling the queue.
        val handledIntroRequests =
            app.onym.android.group.DataStorePreferencesHandledIntroRequestLog(
                applicationContext.introRequestsDataStore,
            )
        val roomIntroRequestStore = try {
            app.onym.android.group.RoomIntroRequestStore(
                dao = groupDatabase.introRequestDao(),
                encryption = storageEncryption,
                handledLog = handledIntroRequests,
            )
        } catch (_: Throwable) {
            null
        }
        val introRequestStore: app.onym.android.group.IntroRequestStore =
            roomIntroRequestStore
                ?: app.onym.android.group.InMemoryIntroRequestStore(handledIntroRequests)
        // Replay what's on disk so a restored request is back in the
        // thread on a cold launch, without waiting for a relay delivery.
        if (roomIntroRequestStore != null) {
            applicationScope.launch { roomIntroRequestStore.start() }
        }
        val introInboxPump = app.onym.android.group.IntroInboxPump(
            transport = inboxTransport,
            store = introRequestStore,
            inboxTagFor = { introPub ->
                app.onym.android.transport.TransportInboxId(
                    app.onym.android.identity.IdentityRepository.inboxTag(introPub),
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
        // Tombstone pruning runs off the UNFILTERED entries flow, on
        // its own collector. It must not ride the pump's stream: that
        // one is scoped to the active identity, so a switch A → B, or a
        // sign-out emitting nothing, would read as "all of A's links
        // retired" and delete A's tombstones — the wipe this whole
        // rephrasing exists to prevent. See IntroKeyRetirementTracker.
        applicationScope.launch {
            val retirement = app.onym.android.group.IntroKeyRetirementTracker()
            introKeyStore.entriesFlow.collect { entries ->
                val current = entries.mapTo(mutableSetOf()) { entry ->
                    entry.introPublicKey.joinToString("") {
                        "%02x".format(it.toInt() and 0xFF)
                    }
                }
                introRequestStore.pruneTombstones(retirement.retired(current))
            }
        }
        // One owner-agnostic sweep at startup. The diff below only sees
        // retirements that happen while an identity is subscribed, so
        // links retired with the app closed — or under another identity
        // — would otherwise linger until MAX_ENTRIES evicted them
        // oldest-first, which can drop a LIVE link's tombstone and bring
        // a handled request back as pending.
        applicationScope.launch {
            introRequestStore.reconcileTombstones(introKeyStore.allLivePublicKeysHex())
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
        val joinRequestSender = app.onym.android.group.JoinRequestSender(
            identity = identityRepository,
            inboxTransport = inboxTransport,
        )

        // Approver-side: turn raw IntroRequests into UI-renderable
        // pending requests + ship sealed GroupInvitationPayloads on
        // user approval. Single instance — the toolbar badge + the
        // modal screen share state via [ApproveRequestsViewModel].
        val joinRequestApprover = app.onym.android.group.JoinRequestApprover(
            activeIdentity = identityRepository,
            envelopeSealer = identityRepository,
            // Method reference, so the secret linter's call-syntax
            // regex doesn't fire. onym:allow-secret-read
            blsSecretKey = identityRepository::blsSecretKey,
            identitySummaries = identityRepository.identities,
            introKeyStore = introKeyStore,
            introRequestStore = introRequestStore,
            groupRepository = groupRepository,
            inboxTransport = inboxTransport,
            scope = applicationScope,
            // PR 88 admin-anchor wiring: same OkHttp client as
            // CreateGroupInteractor so the relayer's auth token rides
            // along.
            relayers = relayerRepository,
            contracts = contractsRepository,
            networkPreference = networkPreference,
            makeContractTransport = contractTransportFactory,
            // Gives the admin its own "X joined" row on approve. Every
            // other member's copy comes from the fanned-out
            // announcement, which the admin never receives.
            systemEvents = chatSystemEvents,
        )
        val approveRequestsViewModel = app.onym.android.group.ApproveRequestsViewModel(
            approver = joinRequestApprover,
        )
        // Kick the collector at app start so requests landing while
        // the chats screen isn't open still drive the badge count.
        approveRequestsViewModel.start()

        // Invitee-side counterpart (PR 158). On explicit Accept it ships
        // a JoinRequestPayload to the offer's intro key via the same
        // shared JoinRequestSender; the admin still has to explicitly
        // approve before anyone is anchored. Started eagerly so the
        // Chats "Invitations" badge reflects offers from app start.
        val pendingInvitesViewModel = app.onym.android.inbox.PendingInvitesViewModel(
            store = pendingInvitesStore,
            verificationStore = pendingVerificationStore,
            groupRepository = groupRepository,
            submitJoin = joinRequestSender::send,
            displayLabel = {
                val activeId = identityRepository.currentIdentityId.value
                identityRepository.identities.value
                    .firstOrNull { it.id == activeId }
                    ?.name
                    .orEmpty()
            },
            retryVerification = { groupIdHex -> groupStateVerifier.retry(groupIdHex) },
        )
        pendingInvitesViewModel.start()

        // Onboarding bundle (PR 3): the gate decision RootScreen
        // collects, the flow factory, and the step contents' seat
        // hooks. Completion re-arms the auto-populate policy and
        // refreshes the relayer list so a skipped notary step gets
        // the published defaults now instead of at the next launch
        // (harmless after an explicit pick — hasUserInteracted is
        // set, so the refresh only updates the known list).
        val onboardingGeneration = kotlinx.coroutines.flow.MutableStateFlow(0)
        // Pin-on-accept for the seeded default directory (see
        // RecommendedDirectoryPinner for the trust rationale): the
        // status probe and the programmatic TOFU are bound to the
        // SEEDED providerId only — a user-added source never goes
        // through here.
        val recommendedDirectoryPinner = discoveryRepository?.let { repo ->
            RecommendedDirectoryPinner(
                seededSourcePinned = {
                    // HYDRATE FIRST: before bootstrap the snapshot
                    // has no sources, and a raw read would misreport
                    // "user removed the seed" (SourceAbsent — never
                    // retried) for a store that simply hadn't loaded.
                    // bootstrap() is idempotent (mutex-guarded
                    // re-read + default re-seed), so awaiting it here
                    // resolves the tri-state from REAL data:
                    //  - seed present        → its pin status;
                    //  - seed absent because the providerId is in
                    //    removedDefaultProviderIds → null, the
                    //    deliberate-removal signal, respected;
                    //  - seed absent otherwise is unreachable after
                    //    bootstrap (mergeDefaults re-seeds unless
                    //    removed) — answered null defensively.
                    repo.bootstrap()
                    val configuration = repo.snapshots.value.configuration
                    val seededId = app.onym.android.discovery.DiscoverySource
                        .onymDefault.providerId
                    val seed = configuration.sources
                        .firstOrNull { it.providerId == seededId }
                    when {
                        seed != null -> seed.pinnedOperatorKeyHex != null
                        else -> null
                    }
                },
                pinSeededSource = {
                    val seed = repo.snapshots.value.sources.first {
                        it.providerId ==
                            app.onym.android.discovery.DiscoverySource
                                .onymDefault.providerId
                    }
                    // Same path as the hub's Verify & Confirm:
                    // addSource fetches + signature-verifies the
                    // manifest; confirmAddSource pins the operator
                    // key and kicks refreshInternal so catalogs
                    // populate. Later manifests are verified against
                    // the pinned key — nothing is trusted blindly.
                    val pending = repo.addSource(seed.url)
                    check(pending.manifest.providerId == seed.providerId) {
                        "recommended-accept pin fetched a different provider " +
                            "(${pending.manifest.providerId})"
                    }
                    repo.confirmAddSource(pending, label = seed.label)
                },
            )
        }

        // ─── Moderation seat (device-recall enforcement client) ─────
        // Dark by default: active only when the enforcement backend is
        // configured (MODERATION_BASE_URL — see app/build.gradle.kts)
        // or an instrumented test registered fakes. The two seams that
        // weaken attestation (backend, provider, clock) are
        // debugActive-gated; store/fetcher swaps ride plain `enabled`.
        val moderationBackend: app.onym.android.moderation.EnforcementBackendClient? = when {
            UITestRegistry.debugActive && UITestRegistry.moderationBackend != null ->
                UITestRegistry.moderationBackend
            // Harness without moderation fakes: the whole seat stays
            // absent, keeping pre-moderation instrumented tests
            // offline and unchanged. debugActive, not enabled:
            // darkening the seat IS disabling moderation, and a
            // release process flipping the plain static must not get
            // that for free (same defense-in-depth as FLAG_SECURE).
            UITestRegistry.debugActive -> null
            BuildConfig.MODERATION_BASE_URL.isNotBlank() ->
                app.onym.android.moderation.OkHttpEnforcementBackendClient(
                    httpClient = httpClient,
                    baseUrl = BuildConfig.MODERATION_BASE_URL,
                    // Emulator loopback (http://10.0.2.2 / localhost)
                    // is a debug-build convenience only; a release
                    // binary refuses any non-https base URL.
                    allowInsecureLoopback = BuildConfig.DEBUG,
                )
            else -> null
        }
        val moderationUi: ModerationUiDependencies?
        if (moderationBackend == null) {
            moderationUi = null
        } else {
            val attestation = if (UITestRegistry.debugActive &&
                UITestRegistry.moderationAttestation != null
            ) {
                UITestRegistry.moderationAttestation!!
            } else {
                app.onym.android.moderation.PlayIntegrityAttestationProvider(
                    context = applicationContext,
                    cloudProjectNumber = BuildConfig.PLAY_CLOUD_PROJECT_NUMBER,
                )
            }
            val moderationSigner = IdentityModerationSigner(identityRepository)
            // debugActive, not enabled: these stores hold the sticky
            // refusal and the cached ban — substituting them is
            // exactly the laundering the persistence wall prevents,
            // which puts them in moderationClock's class, not the
            // I/O-swap class.
            val moderationMandateStore =
                (if (UITestRegistry.debugActive) UITestRegistry.moderationMandateStore else null)
                    ?: app.onym.android.moderation.DataStorePreferencesMandateStore(
                        applicationContext.moderationDataStore,
                    )
            val moderationGateStateStore =
                (if (UITestRegistry.debugActive) UITestRegistry.moderationGateStateStore else null)
                    ?: app.onym.android.moderation.DataStorePreferencesGateStateStore(
                        applicationContext.moderationDataStore,
                    )
            val moderationClock: () -> java.time.Instant =
                (if (UITestRegistry.debugActive) UITestRegistry.moderationClock else null)
                    ?: { java.time.Instant.now() }
            // Discovery-merged directory (iOS parity): catalog
            // entries with seat type "moderation" join the root-key-
            // signed legacy directory, legacy winning on componentId
            // collision — see DiscoveryBackedKnownAuthoritiesFetcher.
            // The registry fake, when present, is used unwrapped:
            // instrumented tests script the exact directory they mean.
            val authoritiesFetcher =
                (if (UITestRegistry.enabled) UITestRegistry.moderationAuthoritiesFetcher else null)
                    ?: DiscoveryBackedKnownAuthoritiesFetcher.wrapping(
                        fallback = app.onym.android.moderation.OkHttpKnownAuthoritiesFetcher(httpClient),
                        catalog = discoveryCatalog,
                    )
            // debugActive, same class as the backend client: where a
            // signed consent gets delivered is a security property.
            val moderationAuthority =
                (if (UITestRegistry.debugActive) UITestRegistry.moderationAuthorityClient else null)
                    ?: app.onym.android.moderation.OkHttpAuthorityClient(
                        httpClient = httpClient,
                        allowInsecureLoopback = BuildConfig.DEBUG,
                    )
            val manifestFetcher =
                (if (UITestRegistry.enabled) UITestRegistry.moderationManifestFetcher else null)
                    ?: app.onym.android.moderation.OkHttpAuthorityManifestFetcher(httpClient)

            val moderationRepository = app.onym.android.moderation.ModerationRepository(
                backend = moderationBackend,
                attestation = attestation,
                signer = moderationSigner,
                mandateStore = moderationMandateStore,
                authority = moderationAuthority,
                clock = moderationClock,
                // Report vertical: the filed-report ledger rides the
                // same moderation DataStore as the mandate blob, and
                // the mandate's authority resolves against the live
                // directory (a report goes to the API base URL the
                // directory designates today, like registration).
                reportStore = app.onym.android.moderation.DataStorePreferencesReportFilingStore(
                    applicationContext.moderationDataStore,
                ),
                caseSubmissionStore =
                    app.onym.android.moderation.DataStorePreferencesCaseSubmissionStore(
                        applicationContext.moderationDataStore,
                    ),
                resolveAuthorityListing = { componentId ->
                    runCatching {
                        authoritiesFetcher.fetchLatest()
                            .firstOrNull { it.componentId == componentId }
                    }.getOrNull()
                },
            )
            val gateCheckRepository = app.onym.android.moderation.GateCheckRepository(
                attestation = attestation,
                backend = moderationBackend,
                moderation = moderationRepository,
                signer = moderationSigner,
                store = moderationGateStateStore,
                scope = applicationScope,
                clock = moderationClock,
            )
            // Shared by the consent surface and the Settings terms
            // view, so definition links open in-app identically.
            val policyDocuments =
                app.onym.android.moderation.OkHttpPolicyDocumentFetcher(httpClient)
            val moderationGateFlow = app.onym.android.moderation.ui.ModerationGateFlow(
                gate = gateCheckRepository,
                authoritiesAvailable = {
                    runCatching { authoritiesFetcher.fetchLatest().isNotEmpty() }
                        .getOrDefault(false)
                },
                reidentificationContact = {
                    moderationRepository.activeMandateRecord()?.authorityContactLine()
                },
                scope = applicationScope,
            )
            applicationScope.launch {
                // Each start is guarded independently: sequential
                // unguarded calls meant one store failure in
                // moderationRepository.start() skipped the gate and
                // flow starts entirely, leaving RootGate Operational
                // — the process running UNMODERATED for its lifetime,
                // the exact inversion of "never toward unmoderated
                // operation". (The DataStore-backed stores also
                // degrade instead of throwing now; this is the second
                // wall.) The gate re-runs the repository load lazily,
                // so a failed eager start self-heals at first check.
                runCatching { moderationRepository.start() }
                    .onFailure { android.util.Log.e("OnymApplication", "moderation start", it) }
                // Launch check + the P1D cadence loop. Without a
                // mandate checkNow answers NotMandated and sends
                // nothing, so starting unconditionally is free.
                runCatching { gateCheckRepository.start() }
                    .onFailure { android.util.Log.e("OnymApplication", "gate start", it) }
                runCatching { moderationGateFlow.start() }
                    .onFailure { android.util.Log.e("OnymApplication", "gate flow start", it) }
                // Complete delivery of a consent whose authority
                // registration a previous session could not finish
                // (registration is retried, never re-consented — the
                // user already signed those exact bytes). Guarded by
                // the pending check so the common every-boot path
                // fetches no directory. Transient failures wait for
                // next boot; a PERMANENT refusal deactivates the
                // record with its reason persisted
                // (MandateRecord.registrationRefusal), which the next
                // consent surface renders — the log below is for
                // operators, not the user's only explanation.
                runCatching {
                    val pending = moderationRepository.pendingRegistration()
                    if (pending != null) {
                        authoritiesFetcher.fetchLatest()
                            .firstOrNull { it.componentId == pending.mandate.authority }
                            ?.let { moderationRepository.registerPending(it) }
                    }
                }.onFailure {
                    android.util.Log.e("OnymApplication", "mandate registration retry", it)
                }
            }
            moderationUi = ModerationUiDependencies(
                gate = moderationGateFlow,
                makeConsentController = { resumeExistingMandate ->
                    app.onym.android.moderation.ui.ModerationConsentController(
                        authoritiesFetcher = authoritiesFetcher,
                        manifestFetcher = manifestFetcher,
                        moderation = moderationRepository,
                        resumeExistingMandate = resumeExistingMandate,
                        // In-app markdown viewer for policy documents
                        // (definitions, evidence rules) — display
                        // path, plain https GET.
                        documents = policyDocuments,
                    )
                },
                directoryNonEmpty = {
                    runCatching { authoritiesFetcher.fetchLatest().isNotEmpty() }
                        .getOrDefault(false)
                },
                repository = moderationRepository,
                retryRegistration = {
                    try {
                        val pending = moderationRepository.pendingRegistration()
                        if (pending != null) {
                            val listing = authoritiesFetcher.fetchLatest()
                                .firstOrNull { it.componentId == pending.mandate.authority }
                            if (listing == null) {
                                "the directory no longer lists ${pending.mandate.authority}"
                            } else {
                                moderationRepository.registerPending(listing)
                                // registerPending swallows TRANSIENT
                                // failures by design (the artifact is
                                // kept for a later retry) — so "no
                                // exception" is not "delivered". Ask
                                // the store, and say so when the tap
                                // changed nothing; a silent Pending
                                // row reads as a dead button.
                                if (moderationRepository.pendingRegistration() != null) {
                                    getString(
                                        app.onym.android.strings.R.string
                                            .moderation_settings_registration_still_pending,
                                    )
                                } else {
                                    null
                                }
                            }
                        } else {
                            null
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message ?: "registration retry failed"
                    }
                },
                documents = policyDocuments,
                makeCaseAppealController = { caseId ->
                    app.onym.android.moderation.ui.CaseAppealController(
                        caseId = caseId,
                        repository = moderationRepository,
                        // applicationScope, not a screen scope: the
                        // signed artifact is persisted before delivery,
                        // and a backgrounded surface must not cancel the
                        // delivery of an appeal already on the wire.
                        scope = applicationScope,
                    )
                },
            )
        }

        val onboardingUi = OnboardingUiDependencies(
            shouldOnboard = onboardingPending,
            makeFlow = {
                app.onym.android.onboarding.OnboardingFlow(
                    store = onboardingStore,
                    // The moderation step enters the walk exactly when
                    // the seat is wired (dark launch: absent backend =
                    // absent step). The directory probe drives the
                    // step's mandatory/Unavailable arithmetic.
                    moderationEnabled = moderationUi != null,
                    moderationDirectoryNonEmpty = {
                        moderationUi?.directoryNonEmpty?.invoke() ?: false
                    },
                )
            },
            onCompleted = {
                relayerAutoPopulateAllowed.set(true)
                onboardingPending.value = false
                applicationScope.launch { runCatching { relayerRepository.refresh() } }
            },
            relayerState = relayerRepository.snapshots,
            // Awaits the same idempotent bootstrap the app kicks at
            // start, so the identity step's checklist reflects the
            // real outcome instead of asserting one. probeIdentityReady
            // rethrows CancellationException — a cancelled check must
            // not masquerade as a bootstrap failure.
            identityReady = {
                probeIdentityReady { identityRepository.bootstrap() }
            },
            pinRecommendedDirectory = {
                recommendedDirectoryPinner?.pinIfUnpinned()
            },
            generation = onboardingGeneration,
            // Explicit restart (Settings → Restart Onboarding): the
            // persisted restart bit survives a mid-walk kill (the
            // gate short-circuits on it at next launch — no
            // grandfathering probe), the policy re-suppresses for the
            // walk exactly like a first run, and the generation bump
            // gives the host a FRESH flow instance. Completion goes
            // through the same complete() → onCompleted path as the
            // first run (markCompleted clears the restart bit).
            requestRestart = {
                onboardingStore.requestRestart()
                relayerAutoPopulateAllowed.set(false)
                onboardingGeneration.value += 1
                onboardingPending.value = true
            },
        )

        // Device backup (storage.backup seat). `null` exactly when no
        // operator is consented yet or the active identity has no
        // recovery phrase — see `BackupSeatComposer`'s doc comment.
        // Resolved synchronously at boot, same posture as the
        // Blossom-server bootstrap above (`runBlocking` over a handful
        // of local DataStore reads, never a network call at this
        // point — the manifest was already verified when it was
        // consented).
        val backupUi: app.onym.android.BackupUiDependencies? = try {
            kotlinx.coroutines.runBlocking {
                app.onym.android.backup.BackupSeatComposer.compose(
                    identityRepository = identityRepository,
                    consentStore = pinnedConsentStore,
                    groupStore = groupStore,
                    messageStore = messageStore,
                    invitationStore = invitationStore,
                    blossomClient = blossomClient,
                    httpClient = httpClient,
                    filesDir = applicationContext.filesDir,
                    scope = applicationScope,
                    backupStateDataStore = applicationContext.backupDataStore,
                )
            }
        } catch (_: Exception) {
            null
        }

        return AppDependencies(
            nostrSignerProvider = nostrSignerProvider,
            moderation = moderationUi,
            backup = backupUi,
            makeRecoveryPhraseBackupViewModel = { activityProvider ->
                RecoveryPhraseBackupViewModel(
                    repository = identityRepository,
                    // UI-test seam: instrumented tests that walk the
                    // reveal (the onboarding recovery step) register
                    // a fake — a real BiometricPrompt can't be
                    // answered from a test. Production always takes
                    // the AndroidBiometricAuthenticator branch.
                    // debugActive, not enabled: bypassing the
                    // biometric gate on the mnemonic must be
                    // impossible in a release binary.
                    authenticator = if (UITestRegistry.debugActive &&
                        UITestRegistry.biometricAuthenticator != null
                    ) {
                        UITestRegistry.biometricAuthenticator!!
                    } else {
                        AndroidBiometricAuthenticator(
                            activityProvider = activityProvider,
                        )
                    },
                    clipboard = clipboard,
                    strings = strings,
                )
            },
            makeRelayerSettingsViewModel = { writeScope ->
                RelayerSettingsViewModel(
                    repository = relayerRepository,
                    writeScope = writeScope,
                )
            },
            makeAnchorsPickerViewModel = {
                AnchorsPickerViewModel(
                    repository = contractsRepository,
                    networkPreference = networkPreference,
                )
            },
            networkPreferenceProvider = networkPreference,
            readReceiptsPreferenceProvider = readReceiptsPreference,
            makeCreateGroupViewModel = {
                val interactor = CreateGroupInteractor(
                    identity = identityRepository,
                    relayers = relayerRepository,
                    contracts = contractsRepository,
                    groups = groupRepository,
                    networkPreference = networkPreference,
                    // Shared factory: production uses the app's
                    // authed OkHttp client; UI tests swap in the
                    // in-memory chain ledger via UITestRegistry.
                    makeContractTransport = contractTransportFactory,
                    inboxTransport = inboxTransport,
                    introducer = inviteIntroducer,
                )
                CreateGroupViewModel(
                    createGroup = { name, invitationMessage, invitees, groupType, avatar, onProgress ->
                        interactor.create(
                            name = name,
                            invitationMessage = invitationMessage,
                            invitees = invitees,
                            groupType = groupType,
                            avatar = avatar,
                            onProgress = onProgress,
                        )
                    },
                )
            },
            makeChatsViewModel = {
                app.onym.android.chats.ChatsViewModel(
                    repository = groupRepository,
                    messageRepository = messageRepository,
                    avatarBroadcaster = app.onym.android.group.GroupAvatarBroadcaster(
                        activeIdentity = identityRepository,
                        identitiesFlow = identityRepository.identities,
                        envelopeSealer = identityRepository,
                        groupRepository = groupRepository,
                        inboxTransport = inboxTransport,
                    ),
                )
            },
            makeChatThreadViewModel = { groupId ->
                // PR A5: per-thread VM. The SendMessageInteractor is
                // built fresh per VM — it's stateless and capturing
                // the closure here keeps the interactor's
                // dependencies (identity, transport, message repo)
                // out of the VM's own constructor.
                val sender = app.onym.android.chats.SendMessageInteractor(
                    activeIdentity = identityRepository,
                    identitiesFlow = identityRepository.identities,
                    envelopeSealer = identityRepository,
                    groupRepository = groupRepository,
                    messageRepository = messageRepository,
                    inboxTransport = inboxTransport,
                    blossomClient = blossomClient,
                    resolveBlossomServerUrl = resolveBlossomServerUrl,
                    encodeVideo = encodeVideo,
                    outbox = chatOutbox,
                    imageLoader = imageLoader,
                    messageSigner = identityRepository,
                )
                app.onym.android.chats.ChatThreadViewModel(
                    groupId = groupId,
                    groupRepository = groupRepository,
                    messageRepository = messageRepository,
                    sendMessage = { gid, body, replyTo ->
                        sender.send(gid, body, replyTo)
                    },
                    sendImage = { gid, data -> sender.sendImage(gid, data) },
                    sendVideo = { gid, uri -> sender.sendVideo(gid, uri) },
                    sendAlbum = { gid, sources -> sender.sendAlbum(gid, sources) },
                    sendVoice = { gid, bytes, duration, waveform ->
                        sender.sendVoice(gid, bytes, duration, waveform)
                    },
                    retryMessage = sender::retry,
                    deleteMessage = { gid, id -> sender.delete(gid, id) },
                    reporting = moderationUi?.let {
                        app.onym.android.chats.ChatReporting.production(
                            repository = it.repository,
                            imageLoader = imageLoader,
                        )
                    },
                    markRead = { gid, lastReadAtMillis ->
                        val ownerId = identityRepository.currentIdentityId.value?.value
                        if (ownerId != null) {
                            groupRepository.markRead(gid, ownerId, lastReadAtMillis)
                        }
                    },
                    chatReceiptSender = chatReceiptSender,
                    readReceiptsEnabled = { readReceiptsPreference.current() },
                    imageLoader = imageLoader,
                    videoLoader = videoLoader,
                    voiceLoader = voiceLoader,
                    // In-thread join requests, replacing the deleted
                    // "Join requests" screen.
                    approveRequests = approveRequestsViewModel,
                    activeBlsPubkeyHex = activeBlsPubkeyHex,
                    unnamedJoinerLabel = getString(
                        app.onym.android.strings.R.string.chat_join_request_unnamed,
                    ),
                )
            },
            makeSearchViewModel = {
                app.onym.android.search.SearchViewModel(
                    messageRepository = messageRepository,
                    groupRepository = groupRepository,
                )
            },
            makeIdentitiesViewModel = {
                app.onym.android.identity.IdentitiesViewModel(identity = identityRepository)
            },
            makeShareInviteViewModel = {
                app.onym.android.group.ShareInviteViewModel(
                    identity = identityRepository,
                    introducer = inviteIntroducer,
                    groupRepository = groupRepository,
                )
            },
            approveRequestsViewModel = approveRequestsViewModel,
            activeBlsPubkeyHex = activeBlsPubkeyHex,
            pendingInvitesViewModel = pendingInvitesViewModel,
            makeNostrRelaySettingsViewModel = { writeScope ->
                app.onym.android.settings.NostrRelaySettingsViewModel(
                    repository = nostrRelaysRepository,
                    writeScope = writeScope,
                )
            },
            nostrRelaysFlow = nostrRelaysRepository.snapshots,
            makeBlossomServerSettingsViewModel = { writeScope ->
                app.onym.android.settings.BlossomServerSettingsViewModel(
                    repository = blossomServersRepository,
                    writeScope = writeScope,
                )
            },
            blossomServersFlow = blossomServersRepository.snapshots,
            clearAllMessages = { messageRepository.clearAll() },
            discovery = discoveryUi,
            onboarding = onboardingUi,
            makeJoinViewModel = { capability ->
                // PR 92: prefill the display-name field from the
                // active identity's alias. Empty when the identity
                // hasn't resolved yet — the field renders its
                // placeholder rather than a stale or hardcoded
                // default. (Bob's report: tapping the deeplink used
                // to show "alice" because the field defaulted to a
                // hardcoded value.)
                val activeId = identityRepository.currentIdentityId.value
                val suggested = identityRepository.identities.value
                    .firstOrNull { it.id == activeId }
                    ?.name
                    .orEmpty()
                app.onym.android.group.JoinViewModel(
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

/** Fallback Blossom base URL when the configured list is empty (the
 *  user explicitly cleared it). Matches the seeded
 *  `BlossomServerEndpoint.onymOfficial` URL. */
private const val DEFAULT_BLOSSOM_SERVER_URL = "https://blossom.onym.app"

/**
 * A small solid-color JPEG for the UI-test video-send path's poster.
 * Only ever called under [UITestRegistry.enabled]; mirrors the image
 * path's generated test bitmap.
 */
private fun debugTestImageBytes(): ByteArray {
    val bitmap = android.graphics.Bitmap.createBitmap(
        240, 160, android.graphics.Bitmap.Config.ARGB_8888,
    )
    bitmap.eraseColor(android.graphics.Color.rgb(0x8B, 0x3D, 0xFD))
    val stream = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
    return stream.toByteArray()
}
