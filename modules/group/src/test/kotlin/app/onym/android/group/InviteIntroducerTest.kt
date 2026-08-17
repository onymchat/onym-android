package app.onym.android.group

import app.onym.android.identity.IdentityId
import app.onym.android.support.InMemoryIntroKeyStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
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

    /** Everything written before labels existed decodes as
     *  `label == null`, i.e. indistinguishable from a shared link — and
     *  listForOwner is newest-first, so on a create-with-invitees group
     *  the one currentOrMint would pick is the LAST INVITEE'S private
     *  offer key. Adopting it hands one person's private link to
     *  everyone; rotating then revokes every legacy invite at once. */
    @Test
    fun legacyEntries_areNeverAdoptedAsTheSharedLink() = runTest {
        val store = InMemoryIntroKeyStore()
        val owner = IdentityId("owner-legacy")
        val groupId = ByteArray(32) { 0x11 }
        val legacyOffer = IntroKeyEntry(
            introPublicKey = ByteArray(32) { 0x22 },
            introPrivateKey = ByteArray(32) { 0x23 },
            ownerIdentityId = owner,
            groupId = groupId,
            createdAtMillis = 9_999_999_999,   // newest, so it would win
            label = null,
            isLegacy = true,
        )
        store.save(legacyOffer)
        val introducer = InviteIntroducer(store)

        val cap = introducer.currentOrMint(ownerIdentityId = owner, groupId = groupId)
        assertFalse(
            cap.introPublicKey.contentEquals(legacyOffer.introPublicKey),
        )

        introducer.rotate(ownerIdentityId = owner, groupId = groupId)
        val live = introducer.liveInvites(ownerIdentityId = owner, groupId = groupId)
        assertTrue(
            live.any { it.introPublicKey.contentEquals(legacyOffer.introPublicKey) },
        )
    }

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

        // Create-with-invitees leaves an offer key as the newest entry;
        // handing it out would collapse the 1:1 mapping.
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
        // The pump only subscribes the active identity's tags, so this
        // would hand out a link nobody is listening on.
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

        // Locks the two-entry-point split; collapsing them would break
        // the offers' 1:1 mapping.
        val shared = introducer.currentOrMint(alice, sampleGroupId)
        val fresh = introducer.mint(alice, sampleGroupId)

        assertFalse(shared.introPublicKey.contentEquals(fresh.introPublicKey))
        assertEquals(2, store.listForOwner(alice).size)
    }

    // ─── concurrency ──────────────────────────────────────────────

    @Test
    fun currentOrMint_concurrentCalls_produceExactlyOneKey() = runTest {
        // Gate the first read so both callers are provably past it
        // before either mints; `async` alone won't reproduce it.
        val gate = CompletableDeferred<Unit>()
        val store = GatedReadIntroKeyStore(InMemoryIntroKeyStore(), gate)
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Default)

        val first = async { introducer.currentOrMint(alice, sampleGroupId) }
        val second = async { introducer.currentOrMint(alice, sampleGroupId) }
        store.awaitFirstRead()
        gate.complete(Unit)
        val results = listOf(first, second).awaitAll()

        assertTrue(results[0].introPublicKey.contentEquals(results[1].introPublicKey))
        assertEquals("a second shared key would be unrevokable", 1, store.listForOwner(alice).size)
    }

    /** Holds the first [listForOwner] open until released, so a second
     *  caller is guaranteed to reach its own read meanwhile. */
    private class GatedReadIntroKeyStore(
        private val inner: InMemoryIntroKeyStore,
        private val gate: CompletableDeferred<Unit>,
    ) : IntroKeyStore by inner {
        private val firstRead = CompletableDeferred<Unit>()
        private var gated = false

        suspend fun awaitFirstRead() = firstRead.await()

        override suspend fun listForOwner(ownerIdentityId: IdentityId): List<IntroKeyEntry> {
            if (!gated) {
                gated = true
                firstRead.complete(Unit)
                gate.await()
            }
            return inner.listForOwner(ownerIdentityId)
        }
    }

    @Test
    fun rotate_concurrentWithCurrentOrMint_handsBackALiveLink() = runTest {
        val store = InMemoryIntroKeyStore()
        val introducer = InviteIntroducer(store, ioDispatcher = Dispatchers.Default)
        val before = introducer.currentOrMint(alice, sampleGroupId)

        val rotate = async { introducer.rotate(alice, sampleGroupId) }
        val resolve = async { introducer.currentOrMint(alice, sampleGroupId) }
        val rotated = rotate.await()
        val handedToCaller = resolve.await()

        // What serialization guarantees is a COHERENT value, not a
        // live one. Whichever call wins the per-group lock, the other
        // sees a settled store: the caller is handed either the fresh
        // link, or the one it resolved before rotate superseded it —
        // never a half-rotated state, and never a second shared link.
        //
        // Asserting the handed link is still live was wrong and flaked
        // roughly one run in three: when `currentOrMint` wins the lock
        // it legitimately returns the pre-rotate link, which rotate
        // then revokes. Benign in the app — ShareInviteFlow republishes
        // the rotated link into its own state, so a dead one is never
        // left on screen.
        val live = store.find(handedToCaller.introPublicKey) != null
        assertTrue(
            "the caller was handed neither the fresh link nor the superseded one",
            live || handedToCaller.introPublicKey.contentEquals(before.introPublicKey),
        )
        assertTrue(
            "rotate must publish a live link",
            store.find(rotated.introPublicKey) != null,
        )
        assertEquals(1, store.listForOwner(alice).count { it.label == null })
    }
}
