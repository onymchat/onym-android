package app.onym.android.group

import app.onym.android.identity.IdentityId
import app.onym.android.support.InMemoryIntroKeyStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Lifecycle contract for [IntroKeyStore]: links do not expire, and
 * `revoke` is what retires one. Replaces the old expiry suite.
 */
class IntroKeyStoreLifecycleTest {

    private val alice = IdentityId("alice-uuid")
    private val bob = IdentityId("bob-uuid")
    private val groupId = ByteArray(32) { 0x42 }

    private fun entry(seed: Byte, owner: IdentityId, createdAtMillis: Long) = IntroKeyEntry(
        introPublicKey = ByteArray(32) { seed },
        introPrivateKey = ByteArray(32) { (seed + 1).toByte() },
        ownerIdentityId = owner,
        groupId = groupId,
        createdAtMillis = createdAtMillis,
    )

    @Test
    fun ancientEntry_isStillVisibleAndUsable() = runTest {
        val store = InMemoryIntroKeyStore()
        // Minted over a year ago. As usable as one minted a second
        // ago; only revoke retires it.
        val ancient = entry(0x10, alice, createdAtMillis = 0L)
        store.save(ancient)

        assertNotNull(store.find(ancient.introPublicKey))
        assertEquals(1, store.listForOwner(alice).size)
        assertEquals(1, store.entriesFlow.value.size)
    }

    @Test
    fun revoke_removesTheEntryFromEveryRead() = runTest {
        val store = InMemoryIntroKeyStore()
        val doomed = entry(0x10, alice, createdAtMillis = 0L)
        val kept = entry(0x20, alice, createdAtMillis = 1_700_000_000_000L)
        store.save(doomed)
        store.save(kept)

        store.revoke(doomed.introPublicKey)

        assertNull(store.find(doomed.introPublicKey))
        assertEquals(
            listOf(kept.introPublicKey.toList()),
            store.listForOwner(alice).map { it.introPublicKey.toList() },
        )
    }

    @Test
    fun revoke_reEmitsEntriesFlow_soThePumpDropsTheInbox() = runTest {
        val store = InMemoryIntroKeyStore()
        val doomed = entry(0x10, bob, createdAtMillis = 1_700_000_000_000L)
        store.save(doomed)
        assertEquals(1, store.entriesFlow.value.size)

        store.revoke(doomed.introPublicKey)

        assertTrue(
            "IntroInboxPump must stop subscribing a revoked inbox",
            store.entriesFlow.value.isEmpty(),
        )
    }

    @Test
    fun revoke_unknownPubkey_isANoOp() = runTest {
        val store = InMemoryIntroKeyStore()
        val kept = entry(0x10, alice, createdAtMillis = 1_700_000_000_000L)
        store.save(kept)

        store.revoke(ByteArray(32) { 0x77 })

        assertEquals(1, store.listForOwner(alice).size)
    }

    @Test
    fun deleteForOwner_stillCascades() = runTest {
        val store = InMemoryIntroKeyStore()
        store.save(entry(0x10, alice, createdAtMillis = 0L))
        store.save(entry(0x20, alice, createdAtMillis = 1_700_000_000_000L))
        store.save(entry(0x30, bob, createdAtMillis = 0L))

        assertEquals(2, store.deleteForOwner(alice))
        assertTrue(store.listForOwner(alice).isEmpty())
        assertEquals(1, store.listForOwner(bob).size)
    }

    // ─── handled-request tombstones ───────────────────────────────

    @Test
    fun tombstoneSurvivesRelaunch_soAHandledRequestStaysGone() = runTest {
        // A fresh store over the same durable log is what a cold start
        // looks like. This is the case a process-lifetime set missed.
        val log = InMemoryHandledIntroRequestLog()
        val request = IntroRequest(
            id = "evt-1",
            targetIntroPublicKey = ByteArray(32) { 0x10 },
            payload = byteArrayOf(0x01),
            receivedAt = Instant.ofEpochSecond(10),
        )

        val first = InMemoryIntroRequestStore(log)
        first.record(request)
        first.consume(request.id)

        val relaunched = InMemoryIntroRequestStore(log)
        assertFalse(
            "a handled request must not return after relaunch",
            relaunched.record(request),
        )
        assertTrue(relaunched.requests.value.isEmpty())
    }

