package app.onym.android.inbox

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GovernanceMember
import app.onym.android.group.GroupInvitationPayload
import app.onym.android.group.GroupRepository
import app.onym.android.group.GroupStore
import app.onym.android.group.MemberAnnouncementPayload
import app.onym.android.group.MemberProfile
import app.onym.android.identity.ActiveIdentityProvider
import app.onym.android.identity.DecryptedEnvelope
import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeDecrypter
import app.onym.android.persistence.InMemoryInvitationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertNotNull
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
    fun invitation_materializesGroupAndAddsSelfProfile() = runTest {
        // Build an invitation with one wire-shipped profile (the
        // inviter), targeting a group that's NOT yet on the device.
        val newGroupId = ByteArray(32) { 0xEE.toByte() }
        val inviterBls = ByteArray(48) { 0x11 }
        val inviterInbox = ByteArray(32) { 0x22 }
        val inviterKey = inviterBls.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val invitation = GroupInvitationPayload(
            version = 1,
            groupId = newGroupId,
            groupSecret = ByteArray(32) { 0x33 },
            name = "From Wire",
            members = listOf(
                GovernanceMember(
                    publicKeyCompressed = inviterBls,
                    leafHash = ByteArray(32) { 0x44 },
                ),
            ),
            epoch = 0uL,
            salt = ByteArray(32) { 0x55 },
            commitment = ByteArray(32) { 0x66 },
            tierRaw = SepTier.SMALL.rawValue,
            groupTypeRaw = SepGroupType.TYRANNY.wireValue,
            adminPubkeyHex = inviterKey,
            memberProfiles = mapOf(
                inviterKey to MemberProfile(alias = "Alice", inboxPublicKey = inviterInbox),
            ),
        )
        val plaintext = Json.encodeToString(GroupInvitationPayload.serializer(), invitation)
            .toByteArray(Charsets.UTF_8)

        // Mock identitiesFlow so the dispatcher can backfill self.
        val selfBls = ByteArray(48) { 0xAA.toByte() }
        val selfInbox = ByteArray(32) { 0xBB.toByte() }
        val identitiesFlow = MutableStateFlow(
            listOf(
                IdentitySummary(
                    id = ownerIdentity,
                    name = "Bob",
                    blsPublicKey = selfBls,
                    inboxPublicKey = selfInbox,
                ),
            ),
        )

        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = StubDecrypter(plaintext, senderPub = ByteArray(32)),
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
            identitiesFlow = identitiesFlow.asStateFlow(),
        )

        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)

        // Group materialized + listed under owner; legacy queue empty.
        val group = groupRepository.snapshots.value.firstOrNull {
            it.groupIdBytes.contentEquals(newGroupId)
        }
        assertNotNull("invitation must materialize a group row", group)
        assertEquals("From Wire", group!!.name)
        assertTrue(group.isPublishedOnChain)
        // Wire-shipped inviter + self both present in the directory.
        val selfKey = selfBls.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(2, group.memberProfiles.size)
        assertEquals("Alice", group.memberProfiles[inviterKey]?.alias)
        assertEquals("Bob", group.memberProfiles[selfKey]?.alias)
        invitationsRepository.bootstrap()
        assertTrue(
            "invitation must NOT land in the legacy queue",
            invitationsRepository.invitations.value.isEmpty(),
        )
    }

    @Test
    fun invitation_redeliveryIsIdempotent() = runTest {
        val newGroupId = ByteArray(32) { 0xEE.toByte() }
        val invitation = GroupInvitationPayload(
            version = 1,
            groupId = newGroupId,
            groupSecret = ByteArray(32) { 0x33 },
            name = "G",
            members = emptyList(),
            epoch = 0uL,
            salt = ByteArray(32) { 0x55 },
            commitment = null,
            tierRaw = SepTier.SMALL.rawValue,
            groupTypeRaw = SepGroupType.TYRANNY.wireValue,
        )
        val plaintext = Json.encodeToString(GroupInvitationPayload.serializer(), invitation)
            .toByteArray(Charsets.UTF_8)
        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = StubDecrypter(plaintext, senderPub = null),
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        dispatcher.dispatch("m2", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        val groups = groupRepository.snapshots.value.filter {
            it.groupIdBytes.contentEquals(newGroupId)
        }
        assertEquals("re-delivery must not mint a duplicate", 1, groups.size)
    }

    @Test
    fun announcement_droppedWhenSenderDoesNotMatchStoredAdmin() = runTest {
        // Seed a group whose adminEd25519PubkeyHex is set.
        val storedAdmin = ByteArray(32) { 0x10 }
        val storedAdminHex = storedAdmin.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        groupStore.replaceForTest(makeGroup(groupId).copy(adminEd25519PubkeyHex = storedAdminHex))
        groupRepository.reload()

        val payload = announcementPayload()
        val plaintext = Json.encodeToString(MemberAnnouncementPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        // Forged announcement — different sender pubkey.
        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = StubDecrypter(plaintext, senderPub = ByteArray(32) { 0x99.toByte() }),
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)

        // Forged announcement must NOT mutate the group.
        assertTrue(groupRepository.snapshots.value.single().memberProfiles.isEmpty())
    }

    @Test
    fun announcement_acceptedWhenSenderMatchesStoredAdmin() = runTest {
        val storedAdmin = ByteArray(32) { 0x10 }
        val storedAdminHex = storedAdmin.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        groupStore.replaceForTest(makeGroup(groupId).copy(adminEd25519PubkeyHex = storedAdminHex))
        groupRepository.reload()

        val payload = announcementPayload()
        val plaintext = Json.encodeToString(MemberAnnouncementPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = StubDecrypter(plaintext, senderPub = storedAdmin),
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        assertEquals(1, groupRepository.snapshots.value.single().memberProfiles.size)
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

    /** Test-only helper — replace a row by id without going through
     *  insertOrUpdate's "preserve createdAt" branch. */
    fun replaceForTest(group: ChatGroup) {
        rows[group.id] = group
    }
}

private class ConstantIdentity(private val id: IdentityId) : ActiveIdentityProvider {
    override val currentIdentityId: StateFlow<IdentityId?> = MutableStateFlow(id)
    override fun registerRemovalListener(listener: (suspend (IdentityId) -> Unit)?) {}
}
