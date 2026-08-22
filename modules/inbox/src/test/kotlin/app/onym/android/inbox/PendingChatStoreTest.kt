package app.onym.android.inbox

import app.onym.android.foundation.StorageEncryption
import app.onym.android.identity.IdentityId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * The store under the chats-list rows for a chat that hasn't opened yet.
 * What matters here is dedup (a relay replays an offer on every
 * reconnect), status transitions, and the re-invite that must not be
 * dropped.
 *
 * Mirrors `PendingChatStoreTests` in onym-ios.
 */
class PendingChatStoreTest {

    private val owner = IdentityId("owner")
    private val other = IdentityId("other")

    @Test
    fun aRowThatCannotBeEncrypted_doesNotRetireTheDisk() = runTest {
        // An encryption failure is a fact about one row, not about the
        // database. Reported as a store failure it demoted the durable
        // store to memory for the rest of the process — losing exactly
        // the durability the row was being written for.
        val disk = RejectingEncodeStore()
        val store = FailoverPendingChatStore(primary = disk)

        val outcome = store.insert(chat(group = 0x11, owner = owner))

        assertEquals(PendingChatWriteOutcome.NOT_ENCRYPTABLE, outcome)

        // The next row, which encrypts fine, still reaches the disk.
        disk.rejecting = false
        val second = store.insert(chat(group = 0x22, owner = owner))
        assertEquals(PendingChatWriteOutcome.INSERTED, second)
        assertEquals(1, disk.written.size)
    }

    @Test
    fun setRules_replacesTheTextWithoutTouchingOfferOrdering() = runTest {
        // The link's rules win over a stored offer's on the confirmation
        // screen, so the row has to be brought up to what was read
        // before anything is signed. Deliberately not `refreshOffer`,
        // which carries an authenticated timestamp and would reorder the
        // row on a text change.
        val store = InMemoryPendingChatStore()
        val row = chat(group = 0x11, owner = owner).copy(invitationMessage = "Older wording.")
        store.insert(row)

        store.setRules(row.id, "Newer wording.")

        val stored = store.list().single()
        assertEquals("Newer wording.", stored.invitationMessage)
        assertEquals(row.offerReceivedAt, stored.offerReceivedAt)
        assertEquals(row.receivedAt, stored.receivedAt)
    }

    @Test
    fun setRules_onARowThatIsGone_isANoOp() = runTest {
        val store = InMemoryPendingChatStore()

        store.setRules("nobody:home", "Be kind.")

        assertTrue(store.list().isEmpty())
    }

    @Test
    fun insert_isKeyedByGroupAndOwner_notByDelivery() = runTest {
        val store = InMemoryPendingChatStore()
        val first = store.insert(chat(group = 0x11, owner = owner))
        // Same group, same identity, later delivery: the same waiting
        // room, not a second one.
        val second = store.insert(
            chat(group = 0x11, owner = owner, receivedAt = Instant.ofEpochSecond(500)),
        )

        assertEquals(PendingChatWriteOutcome.INSERTED, first)
        assertEquals(PendingChatWriteOutcome.ALREADY_PRESENT, second)
        assertEquals(1, store.list().size)
    }

    @Test
    fun insert_keepsOneRowPerIdentityForTheSameGroup() = runTest {
        // Two identities on one device can be invited to the same chat.
        // Collapsing them would hide one person's invitation behind the
        // other's — the bug the groups table's composite key exists to
        // prevent, one layer down.
        val store = InMemoryPendingChatStore()
        store.insert(chat(group = 0x11, owner = owner))
        store.insert(chat(group = 0x11, owner = other))

        assertEquals(2, store.list().size)
    }

    @Test
    fun replayedOffer_doesNotResetAnAcceptedRow() = runTest {
        // The failure this key exists to prevent: the relay re-delivers
        // the offer, and a chat the person already accepted goes back to
        // asking them to accept it.
        val store = InMemoryPendingChatStore()
        val row = chat(group = 0x11, owner = owner)
        store.insert(row)
        store.setStatus(row.id, PendingChat.Status.Requested)

        store.insert(row)

        assertEquals(PendingChat.Status.Requested, store.list().single().status)
    }

