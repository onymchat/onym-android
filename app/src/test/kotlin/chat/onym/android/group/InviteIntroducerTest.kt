package chat.onym.android.group

import chat.onym.android.identity.IdentityId
import chat.onym.android.support.InMemoryIntroKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
        val store = InMemoryIntroKeyStore()
        val frozenNow = 1_700_000_000_000L
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
}
