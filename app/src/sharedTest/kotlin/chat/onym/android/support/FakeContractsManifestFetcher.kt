package chat.onym.android.support

import chat.onym.android.chain.ContractsManifest
import chat.onym.android.chain.ContractsManifestFetcher

/**
 * Reusable test fake for [ContractsManifestFetcher]. Three modes
 * mirror [FakeKnownRelayersFetcher] from PR #17.
 */
class FakeContractsManifestFetcher(var mode: Mode) : ContractsManifestFetcher {

    sealed class Mode {
        /** Return [manifest] paired with [rawJson] (defaults to a
         *  trivial JSON literal — the repository persists it via
         *  the store but tests rarely care about the exact bytes). */
        data class Succeeds(
            val manifest: ContractsManifest,
            val rawJson: String = """{"version":1,"releases":[]}""",
        ) : Mode()

        data class Failing(val error: Throwable) : Mode()

        data class Scripted(
            val responses: List<Result<ContractsManifestFetcher.FetchResult>>,
        ) : Mode()
    }

    var fetchCallCount: Int = 0
        private set

    private var scriptedIndex = 0

    override suspend fun fetch(): ContractsManifestFetcher.FetchResult {
        fetchCallCount += 1
        return when (val m = mode) {
            is Mode.Succeeds -> ContractsManifestFetcher.FetchResult(
                manifest = m.manifest,
                rawJson = m.rawJson,
            )
            is Mode.Failing -> throw m.error
            is Mode.Scripted -> {
                if (scriptedIndex >= m.responses.size) {
                    throw IllegalStateException(
                        "FakeContractsManifestFetcher: scripted responses exhausted (${m.responses.size} consumed)"
                    )
                }
                val r = m.responses[scriptedIndex]
                scriptedIndex += 1
                r.getOrThrow()
            }
        }
    }
}
