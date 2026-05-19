package app.onym.android.inbox

import app.onym.android.chain.ChainStateReading
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupCommitmentBuilder
import app.onym.android.group.GroupInvitationPayload
import app.onym.android.group.GroupRepository
import app.onym.android.group.MemberAnnouncementPayload
import app.onym.android.group.MemberProfile
import app.onym.android.identity.DecryptedEnvelope
import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeDecrypter
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
 *   - PR 83 will add: [app.onym.android.group.GroupInvitationPayload]
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
     *  directory. Pass [app.onym.android.identity.IdentityRepository.identities]
     *  in production. */
    private val identitiesFlow: StateFlow<List<IdentitySummary>>? = null,
    /** Live chain-state reader for PR 89's commitment verification.
     *  When null (test / V1 best-effort), Tyranny payloads still
     *  apply with admin-Ed25519 + envelope-signature gating only. */
    private val chainState: ChainStateReading? = null,
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

        // PR 89: receiver-side commitment verification. For Tyranny
        // groups, the payload's `commitment` MUST match both the
        // recomputed Poseidon root over the wire-shipped members AND
        // the on-chain state. Either mismatch is treated as a forged
        // invitation — drop silently.
        if (groupType == SepGroupType.TYRANNY &&
            !verifyTyrannyInvitation(invitation, tier)
        ) {
            return
        }

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
            sendingPubkey = me.sendingPublicKey,
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

        // PR 89: chain-state check. For Tyranny groups, the announced
        // `commitment + epoch` must match what's actually anchored.
        // Closes the residual spoof path where Bob (with the admin's
        // Ed25519 somehow obtained) ships an announcement with a fake
        // commitment. The chain has the truth.
        if (!verifyTyrannyAnnouncement(payload, group)) return

        val key = payload.newMember.blsPub.toHexLowercase()
        if (group.memberProfiles[key] != null) return  // dedup
        val updated = group.copy(
            memberProfiles = group.memberProfiles + (key to MemberProfile(
                alias = payload.newMember.alias,
                inboxPublicKey = payload.newMember.inboxPub,
                sendingPubkey = payload.newMember.sendingPub,
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

    /**
     * PR 89: validate a Tyranny invitation's commitment against
     * BOTH the wire-shipped state (recomputed Poseidon merkle root)
     * AND the on-chain state.
     *
     * Three failure modes — all return `false`:
     *   - Payload omits `commitment` (pre-PR-88 sender, can't verify).
     *   - Recomputed root != claimed commitment (internally
     *     inconsistent — sender fabricated `members` while copying
     *     a real on-chain commitment).
     *   - On-chain `commitment` != claimed OR on-chain `epoch` !=
     *     claimed (forged commitment that doesn't match anchor).
     *
     * **Known issue (fixed in PR 91):** the recompute path here
     * compares the merkle root to the commitment, but the contract
     * stores `Poseidon(Poseidon(merkle_root, epoch), salt)`. PR 91
     * rewrites this to recompute the FULL Poseidon commitment. Until
     * then, every legitimate Tyranny invitation fails this check —
     * the chain anchor verification still catches forgeries via the
     * second branch.
     *
     * Mirrors `verifyTyrannyInvitation` from onym-ios PR #89.
     */
    private suspend fun verifyTyrannyInvitation(
        invitation: app.onym.android.group.GroupInvitationPayload,
        tier: SepTier,
    ): Boolean {
        // Without a chain reader we treat Tyranny as best-effort;
        // V1 never reaches the on-chain branch in tests.
        val reader = chainState ?: return true
        val claimed = invitation.commitment ?: return false
        // Internal consistency: recompute the FULL Poseidon
        // commitment from `(members, epoch, salt)` and compare. The
        // commitment is `Poseidon(Poseidon(root, epoch), salt)` —
        // NOT just the merkle root. PR 89's original implementation
        // compared the root, which always failed; PR 91 fixes that.
        val recomputed = try {
            val root = GroupCommitmentBuilder.computeMerkleRoot(invitation.members, tier)
            GroupCommitmentBuilder.computePoseidonCommitment(
                poseidonRoot = root,
                epoch = invitation.epoch,
                salt = invitation.salt,
            )
        } catch (_: Throwable) {
            return false
        }
        if (!recomputed.contentEquals(claimed)) return false
        // External anchor.
        val onchain = try {
            reader.tyrannyCommitment(invitation.groupId)
        } catch (_: Throwable) {
            return false
        }
        if (!onchain.commitment.contentEquals(claimed)) return false
        if (onchain.epoch != invitation.epoch) return false
        return true
    }

    /**
     * PR 89: validate a Tyranny [MemberAnnouncementPayload]'s
     * claimed `commitment + epoch` against the on-chain state. Same
     * failure-modes posture as the invitation verifier — any
     * mismatch / missing-field / read-error returns `false` and the
     * announcement is dropped.
     *
     * No recompute here because the announcement only carries one
     * new member, not the full roster. The on-chain check alone is
     * the strong gate.
     *
     * Mirrors `verifyTyrannyAnnouncement` from onym-ios PR #89.
     */
    private suspend fun verifyTyrannyAnnouncement(
        announcement: MemberAnnouncementPayload,
        group: ChatGroup,
    ): Boolean {
        if (group.groupType != SepGroupType.TYRANNY) return true  // best-effort
        val reader = chainState ?: return true
        val claimedCommitment = announcement.commitment ?: return false
        val claimedEpoch = announcement.epoch ?: return false
        val onchain = try {
            reader.tyrannyCommitment(announcement.groupId)
        } catch (_: Throwable) {
            return false
        }
        if (!onchain.commitment.contentEquals(claimedCommitment)) return false
        if (onchain.epoch != claimedEpoch) return false
        return true
    }

    private companion object {
        private val permissiveJson = Json { ignoreUnknownKeys = true }

        private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
            for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
        }
    }
}
