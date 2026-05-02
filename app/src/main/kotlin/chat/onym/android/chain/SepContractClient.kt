package chat.onym.android.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Pins a `(contractId, transport)` pair and exposes the three
 * contract entrypoints PR-A needs:
 *
 *  - `create_group_v2` — Anarchy / OneOnOne / Democracy / Tyranny
 *    creation. Per-type Oligarchy creation lives outside PR-A scope.
 *  - `update_commitment` — Tyranny member-add (used by PR-B+).
 *  - `get_state` — post-create read-back used by the E2E test (PR-D).
 *
 * Stellar Soroban SDK is intentionally not pulled in: the relayer
 * handles tx assembly + signing, this client just POSTs the function
 * call in the [SepContractInvocation] envelope shape.
 *
 * Mirrors `SEPContractClient` from onym-ios PR #24.
 *
 * @param contractId Soroban contract ID the relayer will route the
 *   call to. Comes from `ContractsRepository` selection.
 * @param transport The HTTP-leg seam — production uses
 *   [OkHttpSepContractTransport], tests substitute [FakeSepContractTransport]
 *   (or build an [OkHttpClient] via `FakeOkHttpClient` and wrap).
 */
class SepContractClient(
    val contractId: String,
    private val transport: SepContractTransport,
) {

    suspend fun createGroupV2(request: SepCreateGroupV2Request): SepSubmissionResponse =
        invoke(
            function = "create_group_v2",
            payload = request,
            payloadSerializer = SepCreateGroupV2Request.serializer(),
            responseSerializer = SepSubmissionResponse.serializer(),
        )

    suspend fun updateCommitment(request: SepUpdateCommitmentRequest): SepSubmissionResponse =
        invoke(
            function = "update_commitment",
            payload = request,
            payloadSerializer = SepUpdateCommitmentRequest.serializer(),
            responseSerializer = SepSubmissionResponse.serializer(),
        )

    suspend fun getState(groupId: ByteArray): SepCommitmentEntry =
        invoke(
            function = "get_state",
            payload = SepGetStateRequest(groupId = groupId),
            payloadSerializer = SepGetStateRequest.serializer(),
            responseSerializer = SepCommitmentEntry.serializer(),
        )

    private suspend fun <P, R> invoke(
        function: String,
        payload: P,
        payloadSerializer: KSerializer<P>,
        responseSerializer: KSerializer<R>,
    ): R {
        val invocation = SepContractInvocation(
            contractId = contractId,
            function = function,
            payload = payload,
        )
        return transport.invoke(
            invocation = invocation,
            invocationSerializer = SepContractInvocation.serializer(payloadSerializer),
            responseSerializer = responseSerializer,
        )
    }
}

/**
 * Seam for the network leg. Tests inject [FakeSepContractTransport];
 * production uses [OkHttpSepContractTransport] constructed from a
 * [chat.onym.android.chain.RelayerEndpoint] resolved via
 * `RelayerRepository.selectUrl`.
 */
interface SepContractTransport {
    suspend fun <P, R> invoke(
        invocation: SepContractInvocation<P>,
        invocationSerializer: KSerializer<SepContractInvocation<P>>,
        responseSerializer: KSerializer<R>,
    ): R
}

/**
 * OkHttp-backed transport. POSTs the JSON-encoded invocation envelope
 * to [endpointUrl] and decodes the body on 2xx; throws
 * [SepContractError.BadStatus] on non-2xx (after capturing the body —
 * gotcha #3 in the brief: `Response.body!!.bytes()` is single-shot).
 *
 * @param httpClient Injected for tests; production passes the same
 *   shared [OkHttpClient] as the rest of the app.
 * @param endpointUrl The relayer's contract-call URL. Resolved per
 *   request from `RelayerRepository.selectUrl()`.
 */
class OkHttpSepContractTransport(
    private val httpClient: OkHttpClient,
    private val endpointUrl: String,
) : SepContractTransport {

    override suspend fun <P, R> invoke(
        invocation: SepContractInvocation<P>,
        invocationSerializer: KSerializer<SepContractInvocation<P>>,
        responseSerializer: KSerializer<R>,
    ): R = withContext(Dispatchers.IO) {
        val bodyJson = jsonFormat.encodeToString(invocationSerializer, invocation)
        val request = Request.Builder()
            .url(endpointUrl.toHttpUrl())
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            // Single-shot body read — capture the bytes before
            // checking status so the error path can include the
            // response body without a second `.bytes()` call.
            val responseBytes = try {
                response.body?.bytes() ?: ByteArray(0)
            } catch (e: IOException) {
                throw SepContractError.NotConnected(e)
            }

            if (!response.isSuccessful) {
                val bodyStr = try {
                    responseBytes.toString(Charsets.UTF_8)
                } catch (_: Throwable) {
                    "<non-UTF8 body, ${responseBytes.size} bytes>"
                }
                throw SepContractError.BadStatus(code = response.code, body = bodyStr)
            }

            try {
                jsonFormat.decodeFromString(
                    responseSerializer,
                    responseBytes.toString(Charsets.UTF_8),
                )
            } catch (e: SerializationException) {
                throw SepContractError.MalformedResponse(e)
            } catch (e: IllegalArgumentException) {
                // base64 decode failure inside per-field serializer
                throw SepContractError.MalformedResponse(e)
            }
        }
    }

    companion object {
        /** Permissive decode: relayer may add fields in a future
         *  version, and unknown keys must not break the client. */
        private val jsonFormat = Json {
            ignoreUnknownKeys = true
            // Forward-compat: encode with default values present so a
            // future relayer that REQUIRES a field with our default
            // doesn't break.
            encodeDefaults = true
        }
    }
}
