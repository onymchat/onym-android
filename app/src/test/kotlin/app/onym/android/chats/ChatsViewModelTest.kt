@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupRepository
import app.onym.android.identity.IdentityId
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryMessageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Unit tests for [ChatsViewModel]. Backed by [InMemoryGroupStore] +
 * [InMemoryMessageStore] and real repositories — the in-memory stores are
 * the same ones the repository tests exercise, so these verify the VM's
 * enrich → sort → re-emit path (latest-message subtitle + unread badge +
 * recency sort) without standing up Room or `StorageEncryption`.
 */
class ChatsViewModelTest {

    private val owner = IdentityId("test-owner")

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private suspend fun makeVM(
        groupStore: InMemoryGroupStore,
        messageStore: InMemoryMessageStore = InMemoryMessageStore(),
    ): ChatsViewModel {
        val identity = FakeActiveIdentityProvider(initial = owner)
        val groupRepo = GroupRepository(
            store = groupStore,
            identity = identity,
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        // Pull the preloaded rows into the snapshot StateFlow.
        groupRepo.reload()
        val messageRepo = MessageRepository(
            store = messageStore,
            identity = identity,
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        return ChatsViewModel(repository = groupRepo, messageRepository = messageRepo)
    }

    @Test
    fun items_startEmpty_whenRepositoryHasNoRows() = runTest {
        val vm = makeVM(InMemoryGroupStore())
        assertEquals(emptyList<ChatListItem>(), vm.items.value)
    }

    @Test
    fun items_reflectRepositoryUpdates() = runTest {
        val store = InMemoryGroupStore()
        store.preload(listOf(makeGroup(id = "aa".repeat(32), name = "Friends")))
        val vm = makeVM(store)

        val snapshot = vm.items.first { it.isNotEmpty() }
        assertEquals(1, snapshot.size)
        assertEquals("Friends", snapshot.single().group.name)
        // No messages yet → no preview, zero unread.
        assertEquals(null, snapshot.single().latestPreview)
        assertEquals(0, snapshot.single().unreadCount)
    }

    @Test
    fun items_sortedByLatestMessage_thenSubtitleAndUnread() = runTest {
        val groupStore = InMemoryGroupStore()
        // "older" group created first but has the NEWEST message → it should
        // sort ahead of "newer" (created later, but no messages).
        groupStore.preload(listOf(
            makeGroup(id = "01".repeat(32), name = "older", createdAtMillis = 1_000),
            makeGroup(id = "02".repeat(32), name = "newer", createdAtMillis = 2_000),
        ))
        val messageStore = InMemoryMessageStore()
        messageStore.preload(listOf(
            incoming(group = "01".repeat(32), body = "hey there", sentAt = 9_000),
        ))
        val vm = makeVM(groupStore, messageStore)

        val snapshot = vm.items.first { it.isNotEmpty() }
        // Recency sort: the group with a message leads.
        assertEquals(listOf("older", "newer"), snapshot.map { it.group.name })
        val withMessage = snapshot.first { it.group.name == "older" }
        assertEquals("hey there", withMessage.latestPreview)
        // The group was never opened (lastReadAtMillis null → 0) so the
        // incoming message counts as unread.
        assertEquals(1, withMessage.unreadCount)
        assertTrue(snapshot.first { it.group.name == "newer" }.latestPreview == null)
    }

    private fun incoming(group: String, body: String, sentAt: Long) = ChatMessage(
        id = UUID.randomUUID(),
        groupId = group,
        ownerIdentityId = owner.value,
        senderBlsPubkeyHex = "ab".repeat(48),
        body = body,
        sentAtMillis = sentAt,
        direction = MessageDirection.INCOMING,
        status = MessageStatus.RECEIVED,
        replyToMessageId = null,
        groupType = SepGroupType.TYRANNY,
    )

    private fun makeGroup(
        id: String,
        name: String,
        createdAtMillis: Long = 1_700_000_000_000L,
    ) = ChatGroup(
        id = id,
        name = name,
        groupSecret = ByteArray(32),
        createdAtMillis = createdAtMillis,
        members = emptyList(),
        epoch = 0uL,
        salt = ByteArray(32),
        commitment = null,
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = null,
        isPublishedOnChain = false,
        ownerIdentityId = owner.value,
    )
}
