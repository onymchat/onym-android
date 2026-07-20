package app.onym.android.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.support.TestInvitationEncryptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom
import java.security.Security

/**
 * End-to-end test of [IdentityRepository.decryptInvitation] against
 * a real `EncryptedSharedPreferences`-backed [IdentitySecretStore]
 * + the OnymSDK FFI for identity bootstrap. Each test uses a unique
 * prefs file so runs are isolated.
 *
 * Lives in `androidTest/` (not `test/`) for two reasons that match
 * the existing [IdentityRepositoryTest] placement:
 *
 *  - `EncryptedSharedPreferences` requires the real Android
 *    Keystore (Robolectric's keystore shadow doesn't carry the
 *    AndroidX Security crypto path).
 *  - `IdentityRepository.restore` invokes
 *    `chat.onym.sdk.Common.nostrDerivePublicKey` (and `publicKey`),
 *    which need the FFI .so loaded from the AAR's `jni/<abi>/`.
 *
 * The brief asked for `app/src/test/`; deviation is intentional and
 * mirrors the existing `IdentityRepositoryTest`.
 *
 * Mirrors `IdentityRepositoryInvitationDecryptTests.swift` from
 * onym-ios PR #17. iOS uses the host Mac's real Keychain + CryptoKit
 * directly inside an XCTest target, which is why the iOS twin is in
 * `Tests/OnymIOSTests/` (their JVM equivalent of `app/src/test/`).
 */
@RunWith(AndroidJUnit4::class)
class IdentityRepositoryInvitationDecryptTest {

    /** BIP39 mnemonic used across iOS / stellar-mls / onym-android
     *  cross-platform fixtures. Recovery yields the same identity on
     *  every platform — see `CrossPlatformFixtureTest`. */
    private val testMnemonic =
        "legal winner thank year wave sausage worth useful legal winner thank yellow"

    private lateinit var ctx: Context
    private lateinit var store: IdentitySecretStore
    private lateinit var repo: IdentityRepository

