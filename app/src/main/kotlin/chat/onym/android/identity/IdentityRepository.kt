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
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.MessageDigest

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
) {
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
        internal fun inboxPublicKey(nostrSecret: ByteArray): ByteArray {
            val seed = Bip39.hkdfSha256(
                ikm = nostrSecret,
                salt = "chat.onym.ios".toByteArray(Charsets.UTF_8),
                info = "x25519-key-agreement-v1".toByteArray(Charsets.UTF_8),
                length = 32,
            )
            val sk = X25519PrivateKeyParameters(seed, 0)
            return sk.generatePublicKey().encoded
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
