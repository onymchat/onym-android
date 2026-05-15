@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.inbox

import app.onym.android.identity.IdentityId
import app.onym.android.persistence.IncomingInvitationRecord
import app.onym.android.persistence.IncomingInvitationStatus
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryInvitationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Repository contract against [InMemoryInvitationStore]. The
 * Room-backed companion ([app.onym.android.persistence.RoomInvitationStoreTest])
 * exercises the same seam against the real backend; this suite
 * focuses on repository semantics — StateFlow emission discipline,
 * dedup, mutation propagation, and the per-identity filter +
 * cascade-delete from PR-6 of the deeplink-invite stack.
 *
 * Mirrors `IncomingInvitationsRepositoryTests.swift` from onym-ios PR
 * #16 + the per-identity-routing tests from onym-ios PR #59.
 */
class IncomingInvitationsRepositoryTest {

    private val now = Instant.parse("2026-05-02T12:00:00Z")
    private val alice = IdentityId("alice-id")
    private val bob = IdentityId("bob-id")

    // ─── recordIncoming ───────────────────────────────────────────

    @Test
    fun recordIncoming_savesViaStore() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryInvitationStore()
        val repo = repo(store, this, initialActive = alice)

        repo.recordIncoming(id = "ev1", payload = "p1".toByteArray(), receivedAt = now, ownerIdentityId = alice)

