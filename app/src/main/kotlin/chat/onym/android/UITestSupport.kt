package chat.onym.android

import chat.onym.android.chain.AnchorSelectionStore
import chat.onym.android.chain.ContractsManifestFetcher
import chat.onym.android.chain.KnownRelayersFetcher
import chat.onym.android.chain.RelayerSelectionStore

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
     *  [chat.onym.android.chain.DataStorePreferencesRelayerSelectionStore]. */
    var relayerStore: RelayerSelectionStore? = null

    /** In-memory replacement for
     *  [chat.onym.android.chain.GitHubReleasesKnownRelayersFetcher]. */
    var relayerFetcher: KnownRelayersFetcher? = null

    /** In-memory replacement for
     *  [chat.onym.android.chain.DataStorePreferencesAnchorSelectionStore]. */
    var contractsStore: AnchorSelectionStore? = null

    /** In-memory replacement for
     *  [chat.onym.android.chain.GitHubReleasesContractsManifestFetcher]. */
    var contractsFetcher: ContractsManifestFetcher? = null

    /** Reset between tests. Called from `@Before`. */
    fun reset() {
        enabled = false
        relayerStore = null
        relayerFetcher = null
        contractsStore = null
        contractsFetcher = null
    }
}
