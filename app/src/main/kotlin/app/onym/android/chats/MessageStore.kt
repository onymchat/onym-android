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

    /** Insert one row. Caller is responsible for upstream dedup —
     *  the DAO throws on PK conflict because re-delivery dedup is a
     *  dispatcher-side concern (lands in PR A4). */
    suspend fun insert(message: ChatMessage)

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
