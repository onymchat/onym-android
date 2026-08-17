package app.onym.android.chats

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.UUID

/**
 * The exact bytes a sender signs as a moderation authenticity proof.
 *
 * These are golden-byte tests on purpose. The preimage is verified by
 * an Authority written in another language (Rust) and rebuilt by an
 * iOS recipient (Swift `JSONEncoder`), and neither ever parses these
 * bytes back through this code — drift here does not surface as a
 * parse error anywhere; it surfaces as "every report against an
 * Android sender is invalid", silently, in production.
 *
 * The vectors are copied verbatim from the iOS golden tests
 * (`ChatModerationProofTests.swift`) and the authority's normative
 * fixture (onym-moderation `authority/fixtures/media-commitment-v2.json`).
 * If these fail, this client drifted — the fixtures do not move to
 * accommodate it.
 */
class ChatModerationProofTest {
    private val messageId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val groupId = "group-1"
    private val groupSecret = ByteArray(32) { 0x07 }
    private val sentAtMillis = 1_785_580_800_123L

    /** Computed independently of the implementation — pinned in the
     *  iOS tests and the authority fixture alike. */
    private val binding = "18413f675da1f5410fa8e199b799c2bf3700575ddac201c9a8d2dfdf8327ce6a"

    private val blobSha = "0f".repeat(32)
    private val plaintextSha = "1a".repeat(32)

    private fun imageCommitment() = ChatModerationProof.MediaCommitment(
        blobSha256 = blobSha,
        mimeType = "image/jpeg",
        plaintextSha256 = plaintextSha,
        plaintextByteLength = 421_337,
        width = 1200,
        height = 1600,
    )

    private fun content(
        body: String = "hello",
        media: List<ChatModerationProof.MediaCommitment> = emptyList(),
    ): String = ChatModerationProof.signedContent(
        messageId = messageId,
        groupId = groupId,
        groupSecret = groupSecret,
        sentAtMillis = sentAtMillis,
        body = body,
        media = media,
    )

    /** A message with no media must produce v1 bytes, exactly. Every
     *  text proof already signed by a shipped client depends on this,
     *  so it is the one assertion that must never be "updated to
     *  match" a new implementation. */
    @Test
    fun textMessageProducesTheExactV1Preimage() {
        val expected = "{\"body\":\"hello\",\"group_binding\":\"$binding\"," +
            "\"message_id\":\"00000000-0000-0000-0000-000000000001\"," +
            "\"proof_version\":1,\"sent_at_millis\":1785580800123}"
        assertEquals(expected, content())
    }

    /** The full v2 shape: `media` sorted into place between
     *  `group_binding` and `message_id`, entry keys in byte order,
     *  and the escaped slash in the MIME type. */
    @Test
    fun mediaMessageProducesTheExactV2Preimage() {
        val expected = "{\"body\":\"hello\",\"group_binding\":\"$binding\"," +
            "\"media\":[{\"blob_sha256\":\"$blobSha\",\"height\":1600," +
            "\"mime_type\":\"image\\/jpeg\",\"plaintext_byte_length\":421337," +
            "\"plaintext_sha256\":\"$plaintextSha\",\"width\":1200}]," +
            "\"message_id\":\"00000000-0000-0000-0000-000000000001\"," +
            "\"proof_version\":2,\"sent_at_millis\":1785580800123}"
        assertEquals(expected, content(media = listOf(imageCommitment())))
    }

    /** Audio has no pixel dimensions, so those keys are absent rather
     *  than carrying a zero a verifier might read as a real value. */
    @Test
    fun mediaWithoutDimensionsOmitsWidthAndHeight() {
        val voice = ChatModerationProof.MediaCommitment(
            blobSha256 = blobSha,
            mimeType = "audio/mp4",
            plaintextSha256 = plaintextSha,
            plaintextByteLength = 9,
        )
        val expected = "{\"body\":\"\",\"group_binding\":\"$binding\"," +
            "\"media\":[{\"blob_sha256\":\"$blobSha\",\"mime_type\":\"audio\\/mp4\"," +
            "\"plaintext_byte_length\":9,\"plaintext_sha256\":\"$plaintextSha\"}]," +
            "\"message_id\":\"00000000-0000-0000-0000-000000000001\"," +
            "\"proof_version\":2,\"sent_at_millis\":1785580800123}"
        assertEquals(expected, content(body = "", media = listOf(voice)))
    }

    /** The authority's published cross-implementation vector: the
     *  preimage matches byte-for-byte AND the fixture's Ed25519
     *  signature verifies over exactly our bytes — proving an
     *  Android-built preimage is interchangeable with the iOS one
     *  the authority's verifier was pinned against. */
    @Test
    fun matchesTheAuthorityNormativeFixtureIncludingItsSignature() {
        val preimage = content(media = listOf(imageCommitment()))
        val publicKey = Ed25519PublicKeyParameters(
            "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c".hexBytes(),
            0,
        )
        val signature = Base64.getDecoder().decode(
            "+lXjQDhSsaxssuZj6OgodqrKIbmjRz5mxkyE46hmsvBAj5DDsJVhDnuZKXroRtCNHRYLq5StHjrHe5OwLwHXCQ==",
        )
        val message = preimage.toByteArray(Charsets.UTF_8)
        val verifier = Ed25519Signer().apply {
            init(false, publicKey)
            update(message, 0, message.size)
        }
        assertTrue(
            "authority fixture signature must verify over the Android-built preimage",
            verifier.verifySignature(signature),
        )
    }

    /** Bodies pass through Swift-JSONEncoder-compatible escaping:
     *  slashes and quotes escaped, control characters as short
     *  escapes, non-ASCII raw. A recipient on either platform must
     *  land on these bytes to verify the signature. */
    @Test
    fun bodyEscapingMatchesSwiftJsonEncoder() {
        val body = "a/b \"c\" \\ \n\t\r\u0001 é🙂"
        val produced = content(body = body)
        assertTrue(
            produced.startsWith(
                "{\"body\":\"a\\/b \\\"c\\\" \\\\ \\n\\t\\r\\u0001 é🙂\"",
            ),
        )
    }

    /** Media order is the message's own order and is signed. */
    @Test
    fun mediaOrderIsPartOfTheSignedBytes() {
        val first = imageCommitment()
        val second = imageCommitment().copy(plaintextByteLength = 1)
        assertTrue(content(media = listOf(first, second)) != content(media = listOf(second, first)))
    }

    private fun String.hexBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
