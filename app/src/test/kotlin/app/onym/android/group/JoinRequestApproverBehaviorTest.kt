package app.onym.android.group

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentityRepository
import app.onym.android.support.ConfigurableInboxTransport
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryIntroKeyStore
import app.onym.android.support.PassThroughEnvelopeSealer
import app.onym.android.support.TestInvitationEncryptor
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.SecureRandom
import java.security.Security
import java.time.Instant

/**
 * Behavioral coverage for [JoinRequestApprover]: the decode pipeline,
 * approve/decline, and the multi-use-link contract.
 *
 * Runs on the JVM against `SepGroupType.ANARCHY`. The relayer,
 * contract, proof and network dependencies are touched only inside
 * `anchorTyrannyJoin`, so a non-Tyranny group exercises the whole
 * decode → seal → send → record → consume path with four fakes and no
 * chain stack. Epoch assertions that need a real anchor live in the
 * instrumented suite.
 *
 * Mirrors the multi-use tests in onym-ios `JoinRequestApproverTests`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinRequestApproverBehaviorTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUpBouncyCastle() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 2)
            }
        }
    }

    private val owner = IdentityId("alice-uuid")
    private val groupId = ByteArray(32) { 0x42 }
    private val adminBls = ByteArray(48) { 0x0A }

    // ─── the multi-use contract ───────────────────────────────────

    @Test
    fun approve_twoJoinersSequentially_secondStillDecodesOnTheSameIntroKey() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed()
            val a = env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob")
            val b = env.seedJoiner(bls = 0xB1, inbox = 0xB2, alias = "Carol")
            env.approver.pumpOnce()

            assertEquals(2, env.approver.pending.value.size)

            assertEquals(
                JoinRequestApprover.ApproveOutcome.Sent,
                env.approver.approve(a.requestId),
            )

            // The link survives the first approval — the whole feature.
            // Before, revoking here made B's request undecodable and
            // B's row silently vanished.
            assertNotNull(
                "approving A must not burn the link B is holding",
                env.introKeyStore.find(env.introPub),
            )

            env.approver.pumpOnce()
            assertEquals(1, env.approver.pending.value.size)
            assertTrue(
                env.approver.pending.value.first().joinerBlsPublicKey!!.contentEquals(b.blsPub),
            )

            assertEquals(
                JoinRequestApprover.ApproveOutcome.Sent,
                env.approver.approve(b.requestId),
            )

            val group = env.currentGroup()
            assertEquals(2, group.memberProfiles.size - 1) // minus the admin
            val sends = env.transport.sends()
            assertTrue(sends.any { it.inbox == a.expectedTag })
            assertTrue(sends.any { it.inbox == b.expectedTag })
        }

    @Test
    fun decline_dropsOnlyThatRequest_andKeepsIntroKeyLive() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed()
            val a = env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob")
            val b = env.seedJoiner(bls = 0xB1, inbox = 0xB2, alias = "Carol")
            env.approver.pumpOnce()

            env.approver.decline(a.requestId)
            env.approver.pumpOnce()

            assertNotNull(
                "declining one joiner must not kill the link for everyone else",
                env.introKeyStore.find(env.introPub),
            )
            assertEquals(1, env.approver.pending.value.size)
            assertTrue(
                env.approver.pending.value.first().joinerBlsPublicKey!!.contentEquals(b.blsPub),
            )
            assertTrue("decline ships no NACK", env.transport.sends().isEmpty())

            assertEquals(
                JoinRequestApprover.ApproveOutcome.Sent,
                env.approver.approve(b.requestId),
            )
        }

    @Test
    fun pumpOnce_doesNotCollapseDifferentJoinersOnSameIntroKey() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed()
            env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob")
            env.seedJoiner(bls = 0xB1, inbox = 0xB2, alias = "Carol")
            env.approver.pumpOnce()

            // The collapse key is per joiner identity, not per intro
            // key. Over-merging here would silently drop invitees and
            // neuter the whole feature.
            assertEquals(2, env.approver.pending.value.size)
        }

    @Test
    fun approve_afterTheLinkIsRevoked_requestNoLongerDecodes() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed()
            val a = env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob")
            env.approver.pumpOnce()
            assertEquals(1, env.approver.pending.value.size)

            // Links no longer expire; revoking is what kills one. An
            // in-flight request on a revoked link stops decoding, which
            // is what makes the joiner's Unanswered state reachable.
            env.introKeyStore.revoke(env.introPub)
            env.approver.pumpOnce()

            assertTrue(env.approver.pending.value.isEmpty())
            assertEquals(
                JoinRequestApprover.ApproveOutcome.UnknownRequest,
                env.approver.approve(a.requestId),
            )
        }

    // ─── collapse + replay ────────────────────────────────────────

    @Test
    fun pumpOnce_collapsesRepeatRequestsFromSameJoiner_keepingNewest() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed()
            val first = env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob", atSeconds = 10)
            val newest = env.reRecord(first, atSeconds = 30)
            env.reRecord(first, atSeconds = 20)

            env.approver.pumpOnce()

            assertEquals(1, env.approver.pending.value.size)
            assertEquals(newest, env.approver.pending.value.first().id)
        }

    @Test
    fun approve_consumesEveryCollapsedSibling() = runTest(UnconfinedTestDispatcher()) {
        val env = seed()
        val a = env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob", atSeconds = 10)
        env.reRecord(a, atSeconds = 20)
        env.reRecord(a, atSeconds = 30)
        env.approver.pumpOnce()

        val winner = env.approver.pending.value.single().id
        assertEquals(
            JoinRequestApprover.ApproveOutcome.Sent,
            env.approver.approve(winner),
        )

        // Consuming only the winner would leave the siblings behind to
        // resurface on the next emission.
        assertTrue(env.introRequestStore.requests.value.isEmpty())
        env.approver.pumpOnce()
        assertTrue(env.approver.pending.value.isEmpty())
    }

    @Test
    fun decline_consumesEveryCollapsedSibling() = runTest(UnconfinedTestDispatcher()) {
        val env = seed()
        val a = env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob", atSeconds = 10)
        env.reRecord(a, atSeconds = 20)
        env.approver.pumpOnce()

        env.approver.decline(env.approver.pending.value.single().id)

        assertTrue(env.introRequestStore.requests.value.isEmpty())
        env.approver.pumpOnce()
        assertTrue(env.approver.pending.value.isEmpty())
    }

    @Test
    fun approvedRequest_replayedByRelay_doesNotReappearAsPending() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed()
            val a = env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob")
            env.approver.pumpOnce()
            env.approver.approve(a.requestId)

            // Every relay reconnect replays the inbox in full — the REQ
            // carries no `since` and no `limit`. The intro key is still
            // live now, so without the consume-tombstone this decodes
            // again and the admin sees a request they already approved.
            val recorded = env.introRequestStore.record(
                IntroRequest(
                    id = a.requestId,
                    targetIntroPublicKey = env.introPub,
                    payload = a.sealed,
                    receivedAt = Instant.ofEpochSecond(9_999),
                ),
            )

            assertFalse("a consumed event id must not be re-recorded", recorded)
            env.approver.pumpOnce()
            assertTrue(env.approver.pending.value.isEmpty())
        }

    // ─── re-join recovery ─────────────────────────────────────────

    @Test
    fun approve_alreadyInRoster_reshipsSnapshotWithoutTouchingTheRoster() =
        runTest(UnconfinedTestDispatcher()) {
            // Seed the joiner straight into the cryptographic roster —
            // the state an earlier approve, or a reinstall, leaves
            // behind.
            val joinerBls = ByteArray(48) { 0xC1.toByte() }
            val env = seed(
                extraMembers = listOf(
                    GovernanceMember(
                        publicKeyCompressed = joinerBls,
                        leafHash = ByteArray(32) { 0xC3.toByte() },
                    ),
                ),
            )
            val a = env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob")
            env.approver.pumpOnce()

            val before = env.currentGroup()
            assertEquals(
                JoinRequestApprover.ApproveOutcome.Sent,
                env.approver.approve(a.requestId),
            )
            val after = env.currentGroup()

            // No duplicate leaf, no epoch burned.
            assertEquals(before.members.size, after.members.size)
            assertEquals(before.epoch, after.epoch)
            // But the joiner did get the snapshot again — the point of
            // the recovery path.
            assertTrue(env.transport.sends().any { it.inbox == a.expectedTag })
        }

    // ─── gates ────────────────────────────────────────────────────

    @Test
    fun approve_unknownRequestId_returnsUnknownRequest() = runTest(UnconfinedTestDispatcher()) {
        val env = seed()
        assertEquals(
            JoinRequestApprover.ApproveOutcome.UnknownRequest,
            env.approver.approve("never-seen"),
        )
    }

    @Test
    fun approve_noIdentitySelected_returnsNoIdentityLoaded() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed()
            val a = env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob")
            env.approver.pumpOnce()
            env.activeIdentity.setActive(null)

            assertEquals(
                JoinRequestApprover.ApproveOutcome.NoIdentityLoaded,
                env.approver.approve(a.requestId),
            )
        }

    @Test
    fun approve_noRelayAccepted_leavesRequestPendingForRetry() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed()
            val a = env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob")
            env.approver.pumpOnce()
            env.transport.setReceiptAcceptedBy(0)

            val outcome = env.approver.approve(a.requestId)

            assertTrue(outcome is JoinRequestApprover.ApproveOutcome.TransportFailed)
            assertEquals(1, env.introRequestStore.requests.value.size)
            assertNotNull(env.introKeyStore.find(env.introPub))
        }

    // ─── harness ──────────────────────────────────────────────────

    private data class SeededJoiner(
        val requestId: String,
        val blsPub: ByteArray,
        val inboxPub: ByteArray,
        val sealed: ByteArray,
        val expectedTag: TransportInboxId,
    )

    private inner class Env(
        val approver: JoinRequestApprover,
        val introKeyStore: InMemoryIntroKeyStore,
        val introRequestStore: InMemoryIntroRequestStore,
        val groupRepository: GroupRepository,
        val transport: ConfigurableInboxTransport,
        val activeIdentity: FakeActiveIdentityProvider,
        val introPub: ByteArray,
    ) {
        private var counter = 0

        suspend fun currentGroup(): ChatGroup =
            groupRepository.snapshots.value.single { it.groupIdBytes.contentEquals(groupId) }

        /** Seal one joiner's request to the shared intro key. */
        suspend fun seedJoiner(
            bls: Int,
            inbox: Int,
            alias: String,
            atSeconds: Long = 10,
        ): SeededJoiner {
            val blsPub = ByteArray(48) { bls.toByte() }
            val inboxPub = ByteArray(32) { inbox.toByte() }
            val payload = JoinRequestPayload(
                joinerInboxPublicKey = inboxPub,
                joinerBlsPublicKey = blsPub,
                joinerLeafHash = ByteArray(32) { (bls + 1).toByte() },
                joinerSendingPublicKey = ByteArray(32) { (bls + 2).toByte() },
                joinerDisplayLabel = alias,
                groupId = groupId,
            )
            val sealed = TestInvitationEncryptor.envelopeBytes(
                payload = Json.encodeToString(JoinRequestPayload.serializer(), payload)
                    .toByteArray(Charsets.UTF_8),
                recipientX25519Pubkey = introPub,
            )
            val requestId = "req-${counter++}"
            introRequestStore.record(
                IntroRequest(
                    id = requestId,
                    targetIntroPublicKey = introPub,
                    payload = sealed,
                    receivedAt = Instant.ofEpochSecond(atSeconds),
                ),
            )
            return SeededJoiner(
                requestId = requestId,
                blsPub = blsPub,
                inboxPub = inboxPub,
                sealed = sealed,
                expectedTag = TransportInboxId(IdentityRepository.inboxTag(inboxPub)),
            )
        }

        /**
         * Record the same sealed payload under a fresh event id — a
         * retry or a relay replay, which each carry a distinct id
         * because every send is signed by a fresh ephemeral key.
         * Returns the new id.
         */
        suspend fun reRecord(joiner: SeededJoiner, atSeconds: Long): String {
            val id = "req-${counter++}"
            introRequestStore.record(
                IntroRequest(
                    id = id,
                    targetIntroPublicKey = introPub,
                    payload = joiner.sealed,
                    receivedAt = Instant.ofEpochSecond(atSeconds),
                ),
            )
            return id
        }
    }

    private suspend fun TestScope.seed(
        extraMembers: List<GovernanceMember> = emptyList(),
    ): Env {
        val introKeyStore = InMemoryIntroKeyStore()
        val introPrivate = X25519PrivateKeyParameters(SecureRandom())
        val introPub = introPrivate.generatePublicKey().encoded
        introKeyStore.save(
            IntroKeyEntry(
                introPublicKey = introPub,
                introPrivateKey = introPrivate.encoded,
                ownerIdentityId = owner,
                groupId = groupId,
                createdAtMillis = 1_700_000_000_000L,
            ),
        )

        val activeIdentity = FakeActiveIdentityProvider(owner)
        val groupStore = InMemoryGroupStore()
        val groupRepository = GroupRepository(groupStore, activeIdentity, this)
        groupRepository.insert(
            ChatGroup(
                id = groupId.joinToString("") { "%02x".format(it) },
                name = "Family",
                groupSecret = ByteArray(32) { 0x55 },
                createdAtMillis = 1_700_000_000_000L,
                members = listOf(
                    GovernanceMember(
                        publicKeyCompressed = adminBls,
                        leafHash = ByteArray(32) { 0x0B },
                    ),
                ) + extraMembers,
                memberProfiles = mapOf(
                    adminBls.joinToString("") { "%02x".format(it) } to MemberProfile(
                        alias = "Admin",
                        inboxPublicKey = ByteArray(32) { 0x0C },
                        sendingPubkey = ByteArray(32) { 0x0D },
                    ),
                ),
                epoch = 0uL,
                salt = ByteArray(32) { 0x66 },
                commitment = ByteArray(32) { 0x77 },
                tier = SepTier.SMALL,
                // Anarchy skips the chain anchor entirely, which is what
                // lets this whole suite run on the JVM.
                groupType = SepGroupType.ANARCHY,
                adminPubkeyHex = adminBls.joinToString("") { "%02x".format(it) },
                ownerIdentityId = owner.value,
                isPublishedOnChain = true,
            ),
        )

        val introRequestStore = InMemoryIntroRequestStore()
        val transport = ConfigurableInboxTransport()
        val approver = JoinRequestApprover(
            activeIdentity = activeIdentity,
            envelopeSealer = PassThroughEnvelopeSealer(),
            blsSecretKey = { ByteArray(32) { 0x01 } },
            introKeyStore = introKeyStore,
            introRequestStore = introRequestStore,
            groupRepository = groupRepository,
            inboxTransport = transport,
            scope = this,
        )
        return Env(
            approver = approver,
            introKeyStore = introKeyStore,
            introRequestStore = introRequestStore,
            groupRepository = groupRepository,
            transport = transport,
            activeIdentity = activeIdentity,
            introPub = introPub,
        )
    }
}
