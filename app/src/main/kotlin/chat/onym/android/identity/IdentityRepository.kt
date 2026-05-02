package chat.onym.android.identity

import chat.onym.sdk.Common
import chat.onym.sdk.OnymException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Owns the on-device identity. All EncryptedSharedPreferences I/O,
 * BIP39 + HKDF derivation, and OnymSDK calls happen here; UI observes
 * [snapshots] and never touches secret material.
 *
 * ## Reactive surface
 *
 * [snapshots] is a hot [StateFlow]. Every collector immediately
 * receives the current value (possibly `null` before bootstrap), then
 * a fresh value after every successful `bootstrap` / `generateNew` /
 * `restore` / `wipe`. UI typically uses
 * `repository.snapshots.collectAsStateWithLifecycle()`.
 *
 * iOS uses `actor + AsyncStream` because SwiftUI's `@Observable` macro
 * doesn't auto-subscribe to streams. Android doesn't have that
 * tradeoff — `StateFlow` + Compose's collector handle the per-view
 * ergonomics with no boilerplate.
 *
 * ## Threading
 *
 * A [Mutex] serialises mutation (no two `bootstrap` calls can race).
 * All Storage I/O, PBKDF2, HKDF, BC Curve25519, and FFI work happens
 * inside `withContext(ioDispatcher)` — never blocks the main thread.
 * [snapshots] is read-only off any thread.
 *
 * ## Process death
 *
 * A `class IdentityRepository` won't survive process death; the
 * MutableStateFlow value is reset to `null`. First subsequent
 * `bootstrap()` re-derives an [Identity] from the persisted
 * [StoredSnapshot] in EncryptedSharedPreferences, so the user's
 * identity is intact.
 */
