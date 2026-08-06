package app.onym.android.group

import app.onym.android.identity.IdentityId
import app.onym.android.support.InMemoryIntroKeyStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle contract for [IntroKeyStore]: invite links do not expire,
 * and `revoke` is what retires one.
 *
 * Replaces the old expiry suite. The 24h TTL was removed in favour of
 * explicit revocation — see `IntroKeyStoreLifecycleTests` on iOS for
 * the mirror.
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
}
