package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import java.util.UUID

/**
 * Domain shape of one chat message, as the Android app understands
 * it. Mirrors `ChatMessage.swift` from onym-ios PR #148.
 *
 * Two kinds of fields:
 *  - **Identifying / queryable** ([id], [groupId], [ownerIdentityId],
 *    [sentAtMillis], [direction], [status], [groupType]) stay plain
 *    on disk so the DAO can sort and filter without round-tripping
 *    through the encryption boundary.
 *  - **Sensitive** ([senderBlsPubkeyHex], [body]) ride encrypted —
 *    see [PersistedMessage] for the column split.
 *
 * [direction] is the local-view distinction (incoming = received
 * from someone else, outgoing = sent by this device); [status] is
 * the lifecycle stage (pending → sent / failed, or received). Kept
 * separate so a `PENDING / OUTGOING` row can survive a relaunch
 * during a send.
 */
data class ChatMessage(
    /** Stable per-message id. Matches
     *  [ChatMessagePayload.messageId] on the wire so the receiver
     *  can dedup re-deliveries. */
    val id: UUID,
    /** 64-char lowercase hex of the 32-byte group ID. Matches
     *  [app.onym.android.group.ChatGroup.id]. */
    val groupId: String,
    /** [app.onym.android.identity.IdentityId.value]. Drives the
     *  per-identity filter on [MessageRepository.snapshots]. */
    val ownerIdentityId: String,
    /** 96-char lowercase BLS pubkey hex. Matches
     *  [app.onym.android.group.ChatGroup.memberProfiles] keying so
     *  the chat screen can look up the sender's alias with one
     *  dictionary read. */
    val senderBlsPubkeyHex: String,
    val body: String,
    /** Milliseconds since Unix epoch. */
    val sentAtMillis: Long,
    val direction: MessageDirection,
    val status: MessageStatus,
    val groupType: SepGroupType,
)
