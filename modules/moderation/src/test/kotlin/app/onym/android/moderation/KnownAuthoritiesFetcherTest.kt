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
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The moderation trust chain's ROOT (onym-android#219): the directory
 * carries the operator-key pins every downstream manifest and verdict
 * check verifies against, so the directory itself must verify against
 * the app-pinned root key — hard-refused when unsigned or tampered,
 * exercised here with a real Ed25519 key against an in-process
 * canned-response client, the same posture as
 * [AuthorityManifestFetcherTest] one link down the chain.
 */
class KnownAuthoritiesFetcherTest {
    private val directoryUrl = "https://directory.example/authorities.json"

    private val rootKey: Ed25519PrivateKeyParameters = run {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        generator.generateKeyPair().private as Ed25519PrivateKeyParameters
    }
    private val rootPublicBase64: String =
        Base64.getEncoder().encodeToString(rootKey.generatePublicKey().encoded)

    private val directoryBytes = """{
        "authorities": [{
            "componentId": "onym:component:test-authority",
            "name": "Test Authority",
            "manifestURL": "https://authority.example/manifest.json",
            "apiBaseURL": "https://authority.example",
            "operatorPublicKeyBase64": "${Base64.getEncoder().encodeToString(ByteArray(32) { 3 })}"
        }]
    }""".encodeToByteArray()

    private fun signatureFile(bytes: ByteArray, key: Ed25519PrivateKeyParameters = rootKey): String {
        val signer = Ed25519Signer().apply {
            init(true, key)
            update(bytes, 0, bytes.size)
        }
        // The published wire contract: base64 + trailing newline.
        return Base64.getEncoder().encodeToString(signer.generateSignature()) + "\n"
    }

    private fun fetcher(
        responses: Map<String, ByteArray?>,
        rootKeyBase64: String = rootPublicBase64,
    ): OkHttpKnownAuthoritiesFetcher {
        val http = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val url = chain.request().url.toString()
                    val body = responses[url]
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (body != null) 200 else 404)
                        .message("canned")
                        .body(
                            (body ?: ByteArray(0)).toResponseBody(
                                "application/json".toMediaType(),
                            ),
                        )
                        .build()
                },
            )
            .build()
        return OkHttpKnownAuthoritiesFetcher(http, directoryUrl, rootKeyBase64)
    }

    @Test
    fun `a signed directory verifies and parses`() = runTest {
        val listings = fetcher(
            mapOf(
                directoryUrl to directoryBytes,
                "$directoryUrl.sig" to signatureFile(directoryBytes).encodeToByteArray(),
            ),
        ).fetchLatest()
        assertEquals(1, listings.size)
        assertEquals("onym:component:test-authority", listings.single().componentId)
    }

    /** The acceptance criterion at its sharpest: no signature, no
     * directory — an unsigned release yields no authorities and
     * therefore no consent. */
    @Test
    fun `a missing signature refuses the directory`() = runTest {
        try {
            fetcher(mapOf(directoryUrl to directoryBytes)).fetchLatest()
            fail("an unsigned directory must refuse")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!, e.message!!.contains("signature"))
        }
    }

    /** One flipped byte in the served document — a substituted
     * authority entry — and the root signature no longer covers it. */
    @Test
    fun `tampered directory bytes refuse`() = runTest {
        val tampered = directoryBytes.decodeToString()
            .replace("Test Authority", "Evil Authority")
            .encodeToByteArray()
        try {
            fetcher(
                mapOf(
                    directoryUrl to tampered,
                    "$directoryUrl.sig" to signatureFile(directoryBytes).encodeToByteArray(),
                ),
            ).fetchLatest()
            fail("tampered bytes must refuse")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!, e.message!!.contains("did not verify"))
        }
    }

    /** A valid signature by the WRONG key is the GitHub-compromise
     * case: whoever can publish releases can sign with their own key,
     * and the pin is what makes that insufficient. */
    @Test
    fun `a signature by a different key refuses`() = runTest {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val rogue = generator.generateKeyPair().private as Ed25519PrivateKeyParameters
        try {
            fetcher(
                mapOf(
                    directoryUrl to directoryBytes,
                    "$directoryUrl.sig" to
                        signatureFile(directoryBytes, key = rogue).encodeToByteArray(),
                ),
            ).fetchLatest()
            fail("a rogue-key signature must refuse")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!, e.message!!.contains("did not verify"))
        }
    }

    @Test
    fun `a garbage signature file refuses`() = runTest {
        try {
            fetcher(
                mapOf(
                    directoryUrl to directoryBytes,
                    "$directoryUrl.sig" to "<html>not a signature</html>".encodeToByteArray(),
                ),
            ).fetchLatest()
            fail("garbage must refuse")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!, e.message!!.contains("signature"))
        }
    }

    /** The verification happens BEFORE parsing: unverified bytes get
     * no interpretation at all, even obviously malformed ones. */
    @Test
    fun `unsigned malformed bytes report the signature, not the parse`() = runTest {
        try {
            fetcher(mapOf(directoryUrl to "not json".encodeToByteArray())).fetchLatest()
            fail("must refuse")
        } catch (e: ModerationConsentException) {
            assertTrue(e.message!!, e.message!!.contains("signature"))
        }
    }

    /** The shipped pin is the published root key, byte for byte
     * (onym-authorities/directory-root-pubkey.txt) — and it must
     * decode to a usable Ed25519 key, so a mangled constant can never
     * ship as "no verification". */
    @Test
    fun `the production pin is a 32-byte key matching the published root`() {
        val raw = Base64.getDecoder()
            .decode(OkHttpKnownAuthoritiesFetcher.DIRECTORY_ROOT_KEY_BASE64)
        assertEquals(32, raw.size)
        assertEquals(
            "mD9k/2Xa1D1DGI0IlczLTea9N8Kefe/IWLOO4OF8Fjw=",
            OkHttpKnownAuthoritiesFetcher.DIRECTORY_ROOT_KEY_BASE64,
        )
    }
}
