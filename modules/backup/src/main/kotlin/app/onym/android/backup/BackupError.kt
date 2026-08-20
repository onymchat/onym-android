package app.onym.android.backup

/**
 * The backup domain's error vocabulary — the abstract-boundary error
 * table of onym-system `backup/UI-Backup.md` §13, plus the
 * object-HTTP profile's wire-code mapping (`UI-Backup-Object-HTTP.md`
 * §14) folded into one sealed hierarchy so callers switch on one
 * type regardless of where a failure originated.
 *
 * Mirrors `BackupError` in onym-ios OnymBackup 1:1 — same cases, same
 * semantics.
 */
sealed class BackupError(message: String) : Exception(message) {

    object UnsupportedProfile : BackupError("operator does not implement this backup profile")
    object InvalidEndpoint : BackupError("operator endpoint is malformed or not HTTPS")
    object InvalidReference : BackupError("snapshot reference is malformed or mismatched")
    object TermsUnavailable : BackupError("operator terms could not be fetched or verified")
    class TermsChanged(val currentTermsId: String) :
        BackupError("operator's current terms ($currentTermsId) differ from what was accepted")
    class TermsRegression(val field: String) :
        BackupError("proposed terms are weaker than the pinned terms on field '$field'")
    object AccessRefused : BackupError("access proof was rejected")
    class PaymentRequired(
        val componentId: String,
        val offerIds: List<String>,
        val entitlementIssuers: List<String>,
    ) : BackupError("operator requires a valid entitlement")
    class InvalidEntitlement(val entitlementIssuers: List<String>) :
        BackupError("presented entitlement is malformed, wrong audience/subject, expired, or revoked")
    class SnapshotTooLarge(val maximumSealedSnapshotBytes: Long) :
        BackupError("sealed snapshot exceeds the operator's declared maximum ($maximumSealedSnapshotBytes bytes)")
    class QuotaExceeded(
        val retainedSnapshots: Long,
        val maximumRetainedSnapshots: Long,
        val retainedBytes: Long,
        val limitBytes: Long,
    ) : BackupError("operator's retained-snapshot quota is exhausted")
    object IncompleteSnapshot : BackupError("snapshot is partial, tampered, or fails verification")
    object RetentionExpired : BackupError("snapshot was retained once but is no longer held")
    class ErasureUnconfirmed(val receipt: ErasureReceipt) :
        BackupError("erasure was acknowledged but its completion deadline has not yet passed")
    object ExportWithheld : BackupError("operator withheld export — non-conforming")
    object OperatorUnavailable : BackupError("operator is unreachable")
    class Rejected(val code: String, val operatorMessage: String) :
        BackupError("operator rejected the request: $code — $operatorMessage")
    class LocalFailure(val reason: LocalFailureReason) :
        BackupError("local failure: $reason")

    /** True only for failures worth an automatic retry. Deliberately
     *  excludes [AccessRefused] — a bad signature or a stale clock
     *  would otherwise retry forever. */
    val isRetriable: Boolean
        get() = this is OperatorUnavailable || this is PaymentRequired
}

/** Failures that never left the device — no operator was involved. */
enum class LocalFailureReason {
    NoRecoveryPhrase,
    SealedBytesUnavailable,
    ArchiveUnreadable,
    IneligibleContent,
    KeyMaterialInvalid,
    ArchiveWriterFinished,
    RestoreInterrupted,
    StateUnreadable,
    InvalidUploadGrant,
}
