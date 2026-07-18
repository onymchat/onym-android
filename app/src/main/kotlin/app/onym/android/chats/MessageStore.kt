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

    /** Case-insensitive substring search over one identity's message
     *  bodies across all groups, newest first (capped at [limit]).
     *  Bodies are encrypted at rest, so the store decrypts and filters in
     *  memory. An empty query returns nothing. */
    suspend fun search(ownerIdentityId: String, query: String, limit: Int): List<ChatMessage>

    /** Single-message lookup scoped to `(id, ownerIdentityId)`.
     *  Returns `null` for unknown ids. Drives the retry-on-failed path
     *  on [app.onym.android.chats.SendMessageInteractor.retry] and the
     *  receipt-driven status upgrade. */
    suspend fun findById(id: UUID, ownerIdentityId: String): ChatMessage?

    /** Idempotent insert on [ChatMessage.id]. Returns `true` on a
     *  fresh write, `false` when the row already exists. Nostr
     *  re-delivery of the same wire `messageId` becomes a no-op at
     *  this seam — dispatchers don't need their own dedup. */
    suspend fun insert(message: ChatMessage): Boolean

    /** Hot path for the outgoing send pipeline (pending → sent /
     *  failed) — skips the encryption round-trip the full row would
     *  require. Scoped to `(id, ownerIdentityId)` so it flips only the
     *  addressed identity's copy of the message. */
    suspend fun updateStatus(id: UUID, ownerIdentityId: String, status: MessageStatus)

    /** Cascade delete for the identity-removal flow. Returns the
     *  number of rows deleted. */
    suspend fun deleteForOwner(ownerIdentityId: String): Int

    /** Cascade delete for the group-deletion flow, scoped to the
     *  owner. Returns the number of rows deleted. */
    suspend fun deleteForGroup(groupId: String, ownerIdentityId: String): Int
}
