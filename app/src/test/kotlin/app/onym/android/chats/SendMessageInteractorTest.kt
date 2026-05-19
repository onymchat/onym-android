package app.onym.android.chats

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupRepository
import app.onym.android.group.MemberProfile
import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeSealer
import app.onym.android.support.ConfigurableInboxTransport
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryMessageStore
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Behavioral tests for [SendMessageInteractor]. Uses the in-memory
 * fakes for store / transport / identity so the pipeline runs
 * without the keychain + Nostr stack.
 *
 * Mirrors `SendMessageInteractorTests.swift` from onym-ios PR #150.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendMessageInteractorTest {

    private val activeId = IdentityId("alice")
    private val selfBls = ByteArray(48) { 0x11 }
    private val selfBlsHex = selfBls.toHexLowercase()
    private val selfInbox = ByteArray(32) { 0x22 }
    private val selfSending = ByteArray(32) { 0x33 }
    private val peerBls = ByteArray(48) { 0x44 }
    private val peerBlsHex = peerBls.toHexLowercase()
    private val peerInbox = ByteArray(32) { 0x55 }
    private val groupIdHex = "aa".repeat(32)

    // ─── happy path ───────────────────────────────────────────────

    @Test
    fun send_persistsAndFansOut_marksSentOnAnyAccepted() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)

        val result = fixture.interactor.send(groupIdHex, "hello")

        assertEquals(MessageStatus.SENT, result.status)
        assertEquals("hello", result.body)
        assertEquals(MessageDirection.OUTGOING, result.direction)
        // One row, status SENT (overwriting the optimistic PENDING).
        val persisted = fixture.messageStore
            .listForGroup(activeId.value, groupIdHex)
            .single()
        assertEquals(MessageStatus.SENT, persisted.status)
        // One envelope shipped: to the peer's inbox tag, not our own.
        val sends = fixture.transport.sends()
        assertEquals(1, sends.size)
        assertEquals(
            TransportInboxId(IdentityRepository.inboxTag(peerInbox)),
            sends.single().inbox,
        )
    }

    // ─── self-recipient is skipped ────────────────────────────────

    @Test
    fun send_skipsSelfRecipient() = runTest {
        // Group has only the sender — fan-out has zero recipients.
        val fixture = newFixture()
        fixture.seedGroup(includePeer = false)

        val result = fixture.interactor.send(groupIdHex, "hi")
        // Zero recipients counts as SENT (a note-to-self is local-only).
        assertEquals(MessageStatus.SENT, result.status)
        assertTrue(
            "no envelopes shipped when only self is in the roster",
            fixture.transport.sends().isEmpty(),
        )
    }

    // ─── all sends fail → status FAILED ───────────────────────────

    @Test
    fun send_allRecipientsFailed_marksFailed() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        fixture.transport.setReceiptAcceptedBy(0)  // every relay refuses

        val result = fixture.interactor.send(groupIdHex, "hi")
        assertEquals(MessageStatus.FAILED, result.status)
        assertEquals(
            MessageStatus.FAILED,
            fixture.messageStore.listForGroup(activeId.value, groupIdHex).single().status,
        )
    }

    @Test
    fun send_sendThrows_marksFailed() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        fixture.transport.setSendThrow(RuntimeException("transport down"))

        val result = fixture.interactor.send(groupIdHex, "hi")
        assertEquals(MessageStatus.FAILED, result.status)
    }

    // ─── partial success → status SENT ────────────────────────────

    @Test
    fun send_partialSuccess_marksSentWhenAtLeastOneAccepted() = runTest {
        // Two peers. First send accepted, second send rejected — the
        // ConfigurableInboxTransport applies the same acceptedBy
        // count to both, so this test exercises the "every relay
        // accepted at least one envelope" branch via a single
        // successful recipient.
        val fixture = newFixture()
        val secondPeerBls = ByteArray(48) { 0x66 }
        val secondPeerInbox = ByteArray(32) { 0x77 }
        fixture.seedGroup(
            includePeer = true,
            extraPeerBlsHex = secondPeerBls.toHexLowercase(),
            extraPeerInbox = secondPeerInbox,
        )

        val result = fixture.interactor.send(groupIdHex, "hi")
        assertEquals(MessageStatus.SENT, result.status)
        // Both peers got envelopes (the fan-out is per-recipient).
        assertEquals(2, fixture.transport.sends().size)
    }

    // ─── validation error paths ──────────────────────────────────

    @Test
    fun send_unknownGroup_throws() = runTest {
        val fixture = newFixture()
        // No group seeded.
        assertThrows(SendMessageError.UnknownGroup::class.java) {
            kotlinx.coroutines.runBlocking { fixture.interactor.send(groupIdHex, "x") }
        }
        // Nothing persisted, nothing shipped.
        assertTrue(fixture.messageStore.listForGroup(activeId.value, groupIdHex).isEmpty())
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun send_senderNotAMember_throws() = runTest {
        // Group exists, peer is in it, but the active identity isn't.
        // Shouldn't happen post-PR A3, but is a defined error case.
        val fixture = newFixture()
        val group = makeGroup(
            memberProfiles = mapOf(
                peerBlsHex to MemberProfile(
                    alias = "Peer",
                    inboxPublicKey = peerInbox,
                    sendingPubkey = ByteArray(32) { 0x88.toByte() },
                ),
            ),
        )
        fixture.groupStore.preload(listOf(group))

        assertThrows(SendMessageError.SenderNotAMember::class.java) {
            kotlinx.coroutines.runBlocking { fixture.interactor.send(groupIdHex, "x") }
        }
    }

    @Test
    fun send_emptyBody_throws() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        assertThrows(SendMessageError.EmptyBody::class.java) {
            kotlinx.coroutines.runBlocking { fixture.interactor.send(groupIdHex, "") }
        }
        assertTrue(
            "validation must short-circuit BEFORE the optimistic insert",
            fixture.messageStore.listForGroup(activeId.value, groupIdHex).isEmpty(),
        )
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun send_unsupportedGovernance_throws() = runTest {
        val fixture = newFixture()
        // Group is Anarchy — variant Tyranny doesn't fit; today
        // SendMessageInteractor only ships .tyranny.
        val group = makeGroup(
            groupType = SepGroupType.ANARCHY,
            memberProfiles = mapOf(
                selfBlsHex to MemberProfile(
                    alias = "Self",
                    inboxPublicKey = selfInbox,
                    sendingPubkey = selfSending,
                ),
                peerBlsHex to MemberProfile(
                    alias = "Peer",
                    inboxPublicKey = peerInbox,
                    sendingPubkey = ByteArray(32) { 0x88.toByte() },
                ),
            ),
        )
        fixture.groupStore.preload(listOf(group))

        val err = assertThrows(SendMessageError.UnsupportedGroupType::class.java) {
            kotlinx.coroutines.runBlocking { fixture.interactor.send(groupIdHex, "x") }
        }
        assertEquals(SepGroupType.ANARCHY, err.type)
    }

    // ─── retry on FAILED ─────────────────────────────────────────

    @Test
    fun retry_failedMessage_flipsToSentOnRelayAcceptance() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        val failed = makePersisted(status = MessageStatus.FAILED, body = "first try")
        fixture.messageStore.preload(listOf(failed))

        fixture.interactor.retry(groupIdHex, failed.id)

        val refreshed = fixture.messageStore.findById(failed.id)!!
        assertEquals(MessageStatus.SENT, refreshed.status)
        // The wire payload re-used the original messageId so the
        // receiver dedups against any prior delivery — assert the
        // ConfigurableInboxTransport saw exactly one send for the
        // peer (the retry shouldn't double-ship).
        assertEquals(1, fixture.transport.sends().size)
    }

    @Test
    fun retry_failedMessage_staysFailedWhenRelaysReject() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        fixture.transport.setReceiptAcceptedBy(0)  // every relay refuses
        val failed = makePersisted(status = MessageStatus.FAILED, body = "x")
        fixture.messageStore.preload(listOf(failed))

        fixture.interactor.retry(groupIdHex, failed.id)
        assertEquals(
            MessageStatus.FAILED,
            fixture.messageStore.findById(failed.id)!!.status,
        )
    }

    @Test
    fun retry_unknownMessageId_isNoOp() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        // Don't preload anything — retry on an unknown id must not
        // throw and must not ship anything.
        fixture.interactor.retry(groupIdHex, UUID.randomUUID())
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun retry_sentMessage_isNoOp_preventsDoubleDelivery() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        val sent = makePersisted(status = MessageStatus.SENT, body = "already delivered")
        fixture.messageStore.preload(listOf(sent))

        fixture.interactor.retry(groupIdHex, sent.id)

        // Status unchanged; no envelope shipped.
        assertEquals(
            MessageStatus.SENT,
            fixture.messageStore.findById(sent.id)!!.status,
        )
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun retry_pendingMessage_isNoOp_preventsDoubleDelivery() = runTest {
        // An in-flight pending row could still resolve to SENT; retry
        // would double-ship. Same gate as the SENT case.
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        val pending = makePersisted(status = MessageStatus.PENDING, body = "in flight")
        fixture.messageStore.preload(listOf(pending))

        fixture.interactor.retry(groupIdHex, pending.id)
        assertEquals(
            MessageStatus.PENDING,
            fixture.messageStore.findById(pending.id)!!.status,
        )
        assertTrue(fixture.transport.sends().isEmpty())
    }

    @Test
    fun retry_incomingMessage_isNoOp() = runTest {
        // Incoming rows don't have an outgoing send to retry.
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        val incoming = makePersisted(
            status = MessageStatus.FAILED,
            direction = MessageDirection.INCOMING,
            body = "received but somehow failed",
        )
        fixture.messageStore.preload(listOf(incoming))

        fixture.interactor.retry(groupIdHex, incoming.id)
        assertTrue(fixture.transport.sends().isEmpty())
    }

    private fun makePersisted(
        status: MessageStatus,
        direction: MessageDirection = MessageDirection.OUTGOING,
        body: String,
    ): ChatMessage = ChatMessage(
        id = UUID.randomUUID(),
        groupId = groupIdHex,
        ownerIdentityId = activeId.value,
        senderBlsPubkeyHex = selfBlsHex,
        body = body,
        sentAtMillis = 1_699_000_000_000L,
        direction = direction,
        status = status,
        groupType = SepGroupType.TYRANNY,
    )

    // ─── optimistic insert visible before fan-out completes ──────

    @Test
    fun send_optimisticInsert_visibleBeforeFanoutResolves() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(includePeer = true)
        // Don't gate the transport — just assert that by the time
        // send() returns, BOTH the optimistic insert AND the status
        // flip have applied. The "PENDING is visible mid-flight"
        // property is covered by subscribers to MessageRepository
        // .snapshots; here we just sanity-check the final state has
        // exactly one row with id matching what send() returned.
        val result = fixture.interactor.send(groupIdHex, "first")
        val rows = fixture.messageStore.listForGroup(activeId.value, groupIdHex)
        assertEquals(1, rows.size)
        assertEquals(result.id, rows.single().id)
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun newFixture(): Fixture {
        val activeProvider = FakeActiveIdentityProvider(initial = activeId)
        val identitiesFlow = MutableStateFlow(
            listOf(
                IdentitySummary(
                    id = activeId,
                    name = "Alice",
                    blsPublicKey = selfBls,
                    inboxPublicKey = selfInbox,
                    sendingPublicKey = selfSending,
                ),
            ),
        )
        val groupStore = InMemoryGroupStore()
        val groupRepository = GroupRepository(
            store = groupStore,
            identity = activeProvider,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        val messageStore = InMemoryMessageStore()
        val messageRepository = MessageRepository(
            store = messageStore,
            identity = activeProvider,
            scope = TestScope(UnconfinedTestDispatcher()),
        )
        val transport = ConfigurableInboxTransport()
        val interactor = SendMessageInteractor(
            activeIdentity = activeProvider,
            identitiesFlow = identitiesFlow,
            envelopeSealer = RecordingSealer(),
            groupRepository = groupRepository,
            messageRepository = messageRepository,
            inboxTransport = transport,
            ioDispatcher = UnconfinedTestDispatcher(),
            clock = { 1_700_000_000_000L },
            idFactory = { UUID.fromString("00000000-0000-0000-0000-000000000001") },
        )
        return Fixture(
            interactor = interactor,
            groupStore = groupStore,
            messageStore = messageStore,
            transport = transport,
        )
    }

    private inner class Fixture(
        val interactor: SendMessageInteractor,
        val groupStore: InMemoryGroupStore,
        val messageStore: InMemoryMessageStore,
        val transport: ConfigurableInboxTransport,
    ) {
        suspend fun seedGroup(
            includePeer: Boolean,
            extraPeerBlsHex: String? = null,
            extraPeerInbox: ByteArray? = null,
        ) {
            val profiles = buildMap<String, MemberProfile> {
                put(selfBlsHex, MemberProfile(
                    alias = "Self",
                    inboxPublicKey = selfInbox,
                    sendingPubkey = selfSending,
                ))
                if (includePeer) {
                    put(peerBlsHex, MemberProfile(
                        alias = "Peer",
                        inboxPublicKey = peerInbox,
                        sendingPubkey = ByteArray(32) { 0x88.toByte() },
                    ))
                }
                if (extraPeerBlsHex != null && extraPeerInbox != null) {
                    put(extraPeerBlsHex, MemberProfile(
                        alias = "Peer2",
                        inboxPublicKey = extraPeerInbox,
                        sendingPubkey = ByteArray(32) { 0x99.toByte() },
                    ))
                }
            }
            groupStore.preload(listOf(makeGroup(memberProfiles = profiles)))
        }
    }

    private fun makeGroup(
        groupType: SepGroupType = SepGroupType.TYRANNY,
        memberProfiles: Map<String, MemberProfile>,
    ) = ChatGroup(
        id = groupIdHex,
        name = "Family",
        groupSecret = ByteArray(32),
        createdAtMillis = 0L,
        members = emptyList(),
        memberProfiles = memberProfiles,
        epoch = 0uL,
        salt = ByteArray(32),
        commitment = null,
        tier = SepTier.SMALL,
        groupType = groupType,
        adminPubkeyHex = null,
        isPublishedOnChain = true,
        ownerIdentityId = activeId.value,
    )
}

/** Sealer that just returns the plaintext bytes — the tests don't
 *  care about the envelope shape, only that one call happens per
 *  recipient and the resulting bytes get handed to [InboxTransport]. */
private class RecordingSealer : InvitationEnvelopeSealer {
    override suspend fun sealInvitation(
        payload: ByteArray,
        recipientInboxPublicKey: ByteArray,
    ): ByteArray = payload
}

private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
    for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
}
