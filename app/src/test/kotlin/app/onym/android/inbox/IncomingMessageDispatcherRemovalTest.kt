package app.onym.android.inbox

import app.onym.android.chain.ChainStateReading
import app.onym.android.chain.SepCommitmentEntry
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GovernanceMember
import app.onym.android.group.GroupRepository
import app.onym.android.group.GroupStore
import app.onym.android.group.MemberProfile
import app.onym.android.group.MemberRemovalPayload
import app.onym.android.identity.DecryptedEnvelope
import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeDecrypter
import app.onym.android.persistence.InMemoryInvitationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Behavioral tests for the [IncomingMessageDispatcher] member-removal
 * fast path: remaining-member apply (tombstone + secret rotation),
 * self-removal ([ChatGroup.membershipRevoked]), the admin-signature
 * gate, the on-chain converge-forward check, idempotent re-delivery,
 * and the chain-behind bounded retry.
 *
 * JVM-safe by design: the removal verifier is chain-check-only (no
 * Poseidon recompute), so no OnymSDK JNI is touched.
 */
class IncomingMessageDispatcherRemovalTest {

    private val groupId = ByteArray(32) { 0xAA.toByte() }
    private val ownerIdentity = IdentityId("owner")

    private val adminSending = ByteArray(32) { 0x10 }
    private val adminSendingHex = adminSending.toHexLower()

    private val victimBls = ByteArray(48) { 0xBB.toByte() }
    private val victimHex = victimBls.toHexLower()
    private val otherBls = ByteArray(48) { 0xCC.toByte() }
    private val otherHex = otherBls.toHexLower()

    private val oldSecret = ByteArray(32) { 0x01 }
    private val oldSalt = ByteArray(32) { 0x02 }
    private val newSecret = ByteArray(32) { 0x03 }
    private val newSalt = ByteArray(32) { 0x04 }
    private val newCommitment = ByteArray(32) { 0x05 }

    private lateinit var groupStore: RemovalTestGroupStore
    private lateinit var groupRepository: GroupRepository
    private lateinit var invitationsRepository: IncomingInvitationsRepository

