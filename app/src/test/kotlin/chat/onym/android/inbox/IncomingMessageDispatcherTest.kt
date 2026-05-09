package chat.onym.android.inbox

import chat.onym.android.chain.SepGroupType
import chat.onym.android.chain.SepTier
import chat.onym.android.group.ChatGroup
import chat.onym.android.group.GroupRepository
import chat.onym.android.group.GroupStore
import chat.onym.android.group.MemberAnnouncementPayload
import chat.onym.android.identity.ActiveIdentityProvider
import chat.onym.android.identity.DecryptedEnvelope
import chat.onym.android.identity.IdentityId
import chat.onym.android.identity.InvitationEnvelopeDecrypter
import chat.onym.android.persistence.InMemoryInvitationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Behavioral tests for [IncomingMessageDispatcher]. Drives the
 * dispatcher with a fake decrypter that returns a pre-baked
 * [DecryptedEnvelope]; verifies the announcement fast-path mutates
 * group state (and only group state — never the legacy invitations
 * queue).
 *
 * Mirrors `IncomingMessageDispatcherTests.swift` from onym-ios.
 */
class IncomingMessageDispatcherTest {

    private val groupId = ByteArray(32) { 0xAA.toByte() }
    private val newMemberBlsPub = ByteArray(48) { 0xBB.toByte() }
    private val newMemberInbox = ByteArray(32) { 0xCC.toByte() }
    private val ownerIdentity = IdentityId("owner")

    private lateinit var groupStore: InMemoryGroupStore
    private lateinit var groupRepository: GroupRepository
    private lateinit var invitationsRepository: IncomingInvitationsRepository

    @Before
    fun setUp() = runTest {
        groupStore = InMemoryGroupStore()
        groupRepository = GroupRepository(
            store = groupStore,
            identity = ConstantIdentity(ownerIdentity),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        // Seed a group with the owner so the announcement fast-path
        // can find it. memberProfiles starts empty.
        groupRepository.insert(makeGroup(groupId))
        groupRepository.reload()
        invitationsRepository = IncomingInvitationsRepository(
            store = InMemoryInvitationStore(),
            identity = ConstantIdentity(ownerIdentity),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
    }

    @Test
    fun announcement_appendsToLocalGroupAndDoesNotQueue() = runTest {
        val payload = announcementPayload()
        val plaintext = Json.encodeToString(MemberAnnouncementPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = StubDecrypter(plaintext, senderPub = ByteArray(32)),
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
        )

        dispatcher.dispatch(
            messageId = "m1",
            ownerIdentityId = ownerIdentity,
            payload = byteArrayOf(),
            receivedAt = Instant.EPOCH,
        )

        val updated = groupRepository.snapshots.value.single()
        assertEquals(1, updated.memberProfiles.size)
        val key = newMemberBlsPub.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertNotNull(updated.memberProfiles[key])
        assertEquals("Bob", updated.memberProfiles[key]!!.alias)

        // Legacy queue MUST be untouched for an announcement.
        invitationsRepository.bootstrap()
        assertTrue(invitationsRepository.invitations.value.isEmpty())
    }

    @Test
    fun announcement_unknownGroupIsDroppedNotQueued() = runTest {
        val foreignPayload = MemberAnnouncementPayload(
            version = 1,
            groupId = ByteArray(32) { 0xFF.toByte() },  // not on this device
            newMember = MemberAnnouncementPayload.AnnouncedMember(
                blsPub = newMemberBlsPub, inboxPub = newMemberInbox, alias = "Bob",
            ),
            adminAlias = "Alice",
        )
        val plaintext = Json.encodeToString(MemberAnnouncementPayload.serializer(), foreignPayload)
            .toByteArray(Charsets.UTF_8)
        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = StubDecrypter(plaintext, senderPub = ByteArray(32)),
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
        )

        dispatcher.dispatch(
            messageId = "m1",
            ownerIdentityId = ownerIdentity,
            payload = byteArrayOf(),
            receivedAt = Instant.EPOCH,
        )

        // Local group untouched.
        val unchanged = groupRepository.snapshots.value.single()
        assertTrue(unchanged.memberProfiles.isEmpty())
        // No queue side-effect either.
        invitationsRepository.bootstrap()
        assertTrue(invitationsRepository.invitations.value.isEmpty())
    }

    @Test
    fun announcement_idempotentOnRedelivery() = runTest {
        val payload = announcementPayload()
        val plaintext = Json.encodeToString(MemberAnnouncementPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = StubDecrypter(plaintext, senderPub = ByteArray(32)),
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
        )

        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        dispatcher.dispatch("m2", ownerIdentity, byteArrayOf(), Instant.EPOCH)

        val final = groupRepository.snapshots.value.single()
        assertEquals(1, final.memberProfiles.size)
    }

    @Test
    fun decryptFailure_fallsThroughToInvitationsQueue() = runTest {
        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = ThrowingDecrypter(),
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(0x01), Instant.EPOCH)
        invitationsRepository.bootstrap()
        assertEquals(1, invitationsRepository.invitations.value.size)
    }

