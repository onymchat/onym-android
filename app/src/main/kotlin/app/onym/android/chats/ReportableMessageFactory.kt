package app.onym.android.chats

import app.onym.android.group.ChatGroup
import app.onym.android.moderation.ReportableEvidence
import app.onym.android.moderation.ReportableImage
import app.onym.android.moderation.keyReference
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Turns a received [ChatMessage] into disclosable evidence — or
 * refuses. The refusal matters as much as the product: a Report entry
 * must never appear on a message whose proof cannot verify, because
 * everything past the menu ends at an authority that will refuse the
 * filing anyway.
 *
 * Two entry points, deliberately asymmetric (iOS
 * `ReportableMessageFactory` parity):
 *
 *  - [isReportable] is synchronous, for the context-menu gate. It may
 *    over-offer for photo messages (the bytes need a suspend fetch)
 *    but must never refuse what [make] would accept.
 *  - [make] is the full check: rebuild the exact preimage the sender
 *    signed and verify the proof against the sender's stored
 *    `sendingPubkey`. `null` means "cannot be reported", surfaced as
 *    an alert rather than a form that can only fail.
 *
 * Voice, video and album messages are unreportable for now — same as
 * iOS: their multi-blob disclosure UI hasn't landed, and offering the
 * menu without it would file evidence the reporter never saw.
 */
object ReportableMessageFactory {

    /** Cheap gate for the context menu. Optimistic for photos. */
    fun isReportable(message: ChatMessage, group: ChatGroup): Boolean {
        if (message.direction != MessageDirection.INCOMING) return false
        if (message.isSystem) return false
        if (message.voiceAttachment != null) return false
        if (message.videoAttachment != null) return false
        if (message.albumAttachments != null) return false
        if (message.moderationAuthenticityProof == null) return false
        if (group.memberProfiles[message.senderBlsPubkeyHex] == null) return false
        if (message.imageAttachment != null) return true
        return message.body.isNotEmpty()
    }

    /**
     * Full verification + evidence assembly. [attachmentBytes] are the
     * decrypted plaintext of the photo (from
     * [ChatImageLoader.fetchPlaintext]) — required for a photo
     * message, ignored for text.
     */
    fun make(
        message: ChatMessage,
        group: ChatGroup,
        attachmentBytes: ByteArray? = null,
    ): ReportableEvidence? {
        if (message.direction != MessageDirection.INCOMING) return null
        if (message.isSystem) return null
        if (message.voiceAttachment != null) return null
        if (message.videoAttachment != null) return null
        if (message.albumAttachments != null) return null
        val proof = message.moderationAuthenticityProof ?: return null
        val signature = runCatching { Base64.getDecoder().decode(proof) }.getOrNull()
            ?.takeIf { it.size == 64 } ?: return null
        val profile = group.memberProfiles[message.senderBlsPubkeyHex] ?: return null

        val attachment = message.imageAttachment
        val disclosedContent: String
        val images: List<ReportableImage>
        if (attachment != null) {
            val bytes = attachmentBytes ?: return null
            // Recompute, never trust: the commitment the sender signed
            // hashes the plaintext, so the digest is derived from the
            // bytes in hand — mismatched bytes simply fail the
            // signature check below.
            val plaintextSha = sha256Hex(bytes)
            disclosedContent = ChatModerationProof.signedContent(
                messageId = message.id,
                groupId = group.id,
                groupSecret = group.groupSecret,
                sentAtMillis = message.sentAtMillis,
                body = message.body,
                media = listOf(
                    ChatModerationProof.MediaCommitment(
                        blobSha256 = attachment.sha256,
                        mimeType = attachment.mimeType,
                        plaintextSha256 = plaintextSha,
                        plaintextByteLength = bytes.size,
                        width = attachment.width,
                        height = attachment.height,
                    ),
                ),
            )
            images = listOf(ReportableImage(sha256 = plaintextSha, bytes = bytes))
        } else {
            if (message.body.isEmpty()) return null
            disclosedContent = ChatModerationProof.signedContent(
                messageId = message.id,
                groupId = group.id,
                groupSecret = group.groupSecret,
                sentAtMillis = message.sentAtMillis,
                body = message.body,
            )
            images = emptyList()
        }

        val preimageBytes = disclosedContent.toByteArray(Charsets.UTF_8)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(profile.sendingPubkey, 0))
            update(preimageBytes, 0, preimageBytes.size)
        }
        if (!verifier.verifySignature(signature)) return null

        return ReportableEvidence(
            sourceMessageId = message.id.toString().lowercase(Locale.ROOT),
            accused = keyReference(profile.sendingPubkey),
            disclosedContent = disclosedContent,
            authenticityProofBase64 = proof,
            images = images,
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
