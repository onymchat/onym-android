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
 * **What each method counts.** A [BackupSinkOutcome], not a row count:
 * `landed` is "this row is on the device now", `unreadable` is "this
 * build could not put it anywhere". The distinction has to be made
 * here because nothing above can reconstruct it — see
 * [BackupSinkOutcome] and the comment on `BackupRestorer`'s `skipped`.
 *
 * **Why there is no pre-read of the stores here, unlike iOS.** iOS's
 * `AppBackupSink` reads each store's keys before writing, because
 * `SwiftDataGroupStore.insertOrUpdate` answers `false` for a row it
 * updated in place *and* for a row it could not encode, so the `Bool`
 * alone cannot say whether anything was persisted. Android's seams do
 * not have that ambiguity: [GroupStore.insertOrUpdate] is contractually
 * "`true` on insert, `false` on update" and [MessageStore.insert] is
 * "`true` on a fresh write, `false` when the row already exists" —
 * both persisted either way, and every implementation signals real
 * failure by throwing (Room, encryption) rather than by returning
 * `false`. So `true || false` is `landed` here, and a thrown failure
 * becomes `RestoreInterrupted` one layer up, which is the honest
 * report for it. Copying iOS's pre-read would be buying a defence
 * against an ambiguity this codebase's contracts don't have — and
 * paying a full `list()` of every group at restore time for it.
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

    override suspend fun restoreGroups(records: List<BackupGroupRecord>): BackupSinkOutcome {
        var landed = 0
        var unreadable = 0
        for (record in records) {
            val group = runCatching {
                Json.decodeFromString(ChatGroup.serializer(), record.membersJson)
            }.getOrNull()
            if (group == null) {
                // The one genuinely alarming case: a row this build
                // cannot reconstruct. Everything below is a row that
                // decoded fine and reached the store.
                unreadable++
                continue
            }
            // Both answers mean the group is on this device now:
            // `true` inserted it, `false` updated the row that was
            // already there. Counting only `true` is what made a
            // second restore of the same archive report every group as
            // unrestorable while restoring all of them.
            groupStore.insertOrUpdate(group)
            landed++
        }
        return BackupSinkOutcome(landed = landed, unreadable = unreadable)
    }

    override suspend fun restoreMessages(records: List<BackupMessageRecord>): BackupSinkOutcome {
        var landed = 0
        var unreadable = 0
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
                    // Skip the row on an unknown wire value, like every
                    // other undecodable field in this block (direction/
                    // status above) — never silently downgrade to a
                    // default group type. Matches RoomMessageStore's own
                    // skip-on-unknown handling of the same field.
                    groupType = SepGroupType.entries.firstOrNull { it.wireValue == record.groupTypeWireValue }
                        ?: return@runCatching null,
                )
            }.getOrNull()
            if (message == null) {
                unreadable++
                continue
            }
            // `false` here is a re-delivery / already-present row, not
            // a failure — `MessageStore.insert` is idempotent on
            // `(id, owner)` and throws if the write itself goes wrong.
            // Either way the message is in the thread.
            messageStore.insert(message)
            landed++
        }
        return BackupSinkOutcome(landed = landed, unreadable = unreadable)
    }

    /**
     * Invitations are the one kind restored with a **look before you
     * write**, and the reason is data loss rather than counting.
     *
     * The archive does not carry an invitation's status (see
     * `AppBackupSource.invitations` — only id, owner and payload
     * survive the round trip), so this sink can only ever write
     * `Pending` with a fresh `receivedAt`. `InvitationStore.save` is
     * documented as a dedup — `false` means "already present" — but
     * the wired [app.onym.android.persistence.InMemoryInvitationStore]
     * *overwrites the row* and then returns `false`. So a restore ran
     * over an invitation the holder had already accepted, silently put
     * it back to `Pending`, and then reported it as unrestorable.
     *
     * Reading the ids first fixes both halves: an invitation already
     * on the device is left exactly as it is and counted as landed —
     * its real status is better than the `Pending` this could invent —
     * and only a genuinely new one is written. Carrying the status in
     * the archive would be better still, but that is an archive-format
     * change, and no format change can justify clobbering live rows in
     * the meantime.
     */
    override suspend fun restoreInvitations(records: List<BackupInvitationRecord>): BackupSinkOutcome {
        var landed = 0
        var unreadable = 0
        val present = invitationStore.list().map { it.id }.toMutableSet()
        for (record in records) {
            val payload = runCatching { Base64.getDecoder().decode(record.payloadJson) }.getOrNull()
            if (payload == null) {
                unreadable++
                continue
            }
            if (record.invitationId in present) {
                landed++
                continue
            }
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
            }
            if (saved.isFailure) {
                // The store refused this row (encryption, SQLite). One
                // bad invitation must not abandon the rest, but the
                // person should be told it didn't arrive.
                unreadable++
            } else {
                // `true` inserted; `false` means something inserted it
                // between the `list()` above and here. Either way it's
                // on the device.
                present += record.invitationId
                landed++
            }
        }
        return BackupSinkOutcome(landed = landed, unreadable = unreadable)
    }

    /**
     * Consent restore is special twice over.
     *
     * **The merge.** New consents dedup by
     * `"<componentId>|<manifestHash>"` and always land
     * `isActive = false` — never auto-reactivated, because
     * re-selecting an operator silently would be a consent violation.
     * A **failed** load of the existing consents aborts the merge
     * rather than being read as "no existing consents": overwriting
     * live consents with the restored, inactive ones because a read
     * blipped would be the worst outcome available here. (Caveat worth
     * knowing: the wired
     * [app.onym.android.foundation.DataStorePreferencesPinnedConsentStore]
     * answers a *corrupt* blob with an empty list rather than by
     * throwing, so this guard only catches a load that actually
     * fails. Making corruption distinguishable from a fresh install is
     * a change to that seam's contract and to every caller of it, not
     * to this one.)
     *
     * **The count.** `landed` is every consent in the archive that
     * decoded, not just the ones this merge added. A consent already
     * on the device is the *normal* case — you cannot reach an
     * operator's snapshot without having consented to that operator,
     * so at least one consent in every archive is already held — and
     * reporting only the additions is what made a routine restore tell
     * someone that four of their own consents "couldn't be restored by
     * this version of the app". Only a consent that will not decode is
     * unreadable.
     */
    override suspend fun restoreConsents(records: List<BackupConsentRecord>): BackupSinkOutcome {
        if (records.isEmpty()) return BackupSinkOutcome.None
        val existing = try {
            consentStore.load()
        } catch (_: Exception) {
            // Nothing was merged, so nothing landed. Reporting the
            // archive's consents as unreadable overstates the cause
            // (the archive was fine; this device's store wasn't) but
            // not the effect, which is the part the screen is for.
            return BackupSinkOutcome(landed = 0, unreadable = records.size)
        }
        val existingKeys = existing.map { "${it.componentId}|${it.manifestHash}" }.toSet()

        val decoded = records.mapNotNull { record ->
            runCatching {
                Json.decodeFromString(PinnedConsentRecord.serializer(), record.consentJson)
            }.getOrNull()
        }
        val additions = decoded
            .filter { "${it.componentId}|${it.manifestHash}" !in existingKeys }
            .map(::forcedInactive)

        if (additions.isNotEmpty()) consentStore.save(existing + additions)
        return BackupSinkOutcome(landed = decoded.size, unreadable = records.size - decoded.size)
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
