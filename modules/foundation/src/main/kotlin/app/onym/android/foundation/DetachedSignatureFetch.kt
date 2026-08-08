package app.onym.android.foundation

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Detached-signature convention for GitHub-Release config assets: the
 * signature for `<asset>` is published as the sibling release asset
 * `<asset>.sig` (e.g. `contracts-manifest.json` →
 * `contracts-manifest.json.sig`), containing the raw 64-byte Ed25519
 * signature over the exact asset bytes.
 */
fun signatureUrlFor(assetUrl: String): String = "$assetUrl.sig"

/**
 * Best-effort GET of a detached `.sig` asset for [sigUrl].
 *
 * Returns the raw signature bytes, or `null` when the signature asset
 * is absent / unfetchable / not the expected 64-byte length. **Never
 * throws:** a missing signature is a verification input (→
 * [TrustedAssetVerifier.Result.MISSING]), not a fetch failure, so soft
 * mode keeps working while no assets are signed yet. Enforcement is
 * decided by [TrustedAssetVerifier.gate], not here.
 */
fun OkHttpClient.fetchDetachedSignature(sigUrl: String): ByteArray? =
    try {
        val request = Request.Builder().url(sigUrl.toHttpUrl()).build()
        newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                response.body?.bytes()?.takeIf { it.size == TrustedAssetVerifier.SIGNATURE_LENGTH }
            }
        }
    } catch (_: Exception) {
        null
    }
