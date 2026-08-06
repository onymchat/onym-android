package app.onym.android.group

import app.onym.android.identity.IdentityId
import app.onym.android.support.InMemoryIntroKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.SecureRandom
import java.security.Security

/**
 * Unit tests for [InviteIntroducer] + [IntroKeyStore] contract.
 * Backed by [InMemoryIntroKeyStore] — the EncryptedSharedPreferences-
 * backed prod impl gets exercised in androidTest where the Android
 * Keystore is available.
 */
class InviteIntroducerTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUpBouncyCastle() {
            // BC carries Curve25519; the JDK's built-in providers don't
            // have it on every supported version, so we install BC for
            // the keypair-mint path.
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 2)
            }
        }
    }

    private val alice = IdentityId("alice-uuid")
    private val bob = IdentityId("bob-uuid")
    private val sampleGroupId = ByteArray(32) { 0x42 }

    @Test
    fun mint_producesDistinctKeypairs_acrossInvocations() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        val cap1 = introducer.mint(alice, sampleGroupId)
        val cap2 = introducer.mint(alice, sampleGroupId)

        assertEquals(32, cap1.introPublicKey.size)
        assertEquals(32, cap2.introPublicKey.size)
        assertNotEquals(
            "two mints for the same group must produce distinct intro pubkeys",
            cap1.introPublicKey.toList(),
            cap2.introPublicKey.toList(),
        )
    }

    @Test
    fun mint_persistsKeypair_recoverableViaFind() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        val cap = introducer.mint(alice, sampleGroupId, groupName = "Family")
        val entry = store.find(cap.introPublicKey)
        assertNotNull(entry)
        assertEquals(alice, entry!!.ownerIdentityId)
        assertArrayEquals(sampleGroupId, entry.groupId)
        // Private key must round-trip — that's what decrypts requests
        // in PR-3+.
        assertEquals(32, entry.introPrivateKey.size)
        // PublicKey on the cap must match the persisted entry.
        assertArrayEquals(cap.introPublicKey, entry.introPublicKey)
    }

    @Test
    fun mint_capabilityCarriesGroupName_notTheStore() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        val cap = introducer.mint(alice, sampleGroupId, groupName = "Family")
        assertEquals("Family", cap.groupName)
        // The store doesn't persist the name — names live in the
        // ChatGroup row, not in the per-invite store. Keeps the
        // intro store tightly scoped to crypto material.
        val entry = store.find(cap.introPublicKey)!!
        // (No name field on IntroKeyEntry by design.)
        assertEquals(32, entry.introPublicKey.size)
    }

    @Test
    fun listForOwner_returnsOnlyMatchingIdentitysEntries() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        introducer.mint(alice, sampleGroupId)
        introducer.mint(alice, ByteArray(32) { 0x55 })
        introducer.mint(bob, sampleGroupId)

        val aliceList = store.listForOwner(alice)
        val bobList = store.listForOwner(bob)
        assertEquals(2, aliceList.size)
        assertEquals(1, bobList.size)
        assertTrue(aliceList.all { it.ownerIdentityId == alice })
    }

    @Test
    fun revoke_removesEntry() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        val cap = introducer.mint(alice, sampleGroupId)
        assertNotNull(store.find(cap.introPublicKey))
        store.revoke(cap.introPublicKey)
        assertNull(store.find(cap.introPublicKey))
    }

    @Test
    fun deleteForOwner_cascadesAllOwnedEntries_returnsCount() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        introducer.mint(alice, sampleGroupId)
        introducer.mint(alice, ByteArray(32) { 0x55 })
        introducer.mint(bob, sampleGroupId)

        val removed = store.deleteForOwner(alice)
        assertEquals(2, removed)
        assertEquals(0, store.listForOwner(alice).size)
        assertEquals(1, store.listForOwner(bob).size)
    }

    @Test
    fun mint_rejectsWrongSizedGroupId() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        try {
            introducer.mint(alice, ByteArray(31))
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun mint_clockProvider_stampsCreatedAt() = runTest {
        val frozenNow = 1_700_000_000_000L
        // The store no longer sweeps by age, so a 2023-dated entry
        // survives against today's wall clock without syncing clocks.
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(
            store = store,
            ioDispatcher = Dispatchers.Unconfined,
            random = SecureRandom(),
            clock = { frozenNow },
        )

        val cap = introducer.mint(alice, sampleGroupId)
        val entry = store.find(cap.introPublicKey)!!
        assertEquals(frozenNow, entry.createdAtMillis)
    }

    // ─── currentOrMint (multi-use links) ──────────────────────────

    @Test
    fun currentOrMint_firstCall_mintsAndPersists() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        val cap = introducer.currentOrMint(alice, sampleGroupId)

        val listed = store.listForOwner(alice)
        assertEquals(1, listed.size)
        assertTrue(listed.first().introPublicKey.contentEquals(cap.introPublicKey))
    }

    @Test
    fun currentOrMint_secondCall_reusesSameKey_andDoesNotGrowTheStore() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        val first = introducer.currentOrMint(alice, sampleGroupId)
        val second = introducer.currentOrMint(alice, sampleGroupId)

        assertTrue(first.introPublicKey.contentEquals(second.introPublicKey))
        // The count is what stops this from being "returns the same
        // link but still writes a row".
        assertEquals(1, store.listForOwner(alice).size)
    }

    @Test
    fun currentOrMint_neverExpires_soAnAncientKeyIsStillReused() = runTest {
        var now = 1_700_000_000_000L
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(
            store,
            ioDispatcher = Dispatchers.Unconfined,
            clock = { now },
        )

        val first = introducer.currentOrMint(alice, sampleGroupId)
        // A year later. Invite links have no TTL — only rotate or
        // revoke retires one.
        now += 365L * 24 * 60 * 60 * 1000
        val second = introducer.currentOrMint(alice, sampleGroupId)

        assertTrue(first.introPublicKey.contentEquals(second.introPublicKey))
        assertEquals(1, store.listForOwner(alice).size)
    }

    // ─── rotate / revoke ──────────────────────────────────────────

    @Test
    fun rotate_mintsAFreshKey_andRetiresTheOldOne() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        val old = introducer.currentOrMint(alice, sampleGroupId)
        val new = introducer.rotate(alice, sampleGroupId)

        assertFalse(old.introPublicKey.contentEquals(new.introPublicKey))
        assertNull(
            "the superseded link must stop decrypting requests",
            store.find(old.introPublicKey),
        )
        assertNotNull(store.find(new.introPublicKey))
        assertEquals("rotate must not leave the old slot behind", 1, store.listForOwner(alice).size)
    }

    @Test
    fun rotate_thenCurrentOrMint_returnsTheRotatedKey() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        introducer.currentOrMint(alice, sampleGroupId)
        val rotated = introducer.rotate(alice, sampleGroupId)
        val resolved = introducer.currentOrMint(alice, sampleGroupId)

        assertTrue(rotated.introPublicKey.contentEquals(resolved.introPublicKey))
    }

    @Test
    fun rotate_leavesOtherGroupsAlone() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)
        val other = ByteArray(32) { 0x5A }

        val untouched = introducer.currentOrMint(alice, other)
        introducer.currentOrMint(alice, sampleGroupId)
        introducer.rotate(alice, sampleGroupId)

        assertNotNull(store.find(untouched.introPublicKey))
    }

    @Test
    fun rotate_doesNotRetireLabelledOfferKeys() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        // Per-invitee offers are revoked one at a time from the invite
        // list; rotating the shared link must not sweep them away.
        val offer = introducer.mint(alice, sampleGroupId, label = "aabbccdd")
        introducer.currentOrMint(alice, sampleGroupId)
        introducer.rotate(alice, sampleGroupId)

        assertNotNull(store.find(offer.introPublicKey))
    }

    @Test
    fun currentOrMint_ignoresLabelledOfferKeys() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        // The create-with-invitees flow lands on the share screen with
        // the last invitee's offer key as the newest entry. Handing
        // that out as the group's link would collapse the 1:1
        // request-to-invitee mapping.
        val offer = introducer.mint(alice, sampleGroupId, label = "aabbccdd")
        val shared = introducer.currentOrMint(alice, sampleGroupId)

        assertFalse(offer.introPublicKey.contentEquals(shared.introPublicKey))
    }

    @Test
    fun liveInvites_listsEveryKeyForTheGroup() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)
        val other = ByteArray(32) { 0x5A }

        val shared = introducer.currentOrMint(alice, sampleGroupId)
        val offer = introducer.mint(alice, sampleGroupId, label = "aabbccdd")
        introducer.currentOrMint(alice, other)

        val live = introducer.liveInvites(alice, sampleGroupId)

        assertEquals(2, live.size)
        assertTrue(live.any { it.introPublicKey.contentEquals(shared.introPublicKey) })
        assertEquals(
            offer.introPublicKey.toList(),
            live.single { it.label == "aabbccdd" }.introPublicKey.toList(),
        )
    }

    @Test
    fun currentOrMint_isScopedPerGroupAndPerOwner() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)
        val otherGroup = ByteArray(32) { 0x5A }

        val aliceG1 = introducer.currentOrMint(alice, sampleGroupId)
        val aliceG2 = introducer.currentOrMint(alice, otherGroup)
        // The intro pump only subscribes the *active* identity's tags,
        // so reusing Bob's key for Alice would hand out a link nobody
        // is listening on.
        val bobG1 = introducer.currentOrMint(bob, sampleGroupId)
        val aliceG1Again = introducer.currentOrMint(alice, sampleGroupId)

        assertFalse(aliceG1.introPublicKey.contentEquals(aliceG2.introPublicKey))
        assertFalse(aliceG1.introPublicKey.contentEquals(bobG1.introPublicKey))
        assertTrue(aliceG1.introPublicKey.contentEquals(aliceG1Again.introPublicKey))
    }

    @Test
    fun mint_alwaysMintsFresh_evenWhenALiveKeyExists() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Unconfined)

        // Locks in the two-entry-point split: collapsing `mint` into
        // `currentOrMint` would silently break the create-time offers'
        // 1:1 request-to-invitee mapping.
        val shared = introducer.currentOrMint(alice, sampleGroupId)
        val fresh = introducer.mint(alice, sampleGroupId)

        assertFalse(shared.introPublicKey.contentEquals(fresh.introPublicKey))
        assertEquals(2, store.listForOwner(alice).size)
    }
}
