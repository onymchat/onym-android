package app.onym.android.inbox

import app.onym.android.foundation.StorageEncryption
import app.onym.android.identity.IdentityId
import java.time.Instant

/**
 * On-disk [PendingChatStore], backed by Room + [StorageEncryption].
 *
 * No retention sweep, deliberately. `RoomIntroRequestStore` prunes
 * because a founder accumulates strangers' requests; here the rows are
 * the person's own and there are a handful at most. They are cleared
 * when the group materializes, when the identity is removed, or when the
 * row is swiped away.
 *
 * Mirrors `SwiftDataPendingChatStore` in onym-ios.
 */
class RoomPendingChatStore(
    private val dao: PendingChatDao,
    private val encryption: StorageEncryption,
) : PendingChatStore {

    override suspend fun insert(chat: PendingChat): PendingChatWriteOutcome {
        // Every DAO read here is guarded. Room opens the database
        // lazily on first access, so a device whose file is missing,
        // corrupt, or unreadable throws *here* rather than at
        // construction — and an escaping throwable would cancel the
        // caller's coroutine instead of producing the FAILED outcome
        // the recording path is written to handle.
        val existing = countOrNull(chat.id) ?: return PendingChatWriteOutcome.FAILED
        if (existing > 0) return PendingChatWriteOutcome.ALREADY_PRESENT
        val row = try {
            encode(chat)
        } catch (_: Throwable) {
            // The database is fine; this row can't be sealed. Saying
            // FAILED here would retire the on-disk store for the rest of
            // the process — see `PendingChatWriteOutcome.NOT_ENCRYPTABLE`.
            return PendingChatWriteOutcome.NOT_ENCRYPTABLE
        }
        return try {
            dao.insert(row)
            PendingChatWriteOutcome.INSERTED
        } catch (_: Throwable) {
            // Lost a race with another writer for the same
            // `(group, owner)`: the row exists, which is what the caller
            // needs to know. `ABORT` is what makes this reachable, and
            // is why it is preferred over `REPLACE` — see the DAO.
            // Guarded for the same reason: if the database is the thing
            // that is broken, this read fails too, and the honest
            // answer is FAILED rather than a crash on the way to it.
            val present = countOrNull(chat.id)
            if (present != null && present > 0) {
                PendingChatWriteOutcome.ALREADY_PRESENT
            } else {
                PendingChatWriteOutcome.FAILED
            }
        }
    }

    /** `null` when the read itself failed — distinct from a successful
     *  read of zero rows, which is an absent row and nothing worse. */
    private suspend fun countOrNull(id: String): Int? = try {
        dao.count(id)
    } catch (_: Throwable) {
        null
    }

    override suspend fun setStatus(id: String, status: PendingChat.Status) {
        dao.setStatus(id = id, statusRaw = statusRaw(status), failureRaw = failureRaw(status))
    }

    override suspend fun refreshOffer(
        id: String,
        introPublicKey: ByteArray,
        groupName: String?,
        inviterAlias: String,
        invitationMessage: String?,
        receivedAt: Instant,
    ) {
        val key = try {
            encryption.encrypt(introPublicKey)
        } catch (_: Throwable) {
            return
        }
        val alias = try {
            encryption.encrypt(inviterAlias)
        } catch (_: Throwable) {
            return
        }
        dao.refreshOffer(
            id = id,
            introPublicKey = key,
            groupName = groupName?.let { runCatching { encryption.encrypt(it) }.getOrNull() },
            inviterAlias = alias,
            invitationMessage = invitationMessage?.let {
                runCatching { encryption.encrypt(it) }.getOrNull()
            },
            receivedAtMillis = receivedAt.toEpochMilli(),
        )
    }

    override suspend fun setJoinerLabel(id: String, label: String) {
        val encrypted = runCatching { encryption.encrypt(label) }.getOrNull() ?: return
        dao.setJoinerLabel(id = id, label = encrypted)
    }

    override suspend fun refreshReplyKey(id: String, introPublicKey: ByteArray) {
        val key = try {
            encryption.encrypt(introPublicKey)
        } catch (_: Throwable) {
            return
        }
        dao.refreshReplyKey(id, key)
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun deleteForIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        dao.deleteForIds(ids)
    }

    override suspend fun deleteOwner(ownerIdentityId: IdentityId) =
        dao.deleteOwner(ownerIdentityId.value)

    override suspend fun list(): List<PendingChat> = dao.list().mapNotNull(::decode)

    // ---- mapping ----

    private fun statusRaw(status: PendingChat.Status): String = when (status) {
        PendingChat.Status.Offered -> "offered"
        PendingChat.Status.Requested -> "requested"
        is PendingChat.Status.Failed -> "failed"
    }

    private fun failureRaw(status: PendingChat.Status): String? =
        (status as? PendingChat.Status.Failed)?.failure?.raw

    private fun status(raw: String, failureRaw: String?): PendingChat.Status = when (raw) {
        "requested" -> PendingChat.Status.Requested
        // A failure whose code didn't survive is still a failure, and
        // the transport one is both the likelier and the safer guess: it
        // says "your request didn't go out", which is true of the other
        // case too.
        "failed" -> PendingChat.Status.Failed(
            PendingChat.SendFailure.fromRaw(failureRaw) ?: PendingChat.SendFailure.TRANSPORT,
        )
        else -> PendingChat.Status.Offered
    }

    private fun encode(chat: PendingChat) = PersistedPendingChat(
        id = chat.id,
        ownerIdentityId = chat.ownerIdentityId.value,
        groupIdHex = chat.groupIdHex,
        receivedAtMillis = chat.receivedAt.toEpochMilli(),
        offerReceivedAtMillis = chat.offerReceivedAt?.toEpochMilli(),
        statusRaw = statusRaw(chat.status),
        failureRaw = failureRaw(chat.status),
        encryptedIntroPublicKey = encryption.encrypt(chat.introPublicKey),
        encryptedGroupName = chat.groupName?.let { encryption.encrypt(it) },
        encryptedInviterAlias = encryption.encrypt(chat.inviterAlias),
        encryptedInvitationMessage = chat.invitationMessage?.let { encryption.encrypt(it) },
        encryptedJoinerLabel = chat.joinerLabel?.let { encryption.encrypt(it) },
    )

    /**
     * Only two fields can take the row down with them: the intro key,
     * without which Accept has nowhere to reply, and the owner, without
     * which the row belongs to nobody.
     *
     * Everything else degrades. The alias and the group name are
     * cosmetic, and dropping the row over them would delete from view
     * the one piece of evidence that this person ever asked to join —
     * which is the whole argument for persisting these rows at all.
     */
    private fun decode(row: PersistedPendingChat): PendingChat? {
        val introPublicKey = runCatching {
            encryption.decrypt(row.encryptedIntroPublicKey)
        }.getOrNull() ?: return null
        val owner = runCatching { IdentityId(row.ownerIdentityId) }.getOrNull() ?: return null
        return PendingChat(
            groupId = PendingChat.bytesFromHex(row.groupIdHex),
            ownerIdentityId = owner,
            introPublicKey = introPublicKey,
            groupName = row.encryptedGroupName?.let {
                runCatching { encryption.decryptString(it) }.getOrNull()
            },
            inviterAlias = runCatching {
                encryption.decryptString(row.encryptedInviterAlias)
            }.getOrNull() ?: "",
            joinerLabel = row.encryptedJoinerLabel?.let {
                runCatching { encryption.decryptString(it) }.getOrNull()
            },
            invitationMessage = row.encryptedInvitationMessage?.let {
                runCatching { encryption.decryptString(it) }.getOrNull()
            },
            receivedAt = Instant.ofEpochMilli(row.receivedAtMillis),
            status = status(row.statusRaw, row.failureRaw),
            offerReceivedAt = row.offerReceivedAtMillis?.let(Instant::ofEpochMilli),
        )
    }
}
