package app.onym.android.group

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.identity.IdentityId
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reactive-surface tests for [GroupRepository]. Backed by an
 * in-memory [GroupStore] fake and a [FakeActiveIdentityProvider] so
 * the Room layer doesn't pull in `Robolectric` / `StorageEncryption`
 * here — those are [RoomGroupStoreTest]'s responsibility.
 *
 * After PR-3, the repository is per-identity: every test uses
 * `aliceId` as the active identity so the snapshot filter resolves
 * cleanly. The cascade-delete-on-removal hook is exercised in
 * [removeListener_cascadesDelete].
 */
class GroupRepositoryTest {

    private val aliceId = IdentityId("alice-uuid")
    private val bobId = IdentityId("bob-uuid")

    @Test
    fun snapshots_replaysCurrentOnSubscribe() = runTest {
        val store = InMemoryGroupStore()
        val group = makeGroup(id = "aa".repeat(32), name = "Family", owner = aliceId)
        store.preload(listOf(group))
        val repo = makeRepo(store, active = aliceId)
        repo.reload()

        val snapshot = repo.snapshots.first()
        assertEquals(1, snapshot.size)
        assertEquals(group.id, snapshot.first().id)
    }

    @Test
    fun snapshots_filtersToCurrentIdentity() = runTest {
        val store = InMemoryGroupStore()
        store.preload(listOf(
            makeGroup(id = "aa".repeat(32), name = "Alice's", owner = aliceId),
            makeGroup(id = "bb".repeat(32), name = "Bob's", owner = bobId),
        ))
        val repo = makeRepo(store, active = aliceId)
        repo.reload()

        val snapshot = repo.snapshots.first()
        assertEquals("only Alice's group is visible while she's active", 1, snapshot.size)
        assertEquals("Alice's", snapshot.first().name)
    }

    @Test
    fun snapshots_emptyWhenNoActiveIdentity() = runTest {
        val store = InMemoryGroupStore()
        store.preload(listOf(makeGroup(id = "aa".repeat(32), name = "Family", owner = aliceId)))
        val repo = makeRepo(store, active = null)
        repo.reload()

        assertTrue("no active identity → empty snapshot", repo.snapshots.first().isEmpty())
    }

    @Test
    fun insert_broadcastsNewSnapshot() = runTest {
        val store = InMemoryGroupStore()
        val repo = makeRepo(store, active = aliceId)

        val group = makeGroup(id = "bb".repeat(32), name = "Friends", owner = aliceId)
        val inserted = repo.insert(group)
        assertTrue("first insert returns true", inserted)

        val snapshot = repo.snapshots.value
        assertEquals(1, snapshot.size)
        assertEquals("Friends", snapshot.first().name)
    }

    @Test
    fun insert_secondCallUpdatesAndReturnsFalse() = runTest {
        val store = InMemoryGroupStore()
        val repo = makeRepo(store, active = aliceId)

        val group = makeGroup(id = "bc".repeat(32), name = "Friends", owner = aliceId)
        repo.insert(group)
        val secondInserted = repo.insert(group.copy(name = "Friends (renamed)"))

        assertFalse("second insert with same id is an update", secondInserted)
        assertEquals("Friends (renamed)", repo.snapshots.value.single().name)
    }

    @Test
    fun markPublished_broadcastsUpdatedSnapshot() = runTest {
        val store = InMemoryGroupStore()
        val group = makeGroup(id = "cc".repeat(32), name = "G", owner = aliceId)
        store.preload(listOf(group))
        val repo = makeRepo(store, active = aliceId)
        repo.reload()

        val onchainCommitment = ByteArray(32) { 0x42 }
        repo.markPublished(group.id, onchainCommitment)

        val snapshot = repo.snapshots.value.single()
        assertTrue(snapshot.isPublishedOnChain)
        assertArrayEquals(onchainCommitment, snapshot.commitment)
    }

    @Test
    fun delete_emptiesSnapshot() = runTest {
        val store = InMemoryGroupStore()
        val group = makeGroup(id = "dd".repeat(32), name = "G", owner = aliceId)
        store.preload(listOf(group))
        val repo = makeRepo(store, active = aliceId)
        repo.reload()

        repo.delete(group.id)
        assertTrue(repo.snapshots.value.isEmpty())
    }

    @Test
    fun removeListener_cascadesDelete() = runTest {
        // PR-3 wiring: removing an identity cascades a delete-by-owner
        // through the registered listener so the about-to-be-wiped
        // identity's chats vanish from disk before its secrets do.
        val store = InMemoryGroupStore()
        store.preload(listOf(
            makeGroup(id = "aa".repeat(32), name = "Alice's", owner = aliceId),
            makeGroup(id = "bb".repeat(32), name = "Bob's", owner = bobId),
        ))
        val active = FakeActiveIdentityProvider(initial = aliceId)
        val repo = GroupRepository(
            store = store,
            identity = active,
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        repo.reload()
        assertEquals(1, repo.snapshots.value.size)

        // Simulate IdentityRepository invoking the removal listener
        // for Alice.
        active.emitRemoval(aliceId)
        assertEquals(
            "Alice's groups are gone from the store after the cascade",
            1, store.list().size,
        )
        assertEquals("only Bob's group remains", "Bob's", store.list().single().name)
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun makeRepo(store: GroupStore, active: IdentityId?): GroupRepository =
        GroupRepository(
            store = store,
            identity = FakeActiveIdentityProvider(initial = active),
            scope = TestScope(UnconfinedTestDispatcher()),
        )

    private fun makeGroup(id: String, name: String, owner: IdentityId) = ChatGroup(
        id = id,
        name = name,
        groupSecret = ByteArray(32) { 0x33 },
        createdAtMillis = 1_700_000_000_000L,
        members = emptyList(),
        epoch = 0uL,
        salt = ByteArray(32) { 0x44 },
        commitment = null,
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = null,
        isPublishedOnChain = false,
        ownerIdentityId = owner.value,
    )
}
