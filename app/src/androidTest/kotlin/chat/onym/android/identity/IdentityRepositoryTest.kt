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

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }
}
