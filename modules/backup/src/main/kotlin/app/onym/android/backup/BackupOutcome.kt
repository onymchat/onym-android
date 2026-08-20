package app.onym.android.backup

/**
 * The operator's declared action for one operation, per
 * onym-system `backup/UI-Backup.md` §5.7. `retained`/`alreadyRetained`
 * are the only classes an operator's own `retained` claim actually
 * describes — everything else is either transit state or an explicit
 * refusal/uncertainty, and a client must never render `unknown` as
 * success.
 *
 * Mirrors `BackupOutcomeStatus` in onym-ios OnymBackup.
 */
enum class BackupOutcomeStatus(val wireValue: String) {
    QueuedLocally("queued_locally"),
    Submitted("submitted"),
    Accepted("accepted"),
    Retained("retained"),
    AlreadyRetained("already_retained"),
    Rejected("rejected"),
    Erased("erased"),
    Unreachable("unreachable"),
    /** An unrecognised wire value decodes here — never silently
     *  becomes [Retained] or [Erased]. */
    Unknown("unknown");

    /** `retained` is not a restorability proof, a durability
     *  guarantee, or a statement about any copy the operator does not
     *  control — it is true only for the two classes that describe a
     *  currently-held snapshot. */
    val isRetention: Boolean
        get() = this == Retained || this == AlreadyRetained

    companion object {
        fun fromWireValue(value: String): BackupOutcomeStatus =
            entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

/** The adapter's return for one operation — the durable reference plus
 *  the operator's outcome. Locators, retention dates, and receipts are
 *  untrusted observations, not facts. */
data class BackupOutcome(
    val operationId: String,
    val snapshotReference: SnapshotReference,
    val status: BackupOutcomeStatus,
    /** Profile-specific location hint. Untrusted. */
    val locator: String? = null,
    val retainedUntil: java.time.Instant? = null,
    /** The terms the operator accepted this operation under. */
    val termsId: String? = null,
    /** Profile-defined receipt, opaque to the domain layer. */
    val evidence: String? = null,
)
