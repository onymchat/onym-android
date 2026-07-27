package app.onym.android.chain

import app.onym.android.chats.OkHttpBlossomClient
import app.onym.android.support.FakeOkHttpClient
import app.onym.android.transport.nostr.GitHubReleasesKnownNostrRelaysFetcher
import app.onym.android.transport.nostr.NostrEphemeralSignerProvider
import app.onym.android.transport.nostr.NostrSigner
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ONY-001 regression suite — the relayer bearer token must never leave
 * the relayer origin.
 *
 * These tests reconstruct the exact client wiring from
 * [app.onym.android.OnymApplication]:
 *
 *   - a bearer-free "public" [OkHttpClient] shared with every
 *     cross-origin consumer (GitHub release fetchers, the Nostr relay
 *     fetcher, and the user-configurable Blossom media client);
 *   - a per-relayer client derived via `newBuilder()` that adds a
 *     host-scoped [BearerAuthInterceptor] — the ONLY place the relayer
 *     credential is attached.
 *
 * A trailing capture interceptor stands in for the wire, recording the
 * `Authorization` header that would actually be sent. No `MockWebServer`
 * (see `support/FakeOkHttpClient`).
 */
class BearerAuthScopingTest {

    private val token = "deadbeefcafef00d"
    private val relayerHost = "relayer.example"
    private val relayerUrl = "https://relayer.example/contract"
    private val testContractId =
        "C00000000000000000000000000000000000000000000000000000"

    /** The shared, bearer-free base — production's `httpClient`. */
    private val publicBase = OkHttpClient.Builder().build()

    /** Signer is never touched on the Blossom download path. */
    private val stubSigner = object : NostrEphemeralSignerProvider {
        override fun ephemeral(): NostrSigner =
            throw UnsupportedOperationException("signer not used by download()")
    }

    /**
     * Mirror of the production factory: derive a relayer client from the
     * bearer-free base, adding a host-scoped bearer interceptor, then a
     * capture tap standing in for the network.
     */
    private fun relayerClient(host: String, body: String, sink: (String?) -> Unit): OkHttpClient =
        publicBase.newBuilder()
            .addInterceptor(BearerAuthInterceptor(token = token, allowedHost = host))
            .addInterceptor { chain ->
                sink(chain.request().header("Authorization"))
                FakeOkHttpClient.ok(chain.request(), body)
            }
            .build()

    /** A cross-origin consumer's client: the bearer-free base + capture tap. */
    private fun publicClient(body: String, sink: (String?) -> Unit): OkHttpClient =
        publicBase.newBuilder()
            .addInterceptor { chain ->
                sink(chain.request().header("Authorization"))
                FakeOkHttpClient.ok(chain.request(), body)
            }
            .build()

    // ─── the relayer/contract client DOES carry the token ──────────

    @Test
    fun contractClient_toRelayerHost_carriesBearerToken() = runTest {
        var seen: String? = "<unset>"
        val client = SepContractClient(
            contractID = testContractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = OkHttpSepContractTransport(
                httpClient = relayerClient(
                    host = relayerUrl.toHttpUrl().host,
                    body = """{"accepted":true}""",
                    sink = { seen = it },
                ),
                endpointUrl = relayerUrl,
            ),
        )

        client.createGroupTyranny(stubPayload())

        assertEquals("Bearer $token", seen)
    }

    // ─── cross-origin consumers do NOT carry the token ─────────────

    @Test
    fun blossomClient_doesNotCarryRelayerToken() = runTest {
        var seen: String? = "<unset>"
        val blossom = OkHttpBlossomClient(
            baseUrl = "https://blossom.user-configured.example",
            httpClient = publicClient(body = "blob-bytes", sink = { seen = it }),
            signerProvider = stubSigner,
        )

        blossom.download("00112233")

        // download() sets no Authorization of its own, so a null here
        // proves the relayer bearer token was not smuggled in.
        assertNull(seen)
    }

    @Test
    fun gitHubRelayersFetcher_doesNotCarryRelayerToken() = runTest {
        var seen: String? = "<unset>"
        val fetcher = GitHubReleasesKnownRelayersFetcher(
            httpClient = publicClient(
                body = """{ "version": 1, "relayers": [] }""",
                sink = { seen = it },
            ),
        )

        fetcher.fetch()

        assertNull(seen)
    }

    @Test
    fun gitHubContractsFetcher_doesNotCarryRelayerToken() = runTest {
        var seen: String? = "<unset>"
        val fetcher = GitHubReleasesContractsManifestFetcher(
            httpClient = publicClient(
                body = """{ "version": 1, "contracts": [] }""",
                sink = { seen = it },
            ),
        )

        runCatching { fetcher.fetch() }

        assertNull(seen)
    }

    @Test
    fun nostrRelaysFetcher_doesNotCarryRelayerToken() = runTest {
        var seen: String? = "<unset>"
        val fetcher = GitHubReleasesKnownNostrRelaysFetcher(
            httpClient = publicClient(
                body = """{ "version": 1, "relays": [] }""",
                sink = { seen = it },
            ),
        )

        fetcher.fetch()

        assertNull(seen)
    }

    // ─── host scoping guards the redirect / cross-origin case ──────

    @Test
    fun bearer_attachedOnlyToTheAllowedRelayerHost() {
        var seenRelayer: String? = "<unset>"
        var seenOther: String? = "<unset>"

        // A client scoped to the relayer host, but pointed at a foreign
        // host (as a cross-origin redirect target would be): no token.
        relayerClient(host = relayerHost, body = "{}", sink = { seenOther = it })
            .newCall(Request.Builder().url("https://evil.example/steal").build())
            .execute()
            .close()
        assertNull(seenOther)

        // Same scoping, request to the relayer host itself: token present.
        relayerClient(host = relayerHost, body = "{}", sink = { seenRelayer = it })
            .newCall(Request.Builder().url("https://relayer.example/contract").build())
            .execute()
            .close()
        assertEquals("Bearer $token", seenRelayer)
    }

    private fun stubPayload() = TyrannyCreateGroupPayload(
        groupId = ByteArray(32),
        commitment = ByteArray(32),
        tier = 0,
        adminPubkeyCommitment = ByteArray(32),
        proof = ByteArray(8),
        publicInputs = (0 until 4).map { ByteArray(32) },
    )
}
