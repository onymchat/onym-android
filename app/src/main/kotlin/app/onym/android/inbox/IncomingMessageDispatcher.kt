package app.onym.android.inbox

import app.onym.android.chain.ChainStateReading
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.chats.ChatMessage
import app.onym.android.chats.ChatMessagePayload
import app.onym.android.chats.ChatMessageVariant
import app.onym.android.chats.ChatReceiptPayload
import app.onym.android.chats.ChatReceiptSending
import app.onym.android.chats.MessageDirection
import app.onym.android.chats.NoopChatReceiptSender
import app.onym.android.chats.MessageRepository
import app.onym.android.chats.MessageStatus
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupAvatarPayload
import app.onym.android.group.GroupCommitmentBuilder
import app.onym.android.group.GroupInvitationPayload
import app.onym.android.group.GroupInviteOfferPayload
import app.onym.android.group.GroupRepository
import app.onym.android.group.GroupStateRefreshRequest
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
    /** Persistence target for incoming chat messages. The dispatcher
     *  looks up the sender's [MemberProfile.sendingPubkey], verifies
     *  the envelope's Ed25519 signer matches, and persists via
     *  [MessageRepository.append] (idempotent on
     *  [ChatMessage.id] so Nostr re-delivery is a no-op).
     *  Optional so existing dispatcher tests that don't exercise the
     *  chat-message path can keep their construction sites unchanged. */
    private val messageRepository: MessageRepository? = null,
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
    /** Receive-side sink for decoded [GroupInviteOfferPayload]s — the
     *  push counterpart to the deeplink join flow. An offer lands here
     *  as a [PendingInvite] awaiting the user's explicit Accept (which
     *  ships a [app.onym.android.group.JoinRequestPayload]) or dismiss.
     *  It grants nothing and never materializes a group: membership
     *  only follows the invitee's accept + the admin's explicit
     *  on-chain approve. Defaulted to a fresh store so the many
     *  existing dispatcher tests that don't exercise the offer path
     *  keep their construction sites unchanged; production
     *  ([app.onym.android.OnymApplication]) passes the shared store. */
    private val pendingInvites: PendingInvitesRecording = PendingInvitesStore(),
    /** Seam for the verify-at-current state machine (Option 2, PR 159).
     *  A stale Tyranny snapshot (chain advanced past its epoch) is
     *  deferred here on the invitee side; inbound
     *  [GroupStateRefreshRequest]s are answered here on the admin side.
     *  Defaulted to a no-op for the same reason as [pendingInvites]. */
    private val groupStateRefresher: GroupStateRefreshing = NoopGroupStateRefresher(),
    /** Ships a delivered receipt back to a chat message's sender the
     *  moment we persist it. Defaulted to a no-op so existing
     *  dispatcher tests keep their construction sites unchanged. */
    private val receiptSender: ChatReceiptSending = NoopChatReceiptSender(),
    /** Symmetric read-receipt gate: an inbound READ receipt only
     *  raises a message to [MessageStatus.READ] when this device also
     *  sends read receipts. Defaulted to `true` (the shipping
     *  default). */
    private val readReceiptsEnabled: () -> Boolean = { true },
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

        // Fast path 0: GroupInviteOfferPayload — a push invitation.
        // Decoded + queued for the user's explicit Accept; it carries
        // no epoch / commitment / roster, so it never materializes a
        // group or touches the on-chain commitment. Tried first because
        // its required `inviter_alias` + `intro_pub` + `offer_version`
        // keys are unique to this type — no other inbox payload decodes
        // as one.
        val offer = tryDecodeOffer(envelope.plaintext)
        if (offer != null) {
            recordOffer(offer, messageId, ownerIdentityId, receivedAt)
            return
        }

        // Fast path 0.5: GroupStateRefreshRequest — a member asking the
        // admin for the current group state (Option 2 verify-at-current,
        // PR 159). Admin-side; delegated to the verifier, which gates on
        // the requester being a current member before disclosing the
        // salt. Its required `refresh_group_id` + `requester_inbox_pub`
        // keys are unique, so the trial-decode is unambiguous.
        val refresh = tryDecodeRefreshRequest(envelope.plaintext)
        if (refresh != null) {
            groupStateRefresher.handleRefreshRequest(
                refresh,
                ownerIdentityId,
                envelope.senderEd25519PublicKey,
            )
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

        // Fast path: GroupAvatarPayload — admin-signed group-photo
        // change. Decoded BEFORE the chat-message branch; its unique
        // `avatar_*` keys keep it from colliding with a chat message in
        // either direction. Applies (or clears) the local group's photo
        // after the admin-Ed25519 trust gate.
        val avatarUpdate = tryDecodeGroupAvatar(envelope.plaintext)
        if (avatarUpdate != null) {
            applyAvatar(avatarUpdate, envelope.senderEd25519PublicKey)
            return
        }

        // Fast path: ChatReceiptPayload — a peer acking one of OUR
        // messages as delivered / read. Wire-shape-disjoint from every
        // other payload (unique `kind` + `message_ids` keys), so the
        // trial-decode can't steal a different payload even under
        // ignoreUnknownKeys.
        val receipt = tryDecodeReceipt(envelope.plaintext)
        if (receipt != null) {
            applyReceipt(receipt, ownerIdentityId)
            return
        }

        // Fast path: ChatMessagePayload — body of the chat thread.
        // Verifies the envelope's Ed25519 signer matches the claimed
        // sender's [MemberProfile.sendingPubkey] (insider-spoof
        // defense, PR A3) then persists via [messageRepository].
        val chatMessage = tryDecodeChatMessage(envelope.plaintext)
        if (chatMessage != null) {
            persistChatMessage(
                payload = chatMessage,
                ownerIdentityId = ownerIdentityId,
                senderEd25519PublicKey = envelope.senderEd25519PublicKey,
                receivedAt = receivedAt,
            )
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

        // Receiver-side verification (Option 2, PR 159). For Tyranny
        // groups the snapshot's commitment must match the recomputed
        // Poseidon root AND the on-chain commitment at an EXACT epoch.
        // When the chain has advanced past the snapshot, we can't
        // byte-verify it — so rather than trust an unverifiable snapshot
        // (which would let a self-consistent fake of a young group
        // materialize) we defer to the verifier, which asks the admin
        // for the current state and surfaces a "couldn't verify" state
        // if the admin is unreachable. Non-Tyranny groups skip
        // verification (no admin-anchored update path; trust falls back
        // to the sender's envelope signature).
        if (groupType == SepGroupType.TYRANNY) {
            when (verifyTyrannyInvitation(invitation, tier)) {
                TyrannyInvitationVerification.VERIFIED -> {} // materialize below
                TyrannyInvitationVerification.REJECT -> return
                TyrannyInvitationVerification.STALE_NEEDS_REFRESH -> {
                    groupStateRefresher.deferVerification(invitation, ownerIdentityId)
                    return
                }
            }
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
            // "If present" semantics: a pre-avatar sender omits the key
            // and the group materializes photo-less.
            avatar = invitation.avatar,
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

    private fun tryDecodeOffer(bytes: ByteArray): GroupInviteOfferPayload? = try {
        permissiveJson.decodeFromString(
            GroupInviteOfferPayload.serializer(),
            bytes.toString(Charsets.UTF_8),
        )
    } catch (_: Throwable) {
        null
    }

    private fun tryDecodeRefreshRequest(bytes: ByteArray): GroupStateRefreshRequest? = try {
        permissiveJson.decodeFromString(
            GroupStateRefreshRequest.serializer(),
            bytes.toString(Charsets.UTF_8),
        )
    } catch (_: Throwable) {
        null
    }

    /**
     * Queue a decoded push offer for the user's explicit Accept. Keyed
     * by the inbound Nostr event id so a re-delivered offer (replaceable
     * events are re-fetched on every relaunch) is idempotent in the
     * store. Never materializes a group — that only follows the
     * invitee's accept + the admin's explicit on-chain approve.
     */
    private suspend fun recordOffer(
        offer: GroupInviteOfferPayload,
        messageId: String,
        ownerIdentityId: IdentityId,
        receivedAt: Instant,
    ) {
        pendingInvites.record(
            PendingInvite(
                id = messageId,
                ownerIdentityId = ownerIdentityId,
                introPublicKey = offer.introPublicKey,
                groupId = offer.groupId,
                groupName = offer.groupName,
                inviterAlias = offer.inviterAlias,
                receivedAt = receivedAt,
            ),
        )
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

        // Dedup BEFORE any chain read. The dedup key is BLS pubkey hex,
        // mirroring the producer-side dictionary key in
        // `JoinRequestApprover`. Every relay reconnect replays the full
        // inbox, so an already-applied announcement is re-delivered on
        // each launch — bailing here keeps those replays from each firing
        // a `get_commitment` against the relayer (the launch-time storm).
        // Cheap, local, and idempotent.
        val key = payload.newMember.blsPub.toHexLowercase()
        if (group.memberProfiles[key] != null) return  // dedup

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
     * Apply an inbound [GroupAvatarPayload] to the matching local
     * group's [ChatGroup.avatar]. No-op when:
     *
     *   - The group isn't on this device (drop unknown group ids).
     *   - The trust gate fails: a group with a stored admin Ed25519 key
     *     requires the envelope's verified signer to equal it
     *     (lowercased hex). Skipped (best-effort) only for admin-less
     *     governance models / legacy rows with no stored admin — same
     *     rule as [applyAnnouncement]. There is NO on-chain commitment
     *     check; the avatar isn't part of the cryptographic group state.
     *   - The stored avatar already equals the incoming bytes
     *     (idempotent re-delivery).
     *
     * Otherwise sets the avatar — or clears it when the payload omits
     * the photo (absent = removal) — and persists. `avatar_sender_bls_hex`
     * is informational; never used to authenticate.
     */
    private suspend fun applyAvatar(
        payload: GroupAvatarPayload,
        senderEd25519PublicKey: ByteArray?,
    ) {
        val groups = groupRepository.snapshots.value
        val group = groups.firstOrNull {
            it.groupIdBytes.contentEquals(payload.groupId)
        } ?: return

        // Trust gate (same as member announcements): the change must be
        // signed by the group's known admin.
        val storedAdmin = group.adminEd25519PubkeyHex
        if (storedAdmin != null) {
            val sender = senderEd25519PublicKey ?: return
            if (sender.toHexLowercase() != storedAdmin.lowercase()) return
        }

        // Idempotency: no-op when the stored avatar already matches
        // (both absent, or byte-equal).
        val current = group.avatar
        val incoming = payload.avatar
        val unchanged = if (current == null || incoming == null) {
            current == null && incoming == null
        } else {
            current.contentEquals(incoming)
        }
        if (unchanged) return

        groupRepository.insert(group.copy(avatar = incoming))
    }

    private fun tryDecodeGroupAvatar(bytes: ByteArray): GroupAvatarPayload? = try {
        permissiveJson.decodeFromString(
            GroupAvatarPayload.serializer(),
            bytes.toString(Charsets.UTF_8),
        )
    } catch (_: Throwable) {
        null
    }

    private fun tryDecodeChatMessage(bytes: ByteArray): ChatMessagePayload? = try {
        permissiveJson.decodeFromString(
            ChatMessagePayload.serializer(),
            bytes.toString(Charsets.UTF_8),
        )
    } catch (_: Throwable) {
        null
    }

    private fun tryDecodeReceipt(bytes: ByteArray): ChatReceiptPayload? = try {
        permissiveJson.decodeFromString(
            ChatReceiptPayload.serializer(),
            bytes.toString(Charsets.UTF_8),
        )
    } catch (_: Throwable) {
        null
    }

    /**
     * Apply an inbound receipt: raise the acked outgoing messages to
     * DELIVERED / READ (monotonic — [MessageRepository.upgradeStatus]
     * enforces the ladder). READ receipts are honored only when this
     * device also sends them (symmetric).
     */
    private suspend fun applyReceipt(receipt: ChatReceiptPayload, ownerIdentityId: IdentityId) {
        val messages = messageRepository ?: return
        val newStatus = when (receipt.kind) {
            ChatReceiptPayload.Kind.DELIVERED -> MessageStatus.DELIVERED
            ChatReceiptPayload.Kind.READ -> {
                if (!readReceiptsEnabled()) return
                MessageStatus.READ
            }
        }
        for (id in receipt.messageIds) {
            // The receipt acks OUR (this inbox's identity's) outgoing
            // messages — scope the upgrade to that owner.
            messages.upgradeStatus(id, ownerIdentityId.value, newStatus)
        }
    }

    /**
     * Persist an inbound chat message after running the full trust
     * chain. Drops silently (no fall-through) on any failure — the
     * envelope decrypted to a well-formed chat payload, so it isn't
     * a legacy-queue safety-net candidate.
     *
     * Trust chain:
     *   1. Envelope must have been signed
     *      ([senderEd25519PublicKey] non-null). Anonymous chat
     *      messages aren't part of the V1 trust model.
     *   2. Group lookup: payload's `groupId` must map to a local
     *      [ChatGroup] owned by [ownerIdentityId]. Goes through
     *      [GroupRepository.findForOwner] so the lookup is correct
     *      even when [ownerIdentityId] isn't the currently-active
     *      identity (multi-identity inbox fan-out delivers messages
     *      to each identity's tag independently).
     *   3. Sender lookup: payload's `senderBlsPubkeyHex` must
     *      resolve to a known [MemberProfile] on the group.
     *   4. Insider-spoof check: the envelope's verified Ed25519
     *      signer must bytes-equal that member's stored
     *      [MemberProfile.sendingPubkey]. Closes the gap PR A3 set
     *      up; without this, any group member could write another
     *      member's BLS hex into the payload and the receiver would
     *      mis-attribute.
     *   5. Variant gate: the payload's variant must match the
     *      group's [ChatGroup.groupType] — a Tyranny group can't
     *      receive a 1-on-1-shaped variant.
     *   6. Persist via [MessageRepository.append] — idempotent on
     *      [ChatMessage.id] so Nostr re-delivery of the same wire
     *      `messageId` is a no-op.
     */
    private suspend fun persistChatMessage(
        payload: ChatMessagePayload,
        ownerIdentityId: IdentityId,
        senderEd25519PublicKey: ByteArray?,
        receivedAt: Instant,
    ) {
        val messages = messageRepository ?: return
        val signerPubkey = senderEd25519PublicKey ?: return
        val groupIdHex = payload.groupId.toHexLowercase()
        val group = groupRepository.findForOwner(ownerIdentityId.value, groupIdHex) ?: return
        val claimedSenderHex = payload.senderBlsPubkeyHex.lowercase()
        val profile = group.memberProfiles[claimedSenderHex] ?: return
        if (!profile.sendingPubkey.contentEquals(signerPubkey)) return
        if (!variantMatchesGroup(payload.variant, group.groupType)) return

        val body = payload.variant.body
        val message = ChatMessage(
            id = payload.messageId,
            groupId = groupIdHex,
            ownerIdentityId = ownerIdentityId.value,
            senderBlsPubkeyHex = claimedSenderHex,
            body = body,
            sentAtMillis = payload.sentAtMillis,
            direction = MessageDirection.INCOMING,
            status = MessageStatus.RECEIVED,
            // Pointer to the quoted message, resolved against the local
            // store at render time. No trust gate needed: a ref to a
            // message we never received (or a forged one) just renders
            // as "Message unavailable" — it can't pull in content from
            // outside this group because rendering only resolves local
            // rows.
            replyToMessageId = payload.replyToMessageId,
            groupType = group.groupType,
            // Encrypted image (if any). The blob is fetched + decrypted
            // lazily at render time (ChatImageLoader); nothing is
            // downloaded on receipt.
            imageAttachment = payload.attachment,
            // Encrypted video (if any). Only the small poster loads on
            // render; the video blob downloads on play (ChatVideoLoader).
            videoAttachment = payload.videoAttachment,
        )
        // Use the receivedAt timestamp only for the "ordering by
        // arrival" UI follow-up — wire `sentAtMillis` is the
        // canonical sort key today.
        @Suppress("UNUSED_VARIABLE") val _receivedAt = receivedAt
        messages.append(message)

        // Ack the sender: delivered now (unconditional — it only
        // reveals a device received the ciphertext). Read receipts are
        // sent later, when the user opens the thread.
        receiptSender.send(
            kind = ChatReceiptPayload.Kind.DELIVERED,
            messageIds = listOf(payload.messageId),
            groupId = payload.groupId,
            recipientInboxKey = profile.inboxPublicKey,
        )
    }

    private fun variantMatchesGroup(
        variant: ChatMessageVariant,
        groupType: SepGroupType,
    ): Boolean = when (variant) {
        is ChatMessageVariant.Tyranny -> groupType == SepGroupType.TYRANNY
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
     * Mirrors `verifyTyrannyInvitation` from onym-ios PR #159.
     */
    private suspend fun verifyTyrannyInvitation(
        invitation: app.onym.android.group.GroupInvitationPayload,
        tier: SepTier,
    ): TyrannyInvitationVerification {
        // Without a chain reader we treat Tyranny as best-effort;
        // V1 never reaches the on-chain branch in tests.
        val reader = chainState ?: return TyrannyInvitationVerification.VERIFIED
        val claimed = invitation.commitment ?: return TyrannyInvitationVerification.REJECT
        // Internal consistency: recompute the FULL Poseidon commitment
        // from `(members, epoch, salt)` and compare. The commitment is
        // `Poseidon(Poseidon(root, epoch), salt)` — NOT just the merkle
        // root.
        val recomputed = try {
            val root = GroupCommitmentBuilder.computeMerkleRoot(invitation.members, tier)
            GroupCommitmentBuilder.computePoseidonCommitment(
                poseidonRoot = root,
                epoch = invitation.epoch,
                salt = invitation.salt,
            )
        } catch (_: Throwable) {
            return TyrannyInvitationVerification.REJECT
        }
        if (!recomputed.contentEquals(claimed)) return TyrannyInvitationVerification.REJECT

        // Skip the chain read when we've already verified+materialized
        // this exact (commitment, epoch). Re-confirming what we confirmed
        // on a prior pass adds nothing — the recompute above already
        // rejects a replay that swaps the roster while reusing the
        // commitment (Poseidon would have to collide). This is the
        // load-bearing fix for the launch-time `get_commitment` storm:
        // every relay reconnect replays the full inbox, and without this
        // each replayed invitation re-hit the relayer, tripping its rate
        // limit and making fresh joins fail until the burst subsided.
        val existing = groupRepository.snapshots.value.firstOrNull {
            it.groupIdBytes.contentEquals(invitation.groupId)
        }
        if (existing?.commitment?.contentEquals(claimed) == true &&
            existing.epoch == invitation.epoch
        ) {
            return TyrannyInvitationVerification.VERIFIED
        }

        // Verify at current chain state (Option 2). The chain stores only
        // the LATEST (commitment, epoch), so a snapshot is only
        // byte-verifiable when the chain is exactly at its epoch.
        //   - chain BEHIND the snapshot → our read is lagging the admin's
        //     just-landed `update_commitment` (relayer/indexer catch-up),
        //     OR a self-consistent forgery claiming a future epoch. We
        //     can't tell here, so defer + ask the admin rather than
        //     reject: deferral never materializes without a later exact-
        //     epoch match, so a forgery still can't get in, while a real
        //     lagging read recovers. (Previously a hard reject — the root
        //     cause of "joiner only sees the chat after a restart".)
        //   - chain EXACTLY at the snapshot's epoch → byte-verify the
        //     committed roster. Strong anti-forgery: reproducing
        //     `Poseidon(Poseidon(root, epoch), salt)` needs the random
        //     `salt`, which is never on chain — only a legitimate
        //     invitation carries it.
        //   - chain AHEAD → can't byte-verify here; defer and ask the
        //     admin for the current state rather than trusting (and
        //     thereby letting a self-consistent fake materialize).
        val onchain = try {
            reader.tyrannyCommitment(invitation.groupId)
        } catch (_: Throwable) {
            // Couldn't reach / read the relayer (throttled, offline, or
            // unconfigured). NOT evidence of forgery — never reject. Defer
            // so the verifier retries via the admin-refresh path and the
            // group materializes once the read succeeds, instead of being
            // silently dropped until the next relay replay (a restart).
            return TyrannyInvitationVerification.STALE_NEEDS_REFRESH
        }
        if (onchain.epoch < invitation.epoch) return TyrannyInvitationVerification.STALE_NEEDS_REFRESH
        if (onchain.epoch == invitation.epoch) {
            return if (onchain.commitment.contentEquals(claimed)) {
                TyrannyInvitationVerification.VERIFIED
            } else {
                TyrannyInvitationVerification.REJECT
            }
        }
        return TyrannyInvitationVerification.STALE_NEEDS_REFRESH
    }

    /** Outcome of receiver-side Tyranny invitation verification (PR 159). */
    private enum class TyrannyInvitationVerification {
        /** Internally consistent AND matches the on-chain commitment at
         *  an exact epoch — safe to materialize. */
        VERIFIED,

        /** Internally consistent, but we couldn't byte-verify against the
         *  chain *right now* — the chain advanced past the snapshot, OR
         *  our read lagged behind it, OR the relayer read failed
         *  (throttled / offline / unconfigured). None of these is
         *  evidence of forgery, so we defer and ask the admin for the
         *  current state rather than dropping. Deferral never materializes
         *  a group without a later exact-epoch on-chain match, so forgery
         *  protection is intact. */
        STALE_NEEDS_REFRESH,

        /** Forged / unverifiable — drop. */
        REJECT,
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
        // Same converge-forward gate as the invitation verifier. The
        // announcement is already admin-Ed25519-signed (checked by the
        // caller), so a stale-but-signed roster delta is a legitimate
        // update we may have missed — accept when the chain is at or
        // ahead of the claimed epoch, byte-verifying only on an exact
        // epoch match.
        if (onchain.epoch < claimedEpoch) return false
        if (onchain.epoch == claimedEpoch &&
            !onchain.commitment.contentEquals(claimedCommitment)
        ) {
            return false
        }
        return true
    }

    private companion object {
        private val permissiveJson = Json { ignoreUnknownKeys = true }

        private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
            for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
        }
    }
}
