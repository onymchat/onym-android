package app.onym.android.moderation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One entry in the interface's published authority directory. The
 * directory pins each authority's operator public key out-of-band
 * from its manifest: a MITM that swaps the hosted manifest cannot
 * also swap the key it must verify against.
 */
@Serializable
data class AuthorityListing(
    /** `onym:component:<authority-id>`. */
    val componentId: String,
    /** Human-readable name shown in the consent step. */
    val name: String,
    val manifestURL: String,
    /**
     * Base URL of the Authority HTTP API, explicitly designated by the
     * directory — never inferred from `manifestURL` (manifests may be
     * mirrored; this endpoint receives the user's signed mandate).
     */
    val apiBaseURL: String,
    /** The authority's Ed25519 operator public key, base64 raw bytes. */
    val operatorPublicKeyBase64: String,
)

/**
 * One violation class from an authority manifest, with the mandatory
 * terms every class must declare (Moderation.md §5.2 constraint 1).
 */
@Serializable
data class ViolationClass(
    val classId: String,
    val definition: String = "",
    val responseWindow: String = "",
    val decisionDeadline: String = "",
    /** `"permanent"` or a `P<n>D` duration. */
    val banTerm: String = "",
    val appealWindow: String = "",
    /** `"suspensive"` or `"non-suspensive"`. */
    val appealEffect: String = "",
    val lawfulReporting: String? = null,
)

/**
 * A moderation authority's published, signed manifest (Moderation.md
 * §5.2): the complete enumeration of its power. Decoded tolerantly —
 * the exact bytes, not this decoding, are what the mandate pins.
 */
@Serializable
data class AuthorityManifest(
    val version: Int = 1,
    val componentId: String,
    val seat: String = "moderation",
    /** `onym:key:<hex>` — the verdict/manifest signing key; must equal
     * the directory-pinned key byte for byte. */
    @SerialName("operator") val operatorKey: String,
    val moderationProfileId: String = "",
    val violationClasses: List<ViolationClass> = emptyList(),
    /** Procedure for a device's new holder — surfaced in the ban UX. */
    val newHolderAppeal: String? = null,
    val appellate: String? = null,
    /** Bounds new mandates and new cases, not live process. */
    val validUntil: String,
) {
    fun violationClass(id: String): ViolationClass? =
        violationClasses.firstOrNull { it.classId == id }
}

/**
 * The unit of consent: a decoded manifest together with the **exact
 * bytes** it was fetched as. `manifestHash` — SHA-256 over those exact
 * bytes — is what the mandate pins, and those same bytes are what the
 * authority's detached signature covers. Hashing fetched bytes rather
 * than a re-encoding is deliberate: the draft spec defines no
 * canonical JSON, and persisting `rawBytes` keeps the consented terms
 * displayable and re-verifiable even if the hosted file later changes.
 */
class SignedManifest internal constructor(
    val manifest: AuthorityManifest,
    val rawBytes: ByteArray,
) {
    val manifestHash: String = sha256Hex(rawBytes)

    companion object {
        /**
         * Test-fixture entry point — production instances come from
         * [AuthorityManifestFetcher], which verified the bytes.
         * `internal` is what keeps [ReviewedManifest]'s invariant
         * true: application code cannot pair decoded fields with
         * different raw bytes. The module's testFixtures compilation
         * is a friend of main, so the shared fakes still reach it.
         */
        internal fun forFixtures(manifest: AuthorityManifest, rawBytes: ByteArray): SignedManifest =
            SignedManifest(manifest, rawBytes)
    }
}

/**
 * The one-snapshot reviewed artifact (profile §5.1): what the human
 * reviewed is exactly what the mandate signs. The opacity that makes
 * this safe lives on [SignedManifest]'s internal constructor —
 * application code cannot pair decoded fields with different raw
 * bytes, so wrapping any [SignedManifest] (necessarily
 * fetcher-produced or a declared fixture) is sound, and consent
 * accepts only this wrapper so a refetch cannot be substituted for
 * the snapshot the human saw.
 */
class ReviewedManifest(val signedManifest: SignedManifest)

/** Human-readable extraction for the consent screen: pretty-printed
 * from the exact retained bytes (never a refetch). */
fun SignedManifest.displayJson(): String = try {
    val element = ModerationJson.json.parseToJsonElement(rawBytes.decodeToString())
    prettyJson.encodeToString(JsonElement.serializer(), element)
} catch (_: Exception) {
    rawBytes.decodeToString()
}

private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
