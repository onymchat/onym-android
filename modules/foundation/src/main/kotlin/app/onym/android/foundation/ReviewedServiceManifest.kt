package app.onym.android.foundation

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.time.Instant

/**
 * A service manifest that passed review — the unit of consent,
 * mintable only by [ServiceManifestReviewer.review].
 *
 * [SignedServiceManifest] is a plain value with a public parse
 * entry point, so taking one at consent time would move authenticity
 * (embedded operator signature over the exact bytes, digest pin,
 * expiry) out of the reviewer and onto whoever calls it. Wrapping it
 * in a type with an **internal-only constructor** keeps the check
 * where the invariant lives: the only bytes that can reach a pinned
 * consent record are bytes the reviewer verified. Same discipline as
 * `ReviewedServiceManifest` in onym-ios OnymFoundation (no public
 * initializer — the no-forge guarantee).
 */
class ReviewedServiceManifest internal constructor(
    /** The reviewed manifest, for the consent surface to display. */
    val signedManifest: SignedServiceManifest,
) {
    override fun equals(other: Any?): Boolean =
        other is ReviewedServiceManifest && signedManifest == other.signedManifest

    override fun hashCode(): Int = signedManifest.hashCode()
}

/**
 * Verifies a service manifest's exact published bytes and mints the
 * [ReviewedServiceManifest] token consent APIs require.
 *
 * **Hard-enforced** — unlike [TrustedAssetVerifier]'s soft mode for
 * the legacy lists, there is no accept-anyway path: a manifest that
 * fails any check is rejected. The signature is the manifest's own
 * embedded `signature`, verified against its own `operator` key over
 * the canonical bytes ([ServiceManifestCanonical]); binding that key
 * to an identity the user trusts (catalog digest pin, out-of-band
 * fingerprint check) is the caller's protocol.
 */
class ServiceManifestReviewer {

    /**
     * Review exact manifest bytes.
     *
     * @param raw the bytes as served. These exact bytes end up in the
     *   token — never a re-fetch, never a re-encode.
     * @param expectedDigest `sha256:<hex>` pin when the bytes were
     *   reached through a catalog entry (or a user-confirmed hash).
     *   `null` on the direct paste-URL path, where trust is the
     *   signature self-check plus the user's explicit review.
     * @param now injected clock for the `validUntil` check.
     * @throws ServiceManifestException
     */
    fun review(
        raw: ByteArray,
        expectedDigest: String? = null,
        now: Instant = Instant.now(),
    ): ReviewedServiceManifest {
        if (expectedDigest != null) {
            val actual = ServiceManifestFormat.sha256Digest(raw)
            if (!ServiceManifestFormat.isDigest(expectedDigest) || expectedDigest != actual) {
                throw ServiceManifestException.DigestMismatch(
                    expected = expectedDigest,
                    actual = actual,
                )
            }
        }

        val manifest = SignedServiceManifest.parse(raw)

        val signatureBase64 = manifest.signatureBase64
            ?: throw ServiceManifestException.SignatureInvalid("manifest carries no signature")
        val signature = ServiceManifestFormat.decodeBase64AcceptingUnpadded(signatureBase64)
            ?: throw ServiceManifestException.SignatureInvalid("signature is not valid base64")
        val keyBytes = ServiceManifestFormat.bytesFromLowercaseHex(manifest.operatorPublicKeyHex)
            ?: throw ServiceManifestException.SignatureInvalid(
                "operator key is not a valid Ed25519 public key",
            )
        val signingBytes = ServiceManifestCanonical.signingBytes(raw)
        if (!verifyEd25519(keyBytes, signingBytes, signature)) {
            throw ServiceManifestException.SignatureInvalid(
                "signature does not verify against the operator key",
            )
        }

        val validUntil = manifest.validUntil
        if (validUntil != null && validUntil < now) {
            throw ServiceManifestException.Expired(validUntil)
        }

        return ReviewedServiceManifest(manifest)
    }

    /** Pure BouncyCastle Ed25519 check. Malformed key/signature bytes
     *  are invalid, never a crash — same posture as
     *  [TrustedAssetVerifier.verify]. */
    private fun verifyEd25519(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean = try {
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
        }
        verifier.update(message, 0, message.size)
        verifier.verifySignature(signature)
    } catch (_: Exception) {
        false
    }
}
