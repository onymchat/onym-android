package app.onym.android.push

import app.onym.android.foundation.Base64ByteArraySerializer
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one JSON configuration every push wire type uses. Same posture
 * as :moderation's ModerationJson:
 *
 * - `explicitNulls = false` — absent optionals are omitted, never
 *   `null`: `integrityToken` must be MISSING (not null) when the
 *   device has no usable Play environment.
 * - `ignoreUnknownKeys = true` — tolerant decode, so a backend that
 *   grows a field does not brick every client.
 */
object PushJson {
    val json: Json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /**
     * Tolerant RFC 3339 parse for backend timestamps: seconds
     * precision is what this client SENDS, but the backend's
     * `expiresAt` answers may carry fractional seconds and must not
     * be treated as an unparseable (unreachable-class) response for
     * it. `Instant.parse` accepts both.
     */
    fun parseInstant(raw: String): Instant = try {
        Instant.parse(raw)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("not an RFC 3339 timestamp: $raw", e)
    }
}

// ─── Requests (camelCase; absent optionals are omitted, never null) ──

/** `POST /v1/challenge` body — purpose is `"register"` or
 * `"unregister"`. */
@Serializable
data class PushChallengeRequest(val purpose: String)

/** `POST /v1/challenge` answer. The raw challenge bytes go into the
 * signed payload; the base64 string travels back verbatim so the
 * backend can recompute the payload. Single-use, ~600 s TTL. */
@Serializable
data class IssuedPushChallenge(
    @Serializable(with = Base64ByteArraySerializer::class)
    val challenge: ByteArray,
    val expiresAt: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IssuedPushChallenge) return false
        return challenge.contentEquals(other.challenge) && expiresAt == other.expiresAt
    }

    override fun hashCode(): Int = 31 * challenge.contentHashCode() + expiresAt.hashCode()
}

/** `GET /v1/registration-key` answer: the backend's 32-byte X25519
 * key the FCM token is sealed to. Fetched fresh before each
 * register/unregister so a rotated server key is picked up without a
 * client release. */
@Serializable
data class PushRegistrationKey(
    @Serializable(with = Base64ByteArraySerializer::class)
    val publicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PushRegistrationKey) return false
        return publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = publicKey.contentHashCode()
}

/**
 * `POST /v1/register`. Every field the signature covers is
 * transmitted, so the backend can recompute [SignedPushPayload] and
 * verify it. The integrity token is outside the signed payload (it
 * does not exist until after the requestHash is computed); it binds
 * to the same payload through the echoed hash.
 */
@Serializable
data class PushRegisterRequest(
    /** `onym:key:<hex>` — the identity key reference. */
    val userKey: String,
    /** RFC 3339 UTC, seconds precision — enters the signed payload
     * verbatim. */
    val timestamp: String,
    /** Base64 Ed25519 signature over [SignedPushPayload.register]. */
    val signature: String,
    /** Base64 of the backend-issued challenge bytes, single-use. */
    @Serializable(with = Base64ByteArraySerializer::class)
    val challenge: ByteArray,
    /** The Play Integrity token for this session, as Google returned
     * it. OMITTED (never null) when the device has no usable Play
     * environment — this client never fabricates one. */
    val integrityToken: String? = null,
    val tokenEnvelope: PushTokenEnvelope,
    val subscriptions: List<PushSubscription>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PushRegisterRequest) return false
        return userKey == other.userKey &&
            timestamp == other.timestamp &&
            signature == other.signature &&
            challenge.contentEquals(other.challenge) &&
            integrityToken == other.integrityToken &&
            tokenEnvelope == other.tokenEnvelope &&
            subscriptions == other.subscriptions
    }

    override fun hashCode(): Int {
        var result = userKey.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.hashCode()
        result = 31 * result + challenge.contentHashCode()
        result = 31 * result + (integrityToken?.hashCode() ?: 0)
        result = 31 * result + tokenEnvelope.hashCode()
        result = 31 * result + subscriptions.hashCode()
        return result
    }
}

/** `POST /v1/register` answer: when this registration lapses
 * server-side (the reconciler refreshes before then). */
@Serializable
data class PushRegistration(val expiresAt: String)

/** `POST /v1/unregister` — the register shape minus subscriptions
 * (the signed payload's digest field is empty). Idempotent: a token
 * the backend never saw still answers 200. */
@Serializable
data class PushUnregisterRequest(
    val userKey: String,
    val timestamp: String,
    /** Base64 Ed25519 signature over [SignedPushPayload.unregister]. */
    val signature: String,
    @Serializable(with = Base64ByteArraySerializer::class)
    val challenge: ByteArray,
    val integrityToken: String? = null,
    val tokenEnvelope: PushTokenEnvelope,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PushUnregisterRequest) return false
        return userKey == other.userKey &&
            timestamp == other.timestamp &&
            signature == other.signature &&
            challenge.contentEquals(other.challenge) &&
            integrityToken == other.integrityToken &&
            tokenEnvelope == other.tokenEnvelope
    }

    override fun hashCode(): Int {
        var result = userKey.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + signature.hashCode()
        result = 31 * result + challenge.contentHashCode()
        result = 31 * result + (integrityToken?.hashCode() ?: 0)
        result = 31 * result + tokenEnvelope.hashCode()
        return result
    }
}
