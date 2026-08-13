package app.onym.android.foundation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

/**
 * In-test Ed25519 manifest signing — the test-side twin of
 * [ServiceManifestReviewer]. Keys are BouncyCastle
 * [Ed25519PrivateKeyParameters] from fixed seeds (deterministic
 * manifests, deterministic failures).
 */
object ServiceManifestTestSigning {

    /** The "operator" keypair most tests sign with. */
    val operatorPrivateKey = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)

    /** A second keypair for wrong-key tests. */
    val strangerPrivateKey = Ed25519PrivateKeyParameters(ByteArray(32) { (it + 100).toByte() }, 0)

    val operatorKeyHex: String = operatorPrivateKey.generatePublicKey().encoded.toLowercaseHex()

    fun sign(message: ByteArray, key: Ed25519PrivateKeyParameters = operatorPrivateKey): ByteArray {
        val signer = Ed25519Signer().apply { init(true, key) }
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /**
     * Encode [unsigned] (a manifest object without `signature`),
     * sign its canonical bytes with [key], and return the manifest
     * bytes with the base64 signature embedded at the top level.
     */
    fun signedManifestBytes(
        unsigned: JsonObject,
        key: Ed25519PrivateKeyParameters = operatorPrivateKey,
    ): ByteArray {
        val unsignedBytes = Json.encodeToString(JsonElement.serializer(), unsigned)
            .toByteArray(Charsets.UTF_8)
        val signature = sign(ServiceManifestCanonical.signingBytes(unsignedBytes), key)
        val signed = JsonObject(
            unsigned + ("signature" to JsonPrimitive(Base64.getEncoder().encodeToString(signature))),
        )
        return Json.encodeToString(JsonElement.serializer(), signed).toByteArray(Charsets.UTF_8)
    }

    /** A minimal valid unsigned manifest object, customizable per test. */
    fun unsignedManifest(
        componentId: String = "onym:component:test-courier",
        seat: String = "courier",
        operatorKey: String = "onym:key:$operatorKeyHex",
        validUntil: String? = null,
        offers: List<JsonObject>? = null,
    ): JsonObject = buildJsonObject {
        put("componentId", componentId)
        put("seat", seat)
        put("operator", operatorKey)
        if (validUntil != null) put("validUntil", validUntil)
        if (offers != null) put("offers", buildJsonArray { offers.forEach { add(it) } })
        putJsonObject("endpoints") {
            put("api", "https://courier.example.com/v1")
        }
    }

    /** A ready-to-review signed manifest with the defaults above. */
    fun defaultSignedManifest(): ByteArray = signedManifestBytes(unsignedManifest())

    fun ByteArray.toLowercaseHex(): String = joinToString("") { "%02x".format(it) }
}
