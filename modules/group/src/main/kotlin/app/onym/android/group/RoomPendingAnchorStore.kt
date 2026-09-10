package app.onym.android.group

import app.onym.android.foundation.StorageEncryption

/**
 * On-disk [PendingAnchorStore].
 *
 * The ordering is the whole design: [record] must return only once the
 * row is on disk, because the caller submits the transaction on the
 * next line. A store that batched, cached or wrote asynchronously would
 * leave exactly the gap this is here to close — the process dying
 * between the submit and the write is the case being covered.
 *
 * A failure to write is not swallowed. Silently proceeding would submit
 * a transaction whose salt nothing can recover, which is the state the
 * whole mechanism exists to make unreachable; the caller turns the
 * throw into a refusal the founder can act on instead.
 */
class RoomPendingAnchorStore(
    private val dao: PendingAnchorDao,
    private val encryption: StorageEncryption,
) : PendingAnchorStore {

    override suspend fun record(anchor: PendingAnchor) {
        // An attempt from an epoch this one has already left cannot be
        // waiting to land any more. Swept on the way in so a group that
        // keeps failing doesn't carry rows that can no longer explain
        // anything.
        if (anchor.epochOld > 0uL) {
            dao.clearThrough(
                groupIdHex = hex(anchor.groupId),
                ownerIdentityId = anchor.ownerIdentityId,
                throughEpoch = (anchor.epochOld - 1uL).toLong(),
            )
        }
        dao.insert(
            PersistedPendingAnchor(
                groupIdHex = hex(anchor.groupId),
                ownerIdentityId = anchor.ownerIdentityId,
                epochOld = anchor.epochOld.toLong(),
                createdAtMillis = anchor.createdAtMillis,
                encryptedJoinerPublicKey = encryption.encrypt(anchor.joinerPublicKey),
                encryptedJoinerLeafHash = encryption.encrypt(anchor.joinerLeafHash),
                encryptedSaltNew = encryption.encrypt(anchor.saltNew),
            ),
        )
    }

    override suspend fun pending(
        groupId: ByteArray,
        ownerIdentityId: String,
    ): List<PendingAnchor> = dao.pending(hex(groupId), ownerIdentityId).mapNotNull { row ->
        // A row that won't decrypt is a row that can't identify a
        // landed transaction, so it is skipped rather than allowed to
        // fail the whole reconcile — the remaining candidates are still
        // worth checking.
        runCatching {
            PendingAnchor(
                groupId = groupId,
                ownerIdentityId = row.ownerIdentityId,
                epochOld = row.epochOld.toULong(),
                joinerPublicKey = encryption.decrypt(row.encryptedJoinerPublicKey),
                joinerLeafHash = encryption.decrypt(row.encryptedJoinerLeafHash),
                saltNew = encryption.decrypt(row.encryptedSaltNew),
                createdAtMillis = row.createdAtMillis,
            )
        }.getOrNull()
    }

    override suspend fun clear(
        groupId: ByteArray,
        ownerIdentityId: String,
        throughEpoch: ULong,
    ) = dao.clearThrough(hex(groupId), ownerIdentityId, throughEpoch.toLong())

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
