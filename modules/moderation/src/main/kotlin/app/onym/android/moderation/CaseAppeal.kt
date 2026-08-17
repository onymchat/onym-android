package app.onym.android.moderation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * An appeal filed against one case (Moderation.md §5.7) —
 * `POST /v1/cases/{caseId}/appeal`. The Android port of iOS's
 * `AppealSubmission`.
 *
 * [caseId] is signed as well as pathed: a conforming authority rejects
 * a path/body mismatch, and trusting either alone would reopen
 * cross-case replay — an appeal captured for one case could otherwise
 * be re-pathed at another.
 */
@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class AppealSubmission(
    val caseId: String,
    /** `@EncodeDefault` on none of these: all three are required on
     *  the wire, and `kind` in particular is echoed by the receipt, so
     *  a dropped field would change the signed bytes. */
    val kind: Kind,
    val statement: String,
    /** Base64 Ed25519 by the accused over [signingBytes]. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
    val signature: String = "",
) {
    /**
     * The wire vocabulary is fixed by the reference authority: the
     * spec's error identifier for the second case is
     * `new_holder_claim`, but request and echoed receipt both carry the
     * hyphenated form, and the reference echoes the submitted string
     * verbatim.
     */
    @Serializable
    enum class Kind {
        @SerialName("appeal")
        APPEAL,

        /**
         * A new-holder claim — the spec's mandatory appeal class with
         * expedited review. Present because the receipt's `kind` echo
         * must be comparable against it, and because an artifact that
         * omitted it would be unparseable if the claim path lands
         * later. Nothing on Android files one yet: the ban screen
         * still routes new-holder claims to the authority's URL, since
         * [BanState] carries no `caseId` to appeal against.
         */
        @SerialName("new-holder-claim")
        NEW_HOLDER_CLAIM,
    }

    /**
     * The bytes the accused signs: every field except `signature`,
     * canonical JSON. Same encode-then-structurally-drop idiom as
     * [ModerationReport.signingBytes], so the sort that orders
     * `caseId` before `kind` before `statement` is the shared one.
     */
    fun signingBytes(): ByteArray {
        val encoded = ModerationJson.json.encodeToJsonElement(serializer(), this) as JsonObject
        val unsigned = JsonObject(encoded.filterKeys { it != "signature" })
        return ModerationCanonicalJson.canonicalBytes(unsigned)
    }

    /**
     * The exact body `POST /v1/cases/{caseId}/appeal` receives.
     *
     * Canonical, not plain, JSON — iOS encodes the request through
     * `ModerationCanonicalEncoder` and the authority verifies the
     * signature over the same shape. Deterministic for a given value,
     * which is what lets a retry re-derive byte-identical content from
     * the persisted record rather than re-signing.
     */
    fun wireBytes(): ByteArray {
        val encoded = ModerationJson.json.encodeToJsonElement(serializer(), this) as JsonObject
        return ModerationCanonicalJson.canonicalBytes(encoded)
    }
}

/**
 * The authority's acknowledgment of a filed appeal.
 *
 * [filed] is not proof of much on the new-holder path — the reference
 * answers 200 with `filed: true` unconditionally there, deliberately
 * non-oracular, so only an authenticated status query can show that a
 * claim was recorded. For an ordinary appeal the endpoint is gated
 * server-side on a ban with an open appeal window, so a refusal
 * arrives as a 4xx rather than `filed: false`.
 */
@Serializable
data class AppealReceipt(
    val caseId: String,
    val filed: Boolean,
    val kind: String,
    val note: String? = null,
)

/**
 * One filed (or filing) appeal: the exact signed artifact plus what is
 * needed to replay it and to recognise a re-submission of the same
 * statement. Persisted BEFORE the first network attempt, so an
 * ambiguous failure never loses the only copy of what was signed.
 *
 * The ledger is also the idempotency record. The reference authority
 * does NOT deduplicate appeals — each delivery files a row — so
 * "same statement, same case → return the stored receipt" is the only
 * thing standing between a double-tap and two filings.
 */
