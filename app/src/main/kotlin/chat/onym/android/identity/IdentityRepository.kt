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
import java.security.SecureRandom
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
) : InvitationEnvelopeDecrypter, InvitationEnvelopeSealer, ActiveIdentityProvider {
    private val mutex = Mutex()
    private val _snapshots = MutableStateFlow<Identity?>(null)
    private val _identities = MutableStateFlow<List<IdentitySummary>>(emptyList())
    private val _currentIdentityId = MutableStateFlow<IdentityId?>(null)
    /** Listeners notified when an identity is removed; they get the
     *  removed id BEFORE the storage wipe completes (so they can
     *  cascade-delete owned data). Multi-slot — `GroupRepository`
     *  registers a chat-wipe listener; the deeplink-invite layer
     *  registers an intro-key-wipe listener. Invoked in registration
     *  order; later registrants run after earlier ones.
     *
     *  Snapshot-replace on register so iterators inside `remove()`
     *  don't crash on concurrent mutations. */
    @Volatile
    private var removalListeners: List<suspend (IdentityId) -> Unit> = emptyList()

    /** Hot stream of the **currently-selected** identity's snapshot.
     *  New collectors get the current value immediately, then one new
     *  value per selection change OR every successful mutation. */
    val snapshots: StateFlow<Identity?> = _snapshots.asStateFlow()

    /** Hot stream of every identity on the device (summaries only —
     *  no secret material). Recomposes after add / remove / select /
     *  rename. UI's identity-picker subscribes here. */
    val identities: StateFlow<List<IdentitySummary>> = _identities.asStateFlow()

    /** Hot stream of the currently-selected [IdentityId], or `null` if
     *  none is selected (cold install OR last identity removed).
     *  Group / message repositories filter by this. */
    override val currentIdentityId: StateFlow<IdentityId?> = _currentIdentityId.asStateFlow()

    /** Synchronous read of the current identity. Doesn't trigger a
     *  store load — call [bootstrap] for that. */
    fun currentIdentity(): Identity? = _snapshots.value

    /** Register a listener invoked just before a [remove] wipes its
     *  storage. Multi-slot — appends. Passing `null` clears every
     *  registered listener (used by tests for setup hygiene; never
     *  by production callers).
     *
     *  Listeners are invoked in registration order. A listener that
     *  throws aborts the chain — subsequent listeners + the wipe
     *  itself don't run, so wire ordering matters: register
     *  listeners that MUST run before the wipe (e.g.
     *  GroupRepository's chat-delete) FIRST. */
    override fun registerRemovalListener(listener: (suspend (IdentityId) -> Unit)?) {
        removalListeners = if (listener == null) emptyList() else removalListeners + listener
    }

    /** Back-compat alias. */
    @Deprecated("Use registerRemovalListener", ReplaceWith("registerRemovalListener(listener)"))
    fun setRemovalListener(listener: (suspend (IdentityId) -> Unit)?) {
        registerRemovalListener(listener)
    }

    /**
     * One-shot accessor for the device's 32-byte BLS Fr scalar. Used
     * by the chain layer (`OnymGroupProofGenerator`) to call
     * `Tyranny.proveCreate`. Loads from [store] on every call (no
     * in-memory cache); callers MUST NOT retain the returned bytes
     * beyond the immediate proof-generation hop.
     *
     * @throws IdentityError.IdentityNotLoaded if [bootstrap] /
     *         [restore] hasn't been called or [wipe] was called.
     *
     * Mirrors `IdentityRepository.blsSecretKey` from onym-ios PR #26.
     */
    suspend fun blsSecretKey(): ByteArray = withContext(ioDispatcher) {
        val id = store.loadCurrent() ?: throw IdentityError.IdentityNotLoaded
        store.load(id)?.blsSecretKey ?: throw IdentityError.IdentityNotLoaded
    }

    /**
     * Load the persisted identity (the currently-selected one), or
     * generate a fresh BIP39-backed identity if no snapshot exists.
     * Idempotent: a second call after success returns the same
     * identity without re-reading the store.
     *
     * If multiple identities exist on disk, [store]'s `current`
     * selection (set on every save) determines which one loads.
     * Multi-identity selection / add APIs land in the next PR.
     */
    suspend fun bootstrap(): Identity = mutex.withLock {
        _snapshots.value?.let { return@withLock it }
        withContext(ioDispatcher) {
            val currentId = store.loadCurrent() ?: store.listIds().firstOrNull()
            val stored = currentId?.let { store.load(it) }
            if (stored != null && currentId != null) {
                if (store.loadCurrent() != currentId) store.saveCurrent(currentId)
                val identity = identityFromSnapshot(stored)
                _snapshots.value = identity
                _currentIdentityId.value = currentId
                refreshIdentitiesList()
                identity
            } else {
                generateNewLocked(name = "")
            }
        }
    }

    /**
     * Generate a fresh BIP39-backed identity AND select it. **Replaces
     * the active slot** — use [add] to add a new identity alongside
     * existing ones without taking over the selection.
     *
     * Kept for back-compat with the recovery-flow's "wipe + regenerate"
     * path; once Settings → Identities (PR-5) gives users a non-
     * destructive add, this can be the recovery flow's only consumer.
     */
    suspend fun generateNew(): Identity = mutex.withLock {
        withContext(ioDispatcher) {
            generateNewLocked(name = "")
        }
    }

    /**
     * Restore an identity from a 12- or 24-word BIP39 mnemonic.
     * **Replaces the active slot** — use [add] to restore a new
     * identity alongside existing ones.
     *
     * @throws IdentityError.InvalidMnemonic if the mnemonic is
     *         malformed or has a bad checksum.
     */
    suspend fun restore(mnemonic: String): Identity = mutex.withLock {
        withContext(ioDispatcher) {
            val entropy = Bip39.entropyFromMnemonic(mnemonic)
                ?: throw IdentityError.InvalidMnemonic
            val previousId = store.loadCurrent()
            val newId = IdentityId.new()
            val snapshot = snapshotFromEntropy(entropy, name = "")
            if (previousId != null) {
                removalListeners.forEach { it.invoke(previousId) }
                store.wipe(previousId)
            }
            store.save(newId, snapshot)
            store.saveCurrent(newId)
            val identity = identityFromSnapshot(snapshot)
            _snapshots.value = identity
            _currentIdentityId.value = newId
            refreshIdentitiesList()
            identity
        }
    }

    /** Delete the currently-selected identity. Subscribers receive a
     *  `null` snapshot if no identity remains, or the new active
     *  identity's snapshot otherwise. Cascades through
     *  [removalListener] before wiping (PR-3 hooks the GroupRepository
     *  here). */
    suspend fun wipe() = mutex.withLock {
        withContext(ioDispatcher) {
            store.loadCurrent()?.let { id ->
                removalListeners.forEach { it.invoke(id) }
                store.wipe(id)
            }
            _snapshots.value = null
            _currentIdentityId.value = null
            refreshIdentitiesList()
        }
    }

    // ─── Multi-identity API ──────────────────────────────────────

    /**
     * Add a new identity alongside any existing ones. Generates a
     * fresh BIP39 mnemonic when [restoreMnemonic] is null; restores
     * from the provided mnemonic otherwise. The new identity becomes
     * the active selection.
     *
     * @throws IdentityError.InvalidMnemonic if [restoreMnemonic] is
     *         present but malformed.
     */
    suspend fun add(name: String, restoreMnemonic: String? = null): IdentityId = mutex.withLock {
        withContext(ioDispatcher) {
            val entropy = if (restoreMnemonic != null) {
                Bip39.entropyFromMnemonic(restoreMnemonic) ?: throw IdentityError.InvalidMnemonic
            } else {
                val mnemonic = Bip39.generateMnemonic()
                Bip39.entropyFromMnemonic(mnemonic) ?: throw IdentityError.InvalidMnemonic
            }
            val resolvedName = resolveDisplayName(name)
            val newId = IdentityId.new()
            val snapshot = snapshotFromEntropy(entropy, name = resolvedName)
            store.save(newId, snapshot)
            store.saveCurrent(newId)
            _snapshots.value = identityFromSnapshot(snapshot)
            _currentIdentityId.value = newId
            refreshIdentitiesList()
            newId
        }
    }

    /** Activate [id]. No-op if [id] is already active. Throws
     *  [IdentityError.IdentityNotLoaded] if [id] doesn't exist on
     *  this device. */
    suspend fun select(id: IdentityId) = mutex.withLock {
        withContext(ioDispatcher) {
            if (store.loadCurrent() == id) return@withContext
            val snapshot = store.load(id) ?: throw IdentityError.IdentityNotLoaded
            store.saveCurrent(id)
            _snapshots.value = identityFromSnapshot(snapshot)
            _currentIdentityId.value = id
        }
    }

    /** Remove [id] from the device. If [id] is the active identity,
     *  pick the next one in [identities] order, or `null` if none
     *  remain. Cascades through [removalListener] before wiping
     *  (PR-3 hooks GroupRepository here to delete owned chats). */
    suspend fun remove(id: IdentityId) = mutex.withLock {
        withContext(ioDispatcher) {
            if (id !in store.listIds()) return@withContext
            removalListeners.forEach { it.invoke(id) }
            val wasActive = store.loadCurrent() == id
            store.wipe(id)
            if (wasActive) {
                val next = store.listIds().firstOrNull()
                if (next != null) {
                    store.saveCurrent(next)
                    val snapshot = store.load(next)!!
                    _snapshots.value = identityFromSnapshot(snapshot)
                    _currentIdentityId.value = next
                } else {
                    _snapshots.value = null
                    _currentIdentityId.value = null
                }
            }
            refreshIdentitiesList()
        }
    }

    /**
     * Rename [id] to [newName]. Trims, then a no-op when the trimmed
     * value is empty (matches the iOS prototype's `name || i.name`
     * "blank input keeps old name" behaviour) or unchanged from the
     * current persisted name.
     *
     * Callable on any identity, active or inactive. Refreshes the
     * [identities] summary stream so listeners see the new name; the
     * active-[snapshots] stream isn't touched because [Identity]
     * doesn't carry the display name (only [IdentitySummary] does).
     *
     * @throws IdentityError.IdentityNotLoaded if [id] doesn't exist
     *         on this device.
     */
    suspend fun rename(id: IdentityId, newName: String) = mutex.withLock {
        withContext(ioDispatcher) {
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) return@withContext
            val snapshot = store.load(id) ?: throw IdentityError.IdentityNotLoaded
            if (snapshot.name == trimmed) return@withContext
            store.save(id, snapshot.copy(name = trimmed))
            refreshIdentitiesList()
        }
    }

    /** Recompute [_identities] from disk. Must be called from inside
     *  [mutex] (caller already holds it). */
    private fun refreshIdentitiesList() {
        _identities.value = store.listSnapshots().map { (id, snap) ->
            IdentitySummary(
                id = id,
                name = snap.name.ifBlank { "Identity ${store.listIds().indexOf(id) + 1}" },
                blsPublicKey = try {
                    Common.publicKey(snap.blsSecretKey)
                } catch (e: OnymException) {
                    throw IdentityError.SdkFailure(e.message ?: "publicKey", e)
                },
                inboxPublicKey = inboxPublicKey(snap.nostrSecretKey),
            )
        }
    }

    /** Auto-fill blank display names with `Identity N`. */
    private fun resolveDisplayName(requested: String): String {
        val trimmed = requested.trim()
        if (trimmed.isNotEmpty()) return trimmed
        return "Identity ${store.listIds().size + 1}"
    }

    // ─── InvitationEnvelopeDecrypter ────────────────────────────────

    /**
     * Open an X25519+AES-GCM-sealed invitation envelope addressed to
     * the [asIdentity] inbox keypair. The X25519 private key is
     * recomputed from the persisted nostr secret on every call and
     * never assigned to a stored property — so the only persistent
     * footprint of this method on the recipient's machine is the
     * already-encrypted SharedPreferences blob.
     *
     * Loading by [asIdentity] (rather than the currently-selected
     * identity) is what makes the multi-identity fan-out actually
     * usable: an envelope addressed to identity B that arrived
     * while the user was on identity A still decrypts under B's
     * key when the repository surfaces it. The repository tags
     * every persisted record with its [asIdentity] at receive
     * time; this method consumes that tag.
     *
     * Throws [InvitationDecryptError]; never raw `javax.crypto` /
     * `kotlinx.serialization` exceptions. See [InvitationDecryptError]
     * for the failure-mode taxonomy.
     */
    override suspend fun decryptInvitation(
        envelopeBytes: ByteArray,
        asIdentity: IdentityId,
    ): ByteArray = withContext(ioDispatcher) {
        // Snapshot read happens off the mutex — store I/O is a
        // single atomic SharedPreferences read; concurrent `wipe()`
        // either observes the pre-wipe blob or post-wipe null,
        // both internally consistent.
        val snapshot = store.load(asIdentity) ?: throw InvitationDecryptError.IdentityNotLoaded
        decryptInvitationLocked(snapshot, envelopeBytes)
    }

    override suspend fun decryptInvitationWithSender(
        envelopeBytes: ByteArray,
        asIdentity: IdentityId,
    ): DecryptedEnvelope = withContext(ioDispatcher) {
        val snapshot = store.load(asIdentity) ?: throw InvitationDecryptError.IdentityNotLoaded
        decryptSealedEnvelopeWithKeyAndSender(
            envelopeBytes = envelopeBytes,
            recipientX25519PrivateKey = inboxKeyAgreementPrivateKey(snapshot.nostrSecretKey).encoded,
        )
    }

    private fun decryptInvitationLocked(
        snapshot: StoredSnapshot,
        envelopeBytes: ByteArray,
    ): ByteArray = decryptSealedEnvelopeWithKey(
        envelopeBytes = envelopeBytes,
        recipientX25519PrivateKey = inboxKeyAgreementPrivateKey(snapshot.nostrSecretKey).encoded,
    )

    // ─── InvitationEnvelopeSealer ───────────────────────────────────

    /**
     * Sender-side mirror of [decryptInvitation]. Generates a fresh
     * per-envelope X25519 keypair, derives the AES-GCM key from the
     * ECDH shared secret with [recipientInboxPublicKey], encrypts the
     * payload, signs the ephemeral pubkey with this device's Ed25519
     * identity key (M-5), and returns the JSON-serialised
     * [SealedEnvelope]. Secret material never escapes this method —
     * only the resulting bytes do.
     *
     * The Ed25519 signing key is recomputed from the persisted nostr
     * secret on every call and never assigned to a stored property,
     * matching [decryptInvitation]'s posture for the recipient X25519
     * private key.
     *
     * Mirrors `IdentityRepository.sealInvitation` from onym-ios PR #24.
     */
    override suspend fun sealInvitation(
        payload: ByteArray,
        recipientInboxPublicKey: ByteArray,
    ): ByteArray = withContext(ioDispatcher) {
        if (recipientInboxPublicKey.size != 32) {
            throw InvitationSealError.InvalidRecipientPublicKey(
                "expected 32 bytes, got ${recipientInboxPublicKey.size}"
            )
        }
        val recipientPub = try {
            X25519PublicKeyParameters(recipientInboxPublicKey, 0)
        } catch (e: IllegalArgumentException) {
            throw InvitationSealError.InvalidRecipientPublicKey(e.message ?: "parse failed")
        }
        val id = store.loadCurrent() ?: throw InvitationSealError.IdentityNotLoaded
        val snapshot = store.load(id) ?: throw InvitationSealError.IdentityNotLoaded
        sealInvitationLocked(snapshot, payload, recipientPub)
    }

    private fun sealInvitationLocked(
        snapshot: StoredSnapshot,
        payload: ByteArray,
        recipientPub: X25519PublicKeyParameters,
    ): ByteArray {
        // Fresh per-envelope X25519 keypair. SecureRandom seed is the
        // OS RNG; bouncycastle generates a clamped private scalar
        // internally.
        val ephemeralPrivate = X25519PrivateKeyParameters(SecureRandom())
        val ephemeralPub = ephemeralPrivate.generatePublicKey().encoded

        // ECDH against the recipient's X25519 inbox pubkey.
        val agreement = X25519Agreement().apply { init(ephemeralPrivate) }
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(recipientPub, sharedSecret, 0)

        // HKDF salt + info MUST match decryptInvitation exactly —
        // interop with iOS senders/receivers + stellar-mls senders
        // rides on these constants.
        val aesKey = Bip39.hkdfSha256(
            ikm = sharedSecret,
            salt = "sep-invitation-v1".toByteArray(Charsets.UTF_8),
            info = "aes-256-gcm".toByteArray(Charsets.UTF_8),
            length = 32,
        )

        // Random 12-byte AES-GCM nonce. Each call mints a fresh one;
        // a duplicate would break GCM security against the same key.
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val (ciphertext, authTag) = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
            }
            val combined = cipher.doFinal(payload)
            // GCM emits ciphertext || tag as one buffer; split for the
            // wire shape, which keeps them in separate base64 fields.
            val tagLen = 16
            combined.copyOfRange(0, combined.size - tagLen) to
                combined.copyOfRange(combined.size - tagLen, combined.size)
        } catch (e: Exception) {
            throw InvitationSealError.EncryptionFailed(e)
        }

        // M-5: sign the ephemeral pubkey with the sender's long-term
        // Ed25519 identity key. Recipient verifies in
        // decryptInvitation against `senderEd25519PublicKey`.
        val signingKey = stellarSigningPrivateKey(snapshot.nostrSecretKey)
        val senderPubkey = signingKey.generatePublicKey().encoded
        val ephSignature = try {
            Ed25519Signer().apply {
                init(true, signingKey)
                update(ephemeralPub, 0, ephemeralPub.size)
            }.generateSignature()
        } catch (e: Exception) {
            throw InvitationSealError.SigningFailed(e)
        }

        val envelope = SealedEnvelope(
            version = 1,
            scheme = "x25519-aes-256-gcm-v1",
            ephemeralPublicKey = ephemeralPub,
            ephemeralKeySignature = ephSignature,
            senderEd25519PublicKey = senderPubkey,
            nonce = nonce,
            ciphertext = ciphertext,
            authenticationTag = authTag,
        )
        return try {
            envelopeJsonFormat.encodeToString(SealedEnvelope.serializer(), envelope)
                .toByteArray(Charsets.UTF_8)
        } catch (e: SerializationException) {
            throw InvitationSealError.EncodingFailed(e)
        }
    }

    // ─── Private ─────────────────────────────────────────────────────

    private suspend fun generateNewLocked(name: String): Identity {
        val mnemonic = Bip39.generateMnemonic()
        val entropy = Bip39.entropyFromMnemonic(mnemonic)
            // Unreachable: generateMnemonic always emits a valid mnemonic.
            ?: throw IdentityError.InvalidMnemonic
        val previousId = store.loadCurrent()
        val newId = IdentityId.new()
        val resolved = if (name.isBlank() && previousId == null && store.listIds().isEmpty()) {
            // Bootstrap-from-zero: skip the auto "Identity 1" label so
            // the first install reads as a single nameless identity in
            // the UI (the user can rename later).
            ""
        } else {
            resolveDisplayName(name)
        }
        val snapshot = snapshotFromEntropy(entropy, name = resolved)
        if (previousId != null) {
            removalListeners.forEach { it.invoke(previousId) }
            store.wipe(previousId)
        }
        store.save(newId, snapshot)
        store.saveCurrent(newId)
        val identity = identityFromSnapshot(snapshot)
        _snapshots.value = identity
        _currentIdentityId.value = newId
        refreshIdentitiesList()
        return identity
    }

    private fun snapshotFromEntropy(entropy: ByteArray, name: String): StoredSnapshot {
        val mnemonic = Bip39.mnemonicFromEntropy(entropy)
        val seed = Bip39.seedFromMnemonic(mnemonic)
        return StoredSnapshot(
            entropy = entropy,
            nostrSecretKey = Bip39.deriveNostrKey(seed),
            blsSecretKey = Bip39.deriveBlsKey(seed),
            name = name,
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
         * Open a [SealedEnvelope] with an arbitrary X25519 private
         * key. The standard [decryptInvitation] path derives the key
         * from the active identity's nostr secret; the deeplink-
         * invite flow (PR-3+) uses a per-invite intro privkey
         * persisted in [chat.onym.android.group.IntroKeyStore]. Both
         * paths share this crypto core so the wire format stays in
         * lockstep.
         *
         * Throws [InvitationDecryptError]; never raw `javax.crypto`
         * / `kotlinx.serialization` exceptions.
         */
        /**
         * Same as [decryptSealedEnvelopeWithKey] but returns both
         * the plaintext and the envelope's sender Ed25519 pubkey
         * (when present). Used by the inbox dispatcher (PR 80) so
         * the admin-Ed25519 trust check on
         * `MemberAnnouncementPayload` can authenticate the sender
         * without re-parsing the envelope.
         */
        fun decryptSealedEnvelopeWithKeyAndSender(
            envelopeBytes: ByteArray,
            recipientX25519PrivateKey: ByteArray,
        ): DecryptedEnvelope {
            require(recipientX25519PrivateKey.size == 32) {
                "recipient X25519 priv: expected 32 bytes, got ${recipientX25519PrivateKey.size}"
            }
            val envelope = parseEnvelope(envelopeBytes)
            val plaintext = openEnvelope(envelope, recipientX25519PrivateKey)
            return DecryptedEnvelope(
                plaintext = plaintext,
                senderEd25519PublicKey = envelope.senderEd25519PublicKey,
            )
        }

        fun decryptSealedEnvelopeWithKey(
            envelopeBytes: ByteArray,
            recipientX25519PrivateKey: ByteArray,
        ): ByteArray {
            require(recipientX25519PrivateKey.size == 32) {
                "recipient X25519 priv: expected 32 bytes, got ${recipientX25519PrivateKey.size}"
            }
            val envelope = parseEnvelope(envelopeBytes)
            return openEnvelope(envelope, recipientX25519PrivateKey)
        }

        private fun parseEnvelope(envelopeBytes: ByteArray): SealedEnvelope {
            val envelope = try {
                envelopeJsonFormat.decodeFromString<SealedEnvelope>(
                    envelopeBytes.toString(Charsets.UTF_8)
                )
            } catch (e: SerializationException) {
                throw InvitationDecryptError.MalformedEnvelope("invalid JSON: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw InvitationDecryptError.MalformedEnvelope("invalid base64: ${e.message}", e)
            }
            if (envelope.scheme != INVITATION_SCHEME) {
                throw InvitationDecryptError.UnsupportedScheme(envelope.scheme)
            }
            val ephemeralPub = envelope.ephemeralPublicKey
                ?: throw InvitationDecryptError.MissingEphemeralKey

            // M-5: verify Ed25519 signature on the ephemeral pubkey
            // if the envelope provides one. Prevents MITM substitution.
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
            return envelope
        }

        private fun openEnvelope(
            envelope: SealedEnvelope,
            recipientX25519PrivateKey: ByteArray,
        ): ByteArray {
            val ephemeralPub = envelope.ephemeralPublicKey
                ?: throw InvitationDecryptError.MissingEphemeralKey

            // ECDH against the recipient's X25519 private.
            val recipientPriv = X25519PrivateKeyParameters(recipientX25519PrivateKey, 0)
            val agreement = X25519Agreement().apply { init(recipientPriv) }
            val sharedSecret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(
                X25519PublicKeyParameters(ephemeralPub, 0),
                sharedSecret,
                0,
            )

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
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(aesKey, "AES"),
                    GCMParameterSpec(128, envelope.nonce),
                )
            }
            return try {
                cipher.doFinal(envelope.ciphertext + envelope.authenticationTag)
            } catch (e: AEADBadTagException) {
                throw InvitationDecryptError.DecryptionFailed(e)
            } catch (e: IllegalBlockSizeException) {
                throw InvitationDecryptError.DecryptionFailed(e)
            }
        }

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
        internal fun stellarPublicKey(nostrSecret: ByteArray): ByteArray =
            stellarSigningPrivateKey(nostrSecret).generatePublicKey().encoded

        /**
         * Sibling of [stellarPublicKey] that returns the Ed25519
         * *private* key. Used by [sealInvitation] for the M-5
         * attestation signature on per-envelope ephemeral pubkeys.
         * Internal because it returns secret material; production
         * callers MUST discard the returned object after a single use,
         * never assign to a stored property.
         */
        internal fun stellarSigningPrivateKey(nostrSecret: ByteArray): Ed25519PrivateKeyParameters {
            val seed = Bip39.hkdfSha256(
                ikm = nostrSecret,
                salt = "chat.onym.ios".toByteArray(Charsets.UTF_8),
                info = "stellar-ed25519-v1".toByteArray(Charsets.UTF_8),
                length = 32,
            )
            return Ed25519PrivateKeyParameters(seed, 0)
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
        fun inboxTag(inboxPublicKey: ByteArray): String {
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
