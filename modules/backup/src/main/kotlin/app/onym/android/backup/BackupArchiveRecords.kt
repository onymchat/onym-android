package app.onym.android.backup

import kotlinx.serialization.Serializable

/**
 * Archive-record DTOs — deliberately **not** the app's real domain
 * types (`ChatGroup`, `Message`, ...) serialized directly. A dedicated
 * schema means attachment-shape churn or a Room migration in the chat
 * layer doesn't force an archive-format version bump, and a restore
 * written against an older archive schema still knows what it's
 * reading. Nested per-record shapes the app cares about (member list,
 * attachment descriptors, ...) travel as pre-serialized JSON strings
 * inside these records rather than as typed sub-fields, for the same
 * reason.
 *
 * The composer/restorer never construct these from — or into — the
 * app's live repository types directly; the app-side
 * `BackupSourceProviding`/`BackupSinkProviding` implementations (in
 * `:app`) are the only place that translation happens.
 *
 * Mirrors `BackupArchiveRecords.swift` in onym-ios OnymBackup.
 */
@Serializable
data class BackupGroupRecord(
    val groupId: String,
    val ownerIdentityId: String,
    /** Pre-serialized JSON: member list, roles, group secret epoch,
     *  and whatever else the group layer currently models. */
    val membersJson: String,
    val createdAtEpochSeconds: Long,
    val displayName: String? = null,
)

@Serializable
data class BackupMessageRecord(
    val messageId: String,
    val groupId: String,
    val ownerIdentityId: String,
    val senderPubkeyHex: String,
    val sentAtEpochSeconds: Long,
    /** The message body, plaintext. Named `bodyJson` for historical
     *  reasons in this schema — it is not itself JSON. */
    val bodyJson: String,
    /** Pre-serialized JSON, present only when the message carries an
     *  attachment. Its shape includes the `"sha256"` content address(es)
     *  `BackupComposer` walks structurally when [BackupMediaPolicy] is
     *  [BackupMediaPolicy.IncludeCiphertext]. */
    val attachmentJson: String? = null,
    /** `MessageDirection.name` — `"INCOMING"`/`"OUTGOING"`. */
    val direction: String = "INCOMING",
    /** `MessageStatus.name`. */
    val status: String = "RECEIVED",
    /** `SepGroupType.wireValue` — needed to reconstruct a `ChatMessage`
     *  without re-deriving it from the (possibly not-yet-restored)
     *  owning group. */
    val groupTypeWireValue: String = "anarchy",
    val replyToMessageId: String? = null,
)

@Serializable
data class BackupInvitationRecord(
    val invitationId: String,
    val groupId: String,
    val ownerIdentityId: String,
    val payloadJson: String,
)

@Serializable
data class BackupConsentRecord(
    val componentId: String,
    val manifestHash: String,
    val consentJson: String,
)

/** Ciphertext exactly as the blob/Blossom store holds it — never the
 *  device's decrypted media-cache bytes (those are keyed by public
 *  content address; carrying decrypted bytes in a backup would leak
 *  plaintext keyed by a guessable identifier). */
data class BackupBlobRecord(val sha256: String, val ciphertext: ByteArray)
