package chat.onym.android.group

import chat.onym.android.identity.IdentityId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
 * **Why one keypair per invite instead of one per identity**: per-
 * link revocation. The inviter can stop listening on a specific
 * intro tag → that link goes silent without affecting other
 * outstanding invites. A leaked link only burns its own slot.
 */
class InviteIntroducer(
    private val store: IntroKeyStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

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
     */
    suspend fun mint(
        ownerIdentityId: IdentityId,
        groupId: ByteArray,
        groupName: String? = null,
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
            )
        )

        IntroCapability(
            introPublicKey = publicKey,
            groupId = groupId,
            groupName = groupName,
        )
    }
}
