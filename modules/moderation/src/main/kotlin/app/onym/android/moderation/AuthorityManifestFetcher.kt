package app.onym.android.moderation

import java.io.IOException
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Consent-path failures, with the operator-facing reason kept. */
open class ModerationConsentException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * The device cannot mint Play Integrity tokens (no Google Mobile
 * Services — Huawei post-2019, de-Googled distributions) AND the
 * backend refused the token-less enrollment: the enforcement rail
 * structurally cannot exist on this hardware (profile §8.5's
 * disclosed reach limitation). Product decision: such devices MAY
 * skip moderation selection — consent surfaces route this to the
 * deferrable Unavailable path, unlike an ordinary refusal of a
 * device the rail *does* support. Backend-side identity refusal
 * remains in force for banned identities regardless.
 */
class ModerationUnsupportedDeviceException(message: String, cause: Throwable? = null) :
    ModerationConsentException(message, cause)

/**
 * Fetches the interface's published authority directory. Lossy per
 * entry: one malformed listing is skipped, not the whole directory.
 *
 * Trust chain, end to end: the directory's exact bytes are verified
 * against a detached Ed25519 signature at `<url>.sig`, made by the
 * directory root key whose PUBLIC half ships in this app
 * ([OkHttpKnownAuthoritiesFetcher.DIRECTORY_ROOT_KEY_BASE64] — pinned,
 * never fetched; the private half is held offline by the release
 * manager, see onym-authorities/sign.sh). The verified directory pins
 * each authority's operator key; the manifest fetch below verifies the
 * manifest's own signature against that pinned key. So a compromised
 * host or release account can no longer substitute an authority: it
 * would need the offline root key. Hard-enforced — an unsigned or
 * tampered directory yields no authorities, and therefore no consent
 * (onym-android#219).
 */
interface KnownAuthoritiesFetcher {
    /**
     * Throws on network failure, non-2xx, or a directory with no
     * valid entries. There is NO cache layer today: each caller (the
     * gate flow's probe, the onboarding probe, the consent
     * controller's load) fetches independently — up to a few GETs of
     * a tiny document per launch, deliberately tolerated over
     * coupling those components; add a shared cache here if that
     * budget ever matters.
     */
    suspend fun fetchLatest(): List<AuthorityListing>
}

class OkHttpKnownAuthoritiesFetcher(
    private val httpClient: OkHttpClient,
    private val url: String = DEFAULT_URL,
    /** Test seam only — production callers keep the shipped pin. */
    private val rootKeyBase64: String = DIRECTORY_ROOT_KEY_BASE64,
) : KnownAuthoritiesFetcher {
    override suspend fun fetchLatest(): List<AuthorityListing> = withContext(Dispatchers.IO) {
        val bytes = fetchBytes(httpClient, url, what = "authority directory")

        // Signature BEFORE parsing: unverified bytes get no further
        // interpretation. The pinned key decode is checked every call
        // rather than trusted at init so a corrupted constant fails
        // loudly, never as "no signature checking".
        val rootKey = runCatching { Base64.getDecoder().decode(rootKeyBase64) }
            .getOrNull()
            ?.takeIf { it.size == 32 }
            ?: throw ModerationConsentException("directory root key pin is not a 32-byte Ed25519 key")
        val signature = fetchBytes(httpClient, "$url.sig", what = "authority directory signature")
        verifyDetachedEd25519Signature(bytes, signature, rootKey, what = "authority directory")

        val document = runCatching {
            ModerationJson.json.decodeFromString(
                AuthorityDirectoryDocument.serializer(),
                bytes.decodeToString(),
            )
        }.getOrElse { throw ModerationConsentException("authority directory is malformed", it) }
        val listings = document.authorities.filter { listing ->
            listing.apiBaseURL.startsWith("https://") && listing.manifestURL.startsWith("https://")
        }
        if (listings.isEmpty()) {
            throw ModerationConsentException("authority directory contains no usable entries")
        }
        listings
    }

    @Serializable
    private data class AuthorityDirectoryDocument(
        val authorities: List<AuthorityListing> = emptyList(),
    )

    companion object {
        /** Same publication shape as the relayer/authority lists. */
        const val DEFAULT_URL: String =
            "https://github.com/onymchat/onym-authorities/releases/latest/download/authorities.json"

        /**
         * The directory root key's PUBLIC half — the app-shipped trust
         * anchor the whole moderation pin chain roots in. Mirrors
         * `directory-root-pubkey.txt` in onym-authorities, whose
         * releases carry the matching detached signature; the private
         * half is offline with the release manager (never in a repo,
         * never in CI). Rotating it is an app update by design.
         */
        const val DIRECTORY_ROOT_KEY_BASE64: String =
            "mD9k/2Xa1D1DGI0IlczLTea9N8Kefe/IWLOO4OF8Fjw="
    }
}

/**
 * Fetches an authority's manifest as **exact bytes**, decodes it,
 * requires the declared `operator` to be the directory-pinned key, and
 * verifies the authority's detached Ed25519 signature over those exact
 * bytes (published at `<manifest-url>.sig`, base64). Hard-enforced —
 * this client ships after authorities sign, so there is no soft mode.
 */
interface AuthorityManifestFetcher {
    suspend fun fetch(listing: AuthorityListing): SignedManifest
}

