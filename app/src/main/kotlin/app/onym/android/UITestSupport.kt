package app.onym.android

import app.onym.android.chain.AnchorSelectionStore
import app.onym.android.chain.ContractsManifestFetcher
import app.onym.android.chain.KnownRelayersFetcher
import app.onym.android.chain.RelayerSelectionStore
import app.onym.android.chain.SepContractTransport
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
    }
}
