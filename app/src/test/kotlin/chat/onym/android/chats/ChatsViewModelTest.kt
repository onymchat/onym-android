@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package chat.onym.android.chats

import chat.onym.android.chain.SepGroupType
import chat.onym.android.chain.SepTier
import chat.onym.android.group.ChatGroup
import chat.onym.android.group.GroupRepository
import chat.onym.android.identity.IdentityId
import chat.onym.android.support.FakeActiveIdentityProvider
import chat.onym.android.support.InMemoryGroupStore
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

/**
 * Unit tests for [ChatsViewModel]. Backed by [InMemoryGroupStore] +
 * a real [GroupRepository] — the in-memory store is the same one
 * `GroupRepositoryTest` exercises, so these tests verify the VM's
 * subscribe → re-emit path without standing up Room or
 * `StorageEncryption`.
 *
 * Mirrors the iOS `ChatsFlow` test surface (one-test, two-test
 * minimum) from onym-ios PR #30.
 */
class ChatsViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun groups_startsEmpty_whenRepositoryHasNoRows() = runTest {
        val store = InMemoryGroupStore()
        val repository = GroupRepository(
            store = store,
            identity = FakeActiveIdentityProvider(initial = IdentityId("test-owner")),
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        repository.reload()

        val vm = ChatsViewModel(repository)

        assertEquals(emptyList<ChatGroup>(), vm.groups.value)
    }

    @Test
    fun groups_reflectsRepositoryUpdates() = runTest {
        val store = InMemoryGroupStore()
        val repository = GroupRepository(
            store = store,
            identity = FakeActiveIdentityProvider(initial = IdentityId("test-owner")),
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        repository.reload()
        val vm = ChatsViewModel(repository)
        // Touch the StateFlow so the subscribe-side collector starts
        // (SharingStarted.WhileSubscribed). Without `first()` the
        // upstream wouldn't be active when `insert` fires.
        vm.groups.first()

        repository.insert(makeGroup(id = "aa".repeat(32), name = "Friends"))

        val snapshot = vm.groups.value
        assertEquals(1, snapshot.size)
        assertEquals("Friends", snapshot.single().name)
        assertTrue("group is unpublished by default", !snapshot.single().isPublishedOnChain)
    }

    @Test
    fun groups_sortedByCreatedAtDescending() = runTest {
        val store = InMemoryGroupStore()
        val repository = GroupRepository(
            store = store,
            identity = FakeActiveIdentityProvider(initial = IdentityId("test-owner")),
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        repository.insert(makeGroup(id = "01".repeat(32), name = "older", createdAtMillis = 1_700_000_000_000L))
        repository.insert(makeGroup(id = "02".repeat(32), name = "newer", createdAtMillis = 1_700_000_500_000L))
        repository.reload()

        val vm = ChatsViewModel(repository)
        val snapshot = vm.groups.first()

        assertEquals(listOf("newer", "older"), snapshot.map { it.name })
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
            ownerIdentityId = "test-owner",
    )
}
