package app.onym.android.chats

import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Delivery / read receipt sent back to a message's original sender.
 * Sealed + shipped over the same [app.onym.android.transport.InboxTransport]
 * path as [ChatMessagePayload], addressed to the sender's inbox key.
 *
 * The receiver of a chat message emits [Kind.DELIVERED] as soon as it
 * persists the message; it emits [Kind.READ] when the user opens the
 * thread (gated by the symmetric read-receipt setting). The original
 * sender decodes this and *raises* the matching outgoing message's
 * status — receipts never downgrade (see [MessageStatus.deliveryRank]).
 *
 * Wire-format disjoint from every other inbox payload: the required
 * `kind` + `message_ids` keys appear in no other type, and it carries
 * neither [ChatMessagePayload]'s `variant`/`message_id` nor the invite
 * payloads' fields, so the dispatcher's trial-decode fast-paths can't
 * cross-match it (even under `ignoreUnknownKeys`).
 *
 * Byte-compatible with `ChatReceiptPayload.swift` on onym-ios:
 *  - `group_id` is base64 (matching iOS `Data`),
 *  - `message_ids` are uppercase canonical UUID strings,
 *  - `kind` is `"delivered"` / `"read"`.
 */
@Serializable
data class ChatReceiptPayload(
    val version: Int,
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,
    /** BLS pubkey hex of the party emitting the receipt (the recipient
     *  of the original message). Informational under v1 — any
     *  recipient's receipt raises the sender's status — but recorded so
     *  per-member delivered/read tracking can land later without a wire
     *  bump. */
    @SerialName("sender_bls_pubkey_hex")
    val senderBlsPubkeyHex: String,
    val kind: Kind,
    /** Messages being acknowledged. Batched so opening a thread with
     *  many unread messages ships one receipt, not one per message. */
    @SerialName("message_ids")
    val messageIds: List<@Serializable(with = UuidStringSerializer::class) UUID>,
) {
    @Serializable
    enum class Kind {
        @SerialName("delivered")
        DELIVERED,

        @SerialName("read")
        READ,
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatReceiptPayload) return false
        return version == other.version &&
            groupId.contentEquals(other.groupId) &&
            senderBlsPubkeyHex == other.senderBlsPubkeyHex &&
            kind == other.kind &&
            messageIds == other.messageIds
    }

    override fun hashCode(): Int {
        var h = version
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + senderBlsPubkeyHex.hashCode()
        h = 31 * h + kind.hashCode()
        h = 31 * h + messageIds.hashCode()
        return h
    }
}
