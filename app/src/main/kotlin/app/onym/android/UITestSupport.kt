package app.onym.android

import app.onym.android.chain.AnchorSelectionStore
import app.onym.android.chain.ContractsManifestFetcher
import app.onym.android.chain.KnownRelayersFetcher
import app.onym.android.chain.RelayerSelectionStore
import app.onym.android.chain.SepContractTransport
import app.onym.android.transport.blossom.BlossomClient
import app.onym.android.identity.IdentitySecretStore
import app.onym.android.transport.InboxTransport

/**
 * Indirection point that lets instrumented UI tests inject in-memory
 * fakes for the chain seams without forking the production
 * [OnymApplication.onCreate] wiring.
 *
 * Sequence in an instrumented run:
 *
 *  1. The custom `OnymTestRunner` (in `androidTest/`) populates the
 *     registry fields + flips [enabled] to `true` from
 *     `Instrumentation.onCreate(args)` — runs **before** the
 *     application's `onCreate()`.
 *  2. [OnymApplication.onCreate] checks [enabled]; if `true`, wires
 *     repositories with the registered fakes instead of production
 *     [DataStore] / OkHttp impls.
 *
 * Production builds compile this file but never read [enabled] as
 * `true` — the test runner is the only writer. The cost on
 * production startup is one volatile-boolean read per `onCreate`.
 *
 * Mirrors the iOS pattern of `--ui-testing` launch arguments + an
 * in-process `RelayerRepository` substitution; the Android shape
 * is a registry because Application can't be parameterised at
 * launch time.
 */
@Suppress("MemberVisibilityCanBePrivate")
object UITestRegistry {
    /** When `true`, [OnymApplication.onCreate] wires the seams below
     *  in place of the production DataStore / OkHttp implementations.
     *  Set by the test runner before the application initialises. */
    @Volatile
    var enabled: Boolean = false

    /**
     * The gate for registry seams that WEAKEN a security property —
     * the biometric gate on the recovery phrase, the encrypted
     * secret store, FLAG_SECURE screenshot protection, the
     * signature-expiry clock. Those must be bypassable ONLY in debug
     * builds: [enabled] is a plain mutable static in src/main, so on
     * its own it would let anything running in a release process
     * (dynamite, but defense in depth) switch the protections off.
     * `enabled && BuildConfig.DEBUG` matches
     * [app.onym.android.recovery.AndroidBiometricAuthenticator]'s
     * fail-closed `isDebugBuild` posture; instrumented tests always
     * run the debug build, so the harness is unaffected. Seams that
     * merely SWAP I/O collaborators (fetchers, transports, stores of
     * public data, the onboarding gate driver) stay on [enabled].
     */
    val debugActive: Boolean
        get() = enabled && BuildConfig.DEBUG

    /** In-memory replacement for
     *  [app.onym.android.chain.DataStorePreferencesRelayerSelectionStore]. */
    var relayerStore: RelayerSelectionStore? = null

    /** In-memory replacement for
     *  [app.onym.android.chain.GitHubReleasesKnownRelayersFetcher]. */
    var relayerFetcher: KnownRelayersFetcher? = null

    /** In-memory replacement for
     *  [app.onym.android.chain.DataStorePreferencesAnchorSelectionStore]. */
    var contractsStore: AnchorSelectionStore? = null

    /** In-memory replacement for
     *  [app.onym.android.chain.GitHubReleasesContractsManifestFetcher]. */
    var contractsFetcher: ContractsManifestFetcher? = null

    /** Per-test isolated [IdentitySecretStore]. When non-null,
     *  [OnymApplication] uses it instead of the production
     *  default-prefs-file store — so each instrumented test that
     *  exercises identity flows can wipe + bootstrap into a fresh
     *  EncryptedSharedPreferences file without colliding with
     *  parallel runs. */
    var identitySecretStore: IdentitySecretStore? = null

    /** In-process replacement for [app.onym.android.transport.nostr.NostrInboxTransport].
     *  A loopback transport lets two on-device identities exchange
     *  invitations / messages / receipts with no network. */
    var inboxTransport: InboxTransport? = null

    /** In-memory replacement for the per-request
     *  [app.onym.android.chain.OkHttpSepContractTransport] factory, so a
     *  Tyranny group anchors + verifies against the same in-memory chain
     *  ledger. Keyed by relayer URL (ignored by the fake). */
    var contractTransportFactory: ((String) -> SepContractTransport)? = null

    /** In-memory replacement for the Blossom media server so image
     *  send/receive round-trips with no network. */
    var blossomClient: BlossomClient? = null

    /** Optional fake for the published Nostr-relays feed. When null (the
     *  default), the repository does no network refresh and keeps its
     *  hardcoded seed — so existing UI tests that don't set it are
     *  unaffected. */
    var nostrRelaysFetcher: app.onym.android.transport.nostr.KnownNostrRelaysFetcher? = null

    /** Optional fake for the published Blossom-servers feed. Same
     *  null-means-no-network contract as [nostrRelaysFetcher]. */
    var blossomServersFetcher: app.onym.android.transport.blossom.KnownBlossomServersFetcher? = null

