package app.onym.android.foundation

import android.util.Log
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.IOException

/**
 * Thrown when signature ENFORCEMENT is on and a trust-critical asset
 * arrives without a valid detached signature. Extends [IOException] so
 * it flows through the existing fetch error handling as a fetch
 * failure (callers keep the last-good cached list intact).
 */
internal class SignatureVerificationException(message: String) : IOException(message)

/**
 * Client-side Ed25519 detached-signature verification for the
 * trust-critical config assets that are auto-fetched from GitHub
 * Releases on cold install — the contracts manifest (the group
 * membership / governance commitment authority) and the relayer /
 * Nostr-relay / Blossom server lists (which route all traffic).
 *
 * H-3: previously these were trusted after only a 2xx + schema check,
 * so anyone who could swap the served JSON (a compromised GitHub
 * release asset, a MITM on a stripped TLS hop, a malicious mirror)
 * could subvert governance or route every install through attacker
 * infrastructure. This verifier adds an offline-key signature gate on
 * the raw asset bytes.
 *
 * ## Soft mode (default) vs enforcement
 * [ENFORCE_SIGNATURES] defaults to **OFF (soft mode)**: a missing or
 * invalid signature logs a warning and the asset is still used, so the
 * currently-unsigned GitHub-Release assets keep working with no
 * behavioural change. Flipping it on is a two-step release task:
 *
 *   1. Replace the [SIGNING_PUBLIC_KEY] placeholder with the real
 *      Ed25519 public key whose private half lives in the offline
 *      release-signing pipeline (never in the repo / never on CI).
 *   2. Have that pipeline publish a `<asset>.sig` detached signature
 *      (raw 64-byte Ed25519 signature) next to every trust-critical
 *      asset on each release.
 *
 * Only after both are in place can [ENFORCE_SIGNATURES] be set true.
 */
class TrustedAssetVerifier(
    private val publicKey: ByteArray = SIGNING_PUBLIC_KEY,
    private val enforce: Boolean = ENFORCE_SIGNATURES,
    private val logWarning: (String) -> Unit = ::defaultWarn,
) {
    /** Outcome of a pure crypto check. */
    internal enum class Result {
        /** Signature present and verified against [publicKey]. */
        VERIFIED,

        /** Signature present but did not verify (tamper / wrong key). */
        INVALID,

        /** No signature was supplied (asset unsigned or `.sig` absent). */
        MISSING,
    }

    /**
     * Pure Ed25519 check of [asset] against [signature]. No logging, no
     * throwing — safe to call from anywhere. Returns [Result.MISSING]
     * when [signature] is null so callers can distinguish "unsigned"
     * from "bad signature".
     */
    internal fun verify(asset: ByteArray, signature: ByteArray?): Result {
        if (signature == null) return Result.MISSING
        if (signature.size != SIGNATURE_LENGTH || publicKey.size != PUBLIC_KEY_LENGTH) {
            return Result.INVALID
        }
        return try {
            val verifier = Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
            }
            verifier.update(asset, 0, asset.size)
            if (verifier.verifySignature(signature)) Result.VERIFIED else Result.INVALID
        } catch (_: Exception) {
            // Malformed key/signature bytes etc. — treat as invalid, never crash.
            Result.INVALID
        }
    }

    /**
     * Gate [asset] before it is trusted. In enforcement mode a missing
     * or invalid signature throws [SignatureVerificationException]. In
     * soft mode (default) it logs a warning and returns so unsigned
     * assets keep working.
     *
     * @param assetName human-readable name for logs / error messages.
     */
    fun gate(assetName: String, asset: ByteArray, signature: ByteArray?) {
        when (verify(asset, signature)) {
            Result.VERIFIED -> return
            Result.MISSING -> fail(assetName, "no detached signature available")
            Result.INVALID -> fail(assetName, "detached signature did not verify")
        }
    }

    private fun fail(assetName: String, reason: String) {
        if (enforce) {
            throw SignatureVerificationException("$assetName: $reason (signature enforcement ON)")
        }
        logWarning(
            "$assetName: $reason — proceeding because signature enforcement is OFF " +
                "(soft mode; see TrustedAssetVerifier.ENFORCE_SIGNATURES)",
        )
    }

    companion object {
        internal const val PUBLIC_KEY_LENGTH = 32
        internal const val SIGNATURE_LENGTH = 64
        private const val TAG = "TrustedAssetVerifier"

        /**
         * PLACEHOLDER offline-signing public key (32 zero bytes).
         *
         * MUST be replaced with the REAL Ed25519 public key whose
         * private half is held only by the offline release-signing
         * pipeline BEFORE [ENFORCE_SIGNATURES] is turned on. Until then
         * it is never load-bearing: enforcement is off, so nothing is
         * rejected and this key verifies nothing. Bundled in the app
         * binary (compiled constant) — no network fetch, so it cannot
         * be swapped by the same attacker who could swap the asset.
         */
        internal val SIGNING_PUBLIC_KEY: ByteArray = ByteArray(PUBLIC_KEY_LENGTH)

        /**
         * Master switch for signature ENFORCEMENT. **DEFAULT OFF.**
         *
         * Off (soft mode): missing/invalid signatures are logged and
         * the asset is still used — required today because no assets
         * are signed yet. Turning this on without the real
         * [SIGNING_PUBLIC_KEY] and a `.sig`-publishing release pipeline
         * would brick config fetching on every install.
         */
        internal const val ENFORCE_SIGNATURES = false

        private fun defaultWarn(message: String) {
            try {
                Log.w(TAG, message)
            } catch (_: Throwable) {
                // android.util.Log is not stubbed under plain JVM unit
                // tests (testOptions.unitTests default). Swallow so the
                // fetch path stays exercisable without an Android runtime.
            }
        }
    }
}
