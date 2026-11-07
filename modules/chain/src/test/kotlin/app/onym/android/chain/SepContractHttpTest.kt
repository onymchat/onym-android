package app.onym.android.chain

import app.onym.android.support.FakeOkHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * iOS-parity budget for the relayer contract call.
 *
 * `create_group` makes the relayer run `stellar contract invoke` and wait
 * for ledger inclusion, so the round trip routinely exceeds OkHttp's 10 s
 * defaults. iOS posts through `URLSession.shared` (60 s
 * `timeoutIntervalForRequest`) and works; Android used to give up at 10 s
 * and reported "Couldn't reach the relayer". These tests pin the derived
 * client to the iOS budget and pin the ONY-001 scoping that the derivation
 * must not lose.
 */
class SepContractHttpTest {

    private val token = "deadbeefcafef00d"
    private val relayerUrl = "https://relayer.example/contract"
    private val contractId = "C00000000000000000000000000000000000000000000000000000"
    private val commitmentBody =
        """{"commitment":"ERERERERERERERERERERERERERERERERERERERERERE=","epoch":0}"""

    /** Production's shared, bearer-free base — OkHttp defaults. */
    private val publicBase = OkHttpClient.Builder().build()

    private fun derived(endpointUrl: String = relayerUrl): OkHttpClient =
        SepContractHttp.relayerClient(
            base = publicBase,
            token = token,
            endpointUrl = endpointUrl,
        )

    @Test
    fun derivedClient_waitsAsLongAsIosSession() {
        val client = derived()

        assertEquals(60_000, client.connectTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(60_000, client.writeTimeoutMillis)
        // iOS bounds idle time per request, not total wall time.
        assertEquals(0, client.callTimeoutMillis)
    }

    @Test
    fun derivedClient_leavesTheSharedBaseUntouched() {
        derived()

        // Cross-origin consumers (GitHub release fetchers, Nostr, Blossom)
        // keep OkHttp's defaults — the patience is for the chain call only.
        assertEquals(10_000, publicBase.connectTimeoutMillis)
        assertEquals(10_000, publicBase.readTimeoutMillis)
        assertEquals(10_000, publicBase.writeTimeoutMillis)
    }

    // ─── ONY-001 scoping survives the derivation ───────────────────

    /** The derived client plus a capture tap standing in for the wire. */
    private fun capturing(seen: (String?) -> Unit): OkHttpClient =
        derived().newBuilder()
            .addInterceptor { chain ->
                seen(chain.request().header("Authorization"))
                FakeOkHttpClient.ok(chain.request(), commitmentBody)
            }
            .build()

    private fun clientFor(endpointUrl: String, httpClient: OkHttpClient) =
        SepContractClient(
            contractID = contractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = OkHttpSepContractTransport(
                httpClient = httpClient,
                endpointUrl = endpointUrl,
            ),
        )

    @Test
    fun relayerContractCall_carriesBearerToken() = runTest {
        var seen: String? = "<unset>"

        clientFor(relayerUrl, capturing { seen = it }).getCommitment(ByteArray(32) { 0x11 })

        assertEquals("Bearer $token", seen)
    }

    @Test
    fun sameClientPointedAtAnotherOrigin_doesNotCarryBearerToken() = runTest {
        var seen: String? = "<unset>"
        val elsewhere = "https://elsewhere.example/contract"

        // The client was derived for relayer.example, so the host-scoped
        // interceptor must decline to attach the token to another origin.
        clientFor(elsewhere, capturing { seen = it }).getCommitment(ByteArray(32) { 0x11 })

        assertNull(seen)
    }
}
