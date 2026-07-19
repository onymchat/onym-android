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

    @Test
    fun insert_thenList_roundtripsVideoAttachment() = runTest {
        val video = ChatVideoAttachment(
            sha256 = "ef".repeat(32),
            mimeType = "video/mp4",
            byteSize = 4_200_000,
            width = 1280,
            height = 720,
            durationSeconds = 12.5,
            encKey = ByteArray(32) { 0x9 },
            poster = ChatImageAttachment(
                sha256 = "cd".repeat(32),
                mimeType = "image/jpeg",
                byteSize = 51_234,
                width = 1280,
                height = 720,
                encKey = ByteArray(32) { 0x7 },
                blurhash = "LEHV6nWB2yk8",
                server = "https://blossom.onym.app",
            ),
            server = "https://blossom.onym.app",
        )
        val msg = makeMessage(body = "caption").copy(videoAttachment = video)
        store.insert(msg)

        val listed = store.listForGroup(msg.ownerIdentityId, msg.groupId).single()
        assertEquals(video, listed.videoAttachment)
        assertNull(listed.imageAttachment)
        assertEquals("caption", listed.body)
    }

    @Test
    fun insert_thenList_roundtripsVoiceAttachment() = runTest {
        val voice = ChatVoiceAttachment(
            sha256 = "77".repeat(32),
            mimeType = "audio/mp4",
            byteSize = 48_000,
            durationSeconds = 6.0,
            encKey = ByteArray(32) { 0x5 },
            waveform = (0 until 40).map { it % 256 },
            server = "https://blossom.onym.app",
        )
        val msg = makeMessage(body = "").copy(voiceAttachment = voice)
        store.insert(msg)

        val listed = store.listForGroup(msg.ownerIdentityId, msg.groupId).single()
        assertEquals(voice, listed.voiceAttachment)
        assertNull(listed.imageAttachment)
        assertNull(listed.videoAttachment)
    }

    @Test
    fun insert_thenList_roundtripsAlbumAttachments() = runTest {
        val image = ChatImageAttachment(
            sha256 = "11".repeat(32),
            mimeType = "image/jpeg",
            byteSize = 12_000,
            width = 240,
            height = 160,
            encKey = ByteArray(32) { 0x1 },
            blurhash = "LEHV6nWB2yk8",
            server = "https://blossom.onym.app",
        )
        val video = ChatVideoAttachment(
            sha256 = "22".repeat(32),
            mimeType = "video/mp4",
            byteSize = 4_200_000,
            width = 1280,
            height = 720,
            durationSeconds = 4.0,
            encKey = ByteArray(32) { 0x2 },
            poster = ChatImageAttachment(
                sha256 = "33".repeat(32),
                mimeType = "image/jpeg",
                byteSize = 40_000,
                width = 1280,
                height = 720,
                encKey = ByteArray(32) { 0x3 },
                blurhash = "LEHV6nWB2yk8",
                server = "https://blossom.onym.app",
            ),
            server = "https://blossom.onym.app",
        )
        val album = listOf(
            ChatMediaAttachment.image(image),
            ChatMediaAttachment.video(video),
        )
        val msg = makeMessage(body = "").copy(albumAttachments = album)
        store.insert(msg)

        val listed = store.listForGroup(msg.ownerIdentityId, msg.groupId).single()
        assertEquals(album, listed.albumAttachments)
        assertEquals(2, listed.media.size)
    }

    // ─── search ───────────────────────────────────────────────────

    @Test
    fun search_matchesBodySubstring_caseInsensitive_newestFirst() = runTest {
        store.insert(makeMessage(group = "aa".repeat(32), body = "Let's meet at noon",
            sentAtMillis = 1_700_000_000_000L))
        store.insert(makeMessage(group = "bb".repeat(32), body = "MEETING moved to 3pm",
            sentAtMillis = 1_700_000_500_000L))
        store.insert(makeMessage(group = "aa".repeat(32), body = "unrelated chatter",
            sentAtMillis = 1_700_000_900_000L))

        val hits = store.search(ownerIdentityId = "test-owner", query = "meet", limit = 200)
        assertEquals(listOf("MEETING moved to 3pm", "Let's meet at noon"), hits.map { it.body })
    }

    @Test
    fun search_isOwnerScoped() = runTest {
        store.insert(makeMessage(owner = "alice", body = "secret plan A"))
        store.insert(makeMessage(owner = "bob", body = "secret plan B"))

        val hits = store.search(ownerIdentityId = "alice", query = "secret", limit = 200)
        assertEquals(listOf("secret plan A"), hits.map { it.body })
    }

    @Test
    fun search_emptyQuery_returnsNothing() = runTest {
        store.insert(makeMessage(body = "anything"))
        assertTrue(store.search("test-owner", "   ", 200).isEmpty())
    }

    @Test
    fun search_respectsLimit() = runTest {
        repeat(5) { i ->
            store.insert(makeMessage(body = "match $i", sentAtMillis = 1_700_000_000_000L + i))
        }
        assertEquals(3, store.search("test-owner", "match", 3).size)
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
    fun deleteAll_removesEveryRowAcrossOwnersAndGroups() = runTest {
        val groupA = "aa".repeat(32)
        val groupB = "bb".repeat(32)
        store.insert(makeMessage(owner = "alice", group = groupA, body = "a-A"))
        store.insert(makeMessage(owner = "alice", group = groupB, body = "a-B"))
        store.insert(makeMessage(owner = "bob", group = groupA, body = "b-A"))

        val deleted = store.deleteAll()
        assertEquals(3, deleted)
        assertTrue(store.listForGroup("alice", groupA).isEmpty())
        assertTrue(store.listForGroup("alice", groupB).isEmpty())
        assertTrue(store.listForGroup("bob", groupA).isEmpty())
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

    @Test
    fun deleteById_removesOnlyThatOwnersRow() = runTest {
        val groupId = "aa".repeat(32)
        val sharedId = UUID.randomUUID()
        store.insert(makeMessage(owner = "alice", group = groupId, body = "mine").copy(id = sharedId))
        store.insert(makeMessage(owner = "bob", group = groupId, body = "theirs").copy(id = sharedId))

        val deleted = store.deleteById(sharedId, "alice")
        assertEquals(1, deleted)
        assertTrue(store.listForGroup("alice", groupId).isEmpty())
        // Bob's copy of the same wire id is untouched.
        assertEquals(1, store.listForGroup("bob", groupId).size)
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
        direction: MessageDirection = MessageDirection.OUTGOING,
    ): ChatMessage = ChatMessage(
        id = UUID.randomUUID(),
        groupId = group,
        ownerIdentityId = owner,
        senderBlsPubkeyHex = sender,
        body = body,
        sentAtMillis = sentAtMillis,
        direction = direction,
        status = MessageStatus.PENDING,
        replyToMessageId = replyToMessageId,
        groupType = SepGroupType.TYRANNY,
    )

    @Test
    fun latestMessage_returnsMostRecentBySentAt_decrypted() = runTest {
        val group = "aa".repeat(32)
        store.insert(makeMessage(body = "old", group = group, sentAtMillis = 1_000))
        store.insert(makeMessage(body = "newest", group = group, sentAtMillis = 3_000))
        store.insert(makeMessage(body = "middle", group = group, sentAtMillis = 2_000))

        val latest = store.latestMessage("test-owner", group)
        assertEquals("newest", latest?.body)
        assertNull(store.latestMessage("test-owner", "bb".repeat(32)))
    }

    @Test
    fun unreadCount_countsIncomingAfterMarker() = runTest {
        val group = "aa".repeat(32)
        store.insert(makeMessage(body = "seen", group = group, sentAtMillis = 1_000,
            direction = MessageDirection.INCOMING))
        store.insert(makeMessage(body = "u1", group = group, sentAtMillis = 2_000,
            direction = MessageDirection.INCOMING))
        store.insert(makeMessage(body = "u2", group = group, sentAtMillis = 3_000,
            direction = MessageDirection.INCOMING))
        store.insert(makeMessage(body = "mine", group = group, sentAtMillis = 4_000,
            direction = MessageDirection.OUTGOING))

        assertEquals(2, store.unreadCount("test-owner", group, sinceMillis = 1_500))
        assertEquals(3, store.unreadCount("test-owner", group, sinceMillis = 0))
    }
}
