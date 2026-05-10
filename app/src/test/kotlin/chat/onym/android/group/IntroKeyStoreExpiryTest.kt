package chat.onym.android.group

import chat.onym.android.identity.IdentityId
import chat.onym.android.support.InMemoryIntroKeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Time-based expiry contract for [IntroKeyStore] (issue
 * onymchat/onym-ios#111). An entry older than
 * [IntroKeyEntry.LIFETIME_MILLIS] is treated as if it were revoked:
 * [IntroKeyStore.find] returns null, [IntroKeyStore.listForOwner]
 * and [IntroKeyStore.entriesFlow] omit it, and storage is
 * lazy-purged so [IntroInboxPump] cancels relayer subscriptions.
 */
class IntroKeyStoreExpiryTest {

    private val alice = IdentityId("11111111-1111-1111-1111-111111111111")
    private val sampleGroupId = ByteArray(32) { 0x42 }

    private fun entry(seed: Byte, createdAtMillis: Long): IntroKeyEntry =
        IntroKeyEntry(
            introPublicKey = ByteArray(32) { seed },
            introPrivateKey = ByteArray(32) { (seed + 1).toByte() },
            ownerIdentityId = alice,
            groupId = sampleGroupId,
            createdAtMillis = createdAtMillis,
        )

    @Test
    fun find_returnsNull_forEntryOlderThanLifetime() = runTest {
        val now = 1_700_000_000_000L
        val store = InMemoryIntroKeyStore(clock = { now })
        val stale = entry(
            seed = 0x10,
            createdAtMillis = now - IntroKeyEntry.LIFETIME_MILLIS - 1,
        )
        store.save(stale)

        assertNull(
            "expired intro key must not be findable",
            store.find(stale.introPublicKey),
        )
    }

    @Test
    fun find_returnsEntry_atLifetimeBoundary_minusOne() = runTest {
        val now = 1_700_000_000_000L
        val store = InMemoryIntroKeyStore(clock = { now })
        val fresh = entry(
            seed = 0x11,
            createdAtMillis = now - IntroKeyEntry.LIFETIME_MILLIS + 1,
        )
        store.save(fresh)

        assertNotNull(
            "entry one millisecond under lifetime must still be honored",
            store.find(fresh.introPublicKey),
        )
    }

    @Test
    fun listForOwner_omitsExpiredEntries_keepsFresh() = runTest {
        // Save two entries while both are fresh, then advance the
        // clock so only one crosses the expiry threshold. Exercises
        // the read-side filter, not just the purge-on-save path.
        val clock = AtomicLong(1_700_000_000_000L)
        val store = InMemoryIntroKeyStore(clock = clock::get)
        val older = entry(seed = 0x10, createdAtMillis = clock.get())
        store.save(older)
        clock.addAndGet(IntroKeyEntry.LIFETIME_MILLIS - 60_000L)
        val newer = entry(seed = 0x20, createdAtMillis = clock.get())
        store.save(newer)
        clock.addAndGet(120_000L) // older is now expired, newer is not.

        val list = store.listForOwner(alice)
        assertEquals(1, list.size)
        assertEquals(
            newer.introPublicKey.toList(),
            list[0].introPublicKey.toList(),
        )
    }

    @Test
    fun entriesFlow_reEmits_whenLazyPurgeDropsRows() = runTest {
        // Clock starts inside the lifetime window so the seeded
        // entry is initially live, then advances past expiry so a
        // read triggers the lazy purge and entriesFlow re-emits an
        // empty snapshot — that's what IntroInboxPump needs in
        // order to cancel its relayer subscription.
        val clock = AtomicLong(1_700_000_000_000L)
        val store = InMemoryIntroKeyStore(clock = clock::get)
        val staleSoon = entry(seed = 0x30, createdAtMillis = clock.get())
        store.save(staleSoon)

        // Sanity: flow currently carries the live entry.
        val initial = store.entriesFlow.first()
        assertEquals(1, initial.size)

        clock.addAndGet(IntroKeyEntry.LIFETIME_MILLIS + 1)
        // Trigger the lazy purge.
        store.listForOwner(alice)

        val afterPurge = store.entriesFlow.first()
        assertEquals(
            "lazy purge must clear entriesFlow so the inbox pump cancels relayer subscriptions",
            0,
            afterPurge.size,
        )
    }

    @Test
    fun save_purgesExpiredEntries_priorToInsertion() = runTest {
        // Mint A while fresh, advance past expiry, mint B. The
        // store must silently drop A as part of B's save so the
        // EncryptedSharedPreferences blob doesn't grow unbounded.
        val clock = AtomicLong(1_700_000_000_000L)
        val store = InMemoryIntroKeyStore(clock = clock::get)
        val first = entry(seed = 0x40, createdAtMillis = clock.get())
        store.save(first)
        clock.addAndGet(IntroKeyEntry.LIFETIME_MILLIS + 1)
        val second = entry(seed = 0x50, createdAtMillis = clock.get())
        store.save(second)

        assertNull(store.find(first.introPublicKey))
        assertNotNull(store.find(second.introPublicKey))
    }

}