@Serializable
data class CaseSubmissionRecord(
    /** Stable row identity, so a ledger update can never alias two
     *  rows that share a case, a user and a statement. */
    val id: String,
    val appeal: AppealSubmission,
    /** SHA-256 (lowercase hex) of the statement's UTF-8 bytes.
     *  Survives [redacted], and is what dedup matches on — it answers
     *  "is this the same statement?" without the ledger having to keep
     *  the statement. */
    val statementDigest: String,
    /** The mandate whose standing this appeal was filed under
     *  (SHA-256 hex of its signing bytes). */
    val mandateRef: String,
    /** `onym:key:<hex>` of the identity that filed — the same
     *  identity-removal purge contract as the report ledger. */
    val user: String,
    val authorityName: String,
    /** RFC 3339 UTC, seconds precision. */
    val createdAt: String,
    /** Set when the authority acknowledged the filing. */
    val receipt: AppealReceipt? = null,
) {
    val isResolved: Boolean get() = receipt != null

    /**
     * This record with the statement dropped — what a resolved filing
     * is stored as.
     *
     * The statement is the user's own account of an accusation against
     * them; once the filing has landed, retaining it would leave the
     * ledger an indefinite archive of it. iOS keeps its equivalent
     * store encrypted at rest instead; this module's ledgers are
     * DataStore blobs (see [DataStorePreferencesReportFilingStore]),
     * so redaction on resolve is how the same posture is reached.
     *
     * A resolved row is a receipt stub, never a filable artifact:
     * every delivery path short-circuits on [receipt] before reaching
     * the wire, and dedup runs off [statementDigest].
     */
    fun redacted(): CaseSubmissionRecord = copy(appeal = appeal.copy(statement = ""))
}

interface CaseSubmissionStore {
    suspend fun load(): List<CaseSubmissionRecord>
    suspend fun save(records: List<CaseSubmissionRecord>)
}

/**
 * One-domain-one-blob DataStore persistence, the module's convention
 * ([DataStorePreferencesMandateStore]).
 *
 * Corrupt-blob posture differs from the report ledger's on purpose: an
 * unreadable appeal ledger reads as "no records", and because the
 * authority does not deduplicate appeals, that risks a second filing
 * of the same statement rather than a harmless `duplicate: true`. It
 * is still the right trade — the alternative is refusing to appeal at
 * all — and a duplicate appeal is visible to the user (two rows) where
 * a lost one is not.
 */
class DataStorePreferencesCaseSubmissionStore(
    private val dataStore: DataStore<Preferences>,
) : CaseSubmissionStore {
    override suspend fun load(): List<CaseSubmissionRecord> {
        val raw = dataStore.data
            .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
            .first()[KEY] ?: return emptyList()
        return runCatching {
            ModerationJson.json.decodeFromString(
                ListSerializer(CaseSubmissionRecord.serializer()),
                raw,
            )
        }.getOrDefault(emptyList())
    }

    override suspend fun save(records: List<CaseSubmissionRecord>) {
        dataStore.edit { preferences ->
            preferences[KEY] = ModerationJson.json.encodeToString(
                ListSerializer(CaseSubmissionRecord.serializer()),
                records,
            )
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("moderation_case_submissions")
    }
}

/** Why an appeal could not be filed. Transport outcomes stay
 *  [AuthorityRejectedException] / [AuthorityUnreachableException], as
 *  on the report path — these are the local refusals. */
sealed class AppealFilingException(message: String) : Exception(message) {
    /** No active, countersigned, authority-registered mandate — or the
     *  appeal vertical isn't wired. An appeal cites standing the same
     *  way a report does. */
    class AppealsUnavailable :
        AppealFilingException("appealing requires an active registered mandate")

    /** The mandate's authority has no current directory listing. */
    class AuthorityUnavailable :
        AppealFilingException("the authority is not in the directory right now")

    /** Empty after trimming, or past [ModerationRepository.MAX_STATEMENT_BYTES]. */
    class StatementInvalid(message: String) : AppealFilingException(message)

    /**
     * The authority answered 200 but acknowledged a different case or
     * kind. Terminal, NOT retryable: the filing was accepted
     * server-side and the endpoint does not deduplicate, so replaying
     * would file a second appeal rather than resolve the first. The
     * artifact is discarded before this is thrown.
     */
    class AcknowledgedDifferentSubmission(message: String) : AppealFilingException(message)

    /**
     * The authority answered 200 with `filed: false`. Nonconforming — a
     * refusal is supposed to be a 4xx — so whether the row exists is
     * unknowable from here. Terminal for the same reason as
     * [AcknowledgedDifferentSubmission]: the artifact is discarded, and
     * resubmitting is not offered, because it may already be on file
     * against an endpoint that does not deduplicate.
     */
    class RefusedWithoutFiling(message: String) : AppealFilingException(message)
}
