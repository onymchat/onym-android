package app.onym.android.group

import app.onym.android.identity.IdentityId
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
 * **Two entry points, deliberately**:
 *  - [currentOrMint] — the shared-link path. Hands back the
 *    identity's existing live key for the group when there is one,
 *    minting only when there isn't. Invite links are multi-use: one
 *    keypair serves every joiner who redeems the link inside
 *    [IntroKeyEntry.LIFETIME_MILLIS], so re-opening the share screen
 *    must return the same link rather than stacking a fresh relay REQ
 *    slot per visit.
 *  - [mint] — always a fresh keypair. `CreateGroupInteractor`'s
 *    create-time offers want one key per invitee so an inbound join
 *    request maps 1:1 back to the person the admin meant to invite.
 *
 * **Why a keypair per link instead of one per identity**: the intro
 * key is the only thing that can decrypt requests aimed at it, so
 * scoping it to one group means a leaked link exposes that group's
 * request channel and nothing else — and expiry retires it without
 * touching any other invite.
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

    /**
     * The group's current invite capability for [ownerIdentityId],
     * minting one only when no live key exists.
     *
     * Invite links are multi-use, so the share screen is a view onto
     * the group's live link rather than a link factory: re-entering it
     * must surface the same link instead of leaving a trail of live
     * intro slots, each costing relay REQ filters until it ages out.
     *
     * Reuse deliberately does not re-stamp
     * [IntroKeyEntry.createdAtMillis]. The 24h cap is absolute per
     * link, not a sliding window, so a link that has been circulating
     * for 23 hours still goes dark in one.
     *
     * Scoped to [ownerIdentityId] on purpose: `IntroInboxPump` only
     * subscribes the *active* identity's intro inboxes, so handing back
     * a key another identity minted would produce a link nobody is
     * listening on. Two identities sharing one group each get their own
     * key; that's correct, not duplication.
     *
     * "Live" means present in [IntroKeyStore.listForOwner], which
     * already purges everything past the lifetime and sorts
     * newest-first, so the head of the group's slice is the freshest
     * reusable slot.
     *
     * Mirrors `InviteIntroducer.currentOrMint` from onym-ios.
     */
    suspend fun currentOrMint(
        ownerIdentityId: IdentityId,
        groupId: ByteArray,
        groupName: String? = null,
    ): IntroCapability = withContext(ioDispatcher) {
        // Checked ahead of the store read so a malformed id can't cause
        // a pointless listForOwner pass, and so the throw is the same
        // whichever branch would have run.
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }

        val live = store.listForOwner(ownerIdentityId)
            .firstOrNull { it.groupId.contentEquals(groupId) }
            ?: return@withContext mint(ownerIdentityId, groupId, groupName)

        IntroCapability(
            introPublicKey = live.introPublicKey,
            groupId = groupId,
            groupName = groupName,
        )
    }
}
