package app.onym.android.moderation

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * The byte strings the identity key signs and Play Integrity's
 * `requestHash` binds — the client half of the Android wire contract.
 *
 * The backend's published fixtures are **normative**
 * (onym-moderation `android/fixtures/payload-fixtures.json`, generated
 * by its `src/payload.rs`); `SignedSessionPayloadTest` pins this
 * implementation to them byte-for-byte. The layout: for each field in
 * order — context, challenge (raw bytes), user key, RFC 3339 UTC
 * timestamp, mandate ref (empty when absent) — a big-endian `u32`
 * length prefix followed by the raw bytes. Length prefixes make the
 * concatenation injective; the context string domain-separates
 * enrollment from gate-check (and both from the iOS contexts).
 *
 * The integrity token is **not** a field: it does not exist until
 * after the hash is computed. It binds to the payload through the
 * echoed `requestHash`; the identity signature covers the same bytes
 * directly. Two bindings, one payload.
 */
object SignedSessionPayload {
    const val ENROLL_CONTEXT: String = "onym-moderation-android-enroll-v1"
    const val GATE_CONTEXT: String = "onym-moderation-android-gate-v1"

    /** Signed bytes for `POST /v1/enroll`. */
    fun enrollment(challenge: ByteArray, userKey: String, timestamp: String): ByteArray =
        bytes(ENROLL_CONTEXT, challenge, userKey, timestamp, mandateRef = null)

    /** Signed bytes for `POST /v1/gate-check`. */
    fun gateCheck(
        challenge: ByteArray,
        userKey: String,
        mandateRef: String?,
        timestamp: String,
    ): ByteArray = bytes(GATE_CONTEXT, challenge, userKey, timestamp, mandateRef)

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
        mandateRef: String?,
    ): ByteArray {
        val fields = listOf(
            context.encodeToByteArray(),
            challenge,
            userKey.encodeToByteArray(),
            timestamp.encodeToByteArray(),
            (mandateRef ?: "").encodeToByteArray(),
        )
        val buffer = ByteBuffer.allocate(fields.sumOf { 4 + it.size })
        for (field in fields) {
            buffer.putInt(field.size)
            buffer.put(field)
        }
        return buffer.array()
    }
}
