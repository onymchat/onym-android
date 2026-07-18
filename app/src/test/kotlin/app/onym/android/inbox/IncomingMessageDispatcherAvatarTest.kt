package app.onym.android.inbox

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupAvatarPayload
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Receive-side behavior for [GroupAvatarPayload] in
 * [IncomingMessageDispatcher]: applied on an admin-signer match,
 * rejected on a mismatch, and cleared when the avatar is absent.
 *
 * Mirrors the avatar cases of `IncomingMessageDispatcherTests.swift`
 * from onym-ios PR #166.
 */
class IncomingMessageDispatcherAvatarTest {

    private val groupId = ByteArray(32) { 0xAA.toByte() }
    private val adminEd25519 = ByteArray(32) { 0x10 }
    private val adminEd25519Hex = adminEd25519.toHex()
    private val ownerIdentity = IdentityId("owner")
    private val newAvatar = ByteArray(48) { 0x5A }

    private lateinit var groupStore: AvatarInMemoryGroupStore
    private lateinit var groupRepository: GroupRepository
    private lateinit var invitationsRepository: IncomingInvitationsRepository

    @Before
    fun setUp() = runTest {
        groupStore = AvatarInMemoryGroupStore()
        groupRepository = GroupRepository(
            store = groupStore,
            identity = AvatarConstantIdentity(ownerIdentity),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        invitationsRepository = IncomingInvitationsRepository(
            store = InMemoryInvitationStore(),
            identity = AvatarConstantIdentity(ownerIdentity),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
    }

    @Test
    fun avatar_appliedWhenSenderMatchesStoredAdmin() = runTest {
        seed(group(adminEd25519Hex = adminEd25519Hex, avatar = null))
        dispatch(payload(avatar = newAvatar), senderPub = adminEd25519)

        assertArrayEquals(newAvatar, groupRepository.snapshots.value.single().avatar)
        assertNoQueue()
    }

    @Test
    fun avatar_rejectedWhenSenderDoesNotMatchStoredAdmin() = runTest {
        seed(group(adminEd25519Hex = adminEd25519Hex, avatar = null))
        // Forged sender — different Ed25519 pubkey.
        dispatch(payload(avatar = newAvatar), senderPub = ByteArray(32) { 0x99.toByte() })

        assertNull(
            "forged avatar message must not mutate the group",
            groupRepository.snapshots.value.single().avatar,
        )
    }

    @Test
    fun avatar_clearedWhenPayloadAbsent() = runTest {
        // Group already has a photo; an absent-avatar message clears it.
        seed(group(adminEd25519Hex = adminEd25519Hex, avatar = ByteArray(16) { 0x77 }))
        dispatch(payload(avatar = null), senderPub = adminEd25519)

        assertNull(
            "absent avatar = removal",
            groupRepository.snapshots.value.single().avatar,
        )
    }

    @Test
    fun avatar_droppedForUnknownGroup() = runTest {
        seed(group(adminEd25519Hex = adminEd25519Hex, avatar = null))
        val foreign = GroupAvatarPayload(
            version = 1,
            groupId = ByteArray(32) { 0xFF.toByte() }, // not on device
            senderBlsHex = "cd".repeat(48),
            sentAtMillis = 1L,
            avatar = newAvatar,
        )
        dispatch(foreign, senderPub = adminEd25519)

        assertNull(groupRepository.snapshots.value.single().avatar)
        assertNoQueue()
    }

    @Test
    fun avatar_appliedBestEffortWhenNoStoredAdmin() = runTest {
        // Admin-less / legacy group (no stored admin Ed25519) → trust
        // gate skipped, same as member announcements.
        seed(group(adminEd25519Hex = null, avatar = null))
        dispatch(payload(avatar = newAvatar), senderPub = null)

        assertArrayEquals(newAvatar, groupRepository.snapshots.value.single().avatar)
    }

    // ─── helpers ──────────────────────────────────────────────────

    private suspend fun seed(group: ChatGroup) {
        groupRepository.insert(group)
        groupRepository.reload()
    }

    private suspend fun dispatch(payload: GroupAvatarPayload, senderPub: ByteArray?) {
        val plaintext = Json.encodeToString(GroupAvatarPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = AvatarStubDecrypter(plaintext, senderPub),
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
    }

    private suspend fun assertNoQueue() {
        invitationsRepository.bootstrap()
        assertTrue(invitationsRepository.invitations.value.isEmpty())
    }

    private fun payload(avatar: ByteArray?) = GroupAvatarPayload(
        version = 1,
        groupId = groupId,
        senderBlsHex = "ab".repeat(48),
        sentAtMillis = 1_700_000_000_000L,
        avatar = avatar,
    )

    private fun group(adminEd25519Hex: String?, avatar: ByteArray?): ChatGroup =
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
            avatar = avatar,
        )

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

private class AvatarStubDecrypter(
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

private class AvatarInMemoryGroupStore : GroupStore {
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
    override suspend fun delete(id: String, ownerIdentityId: String) { rows.remove(id) }
}

private class AvatarConstantIdentity(private val id: IdentityId) : ActiveIdentityProvider {
    override val currentIdentityId: StateFlow<IdentityId?> = MutableStateFlow(id)
    override fun registerRemovalListener(listener: (suspend (IdentityId) -> Unit)?) {}
}
