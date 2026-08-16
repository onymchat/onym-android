package app.onym.android.moderation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * One disclosed item of evidence (Moderation.md §5.4): content the
 * reporter legitimately received, with proof the accused authored it.
 * Content without an authenticity proof is a complaint, not evidence
 * — the authority refuses it.
 */
@Serializable
data class ReportEvidenceItem(
    /** The exact canonical proof preimage the sender signed
     * (`ChatModerationProof` on the chat side) — disclosed verbatim,
     * never re-encoded: the authority verifies [authenticityProof]
     * over these UTF-8 bytes as they arrive. */
    val disclosedContent: String,
    /** Base64 Ed25519 signature by the ACCUSED over
     * [disclosedContent]'s UTF-8 bytes. */
    val authenticityProof: String,
    /** Optional reporter-supplied context. Omitted (never `null`) when
     * absent — `ModerationJson` drops null optionals, which the signed
     * form depends on. */
    val context: String? = null,
)

/**
 * A signed report filing (Moderation.md §5.4) — `POST /v1/reports`.
 *
 * Immutable once signed: the authority stores the first filing's exact
 * bytes under `(reporter, reportId)` and refuses a different body for
 * the same pair, so retries must replay the persisted artifact
 * byte-for-byte ([ReportFilingRecord] holds it for exactly that).
 */
@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class ModerationReport(
    /** `@EncodeDefault`: `ModerationJson` drops defaulted properties,
     * and a report without `reportVersion` on the wire would sign
     * bytes the authority parses differently — the version must
     * always be present. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
    val reportVersion: Int = 1,
    val reportId: String,
    /** `onym:key:<hex>` — the reporter's identity key. */
    val reporter: String,
    /** The reporter's own `mandateRef` (SHA-256 hex of their mandate's
     * signing bytes) — proof of standing: reports are accepted only
     * from users who consented to this authority themselves. */
    val reporterMandate: String,
    /** `onym:key:<hex>` — the accused sender, as resolved from the
     * group's member profile. */
    val accused: String,
    val classId: String,
    val evidence: List<ReportEvidenceItem>,
    /** RFC 3339 UTC, seconds precision. */
    val filedAt: String,
    /** Base64 Ed25519 by [reporter] over [signingBytes]. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
    val signature: String = "",
) {
    /**
     * The bytes the reporter signs: every field except `signature`,
     * canonical JSON (UTF-8 byte-ordered keys — `reportId` and
     * `reportVersion` sort BEFORE `reporter`, which is exactly where a
     * case-insensitive sort would disagree and every signature would
     * fail). Same encode-then-structurally-drop idiom as
     * [ModerationMandate.signingBytes].
     */
    fun signingBytes(): ByteArray {
        val encoded = ModerationJson.json.encodeToJsonElement(serializer(), this) as JsonObject
        val unsigned = JsonObject(encoded.filterKeys { it != "signature" })
        return ModerationCanonicalJson.canonicalBytes(unsigned)
    }

    /** JSON bytes of the full signed report — the exact body
     * `POST /v1/reports` receives, and the artifact a retry replays. */
    fun wireBytes(): ByteArray =
        ModerationJson.json.encodeToString(serializer(), this).encodeToByteArray()
}

/**
 * `POST /v1/reports` receipt. A first filing carries `receivedAt` +
 * `intakeWeight`; an exact-byte replay of an already-attached filing
 * answers `duplicate = true` instead (and is success, not an error).
 */
@Serializable
data class ReportReceipt(
    val reportId: String,
    val receivedAt: String? = null,
    val caseId: String,
    val intakeWeight: Double? = null,
    val responseDeadline: String? = null,
    val decisionDeadline: String? = null,
    val duplicate: Boolean? = null,
)

/**
 * One filed (or filing) report with everything needed to replay it
 * byte-identically and to answer "was this message already reported":
 * persisted BEFORE the first network attempt, so a crash mid-delivery
 * never loses the signed artifact.
 */
@Serializable
data class ReportFilingRecord(
    /** Lowercase UUID of the reported chat message. */
    val sourceMessageId: String,
    /** The signed report, exactly as delivered (or owed). */
    val report: ModerationReport,
    val authorityName: String,
    /** Set when the authority acknowledged the filing. */
    val receipt: ReportReceipt? = null,
    /** The authority answered 409 — this exact `(reporter, reportId)`
     * is already on file with a case; treated as success without a
     * receipt of our own. */
    val resolvedWithoutReceipt: Boolean = false,
) {
    val isResolved: Boolean get() = receipt != null || resolvedWithoutReceipt
}

interface ReportFilingStore {
    suspend fun load(): List<ReportFilingRecord>
    suspend fun save(records: List<ReportFilingRecord>)
}

/** One-domain-one-blob DataStore persistence, the module's convention
 * ([DataStorePreferencesMandateStore]). Same corrupt-file posture: an
 * unreadable blob reads as "no records" — a lost ledger means a
 * duplicate filing at worst, which the authority answers with
 * `duplicate: true` or 409, both terminal successes. */
class DataStorePreferencesReportFilingStore(
    private val dataStore: DataStore<Preferences>,
) : ReportFilingStore {
    override suspend fun load(): List<ReportFilingRecord> {
        val raw = dataStore.data
            .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
            .first()[KEY] ?: return emptyList()
        return runCatching {
            ModerationJson.json.decodeFromString(ListSerializer(ReportFilingRecord.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    override suspend fun save(records: List<ReportFilingRecord>) {
        dataStore.edit { preferences ->
            preferences[KEY] = ModerationJson.json.encodeToString(
                ListSerializer(ReportFilingRecord.serializer()),
                records,
            )
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("moderation_reports")
    }
}
