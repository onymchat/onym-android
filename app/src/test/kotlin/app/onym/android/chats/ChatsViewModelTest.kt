@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupMemberRemover
import app.onym.android.group.GroupMemberRemoving
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
        remover: GroupMemberRemoving? = null,
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
        return ChatsViewModel(
            repository = groupRepo,
            messageRepository = messageRepo,
            memberRemover = remover,
        )
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

    @Test
    fun deleteChat_removesGroupAndItsMessages() = runTest {
        val groupStore = InMemoryGroupStore()
        groupStore.preload(listOf(
            makeGroup(id = "01".repeat(32), name = "DeleteMe"),
            makeGroup(id = "02".repeat(32), name = "KeepMe"),
        ))
        val messageStore = InMemoryMessageStore()
        messageStore.preload(listOf(
            incoming(group = "01".repeat(32), body = "bye", sentAt = 5_000),
            incoming(group = "02".repeat(32), body = "hi", sentAt = 6_000),
        ))
        val vm = makeVM(groupStore, messageStore)
        // List is populated with both chats first.
        vm.items.first { it.size == 2 }

        vm.deleteChat("01".repeat(32))

        // The deleted chat's row disappears; the other survives.
        val after = vm.items.first { it.size == 1 }
        assertEquals(listOf("KeepMe"), after.map { it.group.name })
        // Its messages are wiped; the other chat's messages are untouched.
        assertTrue(messageStore.listForGroup(owner.value, "01".repeat(32)).isEmpty())
        assertEquals(1, messageStore.listForGroup(owner.value, "02".repeat(32)).size)
    }

    @Test
    fun deleteChat_unknownGroup_isNoOp() = runTest {
        val groupStore = InMemoryGroupStore()
        groupStore.preload(listOf(makeGroup(id = "01".repeat(32), name = "KeepMe")))
        val vm = makeVM(groupStore)
        vm.items.first { it.isNotEmpty() }

        // A group id not present resolves no owner → nothing removed.
        vm.deleteChat("ff".repeat(32))

        assertEquals(listOf("KeepMe"), vm.items.value.map { it.group.name })
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

    // ─── member removal delegation ────────────────────────────────

    private val groupA = "aa".repeat(32)
    private val groupB = "cc".repeat(32)
    private val victim = "bb".repeat(48)

    @Test
    fun removeMember_noOpWithoutRemover() = runTest {
        val vm = makeVM(InMemoryGroupStore())
        vm.removeMember(groupA, victim)
        assertTrue(vm.removalErrors.value.isEmpty())
        assertTrue(vm.removalsInFlight.value.isEmpty())
    }

    @Test
    fun removeMember_delegatesAndClearsInFlight_onSuccess() = runTest {
        val remover = RecordingRemover(GroupMemberRemover.Outcome.Sent)
        val vm = makeVM(InMemoryGroupStore(), remover = remover)

        vm.removeMember(groupA, "BB".repeat(48))

        assertEquals(1, remover.calls.size)
        assertEquals(groupA to "BB".repeat(48), remover.calls.single())
        // Unconfined main dispatcher → the launch completed inline.
        assertTrue(vm.removalsInFlight.value.isEmpty())
        assertTrue(vm.removalErrors.value.isEmpty())
    }

    @Test
    fun removeMember_surfacesTypedOutcomePerGroup_andClearsOnRequest() = runTest {
        // The VM exposes the raw Outcome — localization is the
        // screen's job (removalErrorText), never the VM's.
        val outcome = GroupMemberRemover.Outcome.AnchorRejected("stale epoch")
        val vm = makeVM(InMemoryGroupStore(), remover = RecordingRemover(outcome))

        vm.removeMember(groupA, victim)

        assertEquals(outcome, vm.removalErrors.value[groupA])
        assertTrue(vm.removalsInFlight.value.isEmpty())
        vm.clearRemovalError(groupA)
        assertTrue(vm.removalErrors.value.isEmpty())
    }

    @Test
    fun removeMember_errorIsScopedToItsOwnGroup() = runTest {
        // The VM is app-scoped: a failure on group A must not surface
        // on group B's members screen.
        val vm = makeVM(
            InMemoryGroupStore(),
            remover = RecordingRemover(GroupMemberRemover.Outcome.NotAdminOfThisGroup),
        )

        vm.removeMember(groupA, victim)

        assertEquals(
            GroupMemberRemover.Outcome.NotAdminOfThisGroup,
            vm.removalErrors.value[groupA],
        )
        assertEquals(null, vm.removalErrors.value[groupB])
    }

    @Test
    fun removeMember_inFlightKeyIsPerGroupAndMember() = runTest {
        // A never-completing removal on group A must not block a
        // removal on group B (or a different member of group A).
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val remover = object : GroupMemberRemoving {
            val calls = mutableListOf<Pair<String, String>>()
            override suspend fun remove(
                groupId: String,
                victimBlsHex: String,
            ): GroupMemberRemover.Outcome {
                calls.add(groupId to victimBlsHex)
                gate.await()
                return GroupMemberRemover.Outcome.Sent
            }
        }
        val vm = makeVM(InMemoryGroupStore(), remover = remover)

        vm.removeMember(groupA, victim)
        vm.removeMember(groupA, victim) // duplicate → ignored
        vm.removeMember(groupB, victim) // other group → allowed
        vm.removeMember(groupA, "dd".repeat(48)) // other member → allowed

        assertEquals(3, remover.calls.size)
        assertEquals(
            setOf(
                ChatsViewModel.removalKey(groupA, victim),
                ChatsViewModel.removalKey(groupB, victim),
                ChatsViewModel.removalKey(groupA, "dd".repeat(48)),
            ),
            vm.removalsInFlight.value,
        )
        gate.complete(Unit)
    }

    private class RecordingRemover(
        private val outcome: GroupMemberRemover.Outcome,
    ) : GroupMemberRemoving {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun remove(groupId: String, victimBlsHex: String): GroupMemberRemover.Outcome {
            calls.add(groupId to victimBlsHex)
            return outcome
        }
    }

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
