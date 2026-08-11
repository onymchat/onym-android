package app.onym.android.chats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.chain.SepGroupType
import app.onym.android.foundation.StorageEncryption
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Covers the system-message surface introduced when join requests moved
 * into the chat thread: persistence of the new `systemEvent` column, the
 * unread carve-out, and the idempotence that keeps relay replays from
 * stacking duplicate "X joined" rows.
 *
 * Mirrors `ChatSystemEventTests.swift` in onym-ios.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ChatSystemEventTest {

    private lateinit var db: MessageDatabase
    private lateinit var store: RoomMessageStore

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, MessageDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomMessageStore(
            dao = db.messageDao(),
            encryption = StorageEncryption(
                SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
            ),
        )
    }

    @After
    fun tearDown() { db.close() }

    private val owner = "owner-1"
    private val groupId = "aa".repeat(32)

    private fun systemMessage(
        event: ChatSystemEvent,
        sentAtMillis: Long = 1_700_000_000_000L,
        id: UUID = UUID.randomUUID(),
    ) = ChatMessage(
        id = id,
        groupId = groupId,
        ownerIdentityId = owner,
        senderBlsPubkeyHex = "22".repeat(48),
        body = "",
        sentAtMillis = sentAtMillis,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.READ,
        groupType = SepGroupType.TYRANNY,
        systemEvent = event,
    )

    private fun ordinaryMessage(sentAtMillis: Long = 1_700_000_000_000L) = ChatMessage(
        id = UUID.randomUUID(),
        groupId = groupId,
        ownerIdentityId = owner,
        senderBlsPubkeyHex = "11".repeat(48),
        body = "hello",
        sentAtMillis = sentAtMillis,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.SENT,
        groupType = SepGroupType.TYRANNY,
    )

    // ─── Persistence ──────────────────────────────────────────────

    @Test
    fun systemEvent_roundTripsThroughTheStore() = runTest {
        store.insert(systemMessage(ChatSystemEvent.MemberJoined("Alice")))

        val listed = store.listForGroup(owner, groupId)
        assertEquals(1, listed.size)
        assertEquals(ChatSystemEvent.MemberJoined("Alice"), listed.first().systemEvent)
        assertTrue(listed.first().isSystem)
    }

    @Test
    fun youJoined_roundTripsThroughTheStore() = runTest {
        store.insert(systemMessage(ChatSystemEvent.YouJoined("Book club")))

        assertEquals(
            ChatSystemEvent.YouJoined("Book club"),
            store.listForGroup(owner, groupId).first().systemEvent,
        )
    }

    @Test
    fun ordinaryMessage_hasNoSystemEvent() = runTest {
        store.insert(ordinaryMessage())

        val listed = store.listForGroup(owner, groupId)
        assertNull(listed.first().systemEvent)
        assertFalse(listed.first().isSystem)
    }

    // ─── Unread ───────────────────────────────────────────────────

    /** "Alice joined" lands on every member's device at once. Counting
     *  it would light up an unread badge on a chat where nobody said
     *  anything. */
    @Test
    fun unreadCount_ignoresSystemNotices() = runTest {
        store.insert(systemMessage(ChatSystemEvent.MemberJoined("Alice")))

        assertEquals(0, store.unreadCount(owner, groupId, 0L))

        store.insert(ordinaryMessage())
        assertEquals(1, store.unreadCount(owner, groupId, 0L))
    }

    // ─── Chat-list preview ────────────────────────────────────────

    /** The sentence is assembled in the UI (where string resources are
     *  reachable), so the Core-level preview stays empty rather than
     *  emitting hardcoded English with no `values-ru` twin. */
    @Test
    fun chatListPreview_isEmptyForNotices() {
        assertEquals("", systemMessage(ChatSystemEvent.MemberJoined("Alice")).chatListPreview)
        assertEquals("hello", ordinaryMessage().chatListPreview)
    }

    /**
     * A notice must not become the chat-list subtitle.
     *
     * Its `chatListPreview` is empty by design (the sentence is built in
     * the UI, where string resources are reachable), so letting a
     * newest-row "Bob joined" win blanked the subtitle and made the
     * group's last real message vanish from the list.
     */
    @Test
    fun latestMessage_skipsNoticesSoTheSubtitleSurvives() = runTest {
        store.insert(ordinaryMessage(sentAtMillis = 1_000L))
        store.insert(
            systemMessage(ChatSystemEvent.MemberJoined("Bob"), sentAtMillis = 2_000L)
        )

        val latest = store.latestMessage(owner, groupId)
        assertEquals("hello", latest?.chatListPreview)
    }

    // ─── Idempotence ──────────────────────────────────────────────

    /** Relays replay the full inbox on every reconnect. The recorder's
     *  derived ids are the backstop behind the callers' dedup guards:
     *  the same join recorded twice must collapse to one row, not stack
     *  a fresh "Alice joined" on every launch. */
    @Test
    fun derivedIds_collapseReplaysOfTheSameJoin() {
        val a = ChatSystemEventRecorder.derivedId(groupId, owner, "member-joined:22")
        val b = ChatSystemEventRecorder.derivedId(groupId, owner, "member-joined:22")
        assertEquals(a, b)
    }

    @Test
    fun derivedIds_differPerJoiner() {
        val alice = ChatSystemEventRecorder.derivedId(groupId, owner, "member-joined:22")
        val bob = ChatSystemEventRecorder.derivedId(groupId, owner, "member-joined:33")
        assertTrue(alice != bob)
    }

    /** Two identities on one device can both be in the same group; each
     *  keeps its own thread, so each needs its own notice. */
    @Test
    fun derivedIds_differPerOwner() {
        val mine = ChatSystemEventRecorder.derivedId(groupId, "owner-1", "member-joined:22")
        val theirs = ChatSystemEventRecorder.derivedId(groupId, "owner-2", "member-joined:22")
        assertTrue(mine != theirs)
    }

    @Test
    fun derivedIds_areWellFormedV4Uuids() {
        val id = ChatSystemEventRecorder.derivedId(groupId, owner, "you-joined")
        assertEquals(4, id.version())
        assertEquals(2, id.variant())
    }
}
