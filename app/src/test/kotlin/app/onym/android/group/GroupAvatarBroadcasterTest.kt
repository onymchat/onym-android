package app.onym.android.group

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.identity.ActiveIdentityProvider
import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeSealer
import app.onym.android.transport.InboundInbox
import app.onym.android.transport.InboxTransport
import app.onym.android.transport.PublishReceipt
import app.onym.android.transport.TransportEndpoint
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sender-side behavior for [GroupAvatarBroadcaster]: applies the change
 * locally, fans out one sealed envelope per member inbox **except the
 * admin's own key**, gates non-admins, and omits the avatar key on
 * removal.
 *
 * Mirrors `GroupAvatarBroadcasterTests.swift` from onym-ios PR #166.
 */
class GroupAvatarBroadcasterTest {

    private val ownerIdentity = IdentityId("owner")
    private val groupIdBytes = ByteArray(32) { 0xAA.toByte() }
    private val groupIdHex = groupIdBytes.toHex()

    private val adminBls = ByteArray(48) { 0x11 }
    private val adminBlsHex = adminBls.toHex()
    private val adminInbox = ByteArray(32) { 0x12 }
    private val m1Inbox = ByteArray(32) { 0x23 }
    private val m2Inbox = ByteArray(32) { 0x34 }
    private val newAvatar = ByteArray(40) { 0x5A }

    private lateinit var store: BroadcasterGroupStore
    private lateinit var groupRepository: GroupRepository
    private lateinit var sealer: RecordingSealer
    private lateinit var transport: RecordingTransport
    private lateinit var identities: MutableStateFlow<List<IdentitySummary>>

    @Before
    fun setUp() = runTest {
        store = BroadcasterGroupStore()
        groupRepository = GroupRepository(
            store = store,
            identity = BroadcasterIdentity(ownerIdentity),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        groupRepository.insert(group())
        groupRepository.reload()
        sealer = RecordingSealer()
        transport = RecordingTransport()
        identities = MutableStateFlow(listOf(adminSummary(adminBls)))
    }

    private fun broadcaster() = GroupAvatarBroadcaster(
        activeIdentity = BroadcasterIdentity(ownerIdentity),
        identitiesFlow = identities,
        envelopeSealer = sealer,
        groupRepository = groupRepository,
        inboxTransport = transport,
    )

    @Test
    fun setAvatar_appliesLocallyAndFansOutToEveryoneButAdmin() = runTest {
        val outcome = broadcaster().setAvatar(groupIdHex, newAvatar)

        assertEquals(GroupAvatarBroadcaster.Outcome.Sent, outcome)
        // Applied locally.
        assertArrayEquals(newAvatar, groupRepository.snapshots.value.single().avatar)
        // Fanned out to the two non-admin members only.
        assertEquals(setOf(m1Inbox.toHex(), m2Inbox.toHex()), sealer.recipients())
        assertEquals(2, transport.sends.size)
    }

    @Test
    fun setAvatar_nonAdminIsRejectedAndNoSends() = runTest {
        // Active identity isn't the admin.
        identities.value = listOf(adminSummary(ByteArray(48) { 0x99.toByte() }))

        val outcome = broadcaster().setAvatar(groupIdHex, newAvatar)

        assertEquals(GroupAvatarBroadcaster.Outcome.NotAdmin, outcome)
        assertNull(groupRepository.snapshots.value.single().avatar)
        assertTrue(sealer.sealed.isEmpty())
        assertTrue(transport.sends.isEmpty())
    }

    @Test
    fun setAvatar_unknownGroup() = runTest {
        val outcome = broadcaster().setAvatar("ff".repeat(32), newAvatar)
        assertEquals(GroupAvatarBroadcaster.Outcome.UnknownGroup, outcome)
    }

    @Test
    fun removeAvatar_clearsLocallyAndOmitsAvatarKey() = runTest {
        // Seed an existing photo first.
        groupRepository.insert(group().copy(avatar = ByteArray(8) { 0x7 }))
        groupRepository.reload()

        val outcome = broadcaster().setAvatar(groupIdHex, null)

        assertEquals(GroupAvatarBroadcaster.Outcome.Sent, outcome)
        assertNull(groupRepository.snapshots.value.single().avatar)
        // Removal payload omits the avatar key entirely.
        assertTrue(sealer.sealed.isNotEmpty())
        sealer.sealed.forEach { (payload, _) ->
            val json = payload.toString(Charsets.UTF_8)
            assertTrue("removal must omit avatar key", !json.contains("\"avatar\":"))
        }
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun group(): ChatGroup = ChatGroup(
        id = groupIdHex,
        name = "Family",
        groupSecret = ByteArray(32),
        createdAtMillis = 0L,
        members = emptyList(),
        memberProfiles = mapOf(
            adminBlsHex to profile(adminInbox),
            ByteArray(48) { 0x22 }.toHex() to profile(m1Inbox),
            ByteArray(48) { 0x33 }.toHex() to profile(m2Inbox),
        ),
        epoch = 0uL,
        salt = ByteArray(32),
        commitment = null,
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = adminBlsHex,
        adminEd25519PubkeyHex = null,
        isPublishedOnChain = true,
        ownerIdentityId = ownerIdentity.value,
        avatar = null,
    )

    private fun profile(inbox: ByteArray) = MemberProfile(
        alias = "x",
        inboxPublicKey = inbox,
        sendingPubkey = ByteArray(32) { 0x01 },
    )

    private fun adminSummary(bls: ByteArray) = IdentitySummary(
        id = ownerIdentity,
        name = "Admin",
        blsPublicKey = bls,
        inboxPublicKey = adminInbox,
        sendingPublicKey = ByteArray(32) { 0x02 },
    )

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

private class RecordingSealer : InvitationEnvelopeSealer {
    val sealed = mutableListOf<Pair<ByteArray, ByteArray>>()
    override suspend fun sealInvitation(
        payload: ByteArray,
        recipientInboxPublicKey: ByteArray,
    ): ByteArray {
        sealed.add(payload to recipientInboxPublicKey)
        return payload
    }
    fun recipients(): Set<String> =
        sealed.map { it.second.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) } }.toSet()
}

private class RecordingTransport : InboxTransport {
    val sends = mutableListOf<TransportInboxId>()
    override suspend fun connect(endpoints: List<TransportEndpoint>) {}
    override suspend fun disconnect() {}
    override suspend fun send(payload: ByteArray, inbox: TransportInboxId): PublishReceipt {
        sends.add(inbox)
        return PublishReceipt(messageId = "msg", acceptedBy = 1)
    }
    override fun subscribe(inbox: TransportInboxId): Flow<InboundInbox> = emptyFlow()
    override suspend fun unsubscribe(inbox: TransportInboxId) {}
}

private class BroadcasterGroupStore : GroupStore {
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
    override suspend fun markPublished(id: String, commitment: ByteArray?) {}
    override suspend fun delete(id: String) { rows.remove(id) }
}

private class BroadcasterIdentity(private val id: IdentityId) : ActiveIdentityProvider {
    override val currentIdentityId: StateFlow<IdentityId?> = MutableStateFlow(id)
    override fun registerRemovalListener(listener: (suspend (IdentityId) -> Unit)?) {}
}
