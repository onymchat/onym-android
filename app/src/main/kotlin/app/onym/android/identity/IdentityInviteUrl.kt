package app.onym.android.identity

import java.util.Base64

/**
 * Public, deterministic invite URL for an identity.
 *
 * Encodes the identity's [IdentitySummary.inboxPublicKey] (the
 * 32-byte X25519 ECDH key peers ECDH against to encrypt invitations)
 * as URL-safe base64 in a `https://onym.app/i?k=…` link. Mirrors
 * the existing [app.onym.android.group.IntroCapability.toAppLink]
 * convention (`/join?c=…` for group invites; `/i?k=…` here for
 * identity invites) so a future App Link entry covers both with the
 * same `onym.app` autoVerify host.
 *
 * Identity-invite onboarding (someone scans this and lands in a
 * one-on-one chat with the holder) isn't implemented end-to-end
 * yet — see follow-up. The URL is already correct for that future
 * flow: every byte the joiner needs is here, and the URL is stable
 * across app sessions (no per-scan minting).
 *
 * Uses URL-safe Base64 (no `=` padding, no `+`/`/`) so the result
 * drops straight into the URL query without percent-encoding.
 */
fun IdentitySummary.inviteUrl(): String {
    val payload = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(inboxPublicKey)
    return "$IDENTITY_INVITE_URL_BASE$payload"
}

/** Base of the identity-invite App Link. */
const val IDENTITY_INVITE_URL_BASE: String = "https://onym.app/i?k="