    @Test
    fun unrecognizedPlaintext_fallsThroughToQueue() = runTest {
        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = StubDecrypter(
                plaintext = "{\"some\":\"random\"}".toByteArray(Charsets.UTF_8),
                senderPub = null,
            ),
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(0x02), Instant.EPOCH)
        invitationsRepository.bootstrap()
        assertEquals(1, invitationsRepository.invitations.value.size)
    }

    private fun announcementPayload() = MemberAnnouncementPayload(
        version = 1,
        groupId = groupId,
        newMember = MemberAnnouncementPayload.AnnouncedMember(
            blsPub = newMemberBlsPub, inboxPub = newMemberInbox, alias = "Bob",
        ),
        adminAlias = "Alice",
    )

    private fun makeGroup(id: ByteArray): ChatGroup {
        val hex = id.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return ChatGroup(
            id = hex,
            name = "Family",
            groupSecret = ByteArray(32),
            createdAtMillis = 0L,
            members = emptyList(),
            memberProfiles = emptyMap(),
            epoch = 0uL,
            salt = ByteArray(32),
            commitment = null,
            tier = SepTier.SMALL,
            groupType = SepGroupType.TYRANNY,
            adminPubkeyHex = null,
            isPublishedOnChain = true,
            ownerIdentityId = ownerIdentity.value,
        )
    }
}

private class StubDecrypter(
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

private class ThrowingDecrypter : InvitationEnvelopeDecrypter {
    override suspend fun decryptInvitation(
        envelopeBytes: ByteArray,
        asIdentity: IdentityId,
    ): ByteArray = throw IllegalStateException("decrypt failed")
    override suspend fun decryptInvitationWithSender(
        envelopeBytes: ByteArray,
        asIdentity: IdentityId,
    ): DecryptedEnvelope = throw IllegalStateException("decrypt failed")
}

private class InMemoryGroupStore : GroupStore {
    private val rows = mutableMapOf<String, ChatGroup>()
    override suspend fun list(): List<ChatGroup> = rows.values.toList()
    override suspend fun listForOwner(ownerIdentityId: String): List<ChatGroup> =
        rows.values.filter { it.ownerIdentityId == ownerIdentityId }
    override suspend fun deleteForOwner(ownerIdentityId: String): Int {
        val before = rows.size
        rows.entries.removeAll { it.value.ownerIdentityId == ownerIdentityId }
        return before - rows.size
    }
    override suspend fun insertOrUpdate(group: ChatGroup): Boolean {
        val isNew = !rows.containsKey(group.id)
        rows[group.id] = group
        return isNew
    }
    override suspend fun markPublished(id: String, commitment: ByteArray?) {
        val existing = rows[id] ?: return
        rows[id] = existing.copy(
            isPublishedOnChain = true,
            commitment = commitment ?: existing.commitment,
        )
    }
    override suspend fun delete(id: String) { rows.remove(id) }
}

private class ConstantIdentity(private val id: IdentityId) : ActiveIdentityProvider {
    override val currentIdentityId: StateFlow<IdentityId?> = MutableStateFlow(id)
    override fun registerRemovalListener(listener: (suspend (IdentityId) -> Unit)?) {}
}
