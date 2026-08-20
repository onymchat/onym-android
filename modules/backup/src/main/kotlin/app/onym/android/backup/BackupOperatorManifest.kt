package app.onym.android.backup

import app.onym.android.foundation.SignedServiceManifest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The `storage.backup` fields read off an already-verified
 * [SignedServiceManifest] — this type never re-verifies a signature,
 * it only decodes `rawBytes`.
 *
 * Mirrors `BackupOperatorManifest` in onym-ios OnymBackup.
 */
data class BackupOperatorManifest(
    val componentId: String,
    val backupProfileId: String,
    val implementationProfileId: String,
    /** First usable `read-write` HTTPS endpoint with a non-empty host
     *  — malformed entries are skipped, never rewritten. `null` if
     *  none qualify. */
    val endpoint: String?,
    val declaredTermsDigest: String,
    val entitlementIssuers: List<String>,
    val maximumSealedSnapshotBytes: Long?,
    val maximumRetainedSnapshots: Long?,
) {
    /** `termsURL(digest)` requires a full `sha256:<64hex>` before
     *  building a fetch URL — an operator response can never smuggle
     *  a path through this. */
    fun termsUrl(digest: String): String? {
        if (endpoint == null || !BackupFormat.isDigest(digest)) return null
        val hex = digest.removePrefix("sha256:")
        return endpoint.trimEnd('/') + "/terms/$hex.json"
    }

    companion object {
        fun from(manifest: SignedServiceManifest): BackupOperatorManifest {
            val obj = Json.parseToJsonElement(String(manifest.rawBytes, Charsets.UTF_8)).jsonObject
            val storage = (obj["storage"] as? JsonObject)?.get("backup") as? JsonObject
                ?: throw BackupError.LocalFailure(LocalFailureReason.ArchiveUnreadable)

            val endpoints = (storage["endpoints"] as? JsonArray).orEmpty()
            val endpoint = endpoints.firstNotNullOfOrNull { element ->
                val entry = element as? JsonObject ?: return@firstNotNullOfOrNull null
                val role = (entry["role"] as? JsonPrimitive)?.content
                val uri = (entry["uri"] as? JsonPrimitive)?.content
                if (role != "read-write" || uri.isNullOrBlank()) return@firstNotNullOfOrNull null
                val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return@firstNotNullOfOrNull null
                if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) return@firstNotNullOfOrNull null
                uri
            }

            val limits = storage["limits"] as? JsonObject
            fun JsonObject?.longField(key: String): Long? =
                (this?.get(key) as? JsonPrimitive)?.content?.toLongOrNull()

            return BackupOperatorManifest(
                componentId = manifest.componentId,
                backupProfileId = (storage["backupProfileId"] as? JsonPrimitive)?.content
                    ?: BackupProfile.PORTABLE_PROFILE_ID,
                implementationProfileId = (storage["implementationProfileId"] as? JsonPrimitive)?.content
                    ?: BackupProfile.IMPLEMENTATION_PROFILE_ID,
                endpoint = endpoint,
                declaredTermsDigest = (storage["declaredTerms"] as? JsonPrimitive)?.content
                    ?: throw BackupError.LocalFailure(LocalFailureReason.ArchiveUnreadable),
                entitlementIssuers = (storage["entitlementIssuers"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: emptyList(),
                maximumSealedSnapshotBytes = limits.longField("maximumSealedSnapshotBytes"),
                maximumRetainedSnapshots = limits.longField("maximumRetainedSnapshots"),
            )
        }
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
