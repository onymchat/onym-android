package app.onym.android.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security
import java.util.UUID

/**
 * Sender side of the invitation envelope. Mirrors
 * [IdentityRepositoryInvitationDecryptTest] but exercises the new
 * [IdentityRepository.sealInvitation] method — sealed bytes round-trip
 * through `decryptInvitation` on a *different* [IdentityRepository]
 * (the recipient) and the M-5 ephemeral-pubkey signature must verify
 * against the sender's identity key.
 *
 * Lives in `androidTest/` for the same reason as the decrypt twin —
 * the underlying `IdentitySecretStore` needs the real Android
 * Keystore + the OnymSDK `.so`.
 *
 * Mirrors `IdentityRepositorySealInvitationTests.swift` from
 * onym-ios PR #24.
 */
@RunWith(AndroidJUnit4::class)
class IdentityRepositorySealInvitationTest {

    /** Cross-platform fixture mnemonics. Two distinct identities so
     *  sender and recipient have different inbox + signing keys. */
    private val senderMnemonic =
        "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val recipientMnemonic =
        "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"

    private lateinit var ctx: Context
    private lateinit var senderStore: IdentitySecretStore
    private lateinit var recipientStore: IdentitySecretStore
    private lateinit var sender: IdentityRepository
    private lateinit var recipient: IdentityRepository

    @Before
    fun setUp() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 2)
        }
        ctx = ApplicationProvider.getApplicationContext()
        senderStore = IdentitySecretStore(
            ctx,
            prefsFileName = "app.onym.android.identity.seal.sender.${UUID.randomUUID()}",
        )
        recipientStore = IdentitySecretStore(
            ctx,
            prefsFileName = "app.onym.android.identity.seal.recipient.${UUID.randomUUID()}",
        )
        sender = IdentityRepository(senderStore)
        recipient = IdentityRepository(recipientStore)
        runBlocking {
            sender.restore(senderMnemonic)
            recipient.restore(recipientMnemonic)
        }
    }

    @After
    fun tearDown() {
        try { senderStore.wipeAll() } catch (_: Throwable) { /* best-effort */ }
        try { recipientStore.wipeAll() } catch (_: Throwable) { /* best-effort */ }
    }

    // ─── happy paths ──────────────────────────────────────────────

    @Test
    fun seal_roundtripsThroughRecipientDecrypt() = runBlocking {
        val recipientIdentity = recipient.snapshots.value!!
        val plaintext = "hello, invitee".toByteArray()

        val sealed = sender.sealInvitation(
            payload = plaintext,
            recipientInboxPublicKey = recipientIdentity.inboxPublicKey,
        )
        val decrypted = recipient.decryptInvitation(sealed, asIdentity = recipient.currentIdentityId.value!!)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun seal_signatureVerifiesAgainstSenderIdentity() = runBlocking {
        val recipientIdentity = recipient.snapshots.value!!
        val senderIdentity = sender.snapshots.value!!

        val sealed = sender.sealInvitation(
            payload = "attested".toByteArray(),
            recipientInboxPublicKey = recipientIdentity.inboxPublicKey,
        )
        val envelope = Json { ignoreUnknownKeys = true }
            .decodeFromString(SealedEnvelope.serializer(), sealed.toString(Charsets.UTF_8))

        val senderPub = envelope.senderEd25519PublicKey
        assertNotNull("envelope must carry sender Ed25519 pubkey", senderPub)
        assertArrayEquals(
            senderIdentity.stellarPublicKey,
            senderPub,
        )
        val ephPub = envelope.ephemeralPublicKey!!
        val sig = envelope.ephemeralKeySignature!!
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(senderPub!!, 0))
        }
        verifier.update(ephPub, 0, ephPub.size)
        assertTrue("M-5 signature over ephemeral pubkey must verify", verifier.verifySignature(sig))
    }

    @Test
    fun seal_freshEphemeralPerCall() = runBlocking {
        val recipientIdentity = recipient.snapshots.value!!
        val first = sender.sealInvitation("a".toByteArray(), recipientIdentity.inboxPublicKey)
        val second = sender.sealInvitation("a".toByteArray(), recipientIdentity.inboxPublicKey)

        val json = Json { ignoreUnknownKeys = true }
        val firstEnv = json.decodeFromString(SealedEnvelope.serializer(), first.toString(Charsets.UTF_8))
        val secondEnv = json.decodeFromString(SealedEnvelope.serializer(), second.toString(Charsets.UTF_8))
        assertFalse(
            "each seal must mint a fresh per-envelope X25519 keypair",
            firstEnv.ephemeralPublicKey!!.contentEquals(secondEnv.ephemeralPublicKey!!),
        )
        assertFalse(
            "different nonce → different ciphertext",
            firstEnv.ciphertext.contentEquals(secondEnv.ciphertext),
        )
    }

    // ─── error paths ──────────────────────────────────────────────

    @Test
    fun seal_rejectsInvalidRecipientPublicKey_wrongSize() = runBlocking {
        val tooShort = ByteArray(16)  // X25519 expects 32B
        assertThrows(InvitationSealError.InvalidRecipientPublicKey::class.java) {
            runBlocking { sender.sealInvitation("x".toByteArray(), tooShort) }
        }
        Unit
    }

    @Test
    fun seal_throwsIdentityNotLoaded_afterWipe() = runBlocking {
        val recipientIdentity = recipient.snapshots.value!!
        sender.wipe()

        val thrown = assertThrows(InvitationSealError.IdentityNotLoaded::class.java) {
            runBlocking {
                sender.sealInvitation("x".toByteArray(), recipientIdentity.inboxPublicKey)
            }
        }
        assertSame(InvitationSealError.IdentityNotLoaded, thrown)
        Unit
    }
}
