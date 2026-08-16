package app.onym.android.moderation

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ─── Session requests (Android profile schemas, camelCase; absent
// optionals are omitted, never null) ─────────────────────────────────

/**
 * First-session enrollment. Every field the signature covers is
 * transmitted, so the backend can recompute `SignedSessionPayload`
 * and verify it. The integrity token is outside the signed payload
 * (it does not exist until after the requestHash is computed); it
 * binds to the same payload through the echoed hash.
 */
@Serializable
data class EnrollmentRequest(
    val userKey: String,
    val timestamp: String,
    /** Base64 of the backend-issued challenge bytes, single-use. */
    val challenge: String,
    /**
     * The Play Integrity token for this session, as Google returned
     * it. Absent only when the device has no usable Play environment —
     * this client never fabricates one.
     */
    val integrityToken: String? = null,
    /** Base64 Ed25519 signature over [SignedSessionPayload.enrollment]. */
    val signature: String,
)

@Serializable
data class DeviceEnrollment(val deviceBinding: String)

/**
 * One gate check: a fresh integrity token and an identity signature in
 * the same session — the profile's only permitted token↔enrollment
 * linkage (Moderation-Device-Recall.md §5.2).
 */
@Serializable
data class GateCheckRequest(
    val userKey: String,
    val mandateRef: String? = null,
    val timestamp: String,
    val challenge: String,
    val integrityToken: String? = null,
    val signature: String,
)

/** Just the signature: this client appends it to its own copy of the
 * mandate, so the round-trip cannot alter a consented field. */
@Serializable
data class InterfaceCountersignature(val signature: String)

/** `POST /v1/challenge` answer. The raw challenge bytes go into the
 * signed payload; the base64 string travels back verbatim so the
 * backend can recompute the payload. */
@Serializable
data class IssuedChallenge(
    val challenge: String,
    val expiresAt: String,
)

// ─── Gate result ─────────────────────────────────────────────────────

/**
 * Why a successful check still refuses to let the app operate. The
 * last four are decided by this client's local grace arithmetic; the
 * first three come from the backend. One shared vocabulary so both
 * ends name the same states.
 */
@Serializable
enum class CheckRequiredReason {
    @SerialName("tokenInvalid") TOKEN_INVALID,
    @SerialName("attestationUnavailable") ATTESTATION_UNAVAILABLE,
    @SerialName("reidentificationRequired") REIDENTIFICATION_REQUIRED,
    @SerialName("offlineGraceExpired") OFFLINE_GRACE_EXPIRED,
    @SerialName("neverChecked") NEVER_CHECKED,
    @SerialName("clockRollback") CLOCK_ROLLBACK,

    // Client-derived only — never in a backend body.
    @SerialName("backendRefused") BACKEND_REFUSED,
    @SerialName("enrollmentLost") ENROLLMENT_LOST,
}

@Serializable
data class BanState(
    val verdictRef: String,
    /**
     * The governing verdict as served, kept opaque: display validation
     * against the consented manifest (iOS's `VerdictValidator` +
     * `sanitize`) is a deferred seam, so the ban screen renders only
     * the flat fields below and never this document's narrative.
     */
    val verdict: JsonElement? = null,
    val authorityContact: String,
    val banExpires: String? = null,
    val appealUrl: String? = null,
    val newHolderUrl: String? = null,
)

@Serializable
data class CaseNotice(
    val noticeVersion: Int = 1,
    val caseId: String,
    val authority: String,
    val accused: String = "",
    val mandateRef: String = "",
    val classId: String = "",
    val evidenceSummary: String = "",
    val responseDeadline: String = "",
    val decisionDeadline: String = "",
    val signature: String = "",
)

/**
 * The backend's gate answer, internally tagged on `status` — the
 * Android profile's own result schema
 * (`onym-moderation-google-device-recall-gate-result-v1`).
 */
@Serializable(with = GateCheckResultSerializer::class)
sealed interface GateCheckResult {
    @Serializable
    data object Clear : GateCheckResult

    @Serializable
    data class CaseOpen(val notices: List<CaseNotice> = emptyList()) : GateCheckResult

    @Serializable
    data class Banned(val ban: BanState) : GateCheckResult

    @Serializable
    data class CheckRequired(val reason: CheckRequiredReason) : GateCheckResult
}

/**
 * Bidirectional `status`-tagged encoding: decoded from backend bodies,
 * and re-encoded verbatim when the last successful result is persisted
 * in [PersistedGateState].
 */
internal object GateCheckResultSerializer : KSerializer<GateCheckResult> {
    override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor(
        "app.onym.android.moderation.GateCheckResult",
    )

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): GateCheckResult {
        val input = decoder as kotlinx.serialization.json.JsonDecoder
        val element = input.decodeJsonElement().jsonObject
        val json = input.json
        return when (val status = element["status"]?.jsonPrimitive?.content) {
            "clear" -> GateCheckResult.Clear
            "caseOpen" -> GateCheckResult.CaseOpen(
                notices = element["notices"]?.let {
                    json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(CaseNotice.serializer()),
                        it,
                    )
                } ?: emptyList(),
            )
            "banned" -> GateCheckResult.Banned(
                ban = json.decodeFromJsonElement(
                    BanState.serializer(),
                    element["ban"] ?: throw IllegalArgumentException("banned result carries no ban"),
                ),
            )
            "checkRequired" -> GateCheckResult.CheckRequired(
                reason = json.decodeFromJsonElement(
                    CheckRequiredReason.serializer(),
                    element["reason"]
                        ?: throw IllegalArgumentException("checkRequired result carries no reason"),
                ),
            )
            else -> throw IllegalArgumentException("unknown gate result status: $status")
        }
    }

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: GateCheckResult,
    ) {
        val output = encoder as kotlinx.serialization.json.JsonEncoder
        val json = output.json
        val element = when (value) {
            is GateCheckResult.Clear -> JsonObject(mapOf("status" to tag("clear")))
            is GateCheckResult.CaseOpen -> JsonObject(
                mapOf(
                    "status" to tag("caseOpen"),
                    "notices" to json.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(CaseNotice.serializer()),
                        value.notices,
                    ),
                ),
            )
            is GateCheckResult.Banned -> JsonObject(
                mapOf(
                    "status" to tag("banned"),
                    "ban" to json.encodeToJsonElement(BanState.serializer(), value.ban),
                ),
            )
            is GateCheckResult.CheckRequired -> JsonObject(
                mapOf(
                    "status" to tag("checkRequired"),
                    "reason" to json.encodeToJsonElement(
                        CheckRequiredReason.serializer(),
                        value.reason,
                    ),
                ),
            )
        }
        output.encodeJsonElement(element)
    }

    private fun tag(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
}
