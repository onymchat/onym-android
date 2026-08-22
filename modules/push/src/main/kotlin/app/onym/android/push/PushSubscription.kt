package app.onym.android.push

import kotlinx.serialization.Serializable

/**
 * One inbox the push backend should watch: the identity's hidden inbox
 * tag (16 lowercase hex chars — `IdentityRepository.inboxTag`) plus
 * the relay URLs the device is itself configured to read it from. The
 * backend watches exactly these relays for exactly these tags; it
 * learns nothing the relays don't already see.
 *
 * The device never SENDS an entry with an empty relay list — the
 * server refuses them — but the signed digest still binds one if
 * present (see [SignedPushPayload.subscriptionsDigest]).
 */
@Serializable
data class PushSubscription(
    val tag: String,
    val relays: List<String>,
)
