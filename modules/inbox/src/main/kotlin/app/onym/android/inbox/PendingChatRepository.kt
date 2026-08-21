package app.onym.android.inbox

import app.onym.android.identity.IdentityId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns a [PendingChatStore] and exposes a per-identity reactive snapshot.
 * Deliberately the same shape as [app.onym.android.group.GroupRepository],
 * because it feeds the same list: every mutation republishes, and the
 * current list is replayed to each new collector.
 *
 * The cache holds every row on the device; the identity filter is
 * applied at publish time, so switching back to an identity is instant.
 *
 * Mirrors `PendingChatRepository` in onym-ios.
 */
class PendingChatRepository(
    private val store: PendingChatStore,
) : PendingChatRecording {

    private val mutex = Mutex()
    private var cached: List<PendingChat> = emptyList()
    private var loaded = false
    private var currentIdentityId: IdentityId? = null

    private val _snapshots = MutableStateFlow<List<PendingChat>>(emptyList())
    val snapshots: StateFlow<List<PendingChat>> = _snapshots.asStateFlow()
    /**
     * Whether [snapshots] can be read as the whole truth for the
     * selected identity — the store has been read *and* there is an
     * identity to filter by.
     *
     * Both halves are needed, because an empty [snapshots] says two
     * different things: "this identity is waiting on nothing" and "no
     * identity is selected, so nothing was published". A reader that
     * takes the second for the first concludes a row is gone when it is
     * only unselected — and the startup order makes that the common
     * case, not the rare one: the store read and the identity bootstrap
     * are separate coroutines, and the read usually wins.
     */
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    // ---- PendingChatRecording ----

    override suspend fun record(chat: PendingChat): PendingChatWriteOutcome = mutex.withLock {
        val outcome = store.insert(chat)
        when (outcome) {
            PendingChatWriteOutcome.INSERTED -> refreshLocked()
            // A second offer for a chat already waiting is a *re-invite*:
            // the founder minted a fresh intro key, and "Generate new
            // link" revoked the one this row holds. Keeping the old key
            // would seal the request to a dead address — reported as
            // sent, never heard, waiting forever. The status is
            // untouched: what the person asked for hasn't changed, only
            // where to ask.
            //
            // Only a *newer* offer wins, which the store decides
            // atomically from `receivedAt` — the sender's timestamp for
            // the offer, not this device's clock. A relay replaying a
            // retained older offer arrives after the re-invite but
            // carries the earlier stamp, and letting it through would
            // put the revoked key back. The republish below runs either
            // way: the write may have been declined, and the snapshot
            // must show what is actually stored.
            PendingChatWriteOutcome.ALREADY_PRESENT -> {
                val offerTime = chat.offerReceivedAt
                if (offerTime == null) {
                    // A link has no authenticated ordering information.
                    // The person's latest explicit tap chooses the reply
                    // key, but it must not erase invitation copy or
                    // poison the offer freshness clock with local time.
                    store.refreshReplyKey(chat.id, chat.introPublicKey)
                } else {
                    store.refreshOffer(
                        id = chat.id,
                        introPublicKey = chat.introPublicKey,
                        groupName = chat.groupName,
                        inviterAlias = chat.inviterAlias,
                        invitationMessage = chat.invitationMessage,
                        receivedAt = offerTime,
                    )
                }
                refreshLocked()
            }
            PendingChatWriteOutcome.FAILED,
            PendingChatWriteOutcome.NOT_RECORDED,
            PendingChatWriteOutcome.NOT_ENCRYPTABLE,
            -> Unit
        }
        outcome
    }

    // ---- mutations ----

    /** The join request is out. Called after the sender reports success,
     *  so the row only claims to be waiting on the founder once
     *  something actually left the device. */
    suspend fun markRequested(id: String) = mutex.withLock {
        store.setStatus(id, PendingChat.Status.Requested)
        refreshLocked()
    }

    suspend fun markFailed(id: String, failure: PendingChat.SendFailure) = mutex.withLock {
        store.setStatus(id, PendingChat.Status.Failed(failure))
        refreshLocked()
    }

    /** Drop a row the person swiped away. Local only — no NACK to the
     *  founder, whose outstanding intro key simply goes unused. */
    suspend fun remove(id: String) = mutex.withLock {
        store.delete(id)
        refreshLocked()
    }

    /**
     * Drop every pending row whose group now exists locally: the founder
     * approved, the invitation materialized the group, and the waiting
     * room has become the chat itself.
     *
     * Takes `(group, owner)` pairs, not group ids. Two identities on one
     * device can be waiting on the same group, and the snapshot that
     * triggers this is filtered to whichever one is selected — so
     * matching on the group alone deleted the other identity's row the
     * moment this one got in.
     */
    suspend fun consumeForMaterialized(groups: List<Pair<String, IdentityId>>) = mutex.withLock {
        if (groups.isEmpty()) return@withLock
        // An empty cache at launch is ambiguous — no pending chats, or
        // no read yet — and the group watcher can emit before the
        // startup load lands. Reading through settles it: without this,
        // a row whose group materialized while the app was closed
        // survives the one emission that would have swept it and then
        // sits in the list until some unrelated group change.
        if (!loaded) refreshLocked()
        val landed = groups.mapTo(mutableSetOf()) { (hex, owner) -> "$hex:${owner.value}" }
        val matched = cached.mapTo(mutableSetOf()) { it.id }.intersect(landed)
        if (matched.isEmpty()) return@withLock
        store.deleteForIds(matched)
        refreshLocked()
    }

    /** Cascade for the identity-removal flow. */
    suspend fun removeForOwner(id: IdentityId) = mutex.withLock {
        store.deleteOwner(id)
        refreshLocked()
    }

    // ---- identity selection ----

    suspend fun setCurrentIdentity(id: IdentityId?) = mutex.withLock {
        if (currentIdentityId == id) return@withLock
        currentIdentityId = id
        publishLocked()
    }

    /** Force a read from the backing store. Used at launch; mutators
     *  call it themselves. */
    suspend fun reload() = mutex.withLock { refreshLocked() }

    /**
     * One-shot read across **all** identities, for the deeplink path: it
     * has to know whether this device already has a row for the group
     * before it creates a second one.
     */
    suspend fun currentChats(): List<PendingChat> = mutex.withLock {
        if (!loaded) refreshLocked()
        cached
    }

    // ---- private ----

    private suspend fun refreshLocked() {
        cached = store.list()
        loaded = true
        publishLocked()
    }

    private fun publishLocked() {
        val active = currentIdentityId
        _snapshots.value = if (active == null) {
            emptyList()
        } else {
            cached.filter { it.ownerIdentityId == active }
        }
        // Published alongside the snapshot, not from the read, so that
        // selecting an identity announces readiness too. The snapshot
        // alone cannot: an identity with no pending rows leaves it empty
        // and equal to its previous value, so it never emits, and a
        // reader waiting for readiness would wait forever.
        _isLoaded.value = loaded && active != null
    }
}