    @Test
    fun refreshOffer_replacesTheReplyChannelButNotTheStatus() = runTest {
        // A re-invite mints a fresh intro key and revokes the old one.
        // Keeping the first would seal Accept to a dead address — sent,
        // never heard, waiting forever.
        val store = InMemoryPendingChatStore()
        val row = chat(group = 0x11, owner = owner)
        store.insert(row)
        store.setStatus(row.id, PendingChat.Status.Requested)

        store.refreshOffer(
            id = row.id,
            introPublicKey = ByteArray(32) { 0x99.toByte() },
            groupName = "Maple Garden (renamed)",
            inviterAlias = "Alice",
            invitationMessage = null,
            receivedAt = row.receivedAt.plusSeconds(60),
        )

        val stored = store.list().single()
        assertTrue(stored.introPublicKey.contentEquals(ByteArray(32) { 0x99.toByte() }))
        assertEquals("Maple Garden (renamed)", stored.groupName)
        assertNull(stored.invitationMessage)
        assertEquals(PendingChat.Status.Requested, stored.status)
    }

    @Test
    fun refreshOffer_declinesAnOfferOlderThanTheOneStored() = runTest {
        // `receivedAt` is the sender's stamp, not this device's clock,
        // so a relay replaying the retained original after a re-invite
        // arrives last carrying the earlier time. Taking it would
        // restore the revoked intro key.
        val store = InMemoryPendingChatStore()
        val row = chat(group = 0x11, owner = owner, receivedAt = Instant.ofEpochSecond(2_000))
        store.insert(row)

        store.refreshOffer(
            id = row.id,
            introPublicKey = ByteArray(32) { 0x99.toByte() },
            groupName = "from the past",
            inviterAlias = "Alice",
            invitationMessage = null,
            receivedAt = Instant.ofEpochSecond(1_000),
        )

        val stored = store.list().single()
        assertTrue(stored.introPublicKey.contentEquals(ByteArray(32) { 0x44 }))
        assertEquals("Maple Garden", stored.groupName)
        assertEquals(Instant.ofEpochSecond(2_000), stored.receivedAt)
    }

    @Test
    fun refreshOffer_declinesTheSameOfferDeliveredTwice() = runTest {
        // Equal stamps are the same offer arriving over two relays, not
        // a re-invite. Nothing to take, and taking it would churn the
        // row's sort position for no reason.
        val store = InMemoryPendingChatStore()
        val row = chat(group = 0x11, owner = owner)
        store.insert(row)

        store.refreshOffer(
            id = row.id,
            introPublicKey = ByteArray(32) { 0x99.toByte() },
            groupName = "Maple Garden",
            inviterAlias = "Alice",
            invitationMessage = null,
            receivedAt = row.receivedAt,
        )

        assertTrue(store.list().single().introPublicKey.contentEquals(ByteArray(32) { 0x44 }))
    }

    @Test
    fun refreshOffer_advancesTheTimestampWithTheKeyItTook() = runTest {
        // So the row's stamp always names the offer whose reply channel
        // it holds — which is what makes the comparison above
        // monotonic rather than a race against the first delivery.
        val store = InMemoryPendingChatStore()
        val row = chat(group = 0x11, owner = owner, receivedAt = Instant.ofEpochSecond(1_000))
        store.insert(row)

        store.refreshOffer(
            id = row.id,
            introPublicKey = ByteArray(32) { 0x99.toByte() },
            groupName = "Maple Garden",
            inviterAlias = "Alice",
            invitationMessage = null,
            receivedAt = Instant.ofEpochSecond(3_000),
        )

        assertEquals(Instant.ofEpochSecond(3_000), store.list().single().receivedAt)
    }

    @Test
    fun refreshOffer_acceptsASenderStampedOfferAfterAClockAheadLinkJoin() = runTest {
        val store = InMemoryPendingChatStore()
        val linkJoin = chat(
            group = 0x11,
            owner = owner,
            receivedAt = Instant.ofEpochSecond(9_000),
            offerReceivedAt = null,
        )
        store.insert(linkJoin)

        store.refreshOffer(
            id = linkJoin.id,
            introPublicKey = ByteArray(32) { 0x99.toByte() },
            groupName = "Maple Garden",
            inviterAlias = "Alice",
            invitationMessage = null,
            receivedAt = Instant.ofEpochSecond(2_000),
        )

        val stored = store.list().single()
        assertTrue(stored.introPublicKey.contentEquals(ByteArray(32) { 0x99.toByte() }))
        assertEquals(Instant.ofEpochSecond(2_000), stored.offerReceivedAt)
    }

    // ─── Room store: a database that cannot be read ──────────────

