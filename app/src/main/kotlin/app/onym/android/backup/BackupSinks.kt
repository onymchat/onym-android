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
import app.onym.android.persistence.IncomingInvitationStatus
import app.onym.android.persistence.InvitationStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Writes restored rows through the app's existing repository/store
 * interfaces. Every write is `insertOrUpdate`/idempotent-on-id, so a
 * repeated restore converges rather than duplicating rows. A row that
 * fails to decode into the app's current shape is skipped, not
 * fatal — the whole archive was already fully verified before any
 * write began (see `BackupRestorer`'s gate 2), so a per-row failure
 * here means "this build doesn't understand this row's shape" (schema
 * drift), and refusing the entire restore over one bad row would cost
 * everything else.
 *
 * Mirrors onym-ios's `AppBackupSink`.
 */
class AppBackupSink(
    private val groupStore: GroupStore,
    private val messageStore: MessageStore,
    private val invitationStore: InvitationStore,
    private val consentStore: PinnedConsentStore,
    /** Writes one blob's ciphertext to wherever the live blob cache
     *  reads from, keyed by its sha256 — so a restored blob is
     *  indistinguishable from a freshly downloaded one to media
     *  loaders. Left as a closure rather than a hardcoded directory:
     *  the composition root owns that path. */
    private val blobWriter: suspend (sha256: String, ciphertext: ByteArray) -> Unit,
) : BackupSinkProviding {

    override suspend fun restoreGroups(records: List<BackupGroupRecord>): Int {
        var written = 0
        for (record in records) {
            val group = runCatching {
                Json.decodeFromString(ChatGroup.serializer(), record.membersJson)
            }.getOrNull() ?: continue
            if (groupStore.insertOrUpdate(group)) written++
        }
        return written
    }

    override suspend fun restoreMessages(records: List<BackupMessageRecord>): Int {
        var written = 0
        for (record in records) {
            val message = runCatching {
                ChatMessage(
                    id = UUID.fromString(record.messageId),
                    groupId = record.groupId,
                    ownerIdentityId = record.ownerIdentityId,
                    senderBlsPubkeyHex = record.senderPubkeyHex,
                    body = record.bodyJson,
                    sentAtMillis = record.sentAtEpochSeconds * 1000,
                    direction = MessageDirection.valueOf(record.direction),
                    status = MessageStatus.valueOf(record.status),
                    replyToMessageId = record.replyToMessageId?.let(UUID::fromString),
                    groupType = SepGroupType.entries.firstOrNull { it.wireValue == record.groupTypeWireValue }
                        ?: SepGroupType.ANARCHY,
                )
            }.getOrNull() ?: continue
            if (messageStore.insert(message)) written++
        }
        return written
    }

    override suspend fun restoreInvitations(records: List<BackupInvitationRecord>): Int {
        var written = 0
        for (record in records) {
            val payload = runCatching { Base64.getDecoder().decode(record.payloadJson) }.getOrNull() ?: continue
            val saved = runCatching {
                invitationStore.save(
                    IncomingInvitationRecord(
                        id = record.invitationId,
                        payload = payload,
                        receivedAt = Instant.now(),
                        status = IncomingInvitationStatus.Pending,
                        ownerIdentityIdString = record.ownerIdentityId,
                    ),
                )
            }.getOrDefault(false)
            if (saved) written++
        }
        return written
    }

    /**
     * Consent restore is special: a **failed** load of existing
     * consents must abort the merge rather than being treated as "no
     * existing consents" — a transient read error must never silently
     * replace every live consent with the restored (inactive) ones.
     * New consents dedup by `"<componentId>|<manifestHash>"` and
     * always land `isActive = false` — never auto-reactivated,
     * because re-selecting an operator silently would be a consent
     * violation.
     */
    override suspend fun restoreConsents(records: List<BackupConsentRecord>): Int {
        if (records.isEmpty()) return 0
        val existing = try {
            consentStore.load()
        } catch (_: Exception) {
            return 0
        }
        val existingKeys = existing.map { "${it.componentId}|${it.manifestHash}" }.toSet()

        val restored = records.mapNotNull { record ->
            runCatching {
                Json.decodeFromString(PinnedConsentRecord.serializer(), record.consentJson)
            }.getOrNull()
        }.filter { "${it.componentId}|${it.manifestHash}" !in existingKeys }
            .map(::forcedInactive)

        if (restored.isEmpty()) return 0
        consentStore.save(existing + restored)
        return restored.size
    }

    override suspend fun restoreBlob(record: BackupBlobRecord) {
        blobWriter(record.sha256, record.ciphertext)
    }

    /** [PinnedConsentRecord]'s primary constructor (and therefore its
     *  synthesized `copy()`) is `internal` to `:foundation`, so from
     *  `:app` the only route to a modified copy is through its own
     *  serializer: decode to a mutable JSON tree, flip `isActive`
     *  structurally, re-encode, re-decode. */
    private fun forcedInactive(record: PinnedConsentRecord): PinnedConsentRecord {
        val encoded = Json.encodeToString(PinnedConsentRecord.serializer(), record)
        val obj = Json.parseToJsonElement(encoded).jsonObject
        val mutated = kotlinx.serialization.json.JsonObject(
            obj.toMutableMap().apply {
                put("isActive", kotlinx.serialization.json.JsonPrimitive(false))
            },
        )
        return Json.decodeFromString(PinnedConsentRecord.serializer(), mutated.toString())
    }
}
