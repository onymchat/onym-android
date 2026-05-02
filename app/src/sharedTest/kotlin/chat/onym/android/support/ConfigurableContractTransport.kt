package chat.onym.android.support

import chat.onym.android.chain.SepContractInvocation
import chat.onym.android.chain.SepContractTransport
import chat.onym.android.chain.SepSubmissionResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Recording [SepContractTransport] with a configurable behavior
 * per call. `behavior` controls whether the next invocation throws
 * or returns a canned response.
 *
 * Mirrors `ConfigurableContractTransport` from onym-ios PR #26.
 */
class ConfigurableContractTransport : SepContractTransport {

    sealed class Behavior {
        data class Response(val response: SepSubmissionResponse) : Behavior()
        data class Throws(val error: Throwable) : Behavior()
    }

    data class Invocation(val function: String, val payloadJson: String)

    private val mutex = Mutex()
    private val _invocations = mutableListOf<Invocation>()
    private var _behavior: Behavior = Behavior.Response(
        SepSubmissionResponse(accepted = true, transactionHash = "0xstub", message = null)
    )

    suspend fun setBehavior(behavior: Behavior) = mutex.withLock { _behavior = behavior }

    suspend fun invocations(): List<Invocation> = mutex.withLock { _invocations.toList() }

    override suspend fun <P, R> invoke(
        invocation: SepContractInvocation<P>,
        invocationSerializer: KSerializer<SepContractInvocation<P>>,
        responseSerializer: KSerializer<R>,
    ): R {
        val encoded = jsonFormat.encodeToString(invocationSerializer, invocation)
        val (currentBehavior, _) = mutex.withLock {
            _invocations.add(Invocation(function = invocation.function, payloadJson = encoded))
            _behavior to Unit
        }
        return when (currentBehavior) {
            is Behavior.Response -> {
                val responseJson = jsonFormat.encodeToString(SepSubmissionResponse.serializer(), currentBehavior.response)
                @Suppress("UNCHECKED_CAST")
                jsonFormat.decodeFromString(responseSerializer, responseJson)
            }
            is Behavior.Throws -> throw currentBehavior.error
        }
    }

    private companion object {
        private val jsonFormat = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
