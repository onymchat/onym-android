package app.onym.android.group

import app.onym.android.identity.IdentityId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.SecureRandom

/**
 * Mints fresh per-invite X25519 keypairs and persists them via
 * [IntroKeyStore]. Returns an [IntroCapability] (the public-facing
 * deeplink payload) — the caller drops it into a deeplink URL and
 * shares.
 *
 * **Threading**: keypair generation + storage I/O run on
 * [ioDispatcher] (`Dispatchers.IO` by default). Cheap enough for
 * the foreground click handler to await directly — X25519 keygen
 * is microseconds; the EncryptedSharedPreferences `commit()` is
 * the dominant cost.
 *
 * [currentOrMint] reuses the group's shared link; [mint] is always
 * fresh, for create-time offers needing one key per invitee.
 */
class InviteIntroducer(
    private val store: IntroKeyStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Serializes the read-decide-write on a group's shared link. Both
     *  paths read the store and then write it, with suspends between. */
    private val linkMutexes = mutableMapOf<String, Mutex>()
    private val linkMutexesGuard = Mutex()

    private suspend fun linkMutex(ownerIdentityId: IdentityId, groupId: ByteArray): Mutex {
        val key = ownerIdentityId.value + ":" +
            groupId.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return linkMutexesGuard.withLock { linkMutexes.getOrPut(key) { Mutex() } }
    }

    /**
     * Mint a fresh intro keypair, persist it, and return the
     * [IntroCapability] the caller will pack into a deeplink URL.
     *
     * @param ownerIdentityId — the identity that's inviting. Used
     *        for cascade-delete when the identity is removed.
     * @param groupId — the on-chain `group_id` the invite is for.
     * @param groupName — optional plaintext name surfaced in the
     *        deeplink for the joiner's preview. Pass `null` for
     *        groups whose name is sensitive (deeplink transits
     *        cleartext channels).
     * @param label — null for the shared link, else the invitee's
     *        fingerprint so the invite list can name the row.
     */
    suspend fun mint(
        ownerIdentityId: IdentityId,
        groupId: ByteArray,
        groupName: String? = null,
        /** The group's rules *as they are now*. A link is a pointer to
         *  the group, so a reused key handed out with rules from
         *  whenever it was minted would have joiners signing text the
         *  founder had already replaced. */
        rules: String? = null,
        label: String? = null,
    ): IntroCapability = withContext(ioDispatcher) {
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }

        // BC's X25519PrivateKeyParameters constructor seeded from
        // SecureRandom does the standard scalar clamping internally.
        // generatePublicKey() runs the Curve25519 base-point mul.
        val privateKey = X25519PrivateKeyParameters(random)
        val publicKey = privateKey.generatePublicKey().encoded
        val privateKeyBytes = privateKey.encoded

        store.save(
            IntroKeyEntry(
                introPublicKey = publicKey,
                introPrivateKey = privateKeyBytes,
                ownerIdentityId = ownerIdentityId,
                groupId = groupId,
                createdAtMillis = clock(),
                label = label,
            )
        )

        IntroCapability(
            introPublicKey = publicKey,
            groupId = groupId,
            groupName = groupName,
            rules = GroupRules.normalized(rules),
        )
    }

    /**
     * The group's shared link (`label == null`), minting only when it
     * has none. Owner-scoped: the pump only hears the active identity.
     */
    suspend fun currentOrMint(
        ownerIdentityId: IdentityId,
        groupId: ByteArray,
        groupName: String? = null,
        /** The group's rules *as they are now*. A link is a pointer to
         *  the group, so a reused key handed out with rules from
         *  whenever it was minted would have joiners signing text the
         *  founder had already replaced. */
        rules: String? = null,
    ): IntroCapability = withContext(ioDispatcher) {
        // Ahead of the store read so the throw is the same whichever
        // branch would have run.
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }

        linkMutex(ownerIdentityId, groupId).withLock {
            val live = store.listForOwner(ownerIdentityId)
                // `isLegacy` excludes rows written before labels
                // existed — those are also `label == null`, and the
                // newest of them on a create-with-invitees group IS
                // the last invitee's private offer key.
                .firstOrNull {
                    it.groupId.contentEquals(groupId) && it.label == null && !it.isLegacy
                }
                ?: return@withContext mint(ownerIdentityId, groupId, groupName, rules)

            IntroCapability(
                introPublicKey = live.introPublicKey,
                groupId = groupId,
                groupName = groupName,
                rules = GroupRules.normalized(rules),
            )
        }
    }

    /**
     * "Generate new link": mint fresh, then revoke the old shared key —
     * that order, so a crash leaves two working links rather than none.
     */
    suspend fun rotate(
        ownerIdentityId: IdentityId,
        groupId: ByteArray,
        groupName: String? = null,
        /** The group's rules *as they are now*. A link is a pointer to
         *  the group, so a reused key handed out with rules from
         *  whenever it was minted would have joiners signing text the
         *  founder had already replaced. */
        rules: String? = null,
    ): IntroCapability = withContext(ioDispatcher) {
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }

        linkMutex(ownerIdentityId, groupId).withLock {
            val superseded = store.listForOwner(ownerIdentityId)
                // Legacy rows excluded for the same reason: rotating
                // the shared link must not silently kill every
                // outstanding pre-upgrade invite.
                .filter {
                    it.groupId.contentEquals(groupId) && it.label == null && !it.isLegacy
                }
                .map { it.introPublicKey }

            val fresh = mint(ownerIdentityId, groupId, groupName, rules)

            for (pub in superseded) {
                if (!pub.contentEquals(fresh.introPublicKey)) store.revoke(pub)
            }
            fresh
        }
    }

    /**
     * Kill one link. Backs the per-row revoke, chiefly for offer keys
     * which have no other way to be retired.
     */
    suspend fun revoke(introPublicKey: ByteArray) = withContext(ioDispatcher) {
        store.revoke(introPublicKey)
    }

    /**
     * Every live invite this identity holds for the group,
     * newest-first. Backs the invite list on the share screen.
     */
    suspend fun liveInvites(
        ownerIdentityId: IdentityId,
        groupId: ByteArray,
    ): List<IntroKeyEntry> = withContext(ioDispatcher) {
        // Sorted here rather than relying on the store: newest-first is
        // the EncryptedPrefs implementation's behaviour, not an
        // [IntroKeyStore] guarantee, and the in-memory double doesn't
        // sort.
        store.listForOwner(ownerIdentityId)
            .filter { it.groupId.contentEquals(groupId) }
            .sortedByDescending { it.createdAtMillis }
    }
}