    @Test
    fun roomInsert_reportsFailedWhenTheFirstReadThrows() = runTest {
        // Room opens the file lazily, so a missing or corrupt database
        // throws at the first DAO call rather than at construction. An
        // escaping throwable cancels the recording coroutine; the
        // caller is written to handle FAILED.
        val store = roomStore(FailingPendingChatDao(failCount = true))

        assertEquals(
            PendingChatWriteOutcome.FAILED,
            store.insert(chat(group = 0x11, owner = owner)),
        )
    }

    @Test
    fun roomInsert_reportsFailedWhenTheRaceRecoveryReadThrows() = runTest {
        // The insert-race path re-reads to tell "another writer got
        // there first" from "the write failed". If the database is what
        // is broken, that read fails too — and the honest answer is
        // still FAILED, not a crash on the way to it.
        val store = roomStore(
            FailingPendingChatDao(
                failCount = false,
                failInsert = true,
                failRecoveryCount = true,
            ),
        )

        assertEquals(
            PendingChatWriteOutcome.FAILED,
            store.insert(chat(group = 0x11, owner = owner)),
        )
    }

    @Test
    fun failoverStore_switchesToMemoryWhenRoomFailsLazily() = runTest {
        val store = FailoverPendingChatStore(
            roomStore(FailingPendingChatDao(failCount = true)),
        )
        val row = chat(group = 0x11, owner = owner)

        assertEquals(PendingChatWriteOutcome.INSERTED, store.insert(row))
        assertEquals(listOf(row), store.list())
        store.setStatus(row.id, PendingChat.Status.Requested)
        assertEquals(PendingChat.Status.Requested, store.list().single().status)
    }

    @Test
    fun roomRefreshOffer_handsTheTimestampToTheGuardedUpdate() = runTest {
        // The "newer wins" comparison lives in the UPDATE's WHERE
        // clause, so the value reaching the DAO is what decides it.
        val dao = RecordingPendingChatDao()
        val store = roomStore(dao)
        val row = chat(group = 0x11, owner = owner, receivedAt = Instant.ofEpochSecond(2_000))

        store.refreshOffer(
            id = row.id,
            introPublicKey = ByteArray(32) { 0x99.toByte() },
            groupName = null,
            inviterAlias = "Alice",
            invitationMessage = null,
            receivedAt = Instant.ofEpochSecond(2_000),
        )

        assertEquals(listOf(2_000_000L), dao.refreshedAtMillis)
    }

    private fun roomStore(dao: PendingChatDao) = RoomPendingChatStore(
        dao = dao,
        encryption = StorageEncryption(
            SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES"),
        ),
    )

    @Test
    fun setStatus_carriesTheFailureCode() = runTest {
        val store = InMemoryPendingChatStore()
        val row = chat(group = 0x11, owner = owner)
        store.insert(row)

        store.setStatus(row.id, PendingChat.Status.Failed(PendingChat.SendFailure.NO_IDENTITY))

        assertEquals(
            PendingChat.Status.Failed(PendingChat.SendFailure.NO_IDENTITY),
            store.list().single().status,
        )
    }

