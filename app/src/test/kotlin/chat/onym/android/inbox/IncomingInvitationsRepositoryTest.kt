package chat.onym.android.inbox

import chat.onym.android.persistence.IncomingInvitationRecord
import chat.onym.android.persistence.IncomingInvitationStatus
import chat.onym.android.support.InMemoryInvitationStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Repository contract against [InMemoryInvitationStore]. The
 * Room-backed companion ([chat.onym.android.persistence.RoomInvitationStoreTest])
 * exercises the same seam against the real backend; this suite
 * focuses on repository semantics — StateFlow emission discipline,
 * dedup, mutation propagation.
 *
 * Mirrors `IncomingInvitationsRepositoryTests.swift` from onym-ios PR #16.
 */
class IncomingInvitationsRepositoryTest {

    private val now = Instant.parse("2026-05-02T12:00:00Z")

    // ─── recordIncoming ───────────────────────────────────────────

    @Test
    fun recordIncoming_savesViaStore() = runTest {
        val store = InMemoryInvitationStore()
        val repo = IncomingInvitationsRepository(store)

        repo.recordIncoming(id = "ev1", payload = "p1".toByteArray(), receivedAt = now)

        val stored = store.list().single()
        assertEquals("ev1", stored.id)
        assertArrayEquals("p1".toByteArray(), stored.payload)
        assertEquals(IncomingInvitationStatus.Pending, stored.status)
    }

    @Test
    fun recordIncoming_idempotentOnId() = runTest {
        val store = InMemoryInvitationStore()
        val repo = IncomingInvitationsRepository(store)

        repo.recordIncoming(id = "ev1", payload = "first".toByteArray(), receivedAt = now)
        // Second record with same id — different payload — must be ignored.
        repo.recordIncoming(id = "ev1", payload = "second".toByteArray(), receivedAt = now)

        val stored = store.list().single()
        assertArrayEquals("first".toByteArray(), stored.payload)
    }

    // ─── status / delete propagation ──────────────────────────────

    @Test
    fun updateStatus_propagatesToStore() = runTest {
        val store = InMemoryInvitationStore()
        store.save(record("ev1", "x", now, IncomingInvitationStatus.Pending))
        val repo = IncomingInvitationsRepository(store)
        repo.bootstrap()

        repo.updateStatus("ev1", IncomingInvitationStatus.Accepted)

        assertEquals(IncomingInvitationStatus.Accepted, store.list().single().status)
    }

    @Test
    fun delete_propagatesToStore() = runTest {
        val store = InMemoryInvitationStore()
        store.save(record("ev1", "x", now))
        store.save(record("ev2", "y", now.plusSeconds(1)))
        val repo = IncomingInvitationsRepository(store)
        repo.bootstrap()

        repo.delete("ev1")

        assertEquals(listOf("ev2"), store.list().map { it.id })
    }

    // ─── StateFlow emission discipline ────────────────────────────

    @Test
    fun stateFlow_initialValue_isEmptyBeforeBootstrap() = runTest {
        val store = InMemoryInvitationStore()
        val repo = IncomingInvitationsRepository(store)
        assertTrue(repo.invitations.value.isEmpty())
    }

    @Test
    fun stateFlow_initialValue_hydratedAfterBootstrap() = runTest {
        val store = InMemoryInvitationStore()
        store.save(record("ev1", "x", now))
        store.save(record("ev2", "y", now.plusSeconds(1)))
        val repo = IncomingInvitationsRepository(store)

        repo.bootstrap()

        // Sorted by receivedAt desc — ev2 first.
        assertEquals(listOf("ev2", "ev1"), repo.invitations.value.map { it.id })
    }

    @Test
    fun stateFlow_skipsRedundantEmitOnDedup() = runTest {
        val store = InMemoryInvitationStore()
        val repo = IncomingInvitationsRepository(store)
        val seen = mutableListOf<List<String>>()
        // Subscribe synchronously by snapshot — value-only check.
        seen.add(repo.invitations.value.map { it.id })

        repo.recordIncoming(id = "ev1", payload = "x".toByteArray(), receivedAt = now)
        val firstSnapshot = repo.invitations.value
        seen.add(firstSnapshot.map { it.id })

        // Duplicate id — must NOT push a redundant snapshot. We
        // assert on object identity: the StateFlow's `value` must
        // be the *same* list instance, not a freshly-built equal
        // one. The repository skips the rebuild when store.save
        // returns false.
        repo.recordIncoming(id = "ev1", payload = "x".toByteArray(), receivedAt = now)
        assertSame(
            "duplicate recordIncoming must not rebuild the snapshot",
            firstSnapshot,
            repo.invitations.value,
        )
    }

    @Test
    fun stateFlow_emitsAfterUpdateStatusAndDelete() = runTest {
        val store = InMemoryInvitationStore()
        val repo = IncomingInvitationsRepository(store)
        repo.recordIncoming(id = "ev1", payload = "x".toByteArray(), receivedAt = now)
        repo.recordIncoming(id = "ev2", payload = "y".toByteArray(), receivedAt = now.plusSeconds(1))

        repo.updateStatus("ev1", IncomingInvitationStatus.Accepted)
        assertEquals(
            IncomingInvitationStatus.Accepted,
            repo.invitations.value.first { it.id == "ev1" }.status,
        )

        repo.delete("ev1")
        assertEquals(listOf("ev2"), repo.invitations.value.map { it.id })
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun record(
        id: String,
        payload: String,
        receivedAt: Instant,
        status: IncomingInvitationStatus = IncomingInvitationStatus.Pending,
    ) = IncomingInvitationRecord(
        id = id,
        payload = payload.toByteArray(Charsets.UTF_8),
        receivedAt = receivedAt,
        status = status,
    )
}
