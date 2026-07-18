package app.onym.android.chats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.chain.SepGroupType
import app.onym.android.persistence.StorageEncryption
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/**
 * Round-trip tests for [RoomMessageStore]. Uses
 * `Room.inMemoryDatabaseBuilder` so the on-disk store isn't
 * touched. Encrypted columns go through a real [StorageEncryption]
 * backed by a fresh AES key per test (no Robolectric Keystore
 * dance).
 *
 * Mirrors `SwiftDataMessageStoreTests.swift` from onym-ios PR #148.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomMessageStoreTest {

    private lateinit var db: MessageDatabase
    private lateinit var store: RoomMessageStore
    private lateinit var encryption: StorageEncryption

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, MessageDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        encryption = StorageEncryption(
            SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES"),
        )
        store = RoomMessageStore(db.messageDao(), encryption)
    }

    @After
    fun tearDown() { db.close() }

    // ─── round-trip ───────────────────────────────────────────────

    @Test
    fun insert_thenList_roundtripsAllFields() = runTest {
        val msg = makeMessage(body = "hello", sender = "ab".repeat(48))
        store.insert(msg)

        val listed = store.listForGroup(msg.ownerIdentityId, msg.groupId)
        assertEquals(1, listed.size)
        val first = listed.single()
        assertEquals(msg.id, first.id)
        assertEquals(msg.groupId, first.groupId)
        assertEquals(msg.ownerIdentityId, first.ownerIdentityId)
        assertEquals("ab".repeat(48), first.senderBlsPubkeyHex)
        assertEquals("hello", first.body)
        assertEquals(msg.sentAtMillis, first.sentAtMillis)
        assertEquals(MessageDirection.OUTGOING, first.direction)
        assertEquals(MessageStatus.PENDING, first.status)
        assertEquals(SepGroupType.TYRANNY, first.groupType)
        assertNull("a non-reply message round-trips a null reply target", first.replyToMessageId)
    }

    // ─── reply reference round-trip ───────────────────────────────

    @Test
    fun insert_thenList_roundtripsReplyTarget() = runTest {
        val target = UUID.randomUUID()
        val msg = makeMessage(body = "agreed", replyToMessageId = target)
        store.insert(msg)

        val first = store.listForGroup(msg.ownerIdentityId, msg.groupId).single()
        assertEquals(target, first.replyToMessageId)
        // The pointer is a plain column — readable on the raw row too.
        assertEquals(
            target.toString(),
            db.messageDao().findByIdAndOwner(msg.id.toString(), msg.ownerIdentityId)!!.replyToMessageId,
        )
    }

    // ─── encryption-at-rest ───────────────────────────────────────

    @Test
    fun encryptedColumns_doNotContainPlaintext() = runTest {
        val plaintextBody = "secret payload".toByteArray(Charsets.UTF_8)
        val msg = makeMessage(body = "secret payload")
        store.insert(msg)

        val raw = db.messageDao().findByIdAndOwner(msg.id.toString(), msg.ownerIdentityId)!!
        assertNotEquals(
            "encryptedBody must not contain the plaintext body",
            plaintextBody.toList(),
            raw.encryptedBody.toList(),
        )
        // Layout sanity: nonce(12) + ciphertext("secret payload", 14 bytes) + tag(16) = 42B.
        assertEquals(
            StorageEncryption.NONCE_SIZE + plaintextBody.size + StorageEncryption.TAG_SIZE,
            raw.encryptedBody.size,
        )
        // …and the seam decrypts it back to plaintext.
        assertEquals("secret payload", store.listForGroup(msg.ownerIdentityId, msg.groupId).single().body)
    }

    // ─── updateStatus hot path ────────────────────────────────────

    @Test
    fun updateStatus_changesStatusWithoutTouchingEncryptedColumns() = runTest {
        val msg = makeMessage(body = "draft")
        store.insert(msg)
        val originalRow = db.messageDao().findByIdAndOwner(msg.id.toString(), msg.ownerIdentityId)!!

        store.updateStatus(msg.id, msg.ownerIdentityId, MessageStatus.SENT)

        val updatedRow = db.messageDao().findByIdAndOwner(msg.id.toString(), msg.ownerIdentityId)!!
        assertEquals(MessageStatus.SENT.name, updatedRow.statusRaw)
        // Hot path skips the encryption round-trip: encryptedBody +
        // encryptedSenderBlsPubkeyHex bytes are unchanged byte-for-byte.
        assertEquals(originalRow.encryptedBody.toList(), updatedRow.encryptedBody.toList())
        assertEquals(
            originalRow.encryptedSenderBlsPubkeyHex.toList(),
            updatedRow.encryptedSenderBlsPubkeyHex.toList(),
        )
        // Domain view reflects the status flip.
        assertEquals(
            MessageStatus.SENT,
            store.listForGroup(msg.ownerIdentityId, msg.groupId).single().status,
        )
    }

    @Test
    fun updateStatus_unknownIdIsNoOp() = runTest {
        store.updateStatus(UUID.randomUUID(), "test-owner", MessageStatus.SENT)
        assertTrue(store.listForGroup("alice", "00".repeat(32)).isEmpty())
    }

    // ─── ordering ─────────────────────────────────────────────────

    @Test
    fun listForGroup_sortsBySentAtAscending() = runTest {
        val older = makeMessage(body = "older", sentAtMillis = 1_700_000_000_000L)
        val newer = makeMessage(body = "newer", sentAtMillis = 1_700_000_500_000L)
        // insert in reverse so we know ordering isn't insertion-order coincidence.
        store.insert(newer)
        store.insert(older)

        val listed = store.listForGroup(older.ownerIdentityId, older.groupId)
        assertEquals(listOf("older", "newer"), listed.map { it.body })
    }

    // ─── per-(owner, group) filter ────────────────────────────────

    @Test
    fun listForGroup_filtersByOwnerAndGroup() = runTest {
        val aliceGroupA = makeMessage(owner = "alice", group = "aa".repeat(32), body = "a-A")
        val aliceGroupB = makeMessage(owner = "alice", group = "bb".repeat(32), body = "a-B")
        val bobGroupA = makeMessage(owner = "bob", group = "aa".repeat(32), body = "b-A")
        store.insert(aliceGroupA)
        store.insert(aliceGroupB)
        store.insert(bobGroupA)

        val out = store.listForGroup("alice", "aa".repeat(32))
        assertEquals(1, out.size)
        assertEquals("a-A", out.single().body)
    }

    // ─── cascade deletes ──────────────────────────────────────────

    @Test
    fun deleteForOwner_removesEveryRowForThatOwner() = runTest {
        val alice1 = makeMessage(owner = "alice", body = "a1")
        val alice2 = makeMessage(owner = "alice", body = "a2")
        val bob1 = makeMessage(owner = "bob", body = "b1")
        store.insert(alice1)
        store.insert(alice2)
        store.insert(bob1)

        val deleted = store.deleteForOwner("alice")
        assertEquals(2, deleted)
        assertTrue(store.listForGroup("alice", alice1.groupId).isEmpty())
        assertEquals(1, store.listForGroup("bob", bob1.groupId).size)
    }

    @Test
    fun deleteForGroup_removesEveryRowForThatGroup() = runTest {
        val groupA = "aa".repeat(32)
        val groupB = "bb".repeat(32)
        store.insert(makeMessage(owner = "alice", group = groupA, body = "a-A1"))
        store.insert(makeMessage(owner = "alice", group = groupA, body = "a-A2"))
        store.insert(makeMessage(owner = "alice", group = groupB, body = "a-B1"))

        val deleted = store.deleteForGroup(groupA, "alice")
        assertEquals(2, deleted)
        assertTrue(store.listForGroup("alice", groupA).isEmpty())
        assertEquals(1, store.listForGroup("alice", groupB).size)
    }

    // ─── multi-identity (same wire id, two owners) ────────────────

    @Test
    fun insert_sameIdTwoOwners_keepsBothRowsWithOwnDirection() = runTest {
        // Regression: the same wire message fanned out to two local
        // identities must keep a row per identity. Before the composite
        // (id, ownerIdentityId) key the second insert's IGNORE dropped
        // it — the second identity never saw the message, and a shared
        // row could flip an outgoing message to incoming.
        val groupId = "aa".repeat(32)
        val sharedId = UUID.randomUUID()
        val outgoing = makeMessage(owner = "owner-a", group = groupId, body = "mine")
            .copy(id = sharedId, direction = MessageDirection.OUTGOING)
        val incoming = makeMessage(owner = "owner-b", group = groupId, body = "mine")
            .copy(id = sharedId, direction = MessageDirection.INCOMING)

        assertTrue(store.insert(outgoing))
        assertTrue("second owner is a fresh insert, not an IGNORE", store.insert(incoming))

        assertEquals(
            listOf(MessageDirection.OUTGOING),
            store.listForGroup("owner-a", groupId).map { it.direction },
        )
        assertEquals(
            listOf(MessageDirection.INCOMING),
            store.listForGroup("owner-b", groupId).map { it.direction },
        )
    }

    @Test
    fun deleteForGroup_isScopedToOwner() = runTest {
        val groupId = "aa".repeat(32)
        store.insert(makeMessage(owner = "owner-a", group = groupId, body = "a"))
        store.insert(makeMessage(owner = "owner-b", group = groupId, body = "b"))

        store.deleteForGroup(groupId, "owner-a")

        assertTrue(store.listForGroup("owner-a", groupId).isEmpty())
        assertEquals(
            "deleting one identity's thread leaves the other's copy",
            1,
            store.listForGroup("owner-b", groupId).size,
        )
    }

    // ─── tolerant decode ──────────────────────────────────────────

    @Test
    fun decode_skipsRowsWithUnknownEnumValues() = runTest {
        val good = makeMessage(body = "ok")
        store.insert(good)
        // Insert a row directly via the DAO with a bogus groupTypeRaw —
        // decoder must skip it, leaving the good row.
        val bogusRow = PersistedMessage(
            id = UUID.randomUUID().toString(),
            groupId = good.groupId,
            ownerIdentityId = good.ownerIdentityId,
            sentAt = good.sentAtMillis + 1,
            directionRaw = MessageDirection.INCOMING.name,
            statusRaw = MessageStatus.RECEIVED.name,
            groupTypeRaw = "spaghetti",
            encryptedSenderBlsPubkeyHex = encryption.encrypt("aa".repeat(48)),
            encryptedBody = encryption.encrypt("x"),
        )
        db.messageDao().insert(bogusRow)
        // Sanity: row was actually written.
        assertNotNull(db.messageDao().findByIdAndOwner(bogusRow.id, bogusRow.ownerIdentityId))

        val listed = store.listForGroup(good.ownerIdentityId, good.groupId)
        assertEquals("bogus row must be filtered out", 1, listed.size)
        assertEquals(good.id, listed.single().id)
    }

    // ─── empty case ───────────────────────────────────────────────

    @Test
    fun listForGroup_unknownGroupReturnsEmptyList() = runTest {
        assertTrue(store.listForGroup("nobody", "ff".repeat(32)).isEmpty())
    }

    @Suppress("unused")
    private fun assertNullOk(value: Any?) { assertNull(value) }

    // ─── helpers ──────────────────────────────────────────────────

    private fun makeMessage(
        body: String,
        owner: String = "test-owner",
        group: String = "aa".repeat(32),
        sender: String = "cc".repeat(48),
        sentAtMillis: Long = 1_700_000_000_000L,
        replyToMessageId: UUID? = null,
    ): ChatMessage = ChatMessage(
        id = UUID.randomUUID(),
        groupId = group,
        ownerIdentityId = owner,
        senderBlsPubkeyHex = sender,
        body = body,
        sentAtMillis = sentAtMillis,
        direction = MessageDirection.OUTGOING,
        status = MessageStatus.PENDING,
        replyToMessageId = replyToMessageId,
        groupType = SepGroupType.TYRANNY,
    )
}
