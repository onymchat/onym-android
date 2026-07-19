package app.onym.android.inbox

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupNamePayload
import app.onym.android.group.GroupRepository
import app.onym.android.group.GroupStore
import app.onym.android.identity.ActiveIdentityProvider
import app.onym.android.identity.DecryptedEnvelope
import app.onym.android.identity.IdentityId
import app.onym.android.identity.InvitationEnvelopeDecrypter
import app.onym.android.persistence.InMemoryInvitationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Receive-side behavior for [GroupNamePayload] (admin group rename) in
 * [IncomingMessageDispatcher]: applied on an admin-signer match, rejected
 * on a mismatch, best-effort when the group has no stored admin. Mirrors
 * the avatar cases and `IncomingMessageDispatcherTests.swift` on iOS.
 */
class IncomingMessageDispatcherNameTest {

    private val groupId = ByteArray(32) { 0xAA.toByte() }
    private val adminEd25519 = ByteArray(32) { 0x10 }
    private val adminEd25519Hex = adminEd25519.toHex()
    private val ownerIdentity = IdentityId("owner")

    private lateinit var groupRepository: GroupRepository
    private lateinit var invitationsRepository: IncomingInvitationsRepository

    @Before
    fun setUp() = runTest {
        groupRepository = GroupRepository(
            store = NameInMemoryGroupStore(),
            identity = NameConstantIdentity(ownerIdentity),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        invitationsRepository = IncomingInvitationsRepository(
            store = InMemoryInvitationStore(),
            identity = NameConstantIdentity(ownerIdentity),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
    }

    @Test
    fun name_appliedWhenSenderMatchesStoredAdmin() = runTest {
        seed(group(adminEd25519Hex = adminEd25519Hex))
        dispatch(payload(name = "Renamed"), senderPub = adminEd25519)
        assertEquals("Renamed", groupRepository.snapshots.value.single().name)
    }

    @Test
    fun name_rejectedWhenSenderDoesNotMatchStoredAdmin() = runTest {
        seed(group(adminEd25519Hex = adminEd25519Hex))
        dispatch(payload(name = "Hacked"), senderPub = ByteArray(32) { 0x99.toByte() })
        assertEquals(
            "forged rename must not mutate the group",
            "Family",
            groupRepository.snapshots.value.single().name,
        )
    }

    @Test
    fun name_droppedForUnknownGroup() = runTest {
        seed(group(adminEd25519Hex = adminEd25519Hex))
        val foreign = GroupNamePayload(
            version = 1,
            groupId = ByteArray(32) { 0xFF.toByte() },
            senderBlsHex = "cd".repeat(48),
            sentAtMillis = 1L,
            name = "Nope",
        )
        dispatch(foreign, senderPub = adminEd25519)
        assertEquals("Family", groupRepository.snapshots.value.single().name)
    }

    @Test
    fun name_appliedBestEffortWhenNoStoredAdmin() = runTest {
        seed(group(adminEd25519Hex = null))
        dispatch(payload(name = "Renamed"), senderPub = null)
        assertEquals("Renamed", groupRepository.snapshots.value.single().name)
    }

    private suspend fun seed(group: ChatGroup) {
        groupRepository.insert(group)
        groupRepository.reload()
    }

    private suspend fun dispatch(payload: GroupNamePayload, senderPub: ByteArray?) {
        val plaintext = Json.encodeToString(GroupNamePayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = NameStubDecrypter(plaintext, senderPub),
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
    }

    private fun payload(name: String) = GroupNamePayload(
        version = 1,
        groupId = groupId,
        senderBlsHex = "ab".repeat(48),
        sentAtMillis = 1_700_000_000_000L,
        name = name,
    )

    private fun group(adminEd25519Hex: String?): ChatGroup =
        ChatGroup(
            id = groupId.toHex(),
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
            adminEd25519PubkeyHex = adminEd25519Hex,
            isPublishedOnChain = true,
            ownerIdentityId = ownerIdentity.value,
            avatar = null,
        )

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

private class NameStubDecrypter(
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

private class NameInMemoryGroupStore : GroupStore {
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
    override suspend fun markPublished(id: String, ownerIdentityId: String, commitment: ByteArray?) {
        val existing = rows[id] ?: return
        rows[id] = existing.copy(
            isPublishedOnChain = true,
            commitment = commitment ?: existing.commitment,
        )
    }
    override suspend fun markRead(id: String, ownerIdentityId: String, lastReadAtMillis: Long) {
        val existing = rows[id] ?: return
        rows[id] = existing.copy(lastReadAtMillis = lastReadAtMillis)
    }
    override suspend fun delete(id: String, ownerIdentityId: String) { rows.remove(id) }
}

private class NameConstantIdentity(private val id: IdentityId) : ActiveIdentityProvider {
    override val currentIdentityId: StateFlow<IdentityId?> = MutableStateFlow(id)
    override fun registerRemovalListener(listener: (suspend (IdentityId) -> Unit)?) {}
}
