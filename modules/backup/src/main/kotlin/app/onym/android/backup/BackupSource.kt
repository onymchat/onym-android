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
 * What `BackupRestorer` writes to. Every method returns the count it
 * actually wrote — the restore summary is built from these returned
 * counts, never from the archive's row counts, so a sink that only
 * accepts some rows is reported honestly rather than assumed complete.
 *
 * Mirrors `BackupSinkProviding` in onym-ios OnymBackup.
 */
interface BackupSinkProviding {
    // Distinct names, not overloads: `List<T>` erases to the same JVM
    // signature across every T, so overloading `restore` here would
    // be a platform-declaration clash, not a Kotlin-only overload set.
    suspend fun restoreGroups(records: List<BackupGroupRecord>): Int
    suspend fun restoreMessages(records: List<BackupMessageRecord>): Int
    suspend fun restoreInvitations(records: List<BackupInvitationRecord>): Int
    suspend fun restoreConsents(records: List<BackupConsentRecord>): Int
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
     *  kind (e.g. `"groups" to 1`). */
    val skipped: Map<String, Int>,
    /** Content addresses the archive referenced (media policy
     *  [BackupMediaPolicy.IncludeCiphertext]) but did not carry.
     *  Always empty for a [BackupMediaPolicy.DescriptorsOnly] archive
     *  — a descriptors-only snapshot never "fails" to carry blobs it
     *  never promised. */
    val unresolvedBlobs: List<String>,
)