    @Test
    fun declinedCollapseKey_survivesAcrossInstances() = runTest {
        val log = InMemoryHandledIntroRequestLog()
        InMemoryIntroRequestStore(log).recordDeclined("joiner-a:group-1")

        // A decline has to outlive the joiner's retry: each retry is a
        // fresh Nostr event, so an id tombstone never matches.
        val after = InMemoryIntroRequestStore(log)
        assertEquals(setOf("joiner-a:group-1"), after.declinedCollapseKeys())
    }

    @Test
    fun reconcile_dropsDeadLinksAndKeepsLiveAndUnattributed() = runTest {
        val log = InMemoryHandledIntroRequestLog()
        val livePub = ByteArray(32) { 0x40 }
        val hex = { b: ByteArray -> b.joinToString("") { "%02x".format(it.toInt() and 0xFF) } }
        val store = InMemoryIntroRequestStore(log)
        store.record(IntroRequest("evt-live", livePub, byteArrayOf(1), Instant.ofEpochSecond(10)))
        store.consume("evt-live")
        store.consume("evt-unattributed")   // no row → unattributed

        store.reconcileTombstones(setOf(hex(livePub)))
        assertEquals(setOf("evt-live", "evt-unattributed"), log.handledIds())

        // Now that link is gone from the device entirely.
        store.reconcileTombstones(emptySet())
        assertEquals(
            setOf("evt-unattributed"), log.handledIds(),
        )
    }

    @Test
    fun pruneTombstones_withNothingRetired_keepsEverything() = runTest {
        val log = InMemoryHandledIntroRequestLog()
        val pub = ByteArray(32) { 0x30 }
        val store = InMemoryIntroRequestStore(log)
        store.record(IntroRequest("evt-other", pub, byteArrayOf(0x01), Instant.ofEpochSecond(10)))
        store.consume("evt-other")

        // What an empty snapshot now produces: nothing died. Under the
        // old keep-set phrasing this wiped the log — and the pump emits
        // exactly this whenever no identity is active, or while another
        // identity is selected.
        store.pruneTombstones(emptySet())

        val after = InMemoryIntroRequestStore(log)
        assertFalse(
            after.record(IntroRequest("evt-other", pub, byteArrayOf(1), Instant.ofEpochSecond(11))),
        )
    }

    @Test
    fun pruneTombstones_dropsRetiredLinks_keepsLiveOnes() = runTest {
        val log = InMemoryHandledIntroRequestLog()
        val livePub = ByteArray(32) { 0x10 }
        val retiredPub = ByteArray(32) { 0x20 }
        val hex = { b: ByteArray -> b.joinToString("") { "%02x".format(it.toInt() and 0xFF) } }
        val store = InMemoryIntroRequestStore(log)

        for ((id, pub) in listOf("evt-live" to livePub, "evt-retired" to retiredPub)) {
            store.record(
                IntroRequest(id, pub, byteArrayOf(0x01), Instant.ofEpochSecond(10)),
            )
            store.consume(id)
        }

        // A retired link's tombstones go with it, so the set stays
        // bounded by the invites that still exist. Named by what died,
        // not what lived: the pump's snapshot is one identity's, while
        // the log spans the process.
        store.pruneTombstones(setOf(hex(retiredPub)))

        val after = InMemoryIntroRequestStore(log)
        assertFalse(
            after.record(IntroRequest("evt-live", livePub, byteArrayOf(1), Instant.ofEpochSecond(11))),
        )
        assertTrue(
            after.record(IntroRequest("evt-retired", retiredPub, byteArrayOf(1), Instant.ofEpochSecond(11))),
        )
    }
}
