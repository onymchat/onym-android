package app.onym.android.support

import app.onym.android.group.PendingAnchor
import app.onym.android.group.PendingAnchorStore

/**
 * Process-lifetime [PendingAnchorStore] for tests.
 *
 * Keeps the two properties the recovery leans on — a record is
 * readable back the moment [record] returns, and a sweep removes
 * exactly the epochs it names — without a database. [failOnRecord]
 * drives the case the production store treats as a refusal: a salt
 * that could not be kept.
 */
class InMemoryPendingAnchorStore(
    var failOnRecord: Boolean = false,
) : PendingAnchorStore {

    private val rows = mutableListOf<PendingAnchor>()

    /** Everything currently held, oldest first. Tests assert on this
     *  to pin *when* the write happened relative to the submit. */
    val recorded: List<PendingAnchor> get() = rows.toList()

    override suspend fun record(anchor: PendingAnchor) {
        if (failOnRecord) throw IllegalStateException("pending anchor store is full")
        rows += anchor
    }

    override suspend fun pending(
        groupId: ByteArray,
        ownerIdentityId: String,
    ): List<PendingAnchor> = rows
        .filter { it.groupId.contentEquals(groupId) && it.ownerIdentityId == ownerIdentityId }
        .sortedByDescending { it.createdAtMillis }

    override suspend fun clear(groupId: ByteArray, ownerIdentityId: String, throughEpoch: ULong) {
        rows.removeAll {
            it.groupId.contentEquals(groupId) &&
                it.ownerIdentityId == ownerIdentityId &&
                it.epochOld <= throughEpoch
        }
    }
}
