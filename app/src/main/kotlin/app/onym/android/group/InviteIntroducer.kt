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
 *    minting only when there isn't. Invite links are multi-use and do
 *    not expire: one keypair serves every joiner who redeems the link
 *    until the inviter revokes it, so re-opening the share screen must
 *    return the same link rather than stacking a fresh relay REQ slot
 *    per visit.
 *  - [mint] — always a fresh keypair. `CreateGroupInteractor`'s
 *    create-time offers want one key per invitee so an inbound join
 *    request maps 1:1 back to the person the admin meant to invite.
 *
 * **Why a keypair per link instead of one per identity**: the intro
 * key is the only thing that can decrypt requests aimed at it, so
 * scoping it to one group means a leaked link exposes that group's
 * request channel and nothing else — and revoking it retires that one
 * link without touching any other invite.
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
     * @param label — null for the group's shared link; the invitee's
     *        key fingerprint for a create-time offer, so the invite
     *        list can name the row.
     */
    suspend fun mint(
        ownerIdentityId: IdentityId,
        groupId: ByteArray,
        groupName: String? = null,
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
     * Rotating is an explicit user action — [rotate], behind
     * "Generate new link".
     *
     * Scoped to [ownerIdentityId] on purpose: `IntroInboxPump` only
     * subscribes the *active* identity's intro inboxes, so handing back
     * a key another identity minted would produce a link nobody is
     * listening on. Two identities sharing one group each get their own
     * key; that's correct, not duplication.
     *
     * `label == null` is the shared link. Offer keys are minted for
     * one named invitee and must never be handed out as the group's
     * link — the common create-with-invitees flow lands on the share
     * screen with the last invitee's key as the newest entry.
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
            .firstOrNull { it.groupId.contentEquals(groupId) && it.label == null }
            ?: return@withContext mint(ownerIdentityId, groupId, groupName)

        IntroCapability(
            introPublicKey = live.introPublicKey,
            groupId = groupId,
            groupName = groupName,
        )
    }

    /**
     * Replace the group's shared link: mint a fresh keypair, then
     * revoke every other shared key this identity holds for the group.
     *
     * This is what "Generate new link" does. Since links no longer
     * expire, rotating is the only way a leaked or over-shared link
     * stops working — and rotating rather than plain-revoking leaves
     * the inviter with a link they can still hand out.
     *
     * Mint-then-revoke, not the reverse: if the process dies between
     * the two, the group is left with two working links rather than
     * none. An extra live link is a nuisance the user can rotate
     * again; zero links with a joiner mid-flow is a dead end.
     *
     * Only the shared link rotates. Per-invitee offer keys are revoked
     * individually from the invite list.
     *
     * Revocation is local. There is no way to tell the relay or the
     * people holding the old link — they simply stop being able to
     * reach anyone, because this device stops subscribing to that
     * intro tag and is the only holder of the private key.
     */
    suspend fun rotate(
        ownerIdentityId: IdentityId,
        groupId: ByteArray,
        groupName: String? = null,
    ): IntroCapability = withContext(ioDispatcher) {
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }

        val superseded = store.listForOwner(ownerIdentityId)
            .filter { it.groupId.contentEquals(groupId) && it.label == null }
            .map { it.introPublicKey }

        val fresh = mint(ownerIdentityId, groupId, groupName)

        for (pub in superseded) {
            if (!pub.contentEquals(fresh.introPublicKey)) store.revoke(pub)
        }
        fresh
    }

    /**
     * Kill one specific link. Used by the per-row revoke on the invite
     * list — chiefly for the create-time offer keys, which have no
     * other way to be retired now that nothing expires.
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
        store.listForOwner(ownerIdentityId).filter { it.groupId.contentEquals(groupId) }
    }
}
