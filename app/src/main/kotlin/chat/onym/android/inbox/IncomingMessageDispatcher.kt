package chat.onym.android.inbox

import chat.onym.android.group.ChatGroup
import chat.onym.android.group.GroupRepository
import chat.onym.android.group.MemberAnnouncementPayload
import chat.onym.android.group.MemberProfile
import chat.onym.android.identity.ActiveIdentityProvider
import chat.onym.android.identity.DecryptedEnvelope
import chat.onym.android.identity.IdentityId
import chat.onym.android.identity.InvitationEnvelopeDecrypter
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Receive-side fan-out target for the inbox pump. Inspects every
 * inbound message after decryption and routes it to the right
 * destination:
 *
 *   - [MemberAnnouncementPayload] → applied directly to the matching
 *     local [ChatGroup.memberProfiles]. Never lands in the
 *     invitations queue.
 *   - PR 83 will add: [chat.onym.android.group.GroupInvitationPayload]
 *     → materializes a local [ChatGroup] under the recipient identity.
 *   - Anything else (unknown / undecryptable plaintext) → persisted
 *     as an opaque [IncomingInvitation] for the legacy display
 *     pipeline. Safety-net: ciphertext we can't open at receive time
 *     (wrong recipient, corrupted envelope) still gets a chance via
 *     [InvitationDecryptor] later.
 *
 * ## Cost
 *
 * Every inbound message is decrypted at receive time (one extra
 * X25519/AES-GCM op per message). For the low-volume Onym inbox
 * this is negligible; the simplification gain — never leaking an
 * announcement or a stale invitation into the queue — is worth it.
 *
 * Mirrors `IncomingMessageDispatcher.swift` from onym-ios PR #80.
 */
class IncomingMessageDispatcher(
    private val envelopeDecrypter: InvitationEnvelopeDecrypter,
    private val groupRepository: GroupRepository,
    private val invitationsRepository: IncomingInvitationsRepository,
) {

    suspend fun dispatch(
        messageId: String,
        ownerIdentityId: IdentityId,
        payload: ByteArray,
        receivedAt: Instant,
    ) {
        // Decrypt once at receive time and grab the sender's Ed25519
        // pubkey at the same hop — fast paths use it for provenance
        // (announcement: verify against stored admin in PR 84;
        // invitation: stamp into the materialized group in PR 83+84).
        val envelope: DecryptedEnvelope = try {
            envelopeDecrypter.decryptInvitationWithSender(
                envelopeBytes = payload,
                asIdentity = ownerIdentityId,
            )
        } catch (_: Throwable) {
            // Couldn't decrypt — fall through to the legacy queue
            // (ciphertext might be addressed to a different identity
            // and decryptable later).
            fallThrough(messageId, ownerIdentityId, payload, receivedAt)
            return
        }

        // Fast path: MemberAnnouncementPayload — incremental roster
        // delta for an existing local group.
        val announcement = tryDecodeAnnouncement(envelope.plaintext)
        if (announcement != null) {
            applyAnnouncement(announcement, envelope.senderEd25519PublicKey)
            return
        }

        // No invitation fast-path yet — that's PR 83. For now anything
        // that isn't an announcement falls through to the legacy queue.
        fallThrough(messageId, ownerIdentityId, payload, receivedAt)
    }

    private suspend fun fallThrough(
        messageId: String,
        ownerIdentityId: IdentityId,
        payload: ByteArray,
        receivedAt: Instant,
    ) {
        invitationsRepository.recordIncoming(
            id = messageId,
            payload = payload,
            receivedAt = receivedAt,
            ownerIdentityId = ownerIdentityId,
        )
    }

    /**
     * Idempotent merge of one announced member into the matching
     * local group's [ChatGroup.memberProfiles]. No-op when:
     *
     *   - The group isn't on this device (joiner whose local
     *     materialization hasn't shipped, or stale announcement for
     *     an unrelated group).
     *   - The member is already known under the same BLS pubkey hex
     *     (re-delivery, or the admin's own approve loop
     *     re-broadcasting).
     *
     * PR 84 adds the admin-Ed25519 trust check. Until then the only
     * provenance gate is the outer envelope's signature (verified by
     * [InvitationEnvelopeDecrypter]).
     */
    private suspend fun applyAnnouncement(
        payload: MemberAnnouncementPayload,
        @Suppress("UNUSED_PARAMETER") senderEd25519PublicKey: ByteArray?,
    ) {
        val groups = groupRepository.snapshots.value
        val group = groups.firstOrNull {
            it.groupIdBytes.contentEquals(payload.groupId)
        } ?: return

        val key = payload.newMember.blsPub.toHexLowercase()
        if (group.memberProfiles[key] != null) return  // dedup
        val updated = group.copy(
            memberProfiles = group.memberProfiles + (key to MemberProfile(
                alias = payload.newMember.alias,
                inboxPublicKey = payload.newMember.inboxPub,
            )),
        )
        groupRepository.insert(updated)
    }

    private fun tryDecodeAnnouncement(bytes: ByteArray): MemberAnnouncementPayload? = try {
        permissiveJson.decodeFromString(
            MemberAnnouncementPayload.serializer(),
            bytes.toString(Charsets.UTF_8),
        )
    } catch (_: Throwable) {
        null
    }

    private companion object {
        private val permissiveJson = Json { ignoreUnknownKeys = true }

        private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
            for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
        }
    }
}
