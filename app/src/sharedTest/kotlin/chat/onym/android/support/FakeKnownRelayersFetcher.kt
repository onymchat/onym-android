package chat.onym.android.support

import chat.onym.android.chain.KnownRelayersFetcher
import chat.onym.android.chain.RelayerEndpoint

/**
 * Reusable test fake for [KnownRelayersFetcher]. Three modes
 * mirror the iOS twin (PR #18):
 *
 *  - [Mode.Succeeds] — happy-path; returns the supplied list.
 *  - [Mode.Failing] — error-path; throws the supplied error.
 *  - [Mode.Scripted] — multi-call; each `fetch()` call pulls the
 *    next [Result] off the script. Walks past the end → throws.
 *
 * Tracks [fetchCallCount] so tests can assert idempotency
 * properties (e.g., `RelayerRepository.start()` calling fetch only
 * once across multiple invocations).
 *
 * Mirrors `FakeKnownRelayersFetcher` from onym-ios PR #18.
 */
class FakeKnownRelayersFetcher(var mode: Mode) : KnownRelayersFetcher {

    sealed class Mode {
        data class Succeeds(val list: List<RelayerEndpoint>) : Mode()
        data class Failing(val error: Throwable) : Mode()
        data class Scripted(val responses: List<Result<List<RelayerEndpoint>>>) : Mode()
    }

    var fetchCallCount: Int = 0
        private set

    private var scriptedIndex = 0

    override suspend fun fetch(): List<RelayerEndpoint> {
        fetchCallCount += 1
        return when (val m = mode) {
            is Mode.Succeeds -> m.list
            is Mode.Failing -> throw m.error
            is Mode.Scripted -> {
                if (scriptedIndex >= m.responses.size) {
                    throw IllegalStateException(
                        "FakeKnownRelayersFetcher: scripted responses exhausted (${m.responses.size} consumed)"
                    )
                }
                val r = m.responses[scriptedIndex]
                scriptedIndex += 1
                r.getOrThrow()
            }
        }
    }
}