class OkHttpAuthorityManifestFetcher(
    private val httpClient: OkHttpClient,
) : AuthorityManifestFetcher {
    override suspend fun fetch(listing: AuthorityListing): SignedManifest =
        withContext(Dispatchers.IO) {
            val rawBytes = fetchBytes(httpClient, listing.manifestURL, what = "authority manifest")
            val manifest = runCatching {
                ModerationJson.json.decodeFromString(
                    AuthorityManifest.serializer(),
                    rawBytes.decodeToString(),
                )
            }.getOrElse { throw ModerationConsentException("authority manifest is malformed", it) }

            if (manifest.componentId != listing.componentId) {
                throw ModerationConsentException(
                    "manifest componentId does not match the directory listing",
                )
            }
            val pinnedKey = directoryPinnedOperatorKey(manifest, listing)

            val signature = fetchBytes(
                httpClient,
                "${listing.manifestURL}.sig",
                what = "authority manifest signature",
            )
            verifyDetachedEd25519Signature(rawBytes, signature, pinnedKey, what = "authority manifest")

            SignedManifest(manifest, rawBytes)
        }

    /**
     * The manifest's declared `operator` must be the directory-pinned
     * key, byte for byte — otherwise a manifest could name its own
     * verdict-signing key and the pin would guard nothing.
     */
    private fun directoryPinnedOperatorKey(
        manifest: AuthorityManifest,
        listing: AuthorityListing,
    ): ByteArray {
        val declared = keyBytesFromReference(manifest.operatorKey)
            ?: throw ModerationConsentException("manifest operator is not an onym:key: reference")
        val pinned = runCatching { Base64.getDecoder().decode(listing.operatorPublicKeyBase64) }
            .getOrNull()
            ?.takeIf { it.size == 32 }
            ?: throw ModerationConsentException("directory listing carries no usable operator key")
        if (!declared.contentEquals(pinned)) {
            throw ModerationConsentException("manifest operator does not match the directory-pinned key")
        }
        return pinned
    }

}

/**
 * Verify a detached Ed25519 signature file (base64 of the 64 raw
 * bytes, whitespace-tolerant) over [rawBytes]. Shared by the directory
 * fetch (against the app-pinned root key) and the manifest fetch
 * (against the directory-pinned operator key) — one wire contract,
 * one verifier.
 */
internal fun verifyDetachedEd25519Signature(
    rawBytes: ByteArray,
    signatureFile: ByteArray,
    publicKey: ByteArray,
    what: String,
) {
    val signature = runCatching {
        Base64.getMimeDecoder().decode(signatureFile.decodeToString().trim())
    }.getOrNull()?.takeIf { it.size == 64 }
        ?: throw ModerationConsentException("$what signature file is not a base64 Ed25519 signature")
    val verifier = Ed25519Signer().apply {
        init(false, Ed25519PublicKeyParameters(publicKey, 0))
        update(rawBytes, 0, rawBytes.size)
    }
    if (!verifier.verifySignature(signature)) {
        throw ModerationConsentException("$what signature did not verify against the pinned key")
    }
}

/** `onym:key:<hex>` → raw bytes, or null when malformed. */
fun keyBytesFromReference(reference: String): ByteArray? {
    val hex = reference.removePrefix(KEY_REFERENCE_PREFIX)
    if (hex == reference || hex.length % 2 != 0 || hex.isEmpty()) return null
    return runCatching {
        ByteArray(hex.length / 2) { i ->
            hex.substring(2 * i, 2 * i + 2).toInt(16).toByte()
        }
    }.getOrNull()
}

fun keyReference(raw: ByteArray): String =
    KEY_REFERENCE_PREFIX + raw.joinToString("") { "%02x".format(it) }

const val KEY_REFERENCE_PREFIX: String = "onym:key:"

private fun fetchBytes(httpClient: OkHttpClient, url: String, what: String): ByteArray {
    if (!url.startsWith("https://")) {
        throw ModerationConsentException("$what URL must use https: $url")
    }
    val request = Request.Builder().url(url).get().build()
    try {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ModerationConsentException("$what fetch failed: HTTP ${response.code}")
            }
            return response.body?.bytes()
                ?: throw ModerationConsentException("$what fetch returned no body")
        }
    } catch (e: IOException) {
        throw ModerationConsentException("$what unreachable", e)
    }
}

/**
 * The manifest's consent-time validity conditions (Moderation.md
 * §5.2) in one place, run before enrollment and mandate signing so an
 * invalid manifest can never end up pinned by a signed mandate.
 */
object AuthorityManifestValidator {
    /** The profile this client implements; a conforming manifest names
     * exactly this. Empty (fixture manifests predating the field) is
     * tolerated until authorities republish. */
    const val PROFILE_ID: String = "onym:moderation-enforcement-profile:google-device-recall-v1"

    fun validateForConsent(signed: SignedManifest, now: Instant) {
        val manifest = signed.manifest
        if (manifest.violationClasses.isEmpty()) {
            throw ModerationConsentException("manifest declares no violation classes")
        }
        val validUntil = runCatching { Rfc3339InstantSerializer.parse(manifest.validUntil) }
            .getOrElse { throw ModerationConsentException("manifest validUntil is unreadable") }
        if (!now.isBefore(validUntil)) {
            throw ModerationConsentException("manifest validity has lapsed; ask the authority to republish")
        }
        if (manifest.moderationProfileId.isNotEmpty() &&
            manifest.moderationProfileId != PROFILE_ID
        ) {
            throw ModerationConsentException(
                "manifest names profile ${manifest.moderationProfileId}; this client implements $PROFILE_ID",
            )
        }
        // A permanent class requires an external appellate authority
        // (§5.2 constraint 2).
        val hasPermanent = manifest.violationClasses.any { it.banTerm == "permanent" }
        if (hasPermanent && (manifest.appellate == null || manifest.appellate == "self")) {
            throw ModerationConsentException(
                "manifest carries a permanent class without an external appellate authority",
            )
        }
    }
}
