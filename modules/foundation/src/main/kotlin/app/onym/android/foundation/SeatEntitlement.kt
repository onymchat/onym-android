package app.onym.android.foundation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.long
import java.time.Instant
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * The credential a seat operator actually checks — a house canonical-
 * JSON + detached Ed25519 signature format, not a JWT. Broker-issued,
 * seat-scoped, and disjoint from any platform account or global
 * identity: it carries only `subject` (the seat access key) and
 * `offerId`.
 *
 * `status` points at a broker-signed [RevocationEpoch] document
 * rather than a per-entitlement status endpoint — WHITEPAPER §17.8
 * forbids the latter (it would let the broker observe every
 * seat-to-holder session).
 *
 * Mirrors `SeatEntitlement` in onym-ios OnymBilling.
 */
class SeatEntitlement private constructor(
    val version: Int,
    val type: String,
    /** `onym:key:<hex>` — the billing broker's issuer key. */
    val issuer: String,
    /** `onym:component:<operator>`. */
    val audience: String,
    /** `onym:seat-key:<64 lowercase hex>` — exact string, prefix
     *  included; never normalized. */
    val subject: String,
    val offerId: String,
    val entitlementId: String,
    val notBefore: Instant,
    val expiresAt: Instant,
    /** Integer-or-decimal-string only — never a float (no canonical
     *  float form across platforms). `null` = unmetered. */
    val quota: String?,
    /** URL to a broker-signed [RevocationEpoch]. */
    val status: String,
    val rawBytes: ByteArray,
) {
    val signatureBase64: String by lazy {
        val obj = Json.parseToJsonElement(String(rawBytes, Charsets.UTF_8)).jsonObject
        (obj["signature"] as JsonPrimitive).content
    }

    override fun equals(other: Any?): Boolean =
        other is SeatEntitlement && rawBytes.contentEquals(other.rawBytes)

    override fun hashCode(): Int = rawBytes.contentHashCode()

    companion object {
        /** Decodes but does NOT verify — see [SeatEntitlementVerifier].
         *  @throws SeatEntitlementException.Malformed */
        fun decode(raw: ByteArray): SeatEntitlement {
            val obj = try {
                Json.parseToJsonElement(String(raw, Charsets.UTF_8)).jsonObject
            } catch (_: Exception) {
                throw SeatEntitlementException.Malformed("not valid JSON")
            }
            fun str(key: String): String =
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw SeatEntitlementException.Malformed("missing or malformed $key")
            fun instant(key: String): Instant =
                ServiceManifestFormat.instantFromRfc3339(str(key))
                    ?: throw SeatEntitlementException.Malformed("$key is not RFC 3339")

            val quotaElement = obj["quota"]
            val quota: String? = when {
                quotaElement == null -> null
                quotaElement is kotlinx.serialization.json.JsonNull -> null
                quotaElement is JsonPrimitive && quotaElement.isString -> quotaElement.content
                quotaElement is JsonPrimitive && !quotaElement.isString -> {
                    // Numeric literal in the wire JSON: only accept if
                    // it has no fractional/exponent component — a
                    // float has no canonical cross-platform form.
                    val raw = quotaElement.content
                    if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
                        throw SeatEntitlementException.Malformed("quota must not be a float")
                    }
                    raw
                }
                else -> throw SeatEntitlementException.Malformed("quota is malformed")
            }

            return SeatEntitlement(
                version = (obj["version"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: throw SeatEntitlementException.Malformed("missing or malformed version"),
                type = str("type"),
                issuer = str("issuer"),
                audience = str("audience"),
                subject = str("subject"),
                offerId = str("offerId"),
                entitlementId = str("entitlementId"),
                notBefore = instant("notBefore"),
                expiresAt = instant("expiresAt"),
                quota = quota,
                status = str("status"),
                rawBytes = raw,
            )
        }
    }
}

sealed class SeatEntitlementException(message: String) : Exception(message) {
    class Malformed(reason: String) : SeatEntitlementException("entitlement malformed: $reason")
    object UntrustedIssuer : SeatEntitlementException("issuer is not in the operator's trusted set")
    object SignatureInvalid : SeatEntitlementException("signature does not verify")
    object WrongAudience : SeatEntitlementException("audience does not match this operator")
    object WrongSubject : SeatEntitlementException("subject does not match the presenting seat key")
    object Expired : SeatEntitlementException("outside notBefore..expiresAt")
    object Revoked : SeatEntitlementException("entitlementId is in the cached revocation epoch")
}

/**
 * Verifies a [SeatEntitlement] in the exact required order — do not
 * reorder. Only after the signature check passes is any other field
 * trusted.
 *
 * Mirrors `SeatEntitlementVerifier` in onym-ios OnymBilling and the
 * operator-side verification order in onym-system
 * `backup/UI-Backup-Object-HTTP.md` §10.4.
 */
object SeatEntitlementVerifier {
    /**
     * @param trustedIssuers pinned from the OPERATOR'S VERIFIED
     *   MANIFEST — never read off the entitlement being checked.
     * @param audience this operator's own componentId.
     * @param subject the device's own presenting seat key
     *   (`onym:seat-key:<hex>`), compared as an exact string.
     * @param revokedEntitlementIds the cached [RevocationEpoch]'s
     *   revoked-id set.
     * @throws SeatEntitlementException
     */
    fun verify(
        entitlement: SeatEntitlement,
        trustedIssuers: Set<String>,
        issuerPublicKeyHex: (issuer: String) -> String?,
        audience: String,
        subject: String,
        revokedEntitlementIds: Set<String>,
        now: Instant = Instant.now(),
    ) {
        if (entitlement.issuer !in trustedIssuers) throw SeatEntitlementException.UntrustedIssuer

        val issuerKeyHex = issuerPublicKeyHex(entitlement.issuer) ?: throw SeatEntitlementException.UntrustedIssuer
        val issuerKeyBytes = ServiceManifestFormat.bytesFromLowercaseHex(issuerKeyHex)
            ?: throw SeatEntitlementException.UntrustedIssuer
        val signingBytes = ServiceManifestCanonical.signingBytes(entitlement.rawBytes, omitting = setOf("signature"))
        val signatureBytes = try {
            Base64.getDecoder().decode(entitlement.signatureBase64)
        } catch (_: Exception) {
            throw SeatEntitlementException.SignatureInvalid
        }
        val verified = try {
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(issuerKeyBytes, 0))
                update(signingBytes, 0, signingBytes.size)
            }.verifySignature(signatureBytes)
        } catch (_: Exception) {
            false
        }
        if (!verified) throw SeatEntitlementException.SignatureInvalid

        // Only past this point is any other field trusted.
        if (entitlement.audience != audience) throw SeatEntitlementException.WrongAudience
        if (entitlement.subject != subject) throw SeatEntitlementException.WrongSubject
        if (now.isBefore(entitlement.notBefore) || !now.isBefore(entitlement.expiresAt)) {
            throw SeatEntitlementException.Expired
        }
        if (entitlement.entitlementId in revokedEntitlementIds) throw SeatEntitlementException.Revoked
    }
}

