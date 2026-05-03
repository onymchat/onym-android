package chat.onym.android.group

/**
 * Persistence seam for chat groups. Async surface mirrors
 * [chat.onym.android.persistence.InvitationStore] (PR #16) so a
 * concrete impl can serialise writes on its own dispatcher without
 * forcing callers onto a specific dispatcher.
 *
 * [ChatGroup] is the domain shape; the store is responsible for
 * AES-GCM-wrapping the sensitive columns at the boundary.
 *
 * Mirrors `GroupStore` from onym-ios PR #25.
 */
interface GroupStore {

    /** All persisted groups, sorted by [ChatGroup.createdAtMillis] desc.
     *  Use [listForOwner] for the per-identity filter. */
    suspend fun list(): List<ChatGroup>

    /** Groups owned by [ownerIdentityId]. Sorted by
     *  [ChatGroup.createdAtMillis] desc. Drives the per-identity
     *  chats filter ([GroupRepository.snapshots]). */
    suspend fun listForOwner(ownerIdentityId: String): List<ChatGroup>

    /** Cascade delete for the identity-removal flow. Returns the
     *  number of rows deleted. */
    suspend fun deleteForOwner(ownerIdentityId: String): Int

    /**
     * Idempotent on [ChatGroup.id]: if the row exists, sensitive +
     * mutable fields are overwritten in place (so a chain-anchor
     * retry can flip [ChatGroup.isPublishedOnChain] and bump the
     * commitment without losing the original [ChatGroup.createdAtMillis]).
     *
     * @return `true` on insert, `false` on update.
     */
    suspend fun insertOrUpdate(group: ChatGroup): Boolean

    /**
     * Convenience for the post-anchor flow: flip
     * [ChatGroup.isPublishedOnChain] to `true` and update the
     * commitment to whatever the relayer's `get_state` returned.
     * No-op if [id] is missing.
     *
     * If [commitment] is `null`, only the flag is updated — the
     * existing commitment column is preserved (the relayer's
     * `get_state` is the source of truth post-anchor; if we don't
     * have a fresh value, we don't blow away whatever's there).
     */
    suspend fun markPublished(id: String, commitment: ByteArray?)

    /** No-op if [id] is absent. */
    suspend fun delete(id: String)
}
