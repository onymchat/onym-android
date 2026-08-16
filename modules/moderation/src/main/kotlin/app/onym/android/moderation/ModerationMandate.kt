package app.onym.android.moderation

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The consent artifact (Moderation.md §5.3): signed by the user at
 * consent, countersigned by the interface's enforcement backend,
 * binding exactly one identity–device pair to one authority under one
 * manifest hash. Immutable once signed — switching authorities means
 * signing a fresh mandate, never editing this one.
 */
@Serializable
data class ModerationMandate(
    val mandateVersion: Int = 1,
    /** `onym:key:<user-identity>`. */
    val user: String,
    /** `onym:component:<interface-id>` — this app. */
    val interface_: String,
    /** `onym:component:<authority-id>`. */
    val authority: String,
    /** SHA-256 (lowercase hex) of the exact manifest bytes consented to. */
    val manifestHash: String,
    val classes: List<String>,
    /**
     * Vendor-local opaque enrollment identifier issued by the
     * enforcement backend — NOT an integrity token (tokens are request
     * artifacts, unlinkable by design; profile §5.2).
     */
    val deviceBinding: String,
    val acceptedAt: String,
    /**
     * `[userSignature, interfaceSignature]`, base64. The interface
     * countersignature comes from the enforcement backend and is
     * appended to this client's own copy — the backend never hands
     * back a mandate, so no consented field can change behind the
     * user's signature.
     */
    val signatures: List<String> = emptyList(),
) {
    /**
     * The bytes the user (and later the interface) sign: every field
     * except `signatures`, canonical JSON. Built by encoding this
     * value and structurally dropping `signatures` — no string surgery
     * on already-encoded JSON.
     */
    fun signingBytes(): ByteArray {
        val encoded = ModerationJson.json.encodeToJsonElement(WireForm.of(this)) as JsonObject
        val unsigned = JsonObject(encoded.filterKeys { it != "signatures" })
        return ModerationCanonicalJson.canonicalBytes(unsigned)
    }

    /**
     * `mandateRef` as verdicts and notices carry it: SHA-256
     * (lowercase hex) over [signingBytes]. Signature-independent, so
     * the reference is stable across countersigning.
     */
    fun mandateHash(): String = sha256Hex(signingBytes())

    /** JSON bytes of the full mandate (signatures included) — what
     * `POST /v1/mandates/countersign` receives and what the record
     * persists. */
    fun wireBytes(): ByteArray =
        ModerationJson.json.encodeToString(WireForm.serializer(), WireForm.of(this))
            .encodeToByteArray()

    /**
     * `interface` is a Kotlin soft keyword usable as a name only with
     * backticks; the wire spelling lives on this private mirror so the
     * public type keeps an ordinary property name.
     */
    @Serializable
    private data class WireForm(
        val mandateVersion: Int,
        val user: String,
        @kotlinx.serialization.SerialName("interface")
        val interface1: String,
        val authority: String,
        val manifestHash: String,
        val classes: List<String>,
        val deviceBinding: String,
        val acceptedAt: String,
        val signatures: List<String>,
    ) {
        companion object {
            fun of(mandate: ModerationMandate) = WireForm(
                mandateVersion = mandate.mandateVersion,
                user = mandate.user,
                interface1 = mandate.interface_,
                authority = mandate.authority,
                manifestHash = mandate.manifestHash,
                classes = mandate.classes,
                deviceBinding = mandate.deviceBinding,
                acceptedAt = mandate.acceptedAt,
                signatures = mandate.signatures,
            )
        }
    }

    companion object {
        fun acceptedAtNow(clock: () -> Instant): String =
            Rfc3339InstantSerializer.format(clock())
    }
}
