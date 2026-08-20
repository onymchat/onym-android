package app.onym.android.backup

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.chats.MessageRepository
import app.onym.android.foundation.PinnedConsentRecord
import app.onym.android.foundation.PinnedConsentStore
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupRepository
import app.onym.android.identity.IdentityId
import app.onym.android.persistence.IncomingInvitationRecord
import app.onym.android.persistence.IncomingInvitationStatus
import app.onym.android.persistence.InMemoryInvitationStore
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryMessageStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * A restore that lands rows on disk and tells nobody is the bug this
 * covers. [AppBackupSink] writes through the stores on purpose — that
 * is what re-encrypts every restored row under this device's at-rest
 * key rather than importing another device's database — so the caches
 * the chat list and the open thread read from are bypassed, and the
 * composition root has to make them catch up. These tests exercise the
 * sink against the real repositories and then the same
 * groups-then-messages refresh `BackupSeatComposer`'s `didRestore`
 * runs, rather than the closure itself, which is one line of wiring.
 *
 * They also pin what the sink *reports*: a row the device already
 * holds has landed, not failed, because the restore screen renders any
 * shortfall as "This version of the app couldn't restore…".
 *
 * Mirrors `BackupRestoreTests.swift` from onym-ios PR #293.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreVisibilityTest {

    private val owner = IdentityId("alice-uuid")
    private val groupId = "aa".repeat(32)

    // ─── the caches catch up ──────────────────────────────────────

    @Test
    fun restoredGroupReachesSnapshotsWithoutAnyOtherMutation() = runTest {
        val groupStore = InMemoryGroupStore()
        val groups = groupRepository(groupStore)
        groups.reload()
        assertTrue("nothing to show before the restore", groups.snapshots.value.isEmpty())

        sink(groupStore = groupStore).restoreGroups(listOf(groupRecord()))

        // The row is on disk and the roster does not know: this is
        // exactly the state QA hit on iOS, where the chat appeared
        // only once the person created an unrelated one and something
        // else refreshed the cache.
        assertTrue("the store write cannot reach the cache", groups.snapshots.value.isEmpty())

        groups.reload()
        assertEquals(1, groups.snapshots.value.size)
        assertEquals(groupId, groups.snapshots.value.single().id)
    }

    @Test
    fun restoredMessagesReachAThreadThatWasCachedEmpty() = runTest {
        val messageStore = InMemoryMessageStore()
        val messages = messageRepository(messageStore)
        val thread = messages.snapshots(groupId)
        assertTrue("the thread was opened before the restore", thread.value.isEmpty())

        sink(messageStore = messageStore).restoreMessages(listOf(messageRecord("hello")))

        assertTrue("the store write cannot reach the cache", thread.value.isEmpty())

        messages.reload()
        assertEquals("hello", thread.value.single().body)
    }

    @Test
    fun refreshingGroupsBeforeMessagesLeavesBothCurrent() = runTest {
        // The ordering `didRestore` fixes: the chat list resolves each
        // row's latest message, so messages refreshed into a roster
        // that does not hold their group yet are work thrown away.
        // Whatever the order costs, both must be current at the end of
        // it — that is what the summary screen is about to claim.
        val groupStore = InMemoryGroupStore()
        val messageStore = InMemoryMessageStore()
        val groups = groupRepository(groupStore)
        val messages = messageRepository(messageStore)
        val thread = messages.snapshots(groupId)

        val sink = sink(groupStore = groupStore, messageStore = messageStore)
        sink.restoreGroups(listOf(groupRecord()))
        sink.restoreMessages(listOf(messageRecord("restored")))

        groups.reload()
        messages.reload()

        assertEquals(1, groups.snapshots.value.size)
        assertEquals("restored", thread.value.single().body)
    }

    // ─── what the summary is allowed to claim ─────────────────────

    @Test
    fun restoringTwiceStillReportsEveryRowAsLanded() = runTest {
        // The second pass inserts nothing — every write is idempotent
        // by design. Counting only fresh inserts is what made a repeat
        // restore report 100% of the groups as unrestorable, having
        // restored all of them.
        val groupStore = InMemoryGroupStore()
        val messageStore = InMemoryMessageStore()
        val sink = sink(groupStore = groupStore, messageStore = messageStore)

        sink.restoreGroups(listOf(groupRecord()))
        sink.restoreMessages(listOf(messageRecord("hi")))

        val secondGroups = sink.restoreGroups(listOf(groupRecord()))
        val secondMessages = sink.restoreMessages(listOf(messageRecord("hi")))

        assertEquals(BackupSinkOutcome(landed = 1, unreadable = 0), secondGroups)
        assertEquals(BackupSinkOutcome(landed = 1, unreadable = 0), secondMessages)
    }

    @Test
    fun aRowThisBuildCannotDecodeIsStillReportedUnreadable() = runTest {
        // The other half of the same contract: loosening the count
        // must not stop the screen saying that part of someone's
        // history did not arrive.
        val sink = sink()

        val groups = sink.restoreGroups(listOf(groupRecord().copy(membersJson = "{not a group}")))
        val messages = sink.restoreMessages(listOf(messageRecord("hi").copy(status = "NOT_A_STATUS")))

        assertEquals(BackupSinkOutcome(landed = 0, unreadable = 1), groups)
        assertEquals(BackupSinkOutcome(landed = 0, unreadable = 1), messages)
    }

    @Test
    fun aConsentTheDeviceAlreadyHoldsIsNotReportedUnreadable() = runTest {
        // Every archive carries at least one consent the device holds
        // — you cannot reach an operator's snapshot without having
        // consented to that operator — so this was not an edge case:
        // an ordinary restore told people their own consents could not
        // be read.
        val consentStore = InMemoryConsentStore()
        val consent = consentRecord("onym:component:backup.example")
        consentStore.save(listOf(consent))

        val outcome = sink(consentStore = consentStore).restoreConsents(
            listOf(backupConsentRecord(consent)),
        )

        assertEquals(BackupSinkOutcome(landed = 1, unreadable = 0), outcome)
        assertEquals("nothing was added", 1, consentStore.load().size)
    }

    @Test
    fun aConsentThatWillNotDecodeIsReportedUnreadable() = runTest {
        val consentStore = InMemoryConsentStore()

        val outcome = sink(consentStore = consentStore).restoreConsents(
            listOf(BackupConsentRecord("onym:component:x", "sha256:" + "0".repeat(64), "{oh dear}")),
        )

        assertEquals(BackupSinkOutcome(landed = 0, unreadable = 1), outcome)
        assertTrue(consentStore.load().isEmpty())
    }

    @Test
    fun aNewConsentLandsInactive() = runTest {
        // Unchanged behaviour, asserted here because the merge was
        // rewritten around it: re-activating a restored consent would
        // silently re-select an operator the person never saw.
        val consentStore = InMemoryConsentStore()
        val consent = consentRecord("onym:component:backup.example")

        val outcome = sink(consentStore = consentStore).restoreConsents(
            listOf(backupConsentRecord(consent)),
        )

        assertEquals(BackupSinkOutcome(landed = 1, unreadable = 0), outcome)
        assertEquals(false, consentStore.load().single().isActive)
    }

    // ─── invitations: the row on the device wins ──────────────────

    @Test
    fun anAcceptedInvitationIsNeitherResetNorReportedUnreadable() = runTest {
        // The archive does not carry an invitation's status, so this
        // sink can only ever write `Pending`. It used to write it over
        // an invitation the holder had already accepted — the wired
        // store overwrites and *then* answers `false` — and then
        // report that same invitation as one this build could not
        // read. Both halves were wrong.
        val invitationStore = InMemoryInvitationStore()
        val accepted = IncomingInvitationRecord(
            id = "invite-1",
            payload = byteArrayOf(1, 2, 3),
            receivedAt = Instant.ofEpochSecond(1_700_000_000),
            status = IncomingInvitationStatus.Accepted,
            ownerIdentityIdString = owner.value,
        )
        invitationStore.save(accepted)

        val outcome = sink(invitationStore = invitationStore).restoreInvitations(
            listOf(
                BackupInvitationRecord(
                    invitationId = "invite-1",
                    groupId = "",
                    ownerIdentityId = owner.value,
                    payloadJson = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
                ),
            ),
        )

        assertEquals(BackupSinkOutcome(landed = 1, unreadable = 0), outcome)
        assertEquals(accepted, invitationStore.list().single())
    }

    @Test
    fun anInvitationTheDeviceDoesNotHaveIsWritten() = runTest {
        val invitationStore = InMemoryInvitationStore()

        val outcome = sink(invitationStore = invitationStore).restoreInvitations(
            listOf(
                BackupInvitationRecord(
                    invitationId = "invite-2",
                    groupId = "",
                    ownerIdentityId = owner.value,
                    payloadJson = Base64.getEncoder().encodeToString(byteArrayOf(9)),
                ),
            ),
        )

        assertEquals(BackupSinkOutcome(landed = 1, unreadable = 0), outcome)
        assertEquals(IncomingInvitationStatus.Pending, invitationStore.list().single().status)
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun sink(
        groupStore: InMemoryGroupStore = InMemoryGroupStore(),
        messageStore: InMemoryMessageStore = InMemoryMessageStore(),
        invitationStore: InMemoryInvitationStore = InMemoryInvitationStore(),
        consentStore: PinnedConsentStore = InMemoryConsentStore(),
    ) = AppBackupSink(
        groupStore = groupStore,
        messageStore = messageStore,
        invitationStore = invitationStore,
        consentStore = consentStore,
        blobWriter = { _, _ -> },
    )

    private fun groupRepository(store: InMemoryGroupStore) = GroupRepository(
        store = store,
        identity = FakeActiveIdentityProvider(initial = owner),
        scope = TestScope(UnconfinedTestDispatcher()),
    )

    private fun messageRepository(store: InMemoryMessageStore) = MessageRepository(
        store = store,
        identity = FakeActiveIdentityProvider(initial = owner),
        scope = TestScope(UnconfinedTestDispatcher()),
    )

    private fun groupRecord(): BackupGroupRecord {
        val group = ChatGroup(
            id = groupId,
            name = "Restored",
            groupSecret = ByteArray(32),
            createdAtMillis = 1_700_000_000_000L,
            members = emptyList(),
            epoch = 0uL,
            salt = ByteArray(32),
            commitment = null,
            tier = SepTier.SMALL,
            groupType = SepGroupType.TYRANNY,
            adminPubkeyHex = null,
            isPublishedOnChain = false,
            ownerIdentityId = owner.value,
        )
        return BackupGroupRecord(
            groupId = group.id,
            ownerIdentityId = group.ownerIdentityId,
            membersJson = Json.encodeToString(ChatGroup.serializer(), group),
            createdAtEpochSeconds = group.createdAtMillis / 1000,
            displayName = group.name,
        )
    }

    /** Stable id per body so a second restore of the "same" message is
     *  actually the same row, the way a repeated archive would be. */
    private fun messageRecord(body: String) = BackupMessageRecord(
        messageId = UUID.nameUUIDFromBytes(body.toByteArray()).toString(),
        groupId = groupId,
        ownerIdentityId = owner.value,
        senderPubkeyHex = "cc".repeat(48),
        sentAtEpochSeconds = 1_700_000_000L,
        bodyJson = body,
        direction = "INCOMING",
        status = "RECEIVED",
        groupTypeWireValue = SepGroupType.TYRANNY.wireValue,
    )

    /** [PinnedConsentRecord]'s constructor is `internal` to
     *  `:foundation`, so a test outside it builds one the only way
     *  anything else does — through its serializer. */
    private fun consentRecord(componentId: String): PinnedConsentRecord =
        Json.decodeFromString(
            PinnedConsentRecord.serializer(),
            """
            {
              "componentId": "$componentId",
              "seatType": "storage.backup",
              "manifestHash": "sha256:${"a".repeat(64)}",
              "manifestBytes": "${Base64.getEncoder().encodeToString("{}".toByteArray())}",
              "acceptedAt": "2026-01-01T00:00:00Z",
              "isActive": true
            }
            """.trimIndent(),
        )

    private fun backupConsentRecord(record: PinnedConsentRecord) = BackupConsentRecord(
        componentId = record.componentId,
        manifestHash = record.manifestHash,
        consentJson = Json.encodeToString(PinnedConsentRecord.serializer(), record),
    )

    private class InMemoryConsentStore : PinnedConsentStore {
        private var records = listOf<PinnedConsentRecord>()
        override suspend fun load(): List<PinnedConsentRecord> = records
        override suspend fun save(records: List<PinnedConsentRecord>) {
            this.records = records
        }
    }
}