    @Test
    fun setStatus_onAMissingRowIsANoOp() = runTest {
        // The ordinary race: the group materializes while a join request
        // is still in flight, so the row is gone by the time the send
        // reports back.
        val store = InMemoryPendingChatStore()
        store.setStatus("nobody:home", PendingChat.Status.Requested)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun deleteForIds_leavesTheOtherIdentitysRowForTheSameGroup() = runTest {
        // Deleting by group alone took the other identity's waiting room
        // with it the moment this one got in.
        val store = InMemoryPendingChatStore()
        val mine = chat(group = 0x11, owner = owner)
        val theirs = chat(group = 0x11, owner = other)
        store.insert(mine)
        store.insert(theirs)

        store.deleteForIds(setOf(mine.id))

        assertEquals(listOf(other), store.list().map { it.ownerIdentityId })
    }

    @Test
    fun list_isNewestFirst() = runTest {
        val store = InMemoryPendingChatStore()
        val old = chat(group = 0x11, owner = owner, receivedAt = Instant.ofEpochSecond(100))
        val recent = chat(group = 0x22, owner = owner, receivedAt = Instant.ofEpochSecond(200))
        store.insert(old)
        store.insert(recent)

        assertEquals(
            listOf(recent.groupIdHex, old.groupIdHex),
            store.list().map { it.groupIdHex },
        )
    }

    @Test
    fun deleteOwner_cascadesForARemovedIdentity() = runTest {
        val store = InMemoryPendingChatStore()
        store.insert(chat(group = 0x11, owner = owner))
        store.insert(chat(group = 0x22, owner = other))

        store.deleteOwner(owner)

        assertEquals(listOf(other), store.list().map { it.ownerIdentityId })
    }

    private fun chat(
        group: Int,
        owner: IdentityId,
        receivedAt: Instant = Instant.ofEpochSecond(1_000),
        status: PendingChat.Status = PendingChat.Status.Offered,
        offerReceivedAt: Instant? = receivedAt,
    ) = PendingChat(
        groupId = ByteArray(32) { group.toByte() },
        ownerIdentityId = owner,
        introPublicKey = ByteArray(32) { 0x44 },
        groupName = "Maple Garden",
        inviterAlias = "Alice",
        invitationMessage = "come in",
        receivedAt = receivedAt,
        status = status,
        offerReceivedAt = offerReceivedAt,
    )
}

/**
 * A DAO whose reads or writes fail the way a missing or corrupt
 * database does — at the call, not at construction, because Room opens
 * the file lazily. Only what [RoomPendingChatStore.insert] touches is
 * implemented with any behavior; the rest is unreachable in these tests.
 */
private class FailingPendingChatDao(
    private val failCount: Boolean,
    private val failInsert: Boolean = false,
    /** Fail the *second* count — the insert-race recovery read, which
     *  runs only after [insert] has already thrown. */
    private val failRecoveryCount: Boolean = false,
) : PendingChatDao {
    private var counts = 0

    override suspend fun list(): List<PersistedPendingChat> = error("unreachable")

    override suspend fun count(id: String): Int {
        counts += 1
        val fails = if (counts == 1) failCount else failRecoveryCount
        if (fails) throw IllegalStateException("database is not readable")
        return 0
    }

    override suspend fun insert(row: PersistedPendingChat) {
        if (failInsert) throw IllegalStateException("database is not writable")
    }

    override suspend fun setStatus(id: String, statusRaw: String, failureRaw: String?) = Unit

    override suspend fun refreshOffer(
        id: String,
        introPublicKey: ByteArray,
        groupName: ByteArray?,
        inviterAlias: ByteArray,
        invitationMessage: ByteArray?,
        receivedAtMillis: Long,
    ) = Unit

    override suspend fun setJoinerLabel(id: String, label: ByteArray) = Unit

    override suspend fun setRules(id: String, rules: ByteArray?) = Unit

    override suspend fun refreshReplyKey(id: String, introPublicKey: ByteArray) = Unit

    override suspend fun delete(id: String) = Unit
    override suspend fun deleteForIds(ids: Set<String>) = Unit
    override suspend fun deleteOwner(ownerIdentityId: String) = Unit
}

/** Records what reached the guarded UPDATE. */
private class RecordingPendingChatDao : PendingChatDao {
    val refreshedAtMillis = mutableListOf<Long>()

    override suspend fun list(): List<PersistedPendingChat> = emptyList()
    override suspend fun count(id: String): Int = 0
    override suspend fun insert(row: PersistedPendingChat) = Unit
    override suspend fun setStatus(id: String, statusRaw: String, failureRaw: String?) = Unit

    override suspend fun refreshOffer(
        id: String,
        introPublicKey: ByteArray,
        groupName: ByteArray?,
        inviterAlias: ByteArray,
        invitationMessage: ByteArray?,
        receivedAtMillis: Long,
    ) {
        refreshedAtMillis.add(receivedAtMillis)
    }

    override suspend fun setJoinerLabel(id: String, label: ByteArray) = Unit

    override suspend fun setRules(id: String, rules: ByteArray?) = Unit

    override suspend fun refreshReplyKey(id: String, introPublicKey: ByteArray) = Unit

    override suspend fun delete(id: String) = Unit
    override suspend fun deleteForIds(ids: Set<String>) = Unit
    override suspend fun deleteOwner(ownerIdentityId: String) = Unit
}

/** A store that seals nothing while [rejecting] — the encode failure a
 *  `StorageEncryption` fault produces, without needing one. */
private class RejectingEncodeStore(
    var rejecting: Boolean = true,
    private val delegate: PendingChatStore = InMemoryPendingChatStore(),
) : PendingChatStore by delegate {
    val written = mutableListOf<PendingChat>()

    override suspend fun insert(chat: PendingChat): PendingChatWriteOutcome {
        if (rejecting) return PendingChatWriteOutcome.NOT_ENCRYPTABLE
        written.add(chat)
        return delegate.insert(chat)
    }
}
