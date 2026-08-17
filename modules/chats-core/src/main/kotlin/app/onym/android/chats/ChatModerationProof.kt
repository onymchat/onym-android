package app.onym.android.chats

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Canonical preimage of the moderation authenticity proof — the exact
 * string the sender signs and a reporter later discloses to an
 * Authority as `EvidenceItem.disclosedContent`.
 *
 * The proof must bind the body to its message identity. A signature
 * over the bare body would be transferable: anyone holding a
 * (body, signature) pair — including a third party who was never a
 * recipient — could file it as evidence for any message, in any group,
 * at any time, and the Authority couldn't tell. Folding `message_id`,
 * `group_binding`, and `sent_at_millis` into the signed bytes pins the
 * proof to one concrete send.
 *
 * A message carrying media signs a v2 preimage, which adds a `media`
 * array committing to each attached blob's exact bytes. Without it an
 * attachment is unreportable in principle, not merely unimplemented:
 * nothing else in the system is a sender commitment to those bytes.
 *
 * ## Byte-exactness
 *
 * The Authority never reconstructs these bytes — it verifies the
 * signature over `disclosedContent` verbatim — so the only
 * implementations that must agree byte-for-byte are the signing
 * sender and the verifying recipient, **across platforms**: an iOS
 * recipient rebuilds this preimage with Swift's `JSONEncoder`
 * (`.sortedKeys`, slashes escaped) and must land on exactly the bytes
 * this object signed. Two properties are load-bearing:
 *
 *  - Keys are in **UTF-8 byte order** (`body`, `group_binding`,
 *    `media`, `message_id`, `proof_version`, `sent_at_millis`).
 *  - Forward slashes are **escaped** (`image\/jpeg`) — Swift
 *    `JSONEncoder`'s default, which the iOS preimage encoder
 *    deliberately keeps. kotlinx.serialization never escapes `/`,
 *    which is why this preimage is hand-assembled rather than run
 *    through a `Json` instance.
 *
 * Pinned byte-for-byte against the iOS golden vectors
 * (`ChatModerationProofTests.swift`) and the authority's normative
 * fixture (onym-moderation `authority/fixtures/media-commitment-v2.json`)
 * by `ChatModerationProofTest`.
 *
 * Mirrors `ChatModerationProof.swift` from onym-ios.
 */
object ChatModerationProof {
    /** Version discriminator inside the preimage so a future shape
     *  change can't be confused with this one. */
    const val VERSION: Int = 1

    /** Version emitted when the message carries media. A v2 preimage
     *  is a v1 preimage plus a `media` array committing to the exact
     *  bytes of every blob the message references. The version — not
     *  the mere presence of the field — is what a verifier dispatches
     *  on. */
    const val MEDIA_VERSION: Int = 2

    /**
     * The sender's commitment to one attached blob's exact bytes.
     *
     * Two hashes, because they answer different questions.
     * [plaintextSha256] is over the bytes a recipient actually
     * decrypts and sees, so it is what an Authority re-derives from
     * disclosed evidence without ever needing the attachment key.
     * [blobSha256] is the ciphertext digest that already addresses
     * the blob on the blob server, so the commitment stays tied to
     * the stored object a recipient really fetched.
     *
     * "Plaintext" means the encoded bytes that travelled —
     * [ChatImageEncoder]'s downscaled, re-encoded JPEG — not the
     * original camera file. The evidence is what was received, which
     * is the only thing a recipient can honestly disclose.
     *
     * [width]/[height] are omitted where they have no meaning
     * (voice), which drops the keys from the preimage entirely rather
     * than encoding a placeholder a verifier might read as real.
     */
    data class MediaCommitment(
        val blobSha256: String,
        val mimeType: String,
        val plaintextSha256: String,
        val plaintextByteLength: Int,
        val width: Int? = null,
        val height: Int? = null,
    )

    /**
     * The preimage for a message that may carry media. Empty [media]
     * yields the v1 shape; a non-empty one yields v2. Media order is
     * the message's own order and is part of the signed bytes, so an
     * album cannot be reordered after the fact.
     */
    fun signedContent(
        messageId: UUID,
        groupId: String,
        groupSecret: ByteArray,
        sentAtMillis: Long,
        body: String,
        media: List<MediaCommitment> = emptyList(),
    ): String {
        val messageIdString = messageId.toString().lowercase(Locale.ROOT)
        val binding = groupBinding(groupId, groupSecret, messageIdString)
        return buildString {
            append("{\"body\":").append(quoted(body))
            append(",\"group_binding\":").append(quoted(binding))
            if (media.isNotEmpty()) {
                append(",\"media\":[")
                media.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    append("{\"blob_sha256\":").append(quoted(item.blobSha256))
                    item.height?.let { append(",\"height\":").append(it) }
                    append(",\"mime_type\":").append(quoted(item.mimeType))
                    append(",\"plaintext_byte_length\":").append(item.plaintextByteLength)
                    append(",\"plaintext_sha256\":").append(quoted(item.plaintextSha256))
                    item.width?.let { append(",\"width\":").append(it) }
                    append('}')
                }
                append(']')
            }
            append(",\"message_id\":").append(quoted(messageIdString))
            append(",\"proof_version\":").append(if (media.isEmpty()) VERSION else MEDIA_VERSION)
            append(",\"sent_at_millis\":").append(sentAtMillis)
            append('}')
        }
    }

    /**
     * The keyed group binding shared by every preimage version:
     * SHA-256 over `"onym-moderation-proof-v1|" ‖ secret ‖
     * "|<groupId>|<messageId>"`. The raw 32-byte secret is the input
     * (not its hex), and the domain string stays `v1` at preimage v2 —
     * it separates this hash from other uses of the group secret, not
     * preimage versions.
     *
     * Binding through the secret rather than the raw group id keeps
     * the disclosed content free of any identifier an outsider can
     * resolve: group ids are public on-chain lookup keys, so a keyless
     * hash could be broken by trial-hashing one candidate per group.
     */
    internal fun groupBinding(
        groupId: String,
        groupSecret: ByteArray,
        messageIdString: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("onym-moderation-proof-v1|".toByteArray(Charsets.UTF_8))
        digest.update(groupSecret)
        digest.update("|$groupId|$messageIdString".toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * JSON string literal exactly as Swift's `JSONEncoder` emits it
     * (without `.withoutEscapingSlashes`): `"` `\` and `/` escaped,
     * the five short control escapes, remaining control characters as
     * lowercase `\u00xx`, everything else raw UTF-8.
     */
    private fun quoted(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '/' -> append("\\/")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (ch < ' ') append("\\u%04x".format(ch.code))
                    else append(ch)
            }
        }
        append('"')
    }
}
