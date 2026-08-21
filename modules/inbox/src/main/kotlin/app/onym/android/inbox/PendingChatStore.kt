package app.onym.android.inbox

import app.onym.android.identity.IdentityId
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Persistence seam for pending chats. Mirrors
 * [app.onym.android.group.GroupStore]: the store holds rows for *every*
 * identity on the device and knows nothing about which one is selected —
 * the per-identity filtering and the snapshot stream live one layer up,
 * in [PendingChatRepository].
 */
interface PendingChatStore {
    /** Idempotent on [PendingChat.id]. An existing row is reported as
     *  [PendingChatWriteOutcome.ALREADY_PRESENT] and left untouched. */
    suspend fun insert(chat: PendingChat): PendingChatWriteOutcome

    /** Replace the status of an existing row. No-op when the row is
     *  gone, which is the ordinary race: the group can materialize while
     *  a join request is still in flight. */
    suspend fun setStatus(id: String, status: PendingChat.Status)

    /**
     * Take the reply channel and the descriptive fields from a newer
     * offer for a row that already exists, leaving its status alone.
     *
     * A re-invite mints a *fresh* intro key and "Generate new link"
     * revokes the old one ([app.onym.android.group.IntroKeyStore.revoke]),
     * so a row that kept the first key would seal its request to a dead
     * address — the transport reports success, the founder never hears
     * it, and the row waits forever with nothing to show for it. Newer
     * offer wins, because the newer key is the only one that can be
     * answered.
     *
     * **Newer, not merely later.** [receivedAt] is the *sender's*
     * timestamp for the offer (a Nostr event's `created_at`, refined by
     * the `ms` tag — see `NostrEvent.displayMilliseconds`), not the
     * moment this device saw it, so a relay replaying a retained older
     * offer after a re-invite carries the older value. The write MUST
     * therefore be applied only when [receivedAt] is strictly after the
     * stored row's, and MUST NOT be applied on ties: without that
     * comparison a replay restores a revoked intro key, and Accept /
     * Ask again then report success into a dead inbox. Applying the
     * write also advances the stored `receivedAt`, so the row's
     * timestamp always names the offer whose key it currently holds.
     *
     * Implementations decide this atomically with the write —
     * comparing in a caller would leave a window between the read and
     * the update.
     */
    suspend fun refreshOffer(
        id: String,
        introPublicKey: ByteArray,
        groupName: String?,
        inviterAlias: String,
        invitationMessage: String?,
        receivedAt: Instant,
    )

    /**
     * Use the capability from a link/QR the person just acted on without
     * changing sender-authenticated offer ordering or invitation copy.
     * Link capabilities carry no authenticated timestamp, so they must
     * never flow through [refreshOffer].
     */
    suspend fun refreshReplyKey(id: String, introPublicKey: ByteArray)

    suspend fun delete(id: String)

    /**
     * Drop rows by id — `<group id hex>:<owner>`, the same key [insert]
     * dedupes on. Ids rather than group hexes because two identities on
     * one device can each be waiting on the same group, and matching on
     * the group alone would delete the other one's row when this one got
     * in.
     */
    suspend fun deleteForIds(ids: Set<String>)

    suspend fun deleteOwner(ownerIdentityId: IdentityId)

    /** Every row on the device, newest first. */
    suspend fun list(): List<PendingChat>
}

/**
 * Process-lifetime [PendingChatStore] for tests and for a device whose
 * on-disk store could not be opened. Losing these rows on relaunch is
 * exactly the failure the Room store exists to prevent, so this is a
 * fallback, not a default.
 */
class InMemoryPendingChatStore : PendingChatStore {
    private val rows = mutableListOf<PendingChat>()

    override suspend fun insert(chat: PendingChat): PendingChatWriteOutcome {
        if (rows.any { it.id == chat.id }) return PendingChatWriteOutcome.ALREADY_PRESENT
        rows.add(chat)
        return PendingChatWriteOutcome.INSERTED
    }

    override suspend fun setStatus(id: String, status: PendingChat.Status) {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return
        rows[index] = rows[index].copy(status = status)
    }

    override suspend fun refreshOffer(
        id: String,
        introPublicKey: ByteArray,
        groupName: String?,
        inviterAlias: String,
        invitationMessage: String?,
        receivedAt: Instant,
    ) {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return
        // Older replay, or the same offer delivered twice: keep what is
        // stored. See the interface doc — the losing case here is a
        // revoked key restored over a live one.
        val storedOfferTime = rows[index].offerReceivedAt
        if (storedOfferTime != null && !receivedAt.isAfter(storedOfferTime)) return
        rows[index] = rows[index].copy(
            introPublicKey = introPublicKey,
            groupName = groupName,
            inviterAlias = inviterAlias,
            invitationMessage = invitationMessage,
            receivedAt = receivedAt,
            offerReceivedAt = receivedAt,
        )
    }

    override suspend fun refreshReplyKey(id: String, introPublicKey: ByteArray) {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return
        rows[index] = rows[index].copy(introPublicKey = introPublicKey)
    }

