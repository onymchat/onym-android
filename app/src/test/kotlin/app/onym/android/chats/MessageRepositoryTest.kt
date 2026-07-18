package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.identity.IdentityId
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryMessageStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Reactive-surface tests for [MessageRepository]. Backed by an
 * in-memory [MessageStore] fake + a [FakeActiveIdentityProvider]
 * so the Room layer doesn't pull in `Robolectric` /
 * `StorageEncryption` here — those are [RoomMessageStoreTest]'s
 * responsibility.
 *
 * Mirrors `MessageRepositoryTests.swift` from onym-ios PR #148.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryTest {

    private val aliceId = IdentityId("alice-uuid")
    private val bobId = IdentityId("bob-uuid")
    private val groupA = "aa".repeat(32)
    private val groupB = "bb".repeat(32)

    // ─── snapshots() lazy + initial hydration ─────────────────────

    @Test
    fun snapshots_hydratesFromStoreOnFirstCall() = runTest {
        val store = InMemoryMessageStore()
        val msg = makeMessage(owner = aliceId, group = groupA, body = "hi")
        store.preload(listOf(msg))
        val repo = makeRepo(store, active = aliceId)

        val flow = repo.snapshots(groupA)
        assertEquals(1, flow.first().size)
        assertEquals("hi", flow.first().single().body)
    }

    @Test
    fun snapshots_emptyWhenNoActiveIdentity() = runTest {
        val store = InMemoryMessageStore()
        store.preload(listOf(makeMessage(owner = aliceId, group = groupA, body = "hi")))
        val repo = makeRepo(store, active = null)

        val flow = repo.snapshots(groupA)
        assertTrue("no active identity → empty snapshot", flow.first().isEmpty())
    }

    @Test
    fun snapshots_secondCallSharesUnderlyingState() = runTest {
        val store = InMemoryMessageStore()
        store.preload(listOf(makeMessage(owner = aliceId, group = groupA, body = "hi")))
        val repo = makeRepo(store, active = aliceId)

        val a = repo.snapshots(groupA)
        val b = repo.snapshots(groupA)
        // .asStateFlow() returns a fresh ReadonlyStateFlow wrapper
        // each call, so the wrappers needn't be identical — but both
        // must observe the same underlying MutableStateFlow. Asserting
        // post-append emissions hit BOTH proves the cache is shared.
        repo.append(makeMessage(owner = aliceId, group = groupA, body = "second"))
        assertEquals(2, a.first().size)
        assertEquals(2, b.first().size)
    }

    // ─── search() ─────────────────────────────────────────────────

    @Test
    fun search_scopedToActiveIdentity_matchesBody() = runTest {
        val store = InMemoryMessageStore()
        store.preload(
            listOf(
                makeMessage(owner = aliceId, group = groupA, body = "dinner plans"),
                makeMessage(owner = bobId, group = groupA, body = "dinner plans too"),
            ),
        )
        val repo = makeRepo(store, active = aliceId)

        val hits = repo.search("dinner")
        assertEquals(1, hits.size)
        assertEquals(aliceId.value, hits.single().ownerIdentityId)
    }

    @Test
    fun search_returnsEmptyWhenNoActiveIdentity() = runTest {
        val store = InMemoryMessageStore()
        store.preload(listOf(makeMessage(owner = aliceId, group = groupA, body = "hi there")))
        val repo = makeRepo(store, active = null)

        assertTrue(repo.search("hi").isEmpty())
    }

    // ─── append() ─────────────────────────────────────────────────

    @Test
    fun append_emitsOnTheGroupFlow() = runTest {
        val store = InMemoryMessageStore()
        val repo = makeRepo(store, active = aliceId)
        val flow = repo.snapshots(groupA)
        assertTrue(flow.first().isEmpty())

        repo.append(makeMessage(owner = aliceId, group = groupA, body = "first"))

        val snapshot = flow.first()
        assertEquals(1, snapshot.size)
        assertEquals("first", snapshot.single().body)
    }

    @Test
    fun append_doesNotEmitOnUnrelatedGroupFlows() = runTest {
        val store = InMemoryMessageStore()
        val repo = makeRepo(store, active = aliceId)
        val flowA = repo.snapshots(groupA)
        val flowB = repo.snapshots(groupB)

        repo.append(makeMessage(owner = aliceId, group = groupA, body = "a-msg"))

        assertEquals(1, flowA.first().size)
        assertTrue("group B subscribers see no emission", flowB.first().isEmpty())
    }

    // ─── updateStatus() ───────────────────────────────────────────

    @Test
    fun updateStatus_flipsStatusOnCachedFlow() = runTest {
        val store = InMemoryMessageStore()
        val msg = makeMessage(owner = aliceId, group = groupA, body = "x")
        val repo = makeRepo(store, active = aliceId)
        repo.append(msg)
        val flow = repo.snapshots(groupA)

        repo.updateStatus(msg.id, aliceId.value, MessageStatus.SENT)
        assertEquals(MessageStatus.SENT, flow.first().single().status)
    }

    @Test
    fun updateStatus_unknownId_doesNotMutateAnyGroup() = runTest {
        val store = InMemoryMessageStore()
        val repo = makeRepo(store, active = aliceId)
        val msg = makeMessage(owner = aliceId, group = groupA, body = "x")
        repo.append(msg)
        val before = repo.snapshots(groupA).first()

        repo.updateStatus(UUID.randomUUID(), aliceId.value, MessageStatus.SENT)

        val after = repo.snapshots(groupA).first()
        assertEquals("status of existing rows must not change", before, after)
    }

    // ─── removeForOwner cascade ───────────────────────────────────

    @Test
    fun removeForOwner_clearsCachedFlowsForActiveIdentity_butPreservesOtherIdentityRowsInStore() = runTest {
        val store = InMemoryMessageStore()
        // Alice + Bob both have a message in group A.
        store.preload(listOf(
            makeMessage(owner = aliceId, group = groupA, body = "alice-msg"),
            makeMessage(owner = bobId, group = groupA, body = "bob-msg"),
        ))
        val repo = makeRepo(store, active = aliceId)
        val flow = repo.snapshots(groupA)
        assertEquals(1, flow.first().size)

        repo.removeForOwner(aliceId.value)

        // Cached flow under active Alice empties out.
        assertTrue("alice rows are gone", flow.first().isEmpty())
        // Bob's row in the same group survives in the store.
        assertEquals(1, store.listForGroup(bobId.value, groupA).size)
    }

    // ─── removeForGroup cascade ───────────────────────────────────

    @Test
    fun removeForGroup_clearsThatGroupsCacheAndStoreRows() = runTest {
        val store = InMemoryMessageStore()
        store.preload(listOf(
            makeMessage(owner = aliceId, group = groupA, body = "a"),
            makeMessage(owner = aliceId, group = groupB, body = "b"),
        ))
        val repo = makeRepo(store, active = aliceId)
        val flowA = repo.snapshots(groupA)
        val flowB = repo.snapshots(groupB)
        assertEquals(1, flowA.first().size)
        assertEquals(1, flowB.first().size)

        repo.removeForGroup(groupA, aliceId.value)

        // The cached flow for A is dropped — re-subscribing yields a
        // fresh empty flow (different identity from the original).
        val flowAReborn = repo.snapshots(groupA)
        assertNotSame(flowA, flowAReborn)
        assertTrue(flowAReborn.first().isEmpty())
        // Group B is untouched.
        assertEquals(1, flowB.first().size)
    }

    // ─── identity switch wipes cache ──────────────────────────────

    @Test
    fun start_identityChange_wipesCache() = runTest {
        val store = InMemoryMessageStore()
        store.preload(listOf(
            makeMessage(owner = aliceId, group = groupA, body = "alice-msg"),
            makeMessage(owner = bobId, group = groupA, body = "bob-msg"),
        ))
        val active = FakeActiveIdentityProvider(initial = aliceId)
        val testScope = TestScope(UnconfinedTestDispatcher())
        val repo = MessageRepository(store, active, testScope)
        repo.start()

        val flowAsAlice = repo.snapshots(groupA)
        assertEquals("alice-msg", flowAsAlice.first().single().body)

        active.setActive(bobId)
        // Re-subscribing post-switch produces a fresh flow that loads
        // Bob's rows for this group.
        val flowAsBob = repo.snapshots(groupA)
        assertNotSame(flowAsAlice, flowAsBob)
        assertEquals("bob-msg", flowAsBob.first().single().body)
    }

    // ─── helpers ──────────────────────────────────────────────────

    // ─── upgradeStatus() (delivery / read receipts) ───────────────

    @Test
    fun upgradeStatus_raisesAlongTheLadder() = runTest {
        val store = InMemoryMessageStore()
        val msg = makeMessage(owner = aliceId, group = groupA, body = "m")
            .copy(status = MessageStatus.SENT)
        store.preload(listOf(msg))
        val repo = makeRepo(store, aliceId)
        val flow = repo.snapshots(groupA)

        repo.upgradeStatus(msg.id, aliceId.value, MessageStatus.DELIVERED)
        assertEquals(MessageStatus.DELIVERED, flow.first().first().status)

        repo.upgradeStatus(msg.id, aliceId.value, MessageStatus.READ)
        assertEquals(MessageStatus.READ, flow.first().first().status)
    }

    @Test
    fun upgradeStatus_neverDowngrades() = runTest {
        val store = InMemoryMessageStore()
        val msg = makeMessage(owner = aliceId, group = groupA, body = "m")
            .copy(status = MessageStatus.READ)
        store.preload(listOf(msg))
        val repo = makeRepo(store, aliceId)
        val flow = repo.snapshots(groupA)

        // A late delivered receipt arriving after read must not lower it.
        repo.upgradeStatus(msg.id, aliceId.value, MessageStatus.DELIVERED)
        assertEquals(MessageStatus.READ, flow.first().first().status)
    }

    @Test
    fun upgradeStatus_ignoresIncomingAndUnknown() = runTest {
        val store = InMemoryMessageStore()
        val incoming = makeMessage(owner = aliceId, group = groupA, body = "in")
            .copy(direction = MessageDirection.INCOMING, status = MessageStatus.RECEIVED)
        store.preload(listOf(incoming))
        val repo = makeRepo(store, aliceId)
        val flow = repo.snapshots(groupA)

        repo.upgradeStatus(incoming.id, aliceId.value, MessageStatus.DELIVERED)
        repo.upgradeStatus(UUID.randomUUID(), aliceId.value, MessageStatus.DELIVERED)  // unknown id
        assertEquals(MessageStatus.RECEIVED, flow.first().first().status)
    }

    private fun makeRepo(
        store: InMemoryMessageStore,
        active: IdentityId?,
    ): MessageRepository = MessageRepository(
        store = store,
        identity = FakeActiveIdentityProvider(initial = active),
        scope = TestScope(UnconfinedTestDispatcher()),
    )

    private fun makeMessage(
        owner: IdentityId,
        group: String,
        body: String,
        sentAtMillis: Long = 1_700_000_000_000L,
    ): ChatMessage = ChatMessage(
        id = UUID.randomUUID(),
        groupId = group,
        ownerIdentityId = owner.value,
        senderBlsPubkeyHex = "cc".repeat(48),
        body = body,
        sentAtMillis = sentAtMillis,
        direction = MessageDirection.OUTGOING,
        status = MessageStatus.PENDING,
        groupType = SepGroupType.TYRANNY,
    )
}