    @Before
    fun setUp() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 2)
        }
        ctx = ApplicationProvider.getApplicationContext()
        store = IdentitySecretStore(
            ctx,
            prefsFileName = "app.onym.android.identity.invitedecrypt",
        )
        repo = IdentityRepository(store)
        runBlocking { repo.restore(testMnemonic) }
    }

    @After
    fun tearDown() {
        try { store.wipeAll() } catch (_: Throwable) { /* best-effort */ }
    }

    // ─── happy paths ──────────────────────────────────────────────

    @Test
    fun decrypt_roundTrip_withoutSignature() = runBlocking {
        val identity = repo.snapshots.value!!
        val payload = "secret bootstrap payload — withSignature=false".toByteArray()

        val envelope = TestInvitationEncryptor.envelopeBytes(
            payload = payload,
            recipientX25519Pubkey = identity.inboxPublicKey,
        )
        val plaintext = repo.decryptInvitation(envelope, asIdentity = repo.currentIdentityId.value!!)
        assertArrayEquals(payload, plaintext)
    }

    @Test
    fun decrypt_roundTrip_withSenderEd25519Signature() = runBlocking {
        val identity = repo.snapshots.value!!
        val sender = Ed25519PrivateKeyParameters(SecureRandom())
        val payload = "secret bootstrap payload — withSignature=true".toByteArray()

        val envelope = TestInvitationEncryptor.envelopeBytes(
            payload = payload,
            recipientX25519Pubkey = identity.inboxPublicKey,
            senderEd25519Private = sender,
        )
        val plaintext = repo.decryptInvitation(envelope, asIdentity = repo.currentIdentityId.value!!)
        assertArrayEquals(payload, plaintext)
    }

    // ─── error paths ──────────────────────────────────────────────

    @Test
    fun decrypt_malformedJson_throwsMalformedEnvelope() = runBlocking {
        val garbage = "not really json {{".toByteArray()
        assertThrows(InvitationDecryptError.MalformedEnvelope::class.java) {
            runBlocking { repo.decryptInvitation(garbage, asIdentity = repo.currentIdentityId.value!!) }
        }
        Unit
    }

    @Test
    fun decrypt_unsupportedScheme_throwsUnsupportedScheme() = runBlocking {
        val bad = """
            {
              "version": 1,
              "scheme": "aes-128-cbc-v0",
              "ephemeral_public_key": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
              "nonce": "AQIDBAUGBwgJCgsM",
              "ciphertext": "AQID",
              "authentication_tag": "AAECAwQFBgcICQoLDA0ODw=="
            }
        """.trimIndent().toByteArray()
        val thrown = assertThrows(InvitationDecryptError.UnsupportedScheme::class.java) {
            runBlocking { repo.decryptInvitation(bad, asIdentity = repo.currentIdentityId.value!!) }
        }
        assertEquals("aes-128-cbc-v0", thrown.scheme)
        Unit
    }

    @Test
    fun decrypt_missingEphemeralKey_throwsMissingEphemeralKey() = runBlocking {
        val bad = """
            {
              "version": 1,
              "scheme": "x25519-aes-256-gcm-v1",
              "nonce": "AQIDBAUGBwgJCgsM",
              "ciphertext": "AQID",
              "authentication_tag": "AAECAwQFBgcICQoLDA0ODw=="
            }
        """.trimIndent().toByteArray()
        val thrown = assertThrows(InvitationDecryptError.MissingEphemeralKey::class.java) {
            runBlocking { repo.decryptInvitation(bad, asIdentity = repo.currentIdentityId.value!!) }
        }
        assertSame(InvitationDecryptError.MissingEphemeralKey, thrown)
        Unit
    }

    @Test
    fun decrypt_tamperedSignature_throwsSignatureFailed() = runBlocking {
        val identity = repo.snapshots.value!!
        val sender = Ed25519PrivateKeyParameters(SecureRandom())
        val envelope = TestInvitationEncryptor.sealedEnvelope(
            payload = "p".toByteArray(),
            recipientX25519Pubkey = identity.inboxPublicKey,
            senderEd25519Private = sender,
        )
        // Flip a bit in the signature (offset past the size header).
        val tampered = envelope.copy(
            ephemeralKeySignature = envelope.ephemeralKeySignature!!.copyOf().also {
                it[0] = (it[0].toInt() xor 0x01).toByte()
            },
        )
        val bytes = Json { encodeDefaults = true }
            .encodeToString(SealedEnvelope.serializer(), tampered)
            .toByteArray()
        val thrown = assertThrows(InvitationDecryptError.SignatureFailed::class.java) {
            runBlocking { repo.decryptInvitation(bytes, asIdentity = repo.currentIdentityId.value!!) }
        }
        assertSame(InvitationDecryptError.SignatureFailed, thrown)
        Unit
    }

    @Test
    fun decrypt_tamperedCiphertext_throwsDecryptionFailed() = runBlocking {
        val identity = repo.snapshots.value!!
        val envelope = TestInvitationEncryptor.sealedEnvelope(
            payload = "to be tampered".toByteArray(),
            recipientX25519Pubkey = identity.inboxPublicKey,
        )
        val tampered = envelope.copy(
            ciphertext = envelope.ciphertext.copyOf().also {
                it[0] = (it[0].toInt() xor 0x01).toByte()
            },
        )
        val bytes = Json { encodeDefaults = true }
            .encodeToString(SealedEnvelope.serializer(), tampered)
            .toByteArray()
        assertThrows(InvitationDecryptError.DecryptionFailed::class.java) {
            runBlocking { repo.decryptInvitation(bytes, asIdentity = repo.currentIdentityId.value!!) }
        }
        Unit
    }

    @Test
    fun decrypt_wrongRecipient_throwsDecryptionFailed() = runBlocking {
        // Encrypt for some random other recipient — auth tag fails
        // for the same reason as a tampered ciphertext (AES-GCM
        // can't tell them apart). Same error class is correct.
        val otherInbox = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val envelope = TestInvitationEncryptor.envelopeBytes(
            payload = "not for us".toByteArray(),
            recipientX25519Pubkey = wellFormedX25519Pubkey(otherInbox),
        )
        assertThrows(InvitationDecryptError.DecryptionFailed::class.java) {
            runBlocking { repo.decryptInvitation(envelope, asIdentity = repo.currentIdentityId.value!!) }
        }
        Unit
    }

    @Test
    fun decrypt_afterWipe_throwsIdentityNotLoaded() = runBlocking {
        val identity = repo.snapshots.value!!
        // Capture the active id BEFORE wipe — `repo.wipe()` clears
        // the active selection (and the snapshot stream) to null, so
        // reading `currentIdentityId.value!!` afterwards would NPE
        // before reaching `decryptInvitation`. The contract under
        // test is that calling decrypt for a no-longer-present
        // identity throws `IdentityNotLoaded` — same id, post-wipe
        // store is what exercises that.
        val identityId = repo.currentIdentityId.value!!
        val envelope = TestInvitationEncryptor.envelopeBytes(
            payload = "p".toByteArray(),
            recipientX25519Pubkey = identity.inboxPublicKey,
        )
        repo.wipe()
        val thrown = assertThrows(InvitationDecryptError.IdentityNotLoaded::class.java) {
            runBlocking { repo.decryptInvitation(envelope, asIdentity = identityId) }
        }
        assertSame(InvitationDecryptError.IdentityNotLoaded, thrown)
        Unit
    }

    /** Force [bytes] onto the X25519 small-subgroup-clear shape that
     *  BouncyCastle expects (clamp the first/last bytes per RFC
     *  7748). Used to fabricate a "well-formed but not us" recipient
     *  pubkey from arbitrary 32 bytes. */
    private fun wellFormedX25519Pubkey(bytes: ByteArray): ByteArray {
        val clamped = bytes.copyOf()
        // The X25519 public key is just a 32-byte u-coord; no
        // clamping needed (clamping applies to private keys). Any
        // 32 random bytes is a valid public key (with negligible
        // probability of being weak / on a small subgroup).
        return clamped
    }
}