    /** Optional fake for the Discovery network seam
     *  ([app.onym.android.discovery.OkHttpDiscoveryFetcher] in
     *  production). Same null-means-no-network contract as
     *  [nostrRelaysFetcher]: when either this or [discoveryStore] is
     *  null under [enabled], no [app.onym.android.discovery.DiscoveryRepository]
     *  is constructed at all — the legacy fetchers run unwrapped and
     *  UI tests stay offline. */
    var discoveryFetcher: app.onym.android.discovery.DiscoveryFetching? = null

    /** In-memory replacement for
     *  [app.onym.android.discovery.DataStorePreferencesDiscoveryStore].
     *  See [discoveryFetcher] for the null contract. */
    var discoveryStore: app.onym.android.discovery.DiscoveryStore? = null

    /**
     * Onboarding gate driver (PR 4). The null default is the explicit
     * BYPASS contract every pre-onboarding instrumented test relies
     * on: with the slot unset, [OnymApplication] resolves the gate in
     * UI-test mode (never onboard) exactly as it did before the slot
     * existed. Setting it makes the walk drivable: the gate reads
     * THIS store's completion flag, and the grandfathering probe is
     * pinned to "fresh user" — deterministic regardless of whatever
     * DataStore state earlier runs left on the device (the in-process
     * application of the iOS #252 emulator-isolation lesson; the
     * grandfathering truth table itself is unit-tested).
     */
    var onboardingStore: app.onym.android.onboarding.OnboardingStore? = null

    /**
     * Fixture-era clock for the discovery trust layer
     * ([app.onym.android.discovery.DiscoveryRepository] +
     *  [app.onym.android.settings.ModuleConsentViewModel]). The
     * conformance fixtures are signed with expiries
     * (`snapshot-1.json` → 2026-09-12); with the real clock the
     * tests would start failing the day the fixtures lapse. Port of
     * the iOS #252 injected-clock lesson. Null (the default) keeps
     * the real clock.
     */
    var discoveryClock: (() -> java.time.Instant)? = null

    /**
     * Fake biometric gate for the recovery-phrase reveal. The real
     * [app.onym.android.recovery.AndroidBiometricAuthenticator]
     * drives a system BiometricPrompt no instrumented test can
     * answer; tests that walk the onboarding recovery step (or the
     * Settings backup flow) register an auto-succeeding fake here.
     * Null (the default) keeps the real prompt.
     */
    var biometricAuthenticator: app.onym.android.recovery.BiometricAuthenticator? = null

    /**
     * Fake enforcement-backend client for the moderation seat. Gated
     * on [debugActive], not [enabled]: swapping the backend bypasses
     * device attestation, which is a security property, not an I/O
     * detail. Non-null (under a debug build) is ALSO the activation
     * switch: production activation needs BuildConfig.MODERATION_BASE_URL,
     * so pre-moderation tests that leave this null run with the whole
     * moderation surface absent, exactly as before it existed.
     */
    var moderationBackend: app.onym.android.moderation.EnforcementBackendClient? = null

    /** Fake Play Integrity provider. [debugActive]-gated for the same
     *  reason as [moderationBackend]. */
    var moderationAttestation: app.onym.android.moderation.DeviceAttestationProvider? = null

    /** In-memory mandate store (I/O swap; [enabled]). */
    var moderationMandateStore: app.onym.android.moderation.MandateStore? = null

    /** In-memory gate-state store (I/O swap; [enabled]). */
    var moderationGateStateStore: app.onym.android.moderation.GateStateStore? = null

    /** Fake authority directory (I/O swap; [enabled]). */
    var moderationAuthoritiesFetcher: app.onym.android.moderation.KnownAuthoritiesFetcher? = null

    /** Fake manifest fetcher (I/O swap; [enabled]). */
    var moderationManifestFetcher: app.onym.android.moderation.AuthorityManifestFetcher? = null

    /**
     * Fixture-era clock for the moderation gate's grace arithmetic.
     * [debugActive]-gated: the clock decides signature freshness and
     * grace expiry, both security properties (same class as
     * [discoveryClock]'s trust-layer cousin).
     */
    var moderationClock: (() -> java.time.Instant)? = null

    /** Reset between tests. Called from `@Before`. */
    fun reset() {
        enabled = false
        relayerStore = null
        relayerFetcher = null
        contractsStore = null
        contractsFetcher = null
        identitySecretStore = null
        inboxTransport = null
        contractTransportFactory = null
        blossomClient = null
        nostrRelaysFetcher = null
        blossomServersFetcher = null
        discoveryFetcher = null
        discoveryStore = null
        onboardingStore = null
        discoveryClock = null
        biometricAuthenticator = null
        moderationBackend = null
        moderationAttestation = null
        moderationMandateStore = null
        moderationGateStateStore = null
        moderationAuthoritiesFetcher = null
        moderationManifestFetcher = null
        moderationClock = null
    }
}
