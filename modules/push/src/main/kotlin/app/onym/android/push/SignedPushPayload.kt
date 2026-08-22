package app.onym.android.push

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * The byte strings the identity key signs and Play Integrity's
 * `requestHash` binds — the client half of the Android push wire
 * contract. Mirrors `SignedSessionPayload` in :moderation (and the
 * reviewed iOS `SignedPushPayload`), with the moderation-android
 * challenge shape: the challenge is a REQUIRED raw-bytes field, never
 * an optional device token.
 *
 * The Rust backend (onym-push `google/`) pins the identical fixtures;
 * `SignedPushPayloadTest` pins this implementation to them
 * byte-for-byte. The layout: for each field in order — context,
 * challenge (raw 32 bytes), user key, RFC 3339 UTC timestamp, FCM
 * token (UTF-8 bytes of the token string), subscriptions digest
 * (empty for unregister) — a big-endian `u32` length prefix followed
 * by the raw bytes. Length prefixes make the concatenation injective;
 * the context string domain-separates register from unregister (and
 * both from the platform-untagged iOS contexts,
 * `onym-push-register-v1` / `onym-push-unregister-v1`).
 *
 * The integrity token is **not** a field: it does not exist until
 * after the hash is computed. It binds to the payload through the
 * echoed `requestHash`; the identity signature covers the same bytes
 * directly. Two bindings, one payload.
 */
object SignedPushPayload {
    const val REGISTER_CONTEXT: String = "onym-push-android-register-v1"
    const val UNREGISTER_CONTEXT: String = "onym-push-android-unregister-v1"

    /** Signed bytes for `POST /v1/register`. */
    fun register(
        challenge: ByteArray,
        userKey: String,
        timestamp: String,
        fcmToken: String,
        subscriptions: List<PushSubscription>,
    ): ByteArray = bytes(
        REGISTER_CONTEXT,
        challenge,
        userKey,
        timestamp,
        fcmToken,
        subscriptionsDigest(subscriptions),
    )

    /** Signed bytes for `POST /v1/unregister` — no subscription set to
     * bind, so the digest field is empty (zero-length, still
     * length-prefixed). */
    fun unregister(
        challenge: ByteArray,
        userKey: String,
        timestamp: String,
        fcmToken: String,
    ): ByteArray = bytes(UNREGISTER_CONTEXT, challenge, userKey, timestamp, fcmToken, ByteArray(0))

    /**
     * SHA-256 over the subscription set exactly as it crosses the wire
     * — per subscription IN WIRE ORDER: `len32(tag) || tag ||
     * be32(relays.count) || per relay len32(relay) || relay`. No
     * sorting, no normalization: the backend recomputes this from the
     * transmitted `subscriptions` array, and any canonicalization on
     * one side only would unbind the signature from what was sent.
     */
    fun subscriptionsDigest(subscriptions: List<PushSubscription>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val count = ByteBuffer.allocate(4)
        for (subscription in subscriptions) {
            digest.update(lengthPrefixed(subscription.tag.encodeToByteArray()))
            count.clear()
            count.putInt(subscription.relays.size)
            digest.update(count.array())
            for (relay in subscription.relays) {
                digest.update(lengthPrefixed(relay.encodeToByteArray()))
            }
        }
        return digest.digest()
    }

    /**
     * The `requestHash` passed to Play Integrity's `setRequestHash`
     * for this payload: base64url (no padding) of SHA-256 over the
     * signed bytes. The backend recomputes it from the transmitted
     * fields and requires Google's echo to match.
     */
    fun requestHash(payload: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(payload))

    private fun bytes(
        context: String,
        challenge: ByteArray,
        userKey: String,
        timestamp: String,
        fcmToken: String,
        subscriptionsDigest: ByteArray,
    ): ByteArray {
        val fields = listOf(
            context.encodeToByteArray(),
            challenge,
            userKey.encodeToByteArray(),
            timestamp.encodeToByteArray(),
            fcmToken.encodeToByteArray(),
            subscriptionsDigest,
        )
        val buffer = ByteBuffer.allocate(fields.sumOf { 4 + it.size })
        for (field in fields) {
            buffer.putInt(field.size)
            buffer.put(field)
        }
        return buffer.array()
    }

    private fun lengthPrefixed(field: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + field.size).putInt(field.size).put(field).array()
}
