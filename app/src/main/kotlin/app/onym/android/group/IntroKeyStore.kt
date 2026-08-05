package app.onym.android.group

import app.onym.android.identity.IdentityId
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistence seam for per-invite ephemeral X25519 keypairs.
 *
 * Lifecycle of one entry:
 *
 *  1. Sender taps "Share invite" → app mints a fresh X25519 keypair
 *     via [InviteIntroducer.mint], persists via [save].
 *  2. Joiner taps the deeplink → app sends a request envelope
 *     encrypted to [IntroKeyEntry.introPublicKey] over Nostr.
 *  3. Sender's intro-inbox fan-out (PR-3) receives → calls [find]
 *     with the targeted introPublicKey → uses
 *     [IntroKeyEntry.introPrivateKey] to decrypt the request
 *     payload.
 *  4. On Approve, sender seals the existing
 *     [GroupInvitationPayload] to the joiner's identity inbox key.
 *     The intro key is NOT retired: one link serves many joiners
 *     until [IntroKeyEntry.LIFETIME_MILLIS] expires.
 *
 * Owner-scoping: every entry carries an [IdentityId]. Removing an
 * identity (multi-identity PR-3) cascades a [deleteForOwner] so
 * we don't leak intro privkeys past the identity that minted them.
 *
 * Time-based expiry: entries older than [IntroKeyEntry.LIFETIME_MILLIS]
 * (24h) are treated as revoked at this boundary — [find] returns
 * null for them, [listForOwner] and [entriesFlow] omit them.
 * Implementations lazy-purge expired rows on each read so the
 * underlying blob stays bounded without a background sweeper, and
 * re-emit on [entriesFlow] so [IntroInboxPump] can cancel relayer
 * subscriptions for expired slots.
 */
interface IntroKeyStore {
    /** Hot stream of every active (non-expired) entry across every
     *  owner. Emits the current snapshot on subscribe, then a fresh
     *  value after every [save] / [revoke] / [deleteForOwner], and
     *  whenever a read lazy-purges expired rows.
     *
     *  Drives the inbox fan-out (PR-3): the wiring layer maps
     *  this → list of intro inbox tags → feeds into the request
     *  pump. New entry → new subscription within the next
     *  emission window. Expired entry → subscription gets cancelled
     *  on the next emission. */
    val entriesFlow: StateFlow<List<IntroKeyEntry>>

    /** Persist a freshly-minted intro entry. Idempotent on
     *  [IntroKeyEntry.introPublicKey] — re-mint with the same pub
     *  is a no-op (shouldn't happen in practice; X25519 keypairs
     *  are uniformly random). */
    suspend fun save(entry: IntroKeyEntry)

    /** Look up an entry by its public key. Returns null when the
     *  pubkey is unknown — happens when an old entry was
     *  [revoke]d, has aged past [IntroKeyEntry.LIFETIME_MILLIS], or
     *  when a request envelope targets a pubkey this device never
     *  minted (probably a forged link). */
    suspend fun find(introPublicKey: ByteArray): IntroKeyEntry?

    /** Every entry minted by [ownerIdentityId] that has not aged
     *  past [IntroKeyEntry.LIFETIME_MILLIS]. Sorted newest first
     *  by [IntroKeyEntry.createdAtMillis]. UI's "Active invites"
     *  list reads here. */
    suspend fun listForOwner(ownerIdentityId: IdentityId): List<IntroKeyEntry>

    /** Single-entry deletion. **No production caller** since invite
     *  links went multi-use: Approve and Decline both leave the key
     *  alive so the rest of the link's holders can still join, and
     *  expiry is handled by the lazy purge in the store's read path.
     *
     *  Retained as the seam a future per-link "revoke this invite"
     *  affordance would use, because the store must be able to
     *  destroy one intro *private* key on demand, and to keep parity
     *  with `IntroKeyStore.swift`'s protocol. Exercised by
     *  `InviteIntroducerTest`. No-op if the pubkey isn't present. */
    suspend fun revoke(introPublicKey: ByteArray)

    /** Cascade for the identity-removal flow. Returns the count
     *  of entries deleted so the caller can log the cleanup
     *  size. Hooked into [app.onym.android.identity.IdentityRepository.registerRemovalListener]
     *  via the wiring layer. */
    suspend fun deleteForOwner(ownerIdentityId: IdentityId): Int
}
