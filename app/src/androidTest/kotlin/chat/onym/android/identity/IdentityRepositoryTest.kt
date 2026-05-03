package chat.onym.android.identity

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security
import java.util.UUID

/**
 * Instrumented tests against the real Android Keystore +
 * EncryptedSharedPreferences. Each test uses its own unique prefs
 * file name so test runs are isolated and can wipe cleanly.
 *
 * The OnymSDK FFI is loaded from the AAR's `jni/<abi>/libonym_sdk_jni.so`
 * — only present on Android, which is why this lives in
 * `androidTest/` and not `test/`. The cross-platform fixture has a
 * JVM-unit twin in [CrossPlatformFixtureTest] that covers everything
 * except the two FFI-derived public keys.
 */
@RunWith(AndroidJUnit4::class)
class IdentityRepositoryTest {

    private lateinit var store: IdentitySecretStore
    private lateinit var repo: IdentityRepository
    private lateinit var prefsFileName: String

    @Before
    fun setUp() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 2)
        }
        prefsFileName = "chat.onym.android.identity.tests.${UUID.randomUUID()}"
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        store = IdentitySecretStore(ctx, prefsFileName)
        repo = IdentityRepository(store)
    }

    @After
    fun tearDown() {
        try {
            store.wipeAll()
        } catch (_: Throwable) {
            // Best-effort cleanup; the unique prefs file name keeps
            // tests from colliding even if a previous run leaked.
        }
    }

    // ─── bootstrap ──────────────────────────────────────────────────

    @Test
    fun bootstrap_freshInstall_generatesAndPersistsBip39Identity() = runBlocking {
        val identity = repo.bootstrap()

        assertEquals(32, identity.nostrPublicKey.size)
        assertEquals(48, identity.blsPublicKey.size)
        assertEquals(32, identity.stellarPublicKey.size)
        assertEquals(32, identity.inboxPublicKey.size)
        assertEquals(16, identity.inboxTag.length)
        assertTrue(identity.stellarAccountID.startsWith("G"))
        assertEquals(56, identity.stellarAccountID.length)
        assertNotNull(identity.recoveryPhrase)
        assertEquals(12, identity.recoveryPhrase!!.split(" ").size)

        val activeId = store.loadCurrent()
        assertNotNull("bootstrap must persist a current-identity selection", activeId)
        val stored = store.load(activeId!!)
        assertNotNull(stored)
        assertEquals(16, stored!!.entropy?.size)
        assertEquals(32, stored.nostrSecretKey.size)
        assertEquals(32, stored.blsSecretKey.size)
    }

    /**
     * **Cross-platform interop fixture (full).** Adds the two
     * FFI-derived pubkeys to the JVM-unit fixture's coverage —
     * `nostrPublicKey` from `Common.nostrDerivePublicKey` and
     * `blsPublicKey` from `Common.publicKey`. Locks in derivation
     * end-to-end against `abandon × 11 + about`.
     */
    @Test
    fun derivation_matchesCrossPlatformFixture() = runBlocking {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon about"
        val identity = repo.restore(mnemonic)

        assertEquals(
            "1ee9632e948a11ff2b00fd0acf11f642fadcf14cd14d1f15b3bb6c072a268894",
            identity.nostrPublicKey.toHex(),
        )
        assertEquals(
            "93c738ad5a4ff1be5692bd9b9eebb168c23710b7926b105fce3ee82fdf94debd" +
                "17fef8ab2950622704438a2f16dbe3d6",
            identity.blsPublicKey.toHex(),
        )
        assertEquals(
            "2d26005ffeaf78d38581e0c1c1cea3a7ae5d9510b0215a122c2b8c7ea24c6118",
            identity.stellarPublicKey.toHex(),
        )
        assertEquals(
            "GAWSMAC772XXRU4FQHQMDQOOUOT24XMVCCYCCWQSFQVYY7VCJRQRRF2K",
            identity.stellarAccountID,
        )
        assertEquals(
            "677244099e153cd18331aa2b44132d82b2a7f385f339b05184ac92df77e79d50",
            identity.inboxPublicKey.toHex(),
        )
        assertEquals(
            "2257fa71222dcc05",
            identity.inboxTag,
        )
    }

    @Test
    fun bootstrap_isIdempotent() = runBlocking {
        val first = repo.bootstrap()
        val second = repo.bootstrap()
        assertEquals(first, second)
    }

    @Test
    fun bootstrap_picksUpExistingKeychainItem() = runBlocking {
        val first = repo.bootstrap()

        // Fresh repo against the same store — should load, not regenerate.
        val secondRepo = IdentityRepository(store)
        val loaded = secondRepo.bootstrap()
        assertEquals(first, loaded)
    }

    // ─── restore ────────────────────────────────────────────────────

    @Test
    fun restore_replacesIdentityWithMnemonicDerivedKeys() = runBlocking {
        val original = repo.bootstrap()
        val originalMnemonic = original.recoveryPhrase!!

        // The all-zeros canonical mnemonic — overwhelmingly unlikely
        // to collide with the random one bootstrap just generated.
        val differentMnemonic = "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
        assertNotEquals(differentMnemonic, originalMnemonic)
        val restored = repo.restore(differentMnemonic)

        assertNotEquals(
            original.nostrPublicKey.toList(),
            restored.nostrPublicKey.toList(),
        )
        assertEquals(differentMnemonic, restored.recoveryPhrase)
    }

    @Test
    fun restore_isDeterministic() = runBlocking {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon about"
        val first = repo.restore(mnemonic)
        repo.wipe()
        val second = repo.restore(mnemonic)
        assertEquals(first, second)
    }

    @Test
    fun restore_rejectsInvalidMnemonic() = runBlocking {
        try {
            repo.restore("not a valid mnemonic at all")
            fail("expected IdentityError.InvalidMnemonic")
        } catch (e: IdentityError.InvalidMnemonic) {
            // expected
        }
    }

    // ─── wipe ──────────────────────────────────────────────────────

    @Test
    fun wipe_clearsKeychainAndCurrentIdentity() = runBlocking {
        repo.bootstrap()
        repo.wipe()
        assertNull(repo.currentIdentity())
        assertTrue(
            "wipe clears the active identity from the keyed store",
            store.listIds().isEmpty(),
        )
        assertNull(
            "wipe also clears the `current` selection",
            store.loadCurrent(),
        )
    }

    // ─── snapshots ─────────────────────────────────────────────────

    @Test
    fun snapshots_yieldsCurrentValueImmediatelyOnSubscribe() = runBlocking {
        val identity = repo.bootstrap()
        val first = repo.snapshots.first()
        assertEquals(identity, first)
    }

    @Test
    fun snapshots_yieldsAfterEveryMutation() = runBlocking {
        // StateFlow's replay-1 semantics mean the latest value is
        // always observable; we don't need to subscribe before
        // mutating to capture every step.
        val initial = repo.snapshots.first()
        assertNull(initial)

        val generated = repo.bootstrap()
        assertEquals(generated, repo.snapshots.first())

        val restored = repo.restore(
            "abandon abandon abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon about"
        )
        assertEquals(restored, repo.snapshots.first())

        repo.wipe()
        assertNull(repo.snapshots.first())
    }

    // ─── Multi-identity API (PR-2) ─────────────────────────────────

    @Test
    fun add_appendsIdentity_andSelectsIt() = runBlocking {
        val first = repo.bootstrap()
        val firstId = repo.currentIdentityId.first()!!
        assertEquals(1, repo.identities.first().size)

        val secondId = repo.add(name = "Work")
        assertNotNull(secondId)
        assertNotEquals("add must mint a fresh id", firstId, secondId)

        val list = repo.identities.first()
        assertEquals(2, list.size)
        assertEquals(secondId, repo.currentIdentityId.first())
        assertEquals("Work", list.single { it.id == secondId }.name)
        // Original identity is preserved (not wiped like restore() does).
        assertEquals(true, list.any { it.id == firstId })
    }

    @Test
    fun add_blankName_autofillsToIdentityN() = runBlocking {
        repo.bootstrap()
        repo.add(name = "  ")
        repo.add(name = "")
        val list = repo.identities.first()
        // Bootstrap yields name="" (intentional — first install reads
        // as a single nameless identity); the two `add` calls fill the
        // 2nd and 3rd slots with the auto-label.
        val names = list.map { it.name }
        assertEquals("Identity 2", names[1])
        assertEquals("Identity 3", names[2])
    }

    @Test
    fun add_withRestoreMnemonic_restoresInsteadOfGenerating() = runBlocking {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon about"
        repo.bootstrap()
        val restoredId = repo.add(name = "Imported", restoreMnemonic = mnemonic)
        // The identity restored from the test-vector mnemonic has a
        // recovery phrase that round-trips to the same words.
        val identity = repo.snapshots.first()!!
        assertEquals(mnemonic, identity.recoveryPhrase)
        assertEquals(restoredId, repo.currentIdentityId.first())
    }

    @Test
    fun select_swapsActiveIdentity_andEmitsOnSnapshots() = runBlocking {
        val firstId = repo.add(name = "A")
        val firstIdentity = repo.snapshots.first()!!
        val secondId = repo.add(name = "B")
        val secondIdentity = repo.snapshots.first()!!
        assertEquals(secondId, repo.currentIdentityId.first())

        repo.select(firstId)
        assertEquals(firstId, repo.currentIdentityId.first())
        // Snapshot bytes match the originally-bootstrapped identity.
        assertArrayEquals(firstIdentity.blsPublicKey, repo.snapshots.first()!!.blsPublicKey)
        assertNotEquals(
            "switching identities must change the public key",
            secondIdentity.blsPublicKey.toList(),
            repo.snapshots.first()!!.blsPublicKey.toList(),
        )
    }

    @Test
    fun select_unknownId_throws() = runBlocking {
        repo.bootstrap()
        try {
            repo.select(IdentityId.new())
            error("expected IdentityNotLoaded")
        } catch (_: IdentityError.IdentityNotLoaded) {
            // expected
        }
    }

    // ─── rename ────────────────────────────────────────────────────

    @Test
    fun rename_inactiveIdentity_persistsAndEmitsOnIdentities() = runBlocking {
        repo.bootstrap()
        val firstId = repo.currentIdentityId.first()!!
        val secondId = repo.add(name = "Work")
        // Active is `secondId`; rename the inactive `firstId`.
        repo.rename(firstId, "Personal")

        val list = repo.identities.first()
        assertEquals("Personal", list.single { it.id == firstId }.name)
        assertEquals("Work", list.single { it.id == secondId }.name)
        // Active selection unchanged.
        assertEquals(secondId, repo.currentIdentityId.first())

        // Survives a full reload from disk.
        val freshRepo = IdentityRepository(IdentitySecretStore(
            ApplicationProvider.getApplicationContext(),
            prefsFileName,
        ))
        freshRepo.bootstrap()
        val reloaded = freshRepo.identities.first()
        assertEquals("Personal", reloaded.single { it.id == firstId }.name)
    }

    @Test
    fun rename_activeIdentity_updatesSummaryNotIdentity() = runBlocking {
        val firstId = repo.add(name = "Original")
        val originalIdentity = repo.snapshots.first()!!
        repo.rename(firstId, "Renamed")

        // Summary list reflects the new name.
        val list = repo.identities.first()
        assertEquals("Renamed", list.single { it.id == firstId }.name)

        // Active Identity (which doesn't carry a name) is unchanged
        // — same key bytes, no spurious re-derivation.
        val afterIdentity = repo.snapshots.first()!!
        assertArrayEquals(originalIdentity.nostrPublicKey, afterIdentity.nostrPublicKey)
        assertArrayEquals(originalIdentity.blsPublicKey, afterIdentity.blsPublicKey)
    }

    @Test
    fun rename_trimsWhitespace() = runBlocking {
        val id = repo.add(name = "Original")
        repo.rename(id, "  Padded   ")
        val list = repo.identities.first()
        assertEquals("Padded", list.single { it.id == id }.name)
    }

    @Test
    fun rename_blankInput_isNoOp() = runBlocking {
        val id = repo.add(name = "Keep")
        repo.rename(id, "   ")
        repo.rename(id, "")
        val list = repo.identities.first()
        assertEquals("Keep", list.single { it.id == id }.name)
    }

    @Test
    fun rename_unknownId_throws() = runBlocking {
        repo.bootstrap()
        try {
            repo.rename(IdentityId.new(), "Whatever")
            fail("expected IdentityNotLoaded")
        } catch (_: IdentityError.IdentityNotLoaded) {
            // expected
        }
    }

    @Test
    fun remove_activeIdentity_pivotsToNext() = runBlocking {
        val firstId = repo.add(name = "A")
        val secondId = repo.add(name = "B")
        assertEquals(secondId, repo.currentIdentityId.first())

        repo.remove(secondId)
        assertEquals("pivot to the remaining identity",
            firstId, repo.currentIdentityId.first())
        assertEquals(1, repo.identities.first().size)
    }

    @Test
    fun remove_lastIdentity_clearsSelection() = runBlocking {
        repo.bootstrap()
        val onlyId = repo.currentIdentityId.first()!!
        repo.remove(onlyId)
        assertNull(repo.currentIdentityId.first())
        assertNull(repo.snapshots.first())
        assertEquals(0, repo.identities.first().size)
    }

    @Test
    fun remove_invokesRemovalListener_beforeWipe() = runBlocking {
        repo.bootstrap()
        val id = repo.currentIdentityId.first()!!
        var listenerSawId: IdentityId? = null
        var snapshotPresentDuringListener = false
        repo.setRemovalListener { invokedId ->
            listenerSawId = invokedId
            // Snapshot must still be on disk while the listener runs —
            // PR-3's GroupRepository will use this window to delete
            // chats owned by the about-to-be-wiped identity.
            snapshotPresentDuringListener = store.load(invokedId) != null
        }
        repo.remove(id)
        assertEquals(id, listenerSawId)
        assertEquals(true, snapshotPresentDuringListener)
        assertNull("after wipe, snapshot is gone", store.load(id))
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}
