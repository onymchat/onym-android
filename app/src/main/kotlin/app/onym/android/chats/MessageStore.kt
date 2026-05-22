package app.onym.android.chats

import java.util.UUID

/**
 * Persistence seam for chat messages. Async surface mirrors
 * [app.onym.android.group.GroupStore] so a concrete impl can
 * serialize writes on its own dispatcher without forcing callers
 * onto a specific one.
 *
 * [ChatMessage] is the domain shape; the store is responsible for
 * AES-GCM-wrapping the sensitive columns at the boundary.
 *
 * Mirrors `MessageStore` from onym-ios PR #148.
 */
interface MessageStore {

    /** Messages for one identity in one group, oldest first. Drives
     *  the chat-thread read path. */
    suspend fun listForGroup(ownerIdentityId: String, groupId: String): List<ChatMessage>

    /** Idempotent insert on [ChatMessage.id]. Returns `true` on a
     *  fresh write, `false` when the row already exists. Nostr
     *  re-delivery of the same wire `messageId` becomes a no-op at
     *  this seam — dispatchers don't need their own dedup. */
    suspend fun insert(message: ChatMessage): Boolean

    /** Hot path for the outgoing send pipeline (pending → sent /
     *  failed) — skips the encryption round-trip the full row would
     *  require. */
    suspend fun updateStatus(id: UUID, status: MessageStatus)

    /** Cascade delete for the identity-removal flow. Returns the
     *  number of rows deleted. */
    suspend fun deleteForOwner(ownerIdentityId: String): Int

    /** Cascade delete for the group-deletion flow. Returns the
     *  number of rows deleted. */
    suspend fun deleteForGroup(groupId: String): Int
}