        val stored = store.list().single()
        assertEquals("ev1", stored.id)
        assertArrayEquals("p1".toByteArray(), stored.payload)
        assertEquals(IncomingInvitationStatus.Pending, stored.status)
        assertEquals(alice.value, stored.ownerIdentityIdString)
    }

    @Test
    fun recordIncoming_idempotentOnId() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryInvitationStore()
        val repo = repo(store, this, initialActive = alice)

        repo.recordIncoming(id = "ev1", payload = "first".toByteArray(), receivedAt = now, ownerIdentityId = alice)
        // Second record with same id — different payload — must be ignored.
        repo.recordIncoming(id = "ev1", payload = "second".toByteArray(), receivedAt = now, ownerIdentityId = alice)

        val stored = store.list().single()
        assertArrayEquals("first".toByteArray(), stored.payload)
    }

    // ─── status / delete propagation ──────────────────────────────

    @Test
    fun updateStatus_propagatesToStore() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryInvitationStore()
        store.save(record("ev1", "x", now, owner = alice))
        val repo = repo(store, this, initialActive = alice)
        repo.bootstrap()

        repo.updateStatus("ev1", IncomingInvitationStatus.Accepted)

        assertEquals(IncomingInvitationStatus.Accepted, store.list().single().status)
    }

    @Test
    fun delete_propagatesToStore() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryInvitationStore()
        store.save(record("ev1", "x", now, owner = alice))
        store.save(record("ev2", "y", now.plusSeconds(1), owner = alice))
        val repo = repo(store, this, initialActive = alice)
        repo.bootstrap()

        repo.delete("ev1")

        assertEquals(listOf("ev2"), store.list().map { it.id })
    }

    // ─── StateFlow emission discipline ────────────────────────────

    @Test
    fun stateFlow_initialValue_isEmptyBeforeBootstrap() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryInvitationStore()
        val repo = repo(store, this, initialActive = alice)
        assertTrue(repo.invitations.value.isEmpty())
    }

    @Test
    fun stateFlow_initialValue_hydratedAfterBootstrap() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryInvitationStore()
        store.save(record("ev1", "x", now, owner = alice))
        store.save(record("ev2", "y", now.plusSeconds(1), owner = alice))
        val repo = repo(store, this, initialActive = alice)

        repo.bootstrap()

        // Sorted by receivedAt desc — ev2 first.
        assertEquals(listOf("ev2", "ev1"), repo.invitations.value.map { it.id })
    }

    @Test
    fun stateFlow_skipsRedundantEmitOnDedup() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryInvitationStore()
        val repo = repo(store, this, initialActive = alice)
        repo.bootstrap()

        repo.recordIncoming(id = "ev1", payload = "x".toByteArray(), receivedAt = now, ownerIdentityId = alice)
        val firstSnapshot = repo.invitations.value

        // Duplicate id — must NOT push a redundant snapshot. We
        // assert on object identity: the StateFlow's `value` must
        // be the *same* list instance, not a freshly-built equal
        // one. The repository skips the rebuild when store.save
        // returns false.
        repo.recordIncoming(id = "ev1", payload = "x".toByteArray(), receivedAt = now, ownerIdentityId = alice)
        assertSame(
            "duplicate recordIncoming must not rebuild the snapshot",
            firstSnapshot,
            repo.invitations.value,
        )
    }

    @Test
    fun stateFlow_emitsAfterUpdateStatusAndDelete() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryInvitationStore()
        val repo = repo(store, this, initialActive = alice)
        repo.recordIncoming(id = "ev1", payload = "x".toByteArray(), receivedAt = now, ownerIdentityId = alice)
        repo.recordIncoming(id = "ev2", payload = "y".toByteArray(), receivedAt = now.plusSeconds(1), ownerIdentityId = alice)

        repo.updateStatus("ev1", IncomingInvitationStatus.Accepted)
        assertEquals(
            IncomingInvitationStatus.Accepted,
            repo.invitations.value.first { it.id == "ev1" }.status,
        )

        repo.delete("ev1")
        assertEquals(listOf("ev2"), repo.invitations.value.map { it.id })
    }

    // ─── per-identity filter + cascade (PR-6) ─────────────────────

    @Test
    fun snapshots_onlyContainCurrentOwnersInvitations_afterFanoutPersistsMultiple() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryInvitationStore()
        val active = FakeActiveIdentityProvider(initial = alice)
        // backgroundScope, not `this` — repo.start() launches a
        // never-completing collectLatest; child jobs of the test
        // scope would block runTest from returning.
        val repo = IncomingInvitationsRepository(store, active, backgroundScope)
        repo.start()

        // Simulate the fan-out persisting envelopes for both
        // identities (the joiner's app may receive a message
        // addressed to identity B while the user is on identity A
        // — that envelope must persist + be filtered out of the
        // current view, not lost).
        repo.recordIncoming("a1", "alice-msg".toByteArray(), now, alice)
        repo.recordIncoming("b1", "bob-msg".toByteArray(), now.plusSeconds(1), bob)

        // While alice is active, snapshots only show alice's row
        // even though the store holds both.
        assertEquals(2, store.list().size)
        assertEquals(listOf("a1"), repo.invitations.value.map { it.id })

        // Switch active to bob — snapshots flip to bob's row. Drain
        // the test scheduler so the collectLatest body runs before
        // we assert (UnconfinedTestDispatcher runs eagerly only for
        // the current coroutine; the backgroundScope collector
        // needs `runCurrent` to pump).
        active.setActive(bob)
        runCurrent()
        assertEquals(listOf("b1"), repo.invitations.value.map { it.id })
    }

    @Test
    fun removeForOwner_wipesThatIdentitysInvitations_andLeavesOthers() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryInvitationStore()
        val repo = repo(store, this, initialActive = alice)
        repo.recordIncoming("a1", "x".toByteArray(), now, alice)
        repo.recordIncoming("a2", "y".toByteArray(), now.plusSeconds(1), alice)
        repo.recordIncoming("b1", "z".toByteArray(), now.plusSeconds(2), bob)

        val removed = repo.removeForOwner(alice)

        assertEquals(2, removed)
        assertEquals(listOf("b1"), store.list().map { it.id })
    }

    @Test
    fun identityRemovalListener_cascadeWipesPersistedRows() = runTest(UnconfinedTestDispatcher()) {
        val store = InMemoryInvitationStore()
        val active = FakeActiveIdentityProvider(initial = alice)
        // backgroundScope, not `this` — repo.start() launches a
        // never-completing collectLatest; child jobs of the test
        // scope would block runTest from returning.
        val repo = IncomingInvitationsRepository(store, active, backgroundScope)
        repo.start()

        repo.recordIncoming("a1", "x".toByteArray(), now, alice)
        repo.recordIncoming("b1", "y".toByteArray(), now.plusSeconds(1), bob)

        // Simulate IdentityRepository removing alice — drives the
        // listener registered in the repository's `init`.
        active.emitRemoval(alice)
        runCurrent()

        assertEquals(
            "alice's persisted rows should be gone after the cascade",
            listOf("b1"), store.list().map { it.id },
        )
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun repo(
        store: InMemoryInvitationStore,
        scope: CoroutineScope,
        initialActive: IdentityId?,
    ): IncomingInvitationsRepository = IncomingInvitationsRepository(
        store = store,
        identity = FakeActiveIdentityProvider(initial = initialActive),
        scope = scope,
    )

    private fun record(
        id: String,
        payload: String,
        receivedAt: Instant,
        owner: IdentityId,
        status: IncomingInvitationStatus = IncomingInvitationStatus.Pending,
    ) = IncomingInvitationRecord(
        id = id,
        payload = payload.toByteArray(Charsets.UTF_8),
        receivedAt = receivedAt,
        status = status,
        ownerIdentityIdString = owner.value,
    )
}
