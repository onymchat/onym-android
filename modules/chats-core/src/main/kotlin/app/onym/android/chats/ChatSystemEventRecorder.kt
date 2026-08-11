package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.group.GroupSystemEventRecording
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The single place that mints system rows ("Alice joined", "You joined
 * X") into a chat thread.
 *
 * Three call sites produce these, on three different devices, from three
 * different triggers:
 *
 *  - the admin, from its own successful `JoinRequestApprover.approve`
 *    (reaches this through the [GroupSystemEventRecording] seam, because
 *    `:group` cannot depend on this module);
 *  - every existing member, from a signature- and chain-verified
 *    `MemberAnnouncementPayload`;
 *  - the joiner, from its freshly materialized group.
 *
 * Nothing about a system row travels on the wire — `ChatMessagePayload`
 * has no `system_event` key and must never grow one. Each device mints
 * its own copy from an event it independently verified, so there is no
 * "forge a join notice" path for a peer.
 *
 * ## Idempotence
 *
 * Row ids are **derived**, not random: a stable UUID from
 * `(groupId, owner, event discriminator)`. Relays replay the full inbox
 * on every reconnect, and the callers' own dedup guards are the first
 * line of defence — this is the second. [MessageRepository.append] is
 * idempotent on `(id, ownerIdentityId)`, so a replay that slips past a
 * guard collapses onto the existing row instead of stacking a duplicate
 * "Alice joined" on every launch.
 *
 * Mirrors `ChatSystemEventRecorder` in onym-ios.
 */
class ChatSystemEventRecorder(
    private val messageRepository: MessageRepository,
) : GroupSystemEventRecording {

    // MARK: - GroupSystemEventRecording

    override suspend fun recordMemberJoined(
        groupId: String,
        ownerIdentityId: String,
        groupType: SepGroupType,
        joinerBlsPubkeyHex: String,
        alias: String,
        atMillis: Long,
    ) {
        record(
            event = ChatSystemEvent.MemberJoined(alias = alias),
            discriminator = "member-joined:${joinerBlsPubkeyHex.lowercase()}",
            groupId = groupId,
            ownerIdentityId = ownerIdentityId,
            groupType = groupType,
            senderBlsPubkeyHex = joinerBlsPubkeyHex,
            atMillis = atMillis,
        )
    }

    // MARK: - Joiner side

    /**
     * Append "You joined <group>" as the opening row of a thread this
     * device was just admitted to. Keyed on the group alone, so a
     * re-delivered invitation can't add a second one.
     */
    suspend fun recordYouJoined(
        groupId: String,
        ownerIdentityId: String,
        groupType: SepGroupType,
        groupName: String,
        ownBlsPubkeyHex: String,
        atMillis: Long,
    ) {
        record(
            event = ChatSystemEvent.YouJoined(groupName = groupName),
            discriminator = "you-joined",
            groupId = groupId,
            ownerIdentityId = ownerIdentityId,
            groupType = groupType,
            senderBlsPubkeyHex = ownBlsPubkeyHex,
            atMillis = atMillis,
        )
    }

    // MARK: - Private

    private suspend fun record(
        event: ChatSystemEvent,
        discriminator: String,
        groupId: String,
        ownerIdentityId: String,
        groupType: SepGroupType,
        senderBlsPubkeyHex: String,
        atMillis: Long,
    ) {
        messageRepository.append(
            ChatMessage(
                id = derivedId(groupId, ownerIdentityId, discriminator),
                groupId = groupId,
                ownerIdentityId = ownerIdentityId,
                // Carried so the row still has a well-formed sender for
                // any code that reads the column; the thread never
                // renders a name header or accent for a system row.
                senderBlsPubkeyHex = senderBlsPubkeyHex,
                // The sentence is assembled in the UI from `systemEvent`,
                // so the body stays empty rather than holding
                // unlocalized English that would leak into search and
                // chat-list previews.
                body = "",
                sentAtMillis = atMillis,
                // INCOMING + READ keeps the row off every outgoing path
                // (retry, receipts, the delivery-status ladder). The
                // unread query excludes system rows explicitly, so this
                // does not light up a badge.
                direction = MessageDirection.INCOMING,
                status = MessageStatus.READ,
                replyToMessageId = null,
                groupType = groupType,
                systemEvent = event,
            )
        )
    }

    internal companion object {
        /**
         * Stable UUID for a system row. SHA-256 over a domain-separated
         * tuple, folded into a v4-shaped UUID — same value on every
         * replay, different across groups, owners, and event kinds.
         */
        fun derivedId(
            groupId: String,
            ownerIdentityId: String,
            discriminator: String,
        ): UUID {
            val seed = "onym-system-event-v1:$groupId:$ownerIdentityId:$discriminator"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(seed.toByteArray(Charsets.UTF_8))
                .copyOf(16)
            // Stamp version 4 + RFC 4122 variant so the value is a
            // well-formed UUID rather than raw hash bytes.
            digest[6] = ((digest[6].toInt() and 0x0F) or 0x40).toByte()
            digest[8] = ((digest[8].toInt() and 0x3F) or 0x80).toByte()
            val buffer = ByteBuffer.wrap(digest)
            return UUID(buffer.long, buffer.long)
        }
    }
}
