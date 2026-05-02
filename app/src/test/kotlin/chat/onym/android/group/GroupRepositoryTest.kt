package chat.onym.android.group

import chat.onym.android.chain.SepGroupType
import chat.onym.android.chain.SepTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reactive-surface tests for [GroupRepository]. Backed by an
 * in-memory [GroupStore] fake (defined at the bottom of this file)
 * so the Room layer doesn't pull in `Robolectric` /
 * `StorageEncryption` here — those are
 * [RoomGroupStoreTest]'s responsibility.
 *
 * Mirrors `GroupRepositoryTests.swift` from onym-ios PR #25.
 */
class GroupRepositoryTest {

    @Test
    fun snapshots_replaysCurrentOnSubscribe() = runTest {
        val store = InMemoryGroupStore()
        val group = makeGroup(id = "aa".repeat(32), name = "Family")
        store.preload(listOf(group))
        val repo = GroupRepository(store)
        repo.reload()

        // StateFlow.first() reads the current value — this is the
        // replay-on-subscribe property the screen relies on for an
        // immediate render.
        val snapshot = repo.snapshots.first()
        assertEquals(1, snapshot.size)
        assertEquals(group.id, snapshot.first().id)
    }

    @Test
    fun insert_broadcastsNewSnapshot() = runTest {
        val store = InMemoryGroupStore()
        val repo = GroupRepository(store)

        val group = makeGroup(id = "bb".repeat(32), name = "Friends")
        val inserted = repo.insert(group)
        assertTrue("first insert returns true", inserted)

        val snapshot = repo.snapshots.value
        assertEquals(1, snapshot.size)
        assertEquals("Friends", snapshot.first().name)
    }

    @Test
    fun insert_secondCallUpdatesAndReturnsFalse() = runTest {
        val store = InMemoryGroupStore()
        val repo = GroupRepository(store)

        val group = makeGroup(id = "bc".repeat(32), name = "Friends")
        repo.insert(group)
        val secondInserted = repo.insert(group.copy(name = "Friends (renamed)"))

        assertFalse("second insert with same id is an update", secondInserted)
        assertEquals("Friends (renamed)", repo.snapshots.value.single().name)
    }

    @Test
    fun markPublished_broadcastsUpdatedSnapshot() = runTest {
        val store = InMemoryGroupStore()
        val group = makeGroup(id = "cc".repeat(32), name = "G")
        store.preload(listOf(group))
        val repo = GroupRepository(store)
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
        val group = makeGroup(id = "dd".repeat(32), name = "G")
        store.preload(listOf(group))
        val repo = GroupRepository(store)
        repo.reload()

        repo.delete(group.id)
        assertTrue(repo.snapshots.value.isEmpty())
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun makeGroup(id: String, name: String) = ChatGroup(
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
    )
}

/**
 * In-memory [GroupStore] fake. Lives next to the test that uses it
 * because PR-B has only one consumer; promote to a `support/`
 * package when PR-C's interactor tests grow a second one. Mirrors
 * the same private-fake placement in
 * `GroupRepositoryTests.swift` (onym-ios PR #25).
 */
private class InMemoryGroupStore : GroupStore {
    private val rows = LinkedHashMap<String, ChatGroup>()

    fun preload(groups: List<ChatGroup>) {
        for (g in groups) rows[g.id] = g
    }

    override suspend fun list(): List<ChatGroup> =
        rows.values.sortedByDescending { it.createdAtMillis }

    override suspend fun insertOrUpdate(group: ChatGroup): Boolean {
        val isNew = !rows.containsKey(group.id)
        rows[group.id] = group
        return isNew
    }

    override suspend fun markPublished(id: String, commitment: ByteArray?) {
        val existing = rows[id] ?: return
        rows[id] = existing.copy(
            isPublishedOnChain = true,
            commitment = commitment ?: existing.commitment,
        )
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
    }
}
