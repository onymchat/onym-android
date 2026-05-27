package app.onym.android.inbox

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.chats.ChatMessagePayload
import app.onym.android.chats.ChatMessageVariant
import app.onym.android.chats.MessageDirection
import app.onym.android.chats.MessageRepository
import app.onym.android.chats.MessageStatus
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupRepository
import app.onym.android.group.MemberProfile
import app.onym.android.identity.DecryptedEnvelope
import app.onym.android.identity.IdentityId
import app.onym.android.identity.InvitationEnvelopeDecrypter
import app.onym.android.persistence.InMemoryInvitationStore
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryMessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Behavioral tests for the chat-message fast path on
 * [IncomingMessageDispatcher] (PR A4). Drives the dispatcher with a
 * fake decrypter that returns a pre-baked [DecryptedEnvelope] and
 * verifies the full trust chain rejects mismatches without falling
 * through to the legacy invitations queue.
 *
 * Mirrors `IncomingMessageDispatcherChatMessageTests.swift` from
 * onym-ios PR #150.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingMessageDispatcherChatMessageTest {

    private val ownerIdentity = IdentityId("alice-id")
    private val otherIdentity = IdentityId("bob-id")
    private val groupIdBytes = ByteArray(32) { 0xAA.toByte() }
    private val groupIdHex = groupIdBytes.toHexLowercase()
    private val senderBlsBytes = ByteArray(48) { 0x11 }
    private val senderBlsHex = senderBlsBytes.toHexLowercase()
    private val senderInbox = ByteArray(32) { 0x22 }
    private val senderSendingKey = ByteArray(32) { 0x33 }

    // ─── happy path ───────────────────────────────────────────────

    @Test
    fun chatMessage_persistsAndDoesNotFallThrough() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(ownerIdentity, withSenderProfile = true)

        val msgId = UUID.fromString("00112233-4455-6677-8899-AABBCCDDEEFF")
        val payload = chatPayload(msgId, body = "hello")
        fixture.dispatch(ownerIdentity, payload, signer = senderSendingKey)

        val rows = fixture.messageStore.listForGroup(ownerIdentity.value, groupIdHex)
        assertEquals(1, rows.size)
        val msg = rows.single()
        assertEquals(msgId, msg.id)
        assertEquals("hello", msg.body)
        assertEquals(MessageDirection.INCOMING, msg.direction)
        assertEquals(MessageStatus.RECEIVED, msg.status)
        assertEquals(senderBlsHex, msg.senderBlsPubkeyHex)
        assertNull("a non-reply inbound message carries no reply target", msg.replyToMessageId)
        // Legacy queue stays empty — chat messages don't fall through.
        fixture.invitationsRepository.bootstrap()
        assertTrue(fixture.invitationsRepository.invitations.value.isEmpty())
    }

    @Test
    fun chatMessage_withReplyRef_persistsTargetId() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(ownerIdentity, withSenderProfile = true)

        val target = UUID.randomUUID()
        val payload = chatPayload(UUID.randomUUID(), body = "agreed", replyToMessageId = target)
        fixture.dispatch(ownerIdentity, payload, signer = senderSendingKey)

        val stored = fixture.messageStore.listForGroup(ownerIdentity.value, groupIdHex).single()
        assertEquals(
            "an inbound reply must carry its target id onto the persisted message",
            target,
            stored.replyToMessageId,
        )
    }

    // ─── trust-chain failures all drop without falling through ───

    @Test
    fun chatMessage_unknownGroupIsDroppedNotQueued() = runTest {
        val fixture = newFixture()
        // No seeded group at all.
        val payload = chatPayload(UUID.randomUUID(), body = "x")
        fixture.dispatch(ownerIdentity, payload, signer = senderSendingKey)

        assertTrue(fixture.messageStore.listForGroup(ownerIdentity.value, groupIdHex).isEmpty())
        fixture.invitationsRepository.bootstrap()
        assertTrue(
            "chat-message decode succeeded; must NOT queue",
            fixture.invitationsRepository.invitations.value.isEmpty(),
        )
    }

    @Test
    fun chatMessage_unknownSenderIsDropped() = runTest {
        val fixture = newFixture()
        // Group present, but the claimed sender's BLS hex isn't in memberProfiles.
        fixture.seedGroup(ownerIdentity, withSenderProfile = false)
        val payload = chatPayload(UUID.randomUUID(), body = "x")
        fixture.dispatch(ownerIdentity, payload, signer = senderSendingKey)

        assertTrue(fixture.messageStore.listForGroup(ownerIdentity.value, groupIdHex).isEmpty())
    }

    @Test
    fun chatMessage_signatureMismatchIsDropped() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(ownerIdentity, withSenderProfile = true)
        // Envelope signed by some OTHER Ed25519 key, not the one
        // recorded on the sender's MemberProfile.
        val payload = chatPayload(UUID.randomUUID(), body = "x")
        fixture.dispatch(
            ownerIdentity,
            payload,
            signer = ByteArray(32) { 0x99.toByte() },
        )

        assertTrue(fixture.messageStore.listForGroup(ownerIdentity.value, groupIdHex).isEmpty())
    }

    @Test
    fun chatMessage_missingEnvelopeSignatureIsDropped() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(ownerIdentity, withSenderProfile = true)
        val payload = chatPayload(UUID.randomUUID(), body = "x")
        // Anonymous chat messages aren't part of the V1 trust model.
        fixture.dispatch(ownerIdentity, payload, signer = null)

        assertTrue(fixture.messageStore.listForGroup(ownerIdentity.value, groupIdHex).isEmpty())
    }

    @Test
    fun chatMessage_wrongOwnerIdentityIsDropped() = runTest {
        val fixture = newFixture()
        // Group owned by Alice, but the inbox fan-out delivered this
        // envelope to Bob's tag — Bob's local view doesn't know about
        // Alice's group, so the lookup misses.
        fixture.seedGroup(ownerIdentity, withSenderProfile = true)
        val payload = chatPayload(UUID.randomUUID(), body = "x")
        fixture.dispatch(otherIdentity, payload, signer = senderSendingKey)

        assertTrue(fixture.messageStore.listForGroup(otherIdentity.value, groupIdHex).isEmpty())
        assertTrue(fixture.messageStore.listForGroup(ownerIdentity.value, groupIdHex).isEmpty())
    }

    // ─── multi-identity correctness ───────────────────────────────

    @Test
    fun chatMessage_persistsForNonActiveIdentity() = runTest {
        // Active identity is Alice, but the inbox tag belongs to Bob;
        // the message must still land in Bob's group regardless of
        // whether Bob is currently selected.
        val activeProvider = FakeActiveIdentityProvider(initial = ownerIdentity)
        val fixture = newFixture(activeProvider = activeProvider)
        fixture.seedGroup(otherIdentity, withSenderProfile = true)

        val msgId = UUID.randomUUID()
        val payload = chatPayload(msgId, body = "for bob")
        fixture.dispatch(otherIdentity, payload, signer = senderSendingKey)

        // Stored under Bob, even though Alice is active.
        val bobRows = fixture.messageStore.listForGroup(otherIdentity.value, groupIdHex)
        assertEquals(1, bobRows.size)
        assertEquals("for bob", bobRows.single().body)
        // Active identity (Alice) has nothing.
        assertTrue(fixture.messageStore.listForGroup(ownerIdentity.value, groupIdHex).isEmpty())
    }

    // ─── idempotency ──────────────────────────────────────────────

    @Test
    fun chatMessage_duplicateMessageIdIsIdempotent() = runTest {
        val fixture = newFixture()
        fixture.seedGroup(ownerIdentity, withSenderProfile = true)

        val msgId = UUID.randomUUID()
        val payload = chatPayload(msgId, body = "first delivery")
        fixture.dispatch(ownerIdentity, payload, signer = senderSendingKey)
        // Same wire messageId arrives again (e.g., Nostr relay
        // re-delivery). Body content could be different in a hostile
        // scenario; we keep whatever landed first.
        val duplicate = payload.copy()
        fixture.dispatch(ownerIdentity, duplicate, signer = senderSendingKey)

        val rows = fixture.messageStore.listForGroup(ownerIdentity.value, groupIdHex)
        assertEquals("re-delivery must not duplicate the row", 1, rows.size)
        assertEquals("first delivery", rows.single().body)
    }

    // ─── variant gate ─────────────────────────────────────────────

    @Test
    fun chatMessage_variantMismatchForGovernanceIsDropped() = runTest {
        // Group is ANARCHY but payload variant is Tyranny —
        // governance-keyed variant gate must reject so we don't
        // mis-render or mis-attribute a cross-governance message.
        val fixture = newFixture()
        fixture.seedGroup(
            ownerIdentity,
            withSenderProfile = true,
            groupType = SepGroupType.ANARCHY,
        )
        val payload = chatPayload(UUID.randomUUID(), body = "x")
        fixture.dispatch(ownerIdentity, payload, signer = senderSendingKey)

        assertTrue(fixture.messageStore.listForGroup(ownerIdentity.value, groupIdHex).isEmpty())
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun chatPayload(
        id: UUID,
        body: String,
        sentAtMillis: Long = 1_700_000_000_000L,
        replyToMessageId: UUID? = null,
    ) = ChatMessagePayload(
        version = 1,
        messageId = id,
        groupId = groupIdBytes,
        senderBlsPubkeyHex = senderBlsHex,
        sentAtMillis = sentAtMillis,
        replyToMessageId = replyToMessageId,
        variant = ChatMessageVariant.Tyranny(body = body),
    )

    private fun newFixture(
        activeProvider: FakeActiveIdentityProvider = FakeActiveIdentityProvider(initial = ownerIdentity),
    ): Fixture {
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
        val invitationsRepository = IncomingInvitationsRepository(
            store = InMemoryInvitationStore(),
            identity = activeProvider,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        return Fixture(
            groupStore = groupStore,
            groupRepository = groupRepository,
            messageStore = messageStore,
            messageRepository = messageRepository,
            invitationsRepository = invitationsRepository,
        )
    }

    private inner class Fixture(
        val groupStore: InMemoryGroupStore,
        val groupRepository: GroupRepository,
        val messageStore: InMemoryMessageStore,
        val messageRepository: MessageRepository,
        val invitationsRepository: IncomingInvitationsRepository,
    ) {
        suspend fun seedGroup(
            owner: IdentityId,
            withSenderProfile: Boolean,
            groupType: SepGroupType = SepGroupType.TYRANNY,
        ) {
            val profiles: Map<String, MemberProfile> = if (withSenderProfile) {
                mapOf(
                    senderBlsHex to MemberProfile(
                        alias = "Sender",
                        inboxPublicKey = senderInbox,
                        sendingPubkey = senderSendingKey,
                    ),
                )
            } else {
                emptyMap()
            }
            groupStore.preload(listOf(makeGroup(owner, groupType, profiles)))
        }

        suspend fun dispatch(
            owner: IdentityId,
            payload: ChatMessagePayload,
            signer: ByteArray?,
        ) {
            val plaintext = jsonFormat
                .encodeToString(ChatMessagePayload.serializer(), payload)
                .toByteArray(Charsets.UTF_8)
            val dispatcher = IncomingMessageDispatcher(
                envelopeDecrypter = StubDecrypterChat(plaintext, signer),
                groupRepository = groupRepository,
                invitationsRepository = invitationsRepository,
                messageRepository = messageRepository,
            )
            dispatcher.dispatch(
                messageId = "envelope-id-${payload.messageId}",
                ownerIdentityId = owner,
                payload = byteArrayOf(),
                receivedAt = Instant.EPOCH,
            )
        }
    }

    private fun makeGroup(
        owner: IdentityId,
        groupType: SepGroupType,
        profiles: Map<String, MemberProfile>,
    ): ChatGroup = ChatGroup(
        id = groupIdHex,
        name = "Family",
        groupSecret = ByteArray(32),
        createdAtMillis = 0L,
        members = emptyList(),
        memberProfiles = profiles,
        epoch = 0uL,
        salt = ByteArray(32),
        commitment = null,
        tier = SepTier.SMALL,
        groupType = groupType,
        adminPubkeyHex = null,
        isPublishedOnChain = true,
        ownerIdentityId = owner.value,
    )

    private companion object {
        private val jsonFormat = Json { encodeDefaults = true }
    }
}

private class StubDecrypterChat(
    private val plaintext: ByteArray,
    private val senderPub: ByteArray?,
) : InvitationEnvelopeDecrypter {
    override suspend fun decryptInvitation(
        envelopeBytes: ByteArray,
        asIdentity: IdentityId,
    ): ByteArray = plaintext

    override suspend fun decryptInvitationWithSender(
        envelopeBytes: ByteArray,
        asIdentity: IdentityId,
    ): DecryptedEnvelope = DecryptedEnvelope(plaintext, senderPub)
}

private fun ByteArray.toHexLowercase(): String = buildString(size * 2) {
    for (b in this@toHexLowercase) append("%02x".format(b.toInt() and 0xFF))
}
