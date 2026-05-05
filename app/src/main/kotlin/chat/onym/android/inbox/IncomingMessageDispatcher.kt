package chat.onym.android.inbox

import chat.onym.android.chain.SepGroupType
import chat.onym.android.chain.SepTier
import chat.onym.android.group.ChatGroup
import chat.onym.android.group.GroupInvitationPayload
import chat.onym.android.group.GroupRepository
import chat.onym.android.group.MemberAnnouncementPayload
import chat.onym.android.group.MemberProfile
import chat.onym.android.identity.DecryptedEnvelope
import chat.onym.android.identity.IdentityId
import chat.onym.android.identity.IdentitySummary
import chat.onym.android.identity.InvitationEnvelopeDecrypter
import kotlinx.coroutines.flow.StateFlow
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
    /** Receiver's own identities, keyed by [IdentityId]. The PR-83
     *  invitation fast-path looks up the recipient's
     *  [IdentitySummary] here to backfill their own
     *  [MemberProfile] entry into the freshly-materialized group's
     *  directory. Pass [chat.onym.android.identity.IdentityRepository.identities]
     *  in production. */
    private val identitiesFlow: StateFlow<List<IdentitySummary>>? = null,
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

        // Fast path: GroupInvitationPayload — materialize a local
        // group under [ownerIdentityId]. Skips the invitations queue
        // because the group is now visible directly in the chats list.
        val invitation = tryDecodeInvitation(envelope.plaintext)
        if (invitation != null) {
            materializeGroup(invitation, ownerIdentityId, envelope.senderEd25519PublicKey)
            return
        }

        // Plaintext didn't match any known payload — fall through.
        fallThrough(messageId, ownerIdentityId, payload, receivedAt)
    }

    /**
     * Materialize a local [ChatGroup] from an inbound
     * [GroupInvitationPayload]. Idempotent on `groupId` —
     * [GroupRepository.insert] delegates to `insertOrUpdate`, so a
     * re-delivery of the same invitation overwrites in place rather
     * than minting a duplicate row.
     *
     * The `memberProfiles` directory is the union of:
     *   - whatever the sender shipped on the wire (PR 82),
     *   - the receiver's own profile (looked up from
     *     [identitiesFlow]). The "self last" ordering means a
     *     sender that mistakenly includes us under our own BLS key
     *     gets overwritten by our locally-trusted alias + inbox pub.
     *
     * Skipped when `tier_raw` / `group_type_raw` don't decode (older
     * or future wire versions) — better to drop the message than
     * materialize a partial group.
     */
    private suspend fun materializeGroup(
        invitation: GroupInvitationPayload,
        ownerIdentityId: IdentityId,
        senderEd25519PublicKey: ByteArray?,
    ) {
        val tier = SepTier.entries.firstOrNull { it.rawValue == invitation.tierRaw } ?: return
        val groupType = SepGroupType.fromWire(invitation.groupTypeRaw) ?: return

        // Wire-shipped profiles first; receiver's own entry overwrites
        // any same-keyed wire entry (sender shouldn't be able to
        // assert our alias).
        val profiles = (invitation.memberProfiles ?: emptyMap()).toMutableMap()
        selfMemberProfileEntry(ownerIdentityId)?.let { (key, profile) ->
            profiles[key] = profile
        }

        // PR 84: stamp the inviting envelope's Ed25519 pubkey as the
        // group's admin signing key. Subsequent
        // `MemberAnnouncementPayload` apply paths verify the sender
        // matches. `null` for Anarchy / OneOnOne (no admin), or when
        // the envelope shipped without a signature block.
        val adminEd25519PubkeyHex: String? = when (groupType) {
            SepGroupType.ANARCHY, SepGroupType.ONE_ON_ONE -> null
            else -> senderEd25519PublicKey?.toHexLowercase()
        }

        val groupIdHex = invitation.groupId.toHexLowercase()
        val group = ChatGroup(
            id = groupIdHex,
            name = invitation.name,
            groupSecret = invitation.groupSecret,
            createdAtMillis = System.currentTimeMillis(),
            members = invitation.members,
            memberProfiles = profiles,
            epoch = invitation.epoch,
            salt = invitation.salt,
            commitment = invitation.commitment,
            tier = tier,
            groupType = groupType,
            adminPubkeyHex = invitation.adminPubkeyHex,
            adminEd25519PubkeyHex = adminEd25519PubkeyHex,
            // Sender already anchored before sending the invite, so
            // by the time it lands the group is on chain.
            isPublishedOnChain = true,
            ownerIdentityId = ownerIdentityId.value,
        )
        groupRepository.insert(group)
    }

    private fun selfMemberProfileEntry(
        identityId: IdentityId,
    ): Pair<String, MemberProfile>? {
        val summaries = identitiesFlow?.value ?: return null
        val me = summaries.firstOrNull { it.id == identityId } ?: return null
        val key = me.blsPublicKey.toHexLowercase()
        return key to MemberProfile(
            alias = me.name,
            inboxPublicKey = me.inboxPublicKey,
        )
    }

    private fun tryDecodeInvitation(bytes: ByteArray): GroupInvitationPayload? = try {
        permissiveJson.decodeFromString(
            GroupInvitationPayload.serializer(),
            bytes.toString(Charsets.UTF_8),
        )
    } catch (_: Throwable) {
        null
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
        senderEd25519PublicKey: ByteArray?,
    ) {
        val groups = groupRepository.snapshots.value
        val group = groups.firstOrNull {
            it.groupIdBytes.contentEquals(payload.groupId)
        } ?: return

        // PR 84 trust check: announcement must be signed by the
        // group's known admin. Skipped (best-effort) when the group
        // has no stored admin Ed25519 — happens for governance
        // models without an admin (Anarchy / OneOnOne) or pre-PR-84
        // rows that materialized before the field existed.
        val storedAdmin = group.adminEd25519PubkeyHex
        if (storedAdmin != null) {
            val sender = senderEd25519PublicKey ?: return
            val senderHex = sender.toHexLowercase()
            if (senderHex != storedAdmin.lowercase()) return
        }

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