    @Before
    fun setUp() = runTest {
        groupStore = RemovalTestGroupStore()
        groupRepository = GroupRepository(
            store = groupStore,
            identity = app.onym.android.support.FakeActiveIdentityProvider(ownerIdentity),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        groupStore.replaceForTest(makeGroup())
        groupRepository.reload()
        invitationsRepository = IncomingInvitationsRepository(
            store = InMemoryInvitationStore(),
            identity = app.onym.android.support.FakeActiveIdentityProvider(ownerIdentity),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
    }

    // ─── remaining-member apply ────────────────────────────────────

    @Test
    fun removal_remainingMember_tombstonesAndRotatesSecrets() = runTest {
        val dispatcher = makeDispatcher(payloadForMembers())
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)

        val updated = groupRepository.snapshots.value.single()
        // Tombstoned, not deleted.
        val victim = updated.memberProfiles[victimHex]
        assertNotNull(victim)
        assertTrue(victim!!.revoked)
        // Other member untouched.
        assertFalse(updated.memberProfiles[otherHex]!!.revoked)
        // Subtracted from the on-chain roster.
        assertTrue(updated.members.none { it.publicKeyCompressed.contentEquals(victimBls) })
        // Rotated state applied.
        assertEquals(2uL, updated.epoch)
        assertTrue(updated.groupSecret.contentEquals(newSecret))
        assertTrue(updated.salt.contentEquals(newSalt))
        assertTrue(updated.commitment!!.contentEquals(newCommitment))
        assertFalse(updated.membershipRevoked)
    }

    @Test
    fun removal_selfTarget_setsMembershipRevokedAndKeepsSecrets() = runTest {
        // The receiving identity IS the victim; their payload variant
        // carries no secrets.
        val selfSummary = IdentitySummary(
            id = ownerIdentity,
            name = "Me",
            blsPublicKey = victimBls,
            inboxPublicKey = ByteArray(32) { 0x21 },
            sendingPublicKey = ByteArray(32) { 0x22 },
        )
        val dispatcher = makeDispatcher(
            payloadForVictim(),
            identities = MutableStateFlow(listOf(selfSummary)).asStateFlow(),
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)

        val updated = groupRepository.snapshots.value.single()
        assertTrue(updated.membershipRevoked)
        // Nothing else moved: history-preserving, secrets untouched.
        assertEquals(1uL, updated.epoch)
        assertTrue(updated.groupSecret.contentEquals(oldSecret))
        assertTrue(updated.salt.contentEquals(oldSalt))
        assertEquals(2, updated.members.size)
        assertFalse(updated.memberProfiles[victimHex]!!.revoked)
    }

    // ─── trust gates ───────────────────────────────────────────────

    @Test
    fun removal_droppedWhenSignerIsNotAdmin() = runTest {
        val dispatcher = makeDispatcher(
            payloadForMembers(),
            senderPub = ByteArray(32) { 0x77 }, // not the admin key
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        assertUntouched()
    }

    @Test
    fun removal_droppedWhenNoSigner() = runTest {
        val dispatcher = makeDispatcher(payloadForMembers(), senderPub = null)
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        assertUntouched()
    }

    @Test
    fun removal_droppedOnExactEpochCommitmentMismatch() = runTest {
        val reader = FakeChainReader(
            SepCommitmentEntry(commitment = ByteArray(32) { 0x66 }, epoch = 2uL),
        )
        val dispatcher = makeDispatcher(payloadForMembers(), chainReader = reader)
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        assertUntouched()
        assertEquals(1, reader.calls)
    }

    @Test
    fun removal_acceptedWhenChainAhead() = runTest {
        // Chain moved past the removal epoch (later joins landed) —
        // the admin-signed removal is still legitimate history.
        val reader = FakeChainReader(
            SepCommitmentEntry(commitment = ByteArray(32) { 0x66 }, epoch = 7uL),
        )
        val dispatcher = makeDispatcher(payloadForMembers(), chainReader = reader)
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        assertTrue(groupRepository.snapshots.value.single().memberProfiles[victimHex]!!.revoked)
    }

    @Test
    fun removal_acceptedOnExactEpochCommitmentMatch() = runTest {
        val reader = FakeChainReader(
            SepCommitmentEntry(commitment = newCommitment, epoch = 2uL),
        )
        val dispatcher = makeDispatcher(payloadForMembers(), chainReader = reader)
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        assertTrue(groupRepository.snapshots.value.single().memberProfiles[victimHex]!!.revoked)
    }

    @Test
    fun removal_chainBehindWithoutRetryScope_isDropped() = runTest {
        val reader = FakeChainReader(
            SepCommitmentEntry(commitment = ByteArray(32) { 0x66 }, epoch = 1uL),
        )
        val dispatcher = makeDispatcher(payloadForMembers(), chainReader = reader)
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        assertUntouched()
    }

    // ─── chain-behind retry ────────────────────────────────────────

    @Test
    fun removal_chainBehind_retriesAndAppliesOnceChainCatchesUp() = runTest {
        // First read: chain still at the pre-removal epoch (the
        // caching reader's ≤10s-stale entry). Second read: caught up.
        val reader = FakeChainReader(
            SepCommitmentEntry(commitment = ByteArray(32) { 0x66 }, epoch = 1uL),
            SepCommitmentEntry(commitment = newCommitment, epoch = 2uL),
        )
        val dispatcher = makeDispatcher(
            payloadForMembers(),
            chainReader = reader,
            // The TestScope itself, NOT backgroundScope: background
            // tasks never drive runTest's virtual clock, so the
            // retry's delay would park forever.
            retryScope = this,
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        // Not applied yet — retry is parked on the virtual clock.
        assertUntouched()

        advanceUntilIdle()

        assertTrue(groupRepository.snapshots.value.single().memberProfiles[victimHex]!!.revoked)
        assertEquals(2, reader.calls)
    }

    @Test
    fun removal_chainStuckBehind_givesUpAfterMaxRetries() = runTest {
        val reader = FakeChainReader(
            SepCommitmentEntry(commitment = ByteArray(32) { 0x66 }, epoch = 1uL),
        )
        val dispatcher = makeDispatcher(
            payloadForMembers(),
            chainReader = reader,
            retryScope = this,
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        advanceUntilIdle()

        assertUntouched()
        // Initial attempt + removalMaxRetries (2) = 3 chain reads.
        assertEquals(3, reader.calls)
    }

    // ─── review regressions ────────────────────────────────────────

    @Test
    fun removal_replayAfterReadmission_doesNotRollStateBack() = runTest {
        // 1. Removal (epoch 2) applies.
        val reader = FakeChainReader(
            SepCommitmentEntry(commitment = newCommitment, epoch = 2uL),
            // Later reads: chain moved on past the re-admission.
            SepCommitmentEntry(commitment = ByteArray(32) { 0x70 }, epoch = 3uL),
        )
        val dispatcher = makeDispatcher(payloadForMembers(), chainReader = reader)
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        assertTrue(groupRepository.snapshots.value.single().memberProfiles[victimHex]!!.revoked)

        // 2. Re-admission: the announcement path resets the tombstone
        //    (local epoch stays at 2 — announcements don't carry state).
        val readmitted = groupRepository.snapshots.value.single()
        groupStore.replaceForTest(
            readmitted.copy(
                memberProfiles = readmitted.memberProfiles +
                    (victimHex to readmitted.memberProfiles[victimHex]!!.copy(revoked = false)),
                groupSecret = ByteArray(32) { 0x71 }, // post-readmission secret
            ),
        )
        groupRepository.reload()

        // 3. The relay replays the OLD epoch-2 removal envelope. The
        //    chain (epoch 3) is ahead — without the local
        //    converge-forward guard this would re-tombstone the member
        //    and roll groupSecret/epoch back to the removal values.
        dispatcher.dispatch("m2", ownerIdentity, byteArrayOf(), Instant.EPOCH)

        val final = groupRepository.snapshots.value.single()
        assertFalse("re-admitted member must stay active", final.memberProfiles[victimHex]!!.revoked)
        assertTrue(
            "post-readmission secret must survive the replay",
            final.groupSecret.contentEquals(ByteArray(32) { 0x71 }),
        )
        assertEquals(2uL, final.epoch)
        // And the replay never even hit the chain (local guard fires first).
        assertEquals(1, reader.calls)
    }

    @Test
    fun removal_failsClosedWhenGroupHasNoStoredAdminKey() = runTest {
        // A group materialized from an unsigned invitation stores no
        // admin Ed25519. senderAuthorizedToMutate's any-current-member
        // fallback must NOT apply to a subtractive op — a member's
        // valid signature is not authority to evict another member.
        val memberSending = ByteArray(32) { 0x42 }
        groupStore.replaceForTest(
            makeGroup().copy(
                adminEd25519PubkeyHex = null,
                memberProfiles = makeGroup().memberProfiles +
                    (otherHex to makeGroup().memberProfiles[otherHex]!!.copy(
                        sendingPubkey = memberSending,
                    )),
            ),
        )
        groupRepository.reload()
        val reader = FakeChainReader(
            SepCommitmentEntry(commitment = newCommitment, epoch = 2uL),
        )
        val dispatcher = makeDispatcher(
            payloadForMembers(),
            senderPub = memberSending, // valid CURRENT member signature
            chainReader = reader,
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)

        val group = groupRepository.snapshots.value.single()
        assertFalse(group.memberProfiles[victimHex]!!.revoked)
        assertEquals(1uL, group.epoch)
        assertEquals(0, reader.calls) // fails closed before any chain read
    }

    @Test
    fun removal_droppedWhenSelfUnresolvable() = runTest {
        // No identitiesFlow → can't classify self vs remaining-member.
        // The safe posture is drop (previously the victim's own device
        // would take the remaining-member branch and delete itself).
        val dispatcher = makeDispatcher(payloadForMembers(), identities = null)
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        assertUntouched()
    }

    // ─── idempotency ───────────────────────────────────────────────

    @Test
    fun removal_redeliveryBailsBeforeChainRead() = runTest {
        val reader = FakeChainReader(
            SepCommitmentEntry(commitment = newCommitment, epoch = 2uL),
        )
        val dispatcher = makeDispatcher(payloadForMembers(), chainReader = reader)
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        dispatcher.dispatch("m2", ownerIdentity, byteArrayOf(), Instant.EPOCH)

        // Second delivery must not have hit the relayer (launch-storm
        // protection) — one read total.
        assertEquals(1, reader.calls)
        assertTrue(groupRepository.snapshots.value.single().memberProfiles[victimHex]!!.revoked)
    }

    @Test
    fun removal_selfRedeliveryBailsWhenAlreadyRevoked() = runTest {
        groupStore.replaceForTest(makeGroup().copy(membershipRevoked = true))
        groupRepository.reload()
        val reader = FakeChainReader(
            SepCommitmentEntry(commitment = newCommitment, epoch = 2uL),
        )
        val selfSummary = IdentitySummary(
            id = ownerIdentity,
            name = "Me",
            blsPublicKey = victimBls,
            inboxPublicKey = ByteArray(32) { 0x21 },
            sendingPublicKey = ByteArray(32) { 0x22 },
        )
        val dispatcher = makeDispatcher(
            payloadForVictim(),
            identities = MutableStateFlow(listOf(selfSummary)).asStateFlow(),
            chainReader = reader,
        )
        dispatcher.dispatch("m1", ownerIdentity, byteArrayOf(), Instant.EPOCH)
        assertEquals(0, reader.calls)
    }

    // ─── helpers ───────────────────────────────────────────────────

    private fun assertUntouched() {
        val group = groupRepository.snapshots.value.single()
        assertFalse(group.memberProfiles[victimHex]!!.revoked)
        assertEquals(1uL, group.epoch)
        assertTrue(group.groupSecret.contentEquals(oldSecret))
        assertFalse(group.membershipRevoked)
    }

    /** Default self-identity: a non-victim member, so the dispatcher
     *  can classify the removal (an unresolvable self now DROPS —
     *  see removal_droppedWhenSelfUnresolvable). */
    private fun nonVictimSelf(): StateFlow<List<IdentitySummary>> = MutableStateFlow(
        listOf(
            IdentitySummary(
                id = ownerIdentity,
                name = "Me",
                blsPublicKey = ByteArray(48) { 0x99.toByte() },
                inboxPublicKey = ByteArray(32) { 0x21 },
                sendingPublicKey = ByteArray(32) { 0x22 },
            ),
        ),
    ).asStateFlow()

    private fun makeDispatcher(
        payload: MemberRemovalPayload,
        senderPub: ByteArray? = adminSending,
        identities: StateFlow<List<IdentitySummary>>? = nonVictimSelf(),
        chainReader: ChainStateReading? = null,
        retryScope: CoroutineScope? = null,
    ): IncomingMessageDispatcher {
        val plaintext = Json.encodeToString(MemberRemovalPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        return IncomingMessageDispatcher(
            envelopeDecrypter = StubRemovalDecrypter(plaintext, senderPub),
            groupRepository = groupRepository,
            invitationsRepository = invitationsRepository,
            identitiesFlow = identities,
            chainState = chainReader,
            retryScope = retryScope,
        )
    }

    private fun payloadForMembers() = MemberRemovalPayload(
        version = 1,
        groupId = groupId,
        removedBlsHex = victimHex,
        commitment = newCommitment,
        epoch = 2uL,
        sentAtMillis = 1_000L,
        groupSecretNew = newSecret,
        saltNew = newSalt,
    )

    private fun payloadForVictim() =
        payloadForMembers().copy(groupSecretNew = null, saltNew = null)

    private fun makeGroup(): ChatGroup = ChatGroup(
        id = groupId.toHexLower(),
        name = "Family",
        groupSecret = oldSecret,
        createdAtMillis = 0L,
        members = listOf(
            GovernanceMember(publicKeyCompressed = victimBls, leafHash = ByteArray(32) { 0x0B }),
            GovernanceMember(publicKeyCompressed = otherBls, leafHash = ByteArray(32) { 0x0C }),
        ),
        memberProfiles = mapOf(
            victimHex to MemberProfile(
                alias = "Victim",
                inboxPublicKey = ByteArray(32) { 0x31 },
                sendingPubkey = ByteArray(32) { 0x32 },
            ),
            otherHex to MemberProfile(
                alias = "Other",
                inboxPublicKey = ByteArray(32) { 0x41 },
                sendingPubkey = ByteArray(32) { 0x42 },
            ),
        ),
        epoch = 1uL,
        salt = oldSalt,
        commitment = ByteArray(32) { 0x50 },
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = "de".repeat(48),
        adminEd25519PubkeyHex = adminSendingHex,
        isPublishedOnChain = true,
        ownerIdentityId = ownerIdentity.value,
    )

    private companion object {
        fun ByteArray.toHexLower(): String = joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }
    }
}

/** Programmable chain reader: serves [entries] in order, repeating
 *  the last one, counting calls. */
private class FakeChainReader(
    private vararg val entries: SepCommitmentEntry,
) : ChainStateReading {
    var calls = 0
        private set

    override suspend fun tyrannyCommitment(groupId: ByteArray): SepCommitmentEntry {
        val entry = entries[minOf(calls, entries.size - 1)]
        calls += 1
        return entry
    }
}

private class StubRemovalDecrypter(
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

private class RemovalTestGroupStore : GroupStore {
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

    fun replaceForTest(group: ChatGroup) {
        rows[group.id] = group
    }
}