/**
 * A broker-signed, publicly fetchable, unauthenticated list of
 * revoked-and-not-yet-expired entitlement ids — polled on the
 * operator's own schedule. There is deliberately no per-entitlement
 * status endpoint (see [SeatEntitlement]'s doc comment); this is the
 * only revocation signal a conforming broker publishes.
 *
 * Fail-open on fetch failure: a caller that cannot refresh this
 * document keeps using the last good epoch rather than refusing —
 * see the caller-side policy note on [SeatEntitlementVerifier.verify]'s
 * `revokedEntitlementIds` parameter. A broker outage must never delete
 * anyone's access.
 */
data class RevocationEpoch(
    val epoch: Long,
    val publishedAt: Instant,
    val revoked: Set<String>,
    val rawBytes: ByteArray,
    val signatureBase64: String,
) {
    override fun equals(other: Any?): Boolean = other is RevocationEpoch && rawBytes.contentEquals(other.rawBytes)
    override fun hashCode(): Int = rawBytes.contentHashCode()

    companion object {
        fun decode(raw: ByteArray): RevocationEpoch {
            val obj = try {
                Json.parseToJsonElement(String(raw, Charsets.UTF_8)).jsonObject
            } catch (_: Exception) {
                throw SeatEntitlementException.Malformed("revocation epoch is not valid JSON")
            }
            val epoch = (obj["epoch"] as? JsonPrimitive)?.long
                ?: throw SeatEntitlementException.Malformed("missing epoch")
            val publishedAt = (obj["publishedAt"] as? JsonPrimitive)?.content
                ?.let(ServiceManifestFormat::instantFromRfc3339)
                ?: throw SeatEntitlementException.Malformed("missing publishedAt")
            val revoked = (obj["revoked"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.toSet()
                ?: emptySet()
            val signature = (obj["signature"] as? JsonPrimitive)?.content
                ?: throw SeatEntitlementException.Malformed("missing signature")
            return RevocationEpoch(epoch, publishedAt, revoked, raw, signature)
        }
    }

    fun verifySignature(issuerPublicKeyHex: String): Boolean {
        val keyBytes = ServiceManifestFormat.bytesFromLowercaseHex(issuerPublicKeyHex) ?: return false
        val signingBytes = ServiceManifestCanonical.signingBytes(rawBytes, omitting = setOf("signature"))
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureBase64)
        } catch (_: Exception) {
            return false
        }
        return try {
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(keyBytes, 0))
                update(signingBytes, 0, signingBytes.size)
            }.verifySignature(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }
}
