package app.onym.android.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.chain.ChainStateReading
import app.onym.android.chain.SepCommitmentEntry
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.GovernanceMember
import app.onym.android.group.GroupCommitmentBuilder
import app.onym.android.group.GroupInvitationPayload
import app.onym.android.group.GroupRepository
import app.onym.android.identity.IdentityId
import app.onym.android.persistence.InMemoryInvitationStore
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.FakeInvitationEnvelopeDecrypter
import app.onym.android.support.InMemoryGroupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * FFI-backed verify-at-current tests for [IncomingMessageDispatcher]
 * (PR 159). Lives in `androidTest/` because the Tyranny invitation
 * verifier recomputes the Poseidon commitment via [GroupCommitmentBuilder],
 * whose native `.so` only loads on the device runtime.
 *
 *  - exact epoch + matching commitment → materializes (the salt-gated
 *    forgery gate).
 *  - chain ahead of the snapshot → deferred to the verifier, NOT
 *    materialized (an unverifiable snapshot must not become a chat).
 *
 * Mirrors `test_invitation_tyranny_chainAhead_defersAndDoesNotMaterialize`
 * from onym-ios PR #159.
 */
@RunWith(AndroidJUnit4::class)
class IncomingMessageDispatcherConvergeForwardFfiTest {

    private val owner = IdentityId("owner")
    private val groupId = ByteArray(32) { 0x42 }
    private val salt = ByteArray(32) { 0x66 }

    @Test
    fun invitation_tyranny_exactEpoch_materializes() = runBlocking {
        val env = environment()
        val realCommitment = realCommitment(epoch = 0uL)
        val plaintext = encode(invitation(commitment = realCommitment, epoch = 0uL))
        val refresher = SpyRefresher()
        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = FakeInvitationEnvelopeDecrypter(
                FakeInvitationEnvelopeDecrypter.Mode.Fixed(plaintext),
            ),
            groupRepository = env.groups,
            invitationsRepository = env.invitations,
            // Chain EXACTLY at the snapshot epoch with the SAME commitment.
            chainState = FakeChainStateFor(SepCommitmentEntry(commitment = realCommitment, epoch = 0uL)),
            groupStateRefresher = refresher,
        )

        dispatcher.dispatch("m-exact", owner, byteArrayOf(), Instant.EPOCH)

        assertNotNull(
            "exact-epoch byte-verified snapshot should materialize",
            env.groups.snapshots.value.firstOrNull { it.groupIdBytes.contentEquals(groupId) },
        )
        assertTrue(refresher.deferred.isEmpty())
    }

    @Test
    fun invitation_tyranny_chainAhead_defersAndDoesNotMaterialize() = runBlocking {
        val env = environment()
        // Snapshot at epoch 0; chain advanced to epoch 5 (different
        // commitment). Can't be byte-verified → defer, don't materialize.
        val plaintext = encode(invitation(commitment = realCommitment(epoch = 0uL), epoch = 0uL))
        val refresher = SpyRefresher()
        val dispatcher = IncomingMessageDispatcher(
            envelopeDecrypter = FakeInvitationEnvelopeDecrypter(
                FakeInvitationEnvelopeDecrypter.Mode.Fixed(plaintext),
            ),
            groupRepository = env.groups,
            invitationsRepository = env.invitations,
            chainState = FakeChainStateFor(
                SepCommitmentEntry(commitment = ByteArray(32) { 0x99.toByte() }, epoch = 5uL),
            ),
            groupStateRefresher = refresher,
        )

        dispatcher.dispatch("m-stale", owner, byteArrayOf(), Instant.EPOCH)

        assertTrue(
            "a stale snapshot must not materialize",
            env.groups.snapshots.value.none { it.groupIdBytes.contentEquals(groupId) },
        )
        assertEquals(
            "stale invitation should be deferred to the verifier",
            listOf(groupId.toList()),
            refresher.deferred.map { it.toList() },
        )
    }

    // ─── helpers ──────────────────────────────────────────────────

    private class Env(val groups: GroupRepository, val invitations: IncomingInvitationsRepository)

    private fun environment(): Env {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        return Env(
            groups = GroupRepository(
                store = InMemoryGroupStore(),
                identity = FakeActiveIdentityProvider(owner),
                scope = scope,
            ),
            invitations = IncomingInvitationsRepository(
                store = InMemoryInvitationStore(),
                identity = FakeActiveIdentityProvider(owner),
                scope = scope,
            ),
        )
    }

    private val members = listOf(
        GovernanceMember(
            publicKeyCompressed = GroupCommitmentBuilder.computePublicKey(ByteArray(32) { 0x42 }),
            leafHash = GroupCommitmentBuilder.computeLeafHash(ByteArray(32) { 0x42 }),
        ),
    )

    private fun realCommitment(epoch: ULong): ByteArray =
        GroupCommitmentBuilder.computePoseidonCommitment(
            poseidonRoot = GroupCommitmentBuilder.computeMerkleRoot(members, SepTier.SMALL),
            epoch = epoch,
            salt = salt,
        )

    private fun invitation(commitment: ByteArray, epoch: ULong) = GroupInvitationPayload(
        version = 1,
        groupId = groupId,
        groupSecret = ByteArray(32) { 0x33 },
        name = "Family",
        members = members,
        epoch = epoch,
        salt = salt,
        commitment = commitment,
        tierRaw = SepTier.SMALL.rawValue,
        groupTypeRaw = SepGroupType.TYRANNY.wireValue,
        adminPubkeyHex = members.first().publicKeyCompressed
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) },
    )

    private fun encode(invitation: GroupInvitationPayload): ByteArray =
        Json.encodeToString(GroupInvitationPayload.serializer(), invitation)
            .toByteArray(Charsets.UTF_8)
}

private class FakeChainStateFor(private val entry: SepCommitmentEntry) : ChainStateReading {
    override suspend fun tyrannyCommitment(groupId: ByteArray): SepCommitmentEntry = entry
}

private class SpyRefresher : GroupStateRefreshing {
    val deferred = mutableListOf<ByteArray>()
    override suspend fun deferVerification(
        invitation: GroupInvitationPayload,
        ownerIdentityId: IdentityId,
    ) {
        deferred.add(invitation.groupId)
    }

    override suspend fun handleRefreshRequest(
        request: app.onym.android.group.GroupStateRefreshRequest,
        ownerIdentityId: IdentityId,
        requesterEd25519: ByteArray?,
    ) {}
}
