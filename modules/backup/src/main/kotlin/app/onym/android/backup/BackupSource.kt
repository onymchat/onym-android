package app.onym.android.backup

/** Whether a snapshot carries attachment ciphertext or only references
 *  to it. Recorded in the archive header so a restore can tell
 *  "descriptors-only by design" apart from "meant to carry blobs, some
 *  missing." */
enum class BackupMediaPolicy { DescriptorsOnly, IncludeCiphertext }

/** A blob store's answer for one content address. [Gone] is an
 *  explicit "not held" (e.g. HTTP 404/410) — expected, blob retention
 *  is shorter-lived by design. Any other failure must be thrown by the
 *  source, never folded into [Gone] — a flaky network must not
 *  silently produce an incomplete "successful" snapshot. */
sealed class BackupBlobAvailability {
    data class Available(val ciphertext: ByteArray) : BackupBlobAvailability()
    data object Gone : BackupBlobAvailability()
}

/**
 * What `BackupComposer` reads from. Holds **only** this interface —
 * no identity repository, no key store — so "seed material can never
 * enter a snapshot" is enforced by the type graph, not a remembered
 * rule.
 *
 * Mirrors `BackupSourceProviding` in onym-ios OnymBackup.
 */
interface BackupSourceProviding {
    suspend fun identityCount(): Int
    suspend fun groups(): List<BackupGroupRecord>
    suspend fun messages(groupId: String, ownerIdentityId: String): List<BackupMessageRecord>
    suspend fun invitations(): List<BackupInvitationRecord>
    suspend fun consents(): List<BackupConsentRecord>
    suspend fun blobCiphertext(sha256: String): BackupBlobAvailability
}

/**
 * What one kind of row did on its way into local state.
 *
 * Two numbers rather than one, because a single count cannot tell the
 * two reasons a row is not *newly inserted* apart, and the restore
 * screen says something very different about each:
 *
 *  - [landed] — the row is on this device now. Freshly inserted,
 *    updated in place, or already present and deliberately left
 *    alone: all three mean the history is here, which is the only
 *    question the summary asks.
 *  - [unreadable] — the row was handed over and did not land: a shape
 *    from a schema this build doesn't know, an id that won't parse, a
 *    store that refused the write. Worth saying out loud, because
 *    part of someone's history did not arrive.
 *
 * The previous shape was a single `Int` of rows written, which
 * `BackupRestorer` subtracted from the archive's own row count to get
 * "skipped". That subtraction is where the two meanings collapsed.
 * Every write on this path is idempotent on purpose — restoring twice,
 * or onto a device that has since received some of the same rows, is
 * meant to converge rather than duplicate — so "not newly inserted" is
 * the *ordinary* outcome, and the screen rendered every one of them as
 * "This version of the app couldn't restore…". Restoring the same
 * archive a second time reported 100% of the groups as unrestorable,
 * having in fact restored all of them.
 *
 * Only the sink knows which of the two happened, so only the sink may
 * say. Arithmetic above it cannot recover the distinction.
 *
 * Mirrors `BackupSinkOutcome` in onym-ios OnymBackup (PR #293).
 */
data class BackupSinkOutcome(
    /** Rows that are on the device now, however they got there. */
    val landed: Int,
    /** Rows handed over that this build could not put anywhere. */
    val unreadable: Int,
) {
    companion object {
        val None = BackupSinkOutcome(landed = 0, unreadable = 0)
    }
}

/**
 * What `BackupRestorer` writes to. Every method reports what actually
 * landed and what it could not read — the restore summary is built
 * from those, never from the archive's row counts, so a sink that only
 * accepts some rows is reported honestly rather than assumed complete,
 * and a sink handed a row the device already holds is not reported as
 * having failed on it. See [BackupSinkOutcome] for why one number
 * cannot carry both.
 *
 * Mirrors `BackupSinkProviding` in onym-ios OnymBackup.
 */
interface BackupSinkProviding {
    // Distinct names, not overloads: `List<T>` erases to the same JVM
    // signature across every T, so overloading `restore` here would
    // be a platform-declaration clash, not a Kotlin-only overload set.
    suspend fun restoreGroups(records: List<BackupGroupRecord>): BackupSinkOutcome
    suspend fun restoreMessages(records: List<BackupMessageRecord>): BackupSinkOutcome
    suspend fun restoreInvitations(records: List<BackupInvitationRecord>): BackupSinkOutcome
    suspend fun restoreConsents(records: List<BackupConsentRecord>): BackupSinkOutcome
    suspend fun restoreBlob(record: BackupBlobRecord)
}

/** What a restore actually did, built from what the sink reported
 *  writing — never from the archive's own row counts. */
data class BackupRestoreSummary(
    val groups: Int,
    val messages: Int,
    val invitations: Int,
    val consents: Int,
    val blobs: Int,
    /** Rows the archive held that the sink could not reconstruct, by
     *  kind (e.g. `"groups" to 1`) — [BackupSinkOutcome.unreadable] as
     *  the sink reported it, never `archiveCount - written`. A row the
     *  device already held is not in here: it landed. */
    val skipped: Map<String, Int>,
    /** Content addresses the archive referenced (media policy
     *  [BackupMediaPolicy.IncludeCiphertext]) but did not carry.
     *  Always empty for a [BackupMediaPolicy.DescriptorsOnly] archive
     *  — a descriptors-only snapshot never "fails" to carry blobs it
     *  never promised. */
    val unresolvedBlobs: List<String>,
)
