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
        /**
         * The seat's fields live at the manifest's **top level**, next
         * to the spine `:foundation` already parsed — `backupProfileId`,
         * `implementationProfileId`, `endpoints`, `capabilities`,
         * `limits`, `declaredTerms`, `entitlementIssuers` — exactly as
         * onym-system `backup/UI-Backup.md` §5.3 lays the document out
         * and exactly as the operator publishes it
         * (`operator/src/documents.rs::manifest_document`). They are NOT
         * nested under a `storage.backup` object: `storage.backup` is
         * the value of the spine's `seat` field, not a container.
         *
         * A manifest missing `declaredTerms` is refused rather than
         * defaulted — the declared terms digest is what enrolment pins
         * and what every later upload is checked against, so there is
         * no safe fallback for it. The two profile ids DO fall back,
         * because this client only speaks one of each and a manifest
         * that omits them is asserting nothing this code could disagree
         * with.
         */
        fun from(manifest: SignedServiceManifest): BackupOperatorManifest {
            val obj = Json.parseToJsonElement(String(manifest.rawBytes, Charsets.UTF_8)).jsonObject

            val endpoints = (obj["endpoints"] as? JsonArray).orEmpty()
            val endpoint = endpoints.firstNotNullOfOrNull { element ->
                val entry = element as? JsonObject ?: return@firstNotNullOfOrNull null
                val role = (entry["role"] as? JsonPrimitive)?.content
                val uri = (entry["uri"] as? JsonPrimitive)?.content
                if (role != "read-write" || uri.isNullOrBlank()) return@firstNotNullOfOrNull null
                val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return@firstNotNullOfOrNull null
                if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) return@firstNotNullOfOrNull null
                uri
            }

            val limits = obj["limits"] as? JsonObject
            fun JsonObject?.longField(key: String): Long? =
                (this?.get(key) as? JsonPrimitive)?.content?.toLongOrNull()

            return BackupOperatorManifest(
                componentId = manifest.componentId,
                backupProfileId = (obj["backupProfileId"] as? JsonPrimitive)?.content
                    ?: BackupProfile.PORTABLE_PROFILE_ID,
                implementationProfileId = (obj["implementationProfileId"] as? JsonPrimitive)?.content
                    ?: BackupProfile.IMPLEMENTATION_PROFILE_ID,
                endpoint = endpoint,
                declaredTermsDigest = (obj["declaredTerms"] as? JsonPrimitive)?.content
                    ?: throw BackupError.LocalFailure(LocalFailureReason.ArchiveUnreadable),
                entitlementIssuers = (obj["entitlementIssuers"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: emptyList(),
                maximumSealedSnapshotBytes = limits.longField("maximumSealedSnapshotBytes"),
                maximumRetainedSnapshots = limits.longField("maximumRetainedSnapshots"),
            )
        }
    }
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