class IdentityRepository(
    private val store: IdentitySecretStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InvitationEnvelopeDecrypter {
    private val mutex = Mutex()
    private val _snapshots = MutableStateFlow<Identity?>(null)

    /** Hot stream of identity snapshots. New collectors get the current
     *  value immediately, then one new value per successful mutation. */
    val snapshots: StateFlow<Identity?> = _snapshots.asStateFlow()

    /** Synchronous read of the current identity. Doesn't trigger a
     *  store load — call [bootstrap] for that. */
    fun currentIdentity(): Identity? = _snapshots.value

    /**
     * Load the persisted identity, or generate a fresh BIP39-backed
     * one if none exists. Idempotent: a second call after success
     * returns the same identity without re-reading the store.
     */
    suspend fun bootstrap(): Identity = mutex.withLock {
        _snapshots.value?.let { return@withLock it }
        withContext(ioDispatcher) {
            val stored = store.load()
            if (stored != null) {
                val identity = identityFromSnapshot(stored)
                _snapshots.value = identity
                identity
            } else {
                generateNewLocked()
            }
        }
    }

    /**
     * Generate a fresh BIP39-backed identity, replacing any existing
     * stored identity. The previous identity is unrecoverable after
     * this call (no in-app backup is taken).
     */
    suspend fun generateNew(): Identity = mutex.withLock {
        withContext(ioDispatcher) {
            generateNewLocked()
        }
    }

    /**
     * Restore an identity from a 12- or 24-word BIP39 mnemonic,
     * replacing any existing stored identity.
     *
     * @throws IdentityError.InvalidMnemonic if the mnemonic is
     *         malformed or has a bad checksum.
     */
    suspend fun restore(mnemonic: String): Identity = mutex.withLock {
        withContext(ioDispatcher) {
            val entropy = Bip39.entropyFromMnemonic(mnemonic)
                ?: throw IdentityError.InvalidMnemonic
            val snapshot = snapshotFromEntropy(entropy)
            store.save(snapshot)
            val identity = identityFromSnapshot(snapshot)
            _snapshots.value = identity
            identity
        }
    }

    /** Delete the persisted identity. Subscribers receive a `null` snapshot. */
    suspend fun wipe() = mutex.withLock {
        withContext(ioDispatcher) {
            store.wipe()
            _snapshots.value = null
        }
    }

    // ─── InvitationEnvelopeDecrypter ────────────────────────────────

    /**
     * Open an X25519+AES-GCM-sealed invitation envelope addressed to
     * this identity's inbox keypair. The X25519 private key is
     * recomputed from the persisted nostr secret on every call and
     * never assigned to a stored property — so the only persistent
     * footprint of this method on the recipient's machine is the
     * already-encrypted SharedPreferences blob.
     *
     * Throws [InvitationDecryptError]; never raw `javax.crypto` /
     * `kotlinx.serialization` exceptions. See [InvitationDecryptError]
     * for the failure-mode taxonomy.
     */
    override suspend fun decryptInvitation(envelopeBytes: ByteArray): ByteArray =
        withContext(ioDispatcher) {
            // Snapshot read happens off the mutex — `store.load()` is a
            // single atomic SharedPreferences read; concurrent `wipe()`
            // either observes the pre-wipe blob or post-wipe null,
            // both internally consistent.
            val snapshot = store.load() ?: throw InvitationDecryptError.IdentityNotLoaded
            decryptInvitationLocked(snapshot, envelopeBytes)
        }

    private fun decryptInvitationLocked(
        snapshot: StoredSnapshot,
        envelopeBytes: ByteArray,
    ): ByteArray {
        val envelope = try {
            envelopeJsonFormat.decodeFromString<SealedEnvelope>(envelopeBytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw InvitationDecryptError.MalformedEnvelope("invalid JSON: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            // base64 decode failure inside the field serializer
            throw InvitationDecryptError.MalformedEnvelope("invalid base64: ${e.message}", e)
        }
        if (envelope.scheme != INVITATION_SCHEME) {
            throw InvitationDecryptError.UnsupportedScheme(envelope.scheme)
        }
        val ephemeralPub = envelope.ephemeralPublicKey
            ?: throw InvitationDecryptError.MissingEphemeralKey

        // M-5: verify Ed25519 signature on the ephemeral pubkey if
        // the envelope provides one. Prevents MITM substitution of
        // the ephemeral key. Mirrors `GroupCrypto.decryptInvitation`
        // in stellar-mls.
        if (envelope.ephemeralKeySignature != null) {
            val senderPubkey = envelope.senderEd25519PublicKey
                ?: throw InvitationDecryptError.MalformedEnvelope(
                    "ephemeral_key_signature present without sender_ed25519_public_key"
                )
            val verifier = Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(senderPubkey, 0))
            }
            verifier.update(ephemeralPub, 0, ephemeralPub.size)
            if (!verifier.verifySignature(envelope.ephemeralKeySignature)) {
                throw InvitationDecryptError.SignatureFailed
            }
        }

        // ECDH against the recipient's X25519 private (recomputed
        // from nostrSecret per call; not stashed on `this`).
        val recipientPrivate = inboxKeyAgreementPrivateKey(snapshot.nostrSecretKey)
        val agreement = X25519Agreement().apply { init(recipientPrivate) }
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(ephemeralPub, 0), sharedSecret, 0)

        // HKDF salt + info MUST match stellar-mls exactly — interop
        // with iOS senders + Android stellar-mls senders rides on
        // these constants.
        val aesKey = Bip39.hkdfSha256(
            ikm = sharedSecret,
            salt = "sep-invitation-v1".toByteArray(Charsets.UTF_8),
            info = "aes-256-gcm".toByteArray(Charsets.UTF_8),
            length = 32,
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, envelope.nonce))
        }
        return try {
            // GCM expects ciphertext + tag concatenated.
            cipher.doFinal(envelope.ciphertext + envelope.authenticationTag)
        } catch (e: AEADBadTagException) {
            throw InvitationDecryptError.DecryptionFailed(e)
        } catch (e: IllegalBlockSizeException) {
            throw InvitationDecryptError.DecryptionFailed(e)
        }
    }

    // ─── Private ─────────────────────────────────────────────────────

    private fun generateNewLocked(): Identity {
        val mnemonic = Bip39.generateMnemonic()
        val entropy = Bip39.entropyFromMnemonic(mnemonic)
            // Unreachable: generateMnemonic always emits a valid mnemonic.
            ?: throw IdentityError.InvalidMnemonic
        val snapshot = snapshotFromEntropy(entropy)
        store.save(snapshot)
        val identity = identityFromSnapshot(snapshot)
        _snapshots.value = identity
        return identity
    }

    private fun snapshotFromEntropy(entropy: ByteArray): StoredSnapshot {
        val mnemonic = Bip39.mnemonicFromEntropy(entropy)
        val seed = Bip39.seedFromMnemonic(mnemonic)
        return StoredSnapshot(
            entropy = entropy,
            nostrSecretKey = Bip39.deriveNostrKey(seed),
            blsSecretKey = Bip39.deriveBlsKey(seed),
        )
    }

    private fun identityFromSnapshot(snapshot: StoredSnapshot): Identity {
        if (snapshot.nostrSecretKey.size != 32) {
            throw IdentityError.StoredSnapshotInvalid(
                "nostrSecretKey: expected 32 bytes, got ${snapshot.nostrSecretKey.size}"
            )
        }
        if (snapshot.blsSecretKey.size != 32) {
            throw IdentityError.StoredSnapshotInvalid(
                "blsSecretKey: expected 32 bytes, got ${snapshot.blsSecretKey.size}"
            )
        }
        val nostrPub = try {
            Common.nostrDerivePublicKey(snapshot.nostrSecretKey)
        } catch (e: OnymException) {
            throw IdentityError.SdkFailure(e.message ?: "nostrDerivePublicKey", e)
        }
        val blsPub = try {
            Common.publicKey(snapshot.blsSecretKey)
        } catch (e: OnymException) {
            throw IdentityError.SdkFailure(e.message ?: "publicKey", e)
        }
        val stellarPub = stellarPublicKey(snapshot.nostrSecretKey)
        val inboxPub = inboxPublicKey(snapshot.nostrSecretKey)
        val phrase = snapshot.entropy?.let(Bip39::mnemonicFromEntropy)
        return Identity(
            nostrPublicKey = nostrPub,
            blsPublicKey = blsPub,
            stellarPublicKey = stellarPub,
            stellarAccountID = StellarStrKey.encodeAccountID(stellarPub),
            inboxPublicKey = inboxPub,
            inboxTag = inboxTag(inboxPub),
            recoveryPhrase = phrase,
        )
    }

    companion object {
        /**
         * `HKDF-SHA256(nostrSecret, salt="chat.onym.ios", info="stellar-ed25519-v1", L=32)`
         * → Ed25519 seed → public key (32 bytes raw).
         *
         * Salt note: BIP39-seed derivations use `chat.onym.bip39`;
         * nostr-secret-derived keys use `chat.onym.ios` (yes, "ios").
         * That's a quirk of the reference impl in
         * `stellar-mls/clients/ios/StellarChat/KeyManager.swift` we
         * preserve for cross-platform interop. Do not change without
         * coordinating with iOS + stellar-mls.
         */
        internal fun stellarPublicKey(nostrSecret: ByteArray): ByteArray {
            val seed = Bip39.hkdfSha256(
                ikm = nostrSecret,
                salt = "chat.onym.ios".toByteArray(Charsets.UTF_8),
                info = "stellar-ed25519-v1".toByteArray(Charsets.UTF_8),
                length = 32,
            )
            val sk = Ed25519PrivateKeyParameters(seed, 0)
            return sk.generatePublicKey().encoded
        }

        /**
         * `HKDF-SHA256(nostrSecret, salt="chat.onym.ios", info="x25519-key-agreement-v1", L=32)`
         * → X25519 seed → public key (32 bytes raw).
         *
         * See `stellarPublicKey` for the salt-quirk note.
         */
        internal fun inboxPublicKey(nostrSecret: ByteArray): ByteArray =
            inboxKeyAgreementPrivateKey(nostrSecret).generatePublicKey().encoded

        /**
         * X25519 private-key parameters for the recipient's inbox
         * keypair. Internal because it returns secret material —
         * intentional only callers are [IdentityRepository] (for
         * decryption) and [inboxPublicKey] (for the public half).
         * Production callers MUST discard the returned object after
         * a single use; never assign to a stored property.
         */
        internal fun inboxKeyAgreementPrivateKey(nostrSecret: ByteArray): X25519PrivateKeyParameters {
            val seed = Bip39.hkdfSha256(
                ikm = nostrSecret,
                salt = "chat.onym.ios".toByteArray(Charsets.UTF_8),
                info = "x25519-key-agreement-v1".toByteArray(Charsets.UTF_8),
                length = 32,
            )
            return X25519PrivateKeyParameters(seed, 0)
        }

        /** Cross-platform interop scheme tag for invitations. Anything
         *  else triggers [InvitationDecryptError.UnsupportedScheme]. */
        private const val INVITATION_SCHEME = "x25519-aes-256-gcm-v1"

        /** Permissive JSON for envelope decoding — extra fields the
         *  sender adds in a future schema version don't break us. */
        private val envelopeJsonFormat = Json {
            ignoreUnknownKeys = true
        }

        /**
         * First 8 bytes of `SHA-256("sep-inbox-v1" || inboxPublicKey)`,
         * hex-encoded (16 chars). MUST match
         * `GroupCrypto.hiddenInboxTag` in stellar-mls and
         * `IdentityRepository.inboxTag` in onym-ios.
         */
        internal fun inboxTag(inboxPublicKey: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("sep-inbox-v1".toByteArray(Charsets.UTF_8))
            md.update(inboxPublicKey)
            val hash = md.digest()
            val sb = StringBuilder(16)
            for (i in 0 until 8) {
                sb.append("%02x".format(hash[i].toInt() and 0xFF))
            }
            return sb.toString()
        }
    }
}
