package app.onym.android.support

import app.onym.android.chain.SepContractInvocation
import app.onym.android.chain.SepContractTransport
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Reusable test fake for [SepContractTransport]. Mirrors the
 * `RecordingTransport` private fixture at the bottom of
 * `SEPContractClientTests.swift` (onym-ios PR #24), promoted to a
 * package-public fake here so PR-C's `CreateGroupInteractor` tests
 * can reuse it without duplicating the encode/decode dance.
 *
 * Records the last invocation as a [JsonObject] so wire-shape
 * assertions (snake_case key checks, base64 payload presence) read
 * naturally.
 */
class FakeSepContractTransport(
    private val cannedResponseJson: String,
) : SepContractTransport {

    private val jsonFormat = Json { encodeDefaults = true }

    /** Last invocation's top-level JSON (`contract_id`, `function`,
     *  `payload`). `null` if `invoke` was never called. */
    @Volatile
    var lastInvocationJson: JsonObject? = null
        private set

    /** Last invocation's `function` value. */
    val lastFunction: String?
        get() = (lastInvocationJson?.get("function") as? kotlinx.serialization.json.JsonPrimitive)?.content

    /** Last invocation's `payload` field as a JsonObject (most
     *  payloads are objects; pin tests grab keys off this). */
    val lastPayloadJson: JsonObject?
        get() = lastInvocationJson?.get("payload") as? JsonObject

    override suspend fun <P, R> invoke(
        invocation: SepContractInvocation<P>,
        invocationSerializer: KSerializer<SepContractInvocation<P>>,
        responseSerializer: KSerializer<R>,
    ): R {
        val encoded = jsonFormat.encodeToString(invocationSerializer, invocation)
        lastInvocationJson = jsonFormat.parseToJsonElement(encoded) as JsonObject
        return jsonFormat.decodeFromString(responseSerializer, cannedResponseJson)
    }
}
