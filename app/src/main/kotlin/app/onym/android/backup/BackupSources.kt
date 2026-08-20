package app.onym.android.backup

import app.onym.android.chain.SepGroupType
import app.onym.android.chats.ChatMessage
import app.onym.android.chats.MessageDirection
import app.onym.android.chats.MessageStatus
import app.onym.android.chats.MessageStore
import app.onym.android.foundation.PinnedConsentRecord
import app.onym.android.foundation.PinnedConsentStore
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupStore
import app.onym.android.persistence.IncomingInvitationRecord
import app.onym.android.persistence.InvitationStore
import app.onym.android.transport.blossom.BlobNotFoundException
import app.onym.android.transport.blossom.BlossomClient
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID

/**
 * Reads through the app's existing repository/store interfaces —
 * never raw DB rows — the same posture as onym-ios's `AppBackupSource`.
 * Composed only from these seams (never `IdentityRepository`), so
 * "seed material can never enter a snapshot" is enforced by
 * `BackupComposer`'s own constructor shape one layer up.
 *
 * `ChatGroup` round-trips through its own `@Serializable` form
 * exactly — no lossy re-modeling. `ChatMessage` isn't `@Serializable`,
 * so [BackupMessageRecord] carries the handful of extra fields
 * (direction, status, groupType) needed to reconstruct one without
 * guessing, mirroring the shape `RoomMessageStore`'s own row-decode
 * path already reconstructs.
 */
class AppBackupSource(
    private val groupStore: GroupStore,
    private val messageStore: MessageStore,
    private val invitationStore: InvitationStore,
    private val consentStore: PinnedConsentStore,
    /** `null` when no Blossom server is configured — every blob
     *  resolves to [app.onym.android.backup.BackupBlobAvailability.Gone]
     *  rather than throwing. */
    private val blossomClient: BlossomClient?,
    private val identityCountProvider: suspend () -> Int,
) : BackupSourceProviding {

    override suspend fun identityCount(): Int = identityCountProvider()

    override suspend fun groups(): List<BackupGroupRecord> =
        groupStore.list().map { group ->
            BackupGroupRecord(
                groupId = group.id,
                ownerIdentityId = group.ownerIdentityId,
                membersJson = Json.encodeToString(ChatGroup.serializer(), group),
                createdAtEpochSeconds = group.createdAtMillis / 1000,
                displayName = group.name,
            )
        }

    override suspend fun messages(groupId: String, ownerIdentityId: String): List<BackupMessageRecord> =
        messageStore.listForGroup(ownerIdentityId, groupId).map { message ->
            BackupMessageRecord(
                messageId = message.id.toString(),
                groupId = message.groupId,
                ownerIdentityId = message.ownerIdentityId,
                senderPubkeyHex = message.senderBlsPubkeyHex,
                sentAtEpochSeconds = message.sentAtMillis / 1000,
                bodyJson = message.body,
                attachmentJson = encodeAttachments(message),
                direction = message.direction.name,
                status = message.status.name,
                groupTypeWireValue = message.groupType.wireValue,
                replyToMessageId = message.replyToMessageId?.toString(),
            )
        }

    override suspend fun invitations(): List<BackupInvitationRecord> =
        invitationStore.list().map { record ->
            BackupInvitationRecord(
                invitationId = record.id,
                // IncomingInvitationRecord doesn't carry its target
                // group id directly (it's derived from the payload at
                // accept time) — left blank; the sink restores the
                // payload verbatim and doesn't need this to be set.
                groupId = "",
                ownerIdentityId = record.ownerIdentityIdString,
                payloadJson = Base64.getEncoder().encodeToString(record.payload),
            )
        }

    override suspend fun consents(): List<BackupConsentRecord> =
        consentStore.load().map { record ->
            BackupConsentRecord(
                componentId = record.componentId,
                manifestHash = record.manifestHash,
                consentJson = Json.encodeToString(PinnedConsentRecord.serializer(), record),
            )
        }

    override suspend fun blobCiphertext(sha256: String): app.onym.android.backup.BackupBlobAvailability {
        val client = blossomClient ?: return app.onym.android.backup.BackupBlobAvailability.Gone
        return try {
            app.onym.android.backup.BackupBlobAvailability.Available(client.download(sha256))
        } catch (_: BlobNotFoundException) {
            app.onym.android.backup.BackupBlobAvailability.Gone
        }
    }

    /** Walks the message's typed attachment fields into the generic
     *  `{"sha256": "..."}`-bearing JSON shape [BackupComposer] scans
     *  structurally. Only image/video/album/voice attachments carry a
     *  content address today; extending this when a new attachment
     *  kind gains one is a one-line addition here, not a `:backup`
     *  change. */
    private fun encodeAttachments(message: ChatMessage): String? {
        val addresses = buildList {
            message.imageAttachment?.sha256?.let(::add)
            message.videoAttachment?.sha256?.let(::add)
            message.voiceAttachment?.sha256?.let(::add)
            message.albumAttachments?.forEach { media ->
                media.image?.sha256?.let(::add)
                media.video?.sha256?.let(::add)
            }
        }
        if (addresses.isEmpty()) return null
        return "{\"sha256\":[" + addresses.joinToString(",") { "\"$it\"" } + "]}"
    }
}
