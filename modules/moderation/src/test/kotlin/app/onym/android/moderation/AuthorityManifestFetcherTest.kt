package app.onym.android.moderation

import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The consent trust anchor, exercised end to end against an in-process
 * canned-response OkHttp client and a real Ed25519 operator key: the
 * one path a MITM attacks. Everything the fetcher guarantees is pinned
 * here — the directory-pinned key equality, the detached signature
 * over the EXACT bytes, the componentId binding, and the `.sig`
 * shape — because a consent minted past any of these pins the wrong
 * terms with the user's own signature.
 */
class AuthorityManifestFetcherTest {
    private val manifestUrl = "https://authority.example/manifest.json"

    private val operatorKey: Ed25519PrivateKeyParameters = run {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        generator.generateKeyPair().private as Ed25519PrivateKeyParameters
    }
    private val operatorPublic: Ed25519PublicKeyParameters =
        operatorKey.generatePublicKey()

    private fun listing(pinnedKey: ByteArray = operatorPublic.encoded) = AuthorityListing(
        componentId = "onym:component:test-authority",
        name = "Test Authority",
        manifestURL = manifestUrl,
        apiBaseURL = "https://authority.example",
        operatorPublicKeyBase64 = Base64.getEncoder().encodeToString(pinnedKey),
    )

    private fun manifestBytes(
        componentId: String = "onym:component:test-authority",
        operator: String = keyReference(operatorPublic.encoded),
    ): ByteArray = """{
        "componentId": "$componentId",
        "operator": "$operator",
        "moderationProfileId": "onym:moderation-profile:consent-bound-v1",
        "violationClasses": [{"classId": "csam"}],
        "validUntil": "2100-01-01T00:00:00Z"
    }""".encodeToByteArray()

    private fun sign(bytes: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, operatorKey)
        update(bytes, 0, bytes.size)
        generateSignature()
    }

    /** An OkHttpClient whose interceptor serves canned bodies and
     * never touches the network. */
    private fun servingClient(responses: Map<String, ByteArray>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val url = chain.request().url.toString()
                    val body = responses[url]
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (body != null) 200 else 404)
                        .message(if (body != null) "OK" else "Not Found")
                        .body((body ?: ByteArray(0)).toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()

    private fun fetcher(
        manifest: ByteArray,
        signature: ByteArray? = sign(manifest),
    ): OkHttpAuthorityManifestFetcher {
        val responses = buildMap {
            put(manifestUrl, manifest)
            if (signature != null) {
                put("$manifestUrl.sig", Base64.getEncoder().encode(signature))
            }
        }
        return OkHttpAuthorityManifestFetcher(servingClient(responses))
    }

    @Test
    fun `a signed manifest fetches with its exact bytes and hash`() = runTest {
        val raw = manifestBytes()
        val signed = fetcher(raw).fetch(listing())
        assertTrue(signed.rawBytes.contentEquals(raw))
        assertEquals(sha256Hex(raw), signed.manifestHash)
        assertEquals("onym:component:test-authority", signed.manifest.componentId)
    }

    /** The core MITM check: the signature is over the exact bytes, so
     * any tampering after signing fails verification. */
    @Test
    fun `tampered manifest bytes fail the detached signature`() = runTest {
        val raw = manifestBytes()
        val tampered = raw.decodeToString().replace("csam", "spam").encodeToByteArray()
        val responses = mapOf(
            manifestUrl to tampered,
            "$manifestUrl.sig" to Base64.getEncoder().encode(sign(raw)),
        )
        try {
            OkHttpAuthorityManifestFetcher(servingClient(responses)).fetch(listing())
            fail("tampered bytes must not verify")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!, e.message!!.contains("signature"))
        }
    }

    /** A manifest naming its own key would make the directory pin
     * guard nothing: declared operator must equal the pinned key byte
     * for byte. */
    @Test
    fun `an operator key that is not the directory-pinned key is refused`() = runTest {
        val raw = manifestBytes(operator = "onym:key:" + "ab".repeat(32))
        try {
            fetcher(raw).fetch(listing())
            fail("a substituted operator key must be refused")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!, e.message!!.contains("directory-pinned"))
        }
    }

    @Test
    fun `a manifest for a different authority is refused`() = runTest {
        val raw = manifestBytes(componentId = "onym:component:someone-else")
        try {
            fetcher(raw).fetch(listing())
            fail("componentId must match the listing")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!, e.message!!.contains("componentId"))
        }
    }

    @Test
    fun `a malformed signature file is refused`() = runTest {
        val raw = manifestBytes()
        val responses = mapOf(
            manifestUrl to raw,
            "$manifestUrl.sig" to "not-a-signature".encodeToByteArray(),
        )
        try {
            OkHttpAuthorityManifestFetcher(servingClient(responses)).fetch(listing())
            fail("a garbage .sig must be refused")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!, e.message!!.contains("signature"))
        }
    }

    @Test
    fun `a missing signature file is refused`() = runTest {
        val raw = manifestBytes()
        try {
            fetcher(raw, signature = null).fetch(listing())
            fail("no detached signature, no manifest")
        } catch (_: ModerationConsentException) {
            // 404 on the .sig — refused, whatever the wording.
        }
    }

    /** A signature by some other key — even a valid Ed25519 one —
     * must not verify against the pinned key. */
    @Test
    fun `a signature by a different key is refused`() = runTest {
        val raw = manifestBytes()
        val rogue = Ed25519KeyPairGenerator().run {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
            generateKeyPair().private as Ed25519PrivateKeyParameters
        }
        val rogueSignature = Ed25519Signer().run {
            init(true, rogue)
            update(raw, 0, raw.size)
            generateSignature()
        }
        try {
            fetcher(raw, signature = rogueSignature).fetch(listing())
            fail("a rogue key's signature must not verify")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!, e.message!!.contains("signature"))
        }
    }

    /** A directory listing whose pinned key is unusable must refuse,
     * not fall back to trusting the manifest's own declaration. */
    @Test
    fun `an unusable directory pin is refused`() = runTest {
        val raw = manifestBytes()
        try {
            fetcher(raw).fetch(listing(pinnedKey = ByteArray(7)))
            fail("a short pinned key must be refused")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!, e.message!!.contains("operator key"))
        }
    }
}