    override suspend fun delete(id: String) {
        rows.removeAll { it.id == id }
    }

    override suspend fun deleteForIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        rows.removeAll { it.id in ids }
    }

    override suspend fun deleteOwner(ownerIdentityId: IdentityId) {
        rows.removeAll { it.ownerIdentityId == ownerIdentityId }
    }

    override suspend fun list(): List<PendingChat> = rows.sortedByDescending { it.receivedAt }
}

/**
 * Keeps the app usable when Room fails lazily on its first real access.
 *
 * Successful primary operations are mirrored into memory. The first
 * primary failure permanently selects that mirror for the process, so
 * later reads and mutations stay coherent instead of repeatedly touching
 * a corrupt or unreadable database.
 */
class FailoverPendingChatStore(
    primary: PendingChatStore?,
    private val fallback: PendingChatStore = InMemoryPendingChatStore(),
) : PendingChatStore {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var primary: PendingChatStore? = primary

    override suspend fun insert(chat: PendingChat): PendingChatWriteOutcome = mutex.withLock {
        val disk = primary ?: return@withLock fallback.insert(chat)
        try {
            when (val outcome = disk.insert(chat)) {
                PendingChatWriteOutcome.INSERTED -> {
                    fallback.insert(chat)
                    outcome
                }
                PendingChatWriteOutcome.ALREADY_PRESENT -> {
                    // A row may predate this process, so populate the
                    // mirror before any later lazy-open failure forces
                    // us to rely on it.
                    try {
                        replaceFallback(disk.list())
                        outcome
                    } catch (_: Throwable) {
                        primary = null
                        fallback.insert(chat)
                    }
                }
                PendingChatWriteOutcome.FAILED, PendingChatWriteOutcome.NOT_RECORDED -> {
                    primary = null
                    fallback.insert(chat)
                }
                // Not a reason to give up on the disk: the row is the
                // problem, and the mirror would fail on it too.
                PendingChatWriteOutcome.NOT_ENCRYPTABLE -> outcome
            }
        } catch (_: Throwable) {
            primary = null
            fallback.insert(chat)
        }
    }

    override suspend fun setStatus(id: String, status: PendingChat.Status) = mutex.withLock {
        usePrimaryOrFallback(
            primaryCall = { it.setStatus(id, status) },
            fallbackCall = { it.setStatus(id, status) },
        )
    }

    override suspend fun refreshOffer(
        id: String,
        introPublicKey: ByteArray,
        groupName: String?,
        inviterAlias: String,
        invitationMessage: String?,
        receivedAt: Instant,
    ) = mutex.withLock {
        usePrimaryOrFallback(
            primaryCall = {
                it.refreshOffer(
                    id, introPublicKey, groupName, inviterAlias, invitationMessage, receivedAt,
                )
            },
            fallbackCall = {
                it.refreshOffer(
                    id, introPublicKey, groupName, inviterAlias, invitationMessage, receivedAt,
                )
            },
        )
    }

    override suspend fun refreshReplyKey(id: String, introPublicKey: ByteArray) = mutex.withLock {
        usePrimaryOrFallback(
            primaryCall = { it.refreshReplyKey(id, introPublicKey) },
            fallbackCall = { it.refreshReplyKey(id, introPublicKey) },
        )
    }

    override suspend fun delete(id: String) = mutex.withLock {
        usePrimaryOrFallback(
            primaryCall = { it.delete(id) },
            fallbackCall = { it.delete(id) },
        )
    }

    override suspend fun deleteForIds(ids: Set<String>) = mutex.withLock {
        usePrimaryOrFallback(
            primaryCall = { it.deleteForIds(ids) },
            fallbackCall = { it.deleteForIds(ids) },
        )
    }

    override suspend fun deleteOwner(ownerIdentityId: IdentityId) = mutex.withLock {
        usePrimaryOrFallback(
            primaryCall = { it.deleteOwner(ownerIdentityId) },
            fallbackCall = { it.deleteOwner(ownerIdentityId) },
        )
    }

    override suspend fun list(): List<PendingChat> = mutex.withLock {
        val disk = primary ?: return@withLock fallback.list()
        try {
            val rows = disk.list()
            replaceFallback(rows)
            rows
        } catch (_: Throwable) {
            primary = null
            fallback.list()
        }
    }

    private suspend fun usePrimaryOrFallback(
        primaryCall: suspend (PendingChatStore) -> Unit,
        fallbackCall: suspend (PendingChatStore) -> Unit,
    ) {
        val disk = primary
        if (disk == null) {
            fallbackCall(fallback)
            return
        }
        try {
            primaryCall(disk)
            fallbackCall(fallback)
        } catch (_: Throwable) {
            primary = null
            fallbackCall(fallback)
        }
    }

    private suspend fun replaceFallback(rows: List<PendingChat>) {
        val oldIds = fallback.list().mapTo(mutableSetOf()) { it.id }
        fallback.deleteForIds(oldIds)
        rows.forEach { fallback.insert(it) }
    }
}
