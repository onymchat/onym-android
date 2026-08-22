package app.onym.android.group

import app.onym.android.chain.GovernanceMember
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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
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
 * Decode, approve/decline and the multi-use contract. Anarchy keeps
 * the chain deps out, so the whole path runs on the JVM with fakes.
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

    /**
     * A create-time offer key is 1:1 with one invitee and spent once
     * they are in, so approve retires it — while the shared link, which
     * is meant to be multi-use, survives.
     *
     * Matched on the LINK the request decoded against, not the joiner's
     * 4-byte inbox fingerprint. A fingerprint match broke two ways: a
     * reinstalled joiner presents a fresh inbox key, so their offer key
     * would never be retired; and two invitees can collide on four
     * bytes, so a bystander's link would be revoked alongside. Both are
     * pinned below.
     */
    @Test
    fun approve_retiresTheOfferKeyItArrivedOn_andSparesTheSharedLink() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed()
            val offer = env.seedOfferKey(label = "aabbccdd", seedByte = 0x51)
            val joiner = env.seedJoiner(
                bls = 0xC1, inbox = 0xC2, alias = "Bob", arrivesOn = offer,
            )
            env.approver.pumpOnce()

            assertEquals(
                JoinRequestApprover.ApproveOutcome.Sent,
                env.approver.approve(joiner.requestId),
            )

            assertNull(
                "the spent offer key must not keep a private link alive",
                env.introKeyStore.find(offer),
            )
            assertNotNull(
                "the group's shared link is multi-use and must survive",
                env.introKeyStore.find(env.introPub),
            )
        }

    @Test
    fun approve_doesNotRetireABystandersKeySharingTheFingerprintLabel() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed()
            // Same 4-byte label, different invitee — the collision the
            // share screen's own test-tag comment calls out.
            val used = env.seedOfferKey(label = "aabbccdd", seedByte = 0x61)
            val bystander = env.seedOfferKey(label = "aabbccdd", seedByte = 0x62)
            val joiner = env.seedJoiner(
                bls = 0xC1, inbox = 0xC2, alias = "Bob", arrivesOn = used,
            )
            env.approver.pumpOnce()

            env.approver.approve(joiner.requestId)

            assertNull(env.introKeyStore.find(used))
            assertNotNull(
                "a label match would have revoked an unrelated invitee's link",
                env.introKeyStore.find(bystander),
            )
        }

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

            // The whole feature. Before, revoking here made B's request
            // undecodable and B's row silently vanished.
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

            // Collapse is per joiner, not per key; over-merging would
            // silently drop invitees.
            assertEquals(2, env.approver.pending.value.size)
        }

    @Test
    fun approve_afterTheLinkIsRevoked_requestNoLongerDecodes() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed()
            val a = env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob")
            env.approver.pumpOnce()
            assertEquals(1, env.approver.pending.value.size)

            // Revoking is what kills a link now; an in-flight request on
            // one stops decoding.
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

            // Reconnects replay the inbox in full, and the key is still
            // live — without the tombstone this decodes again.
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
            // Straight into the roster — what an earlier approve, or a
            // reinstall, leaves behind.
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

    // ─── the rules a joiner is asked to sign ──────────────────────

    @Test
    fun aRequestDecodedBeforeTheGroupLoaded_isDecidedAgainWhenItArrives() =
        runTest(UnconfinedTestDispatcher()) {
            // Persisted intro requests routinely emit before the group
            // store hydrates. Decided once in that window, every row
            // would carry "this group has no rules" — a claim, made by
            // omission, standing until some unrelated request happened
            // to arrive. The collector watches group snapshots for
            // exactly this, and `PendingRequest` compares its verdict so
            // the re-decision actually reaches the flow.
            val env = seed()
            env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob", agreesTo = "Be kind.")
            env.approver.start()
            assertEquals(
                "precondition: nothing to check it against yet",
                JoinRequestApprover.RulesAgreement.NOT_REQUIRED,
                env.approver.pending.value.single().rulesAgreement,
            )

            env.groupRepository.insert(env.currentGroup().copy(invitationMessage = "Be kind."))

            assertEquals(
                JoinRequestApprover.RulesAgreement.AGREED,
                env.approver.pending.value.single().rulesAgreement,
            )
            coroutineContext.cancelChildren()
        }

    // ─── harness ──────────────────────────────────────────────────

    private data class SeededJoiner(
        val requestId: String,
        val blsPub: ByteArray,
        val inboxPub: ByteArray,
        val sealed: ByteArray,
        val expectedTag: TransportInboxId,
    )

    // ─── the rules a joiner is asked to sign ──────────────────────

    @Test
    fun aGroupsInvitationMessage_isWhatTheJoinerIsAskedToSign() =
        runTest(UnconfinedTestDispatcher()) {
            // The conflation, pinned: since the rename, a group's
            // invitation message *is* its rules, and this is the only
            // place that fact is load-bearing. A joiner who signed that
            // text reads as agreed; the same joiner signing anything
            // else does not.
            val env = seed(rules = "Be kind. No links.")
            env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob", agreesTo = "Be kind. No links.")
            env.approver.pumpOnce()

            assertEquals(
                JoinRequestApprover.RulesAgreement.AGREED,
                env.approver.pending.value.single().rulesAgreement,
            )
        }

    @Test
    fun aJoinerWhoSignedAnotherText_readsAsUnknownRatherThanAgreed() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed(rules = "Be kind. No links.")
            env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob", agreesTo = "Anything goes.")
            env.approver.pumpOnce()

            assertEquals(
                JoinRequestApprover.RulesAgreement.UNKNOWN_RULES,
                env.approver.pending.value.single().rulesAgreement,
            )
        }

    @Test
    fun aGroupWithNoRules_asksForNothing() =
        runTest(UnconfinedTestDispatcher()) {
            val env = seed()
            env.seedJoiner(bls = 0xC1, inbox = 0xC2, alias = "Bob")
            env.approver.pumpOnce()

            assertEquals(
                JoinRequestApprover.RulesAgreement.NOT_REQUIRED,
                env.approver.pending.value.single().rulesAgreement,
            )
        }

    @Test
    fun approve_keepsTheAgreementAndTheTextItCovers() =
        runTest(UnconfinedTestDispatcher()) {
            // The request is consumed at approval, so what the founder
            // decided in front of has to be retained on the member —
            // with the words, not a pointer to whatever the group says
            // by then.
            val env = seed(rules = "Be kind. No links.")
            val joiner = env.seedJoiner(
                bls = 0xC1, inbox = 0xC2, alias = "Bob", agreesTo = "Be kind. No links.",
            )
            env.approver.pumpOnce()

            env.approver.approve(joiner.requestId)

            val key = joiner.blsPub.joinToString("") { "%02x".format(it) }
            val member = env.currentGroup().memberProfiles[key]!!
            assertEquals("Be kind. No links.", member.rulesText)
            assertTrue(member.agreedToRules(groupId))
        }

    @Test
    fun aFoundersLaterEdit_doesNotUnpickAnAgreementAlreadyRecorded() =
        runTest(UnconfinedTestDispatcher()) {
            // The whole reason the text is stored beside the signature
            // rather than read back off the live group.
            val env = seed(rules = "Be kind. No links.")
            val joiner = env.seedJoiner(
                bls = 0xC1, inbox = 0xC2, alias = "Bob", agreesTo = "Be kind. No links.",
            )
            env.approver.pumpOnce()
            env.approver.approve(joiner.requestId)

            env.groupRepository.insert(
                env.currentGroup().copy(invitationMessage = "Rewritten afterwards."),
            )

            val key = joiner.blsPub.joinToString("") { "%02x".format(it) }
            val member = env.currentGroup().memberProfiles[key]!!
            assertTrue(member.agreedToRules(groupId))
        }

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
        /** Persist a per-invitee offer key and return its pubkey. */
        suspend fun seedOfferKey(label: String, seedByte: Int): ByteArray {
            val priv = X25519PrivateKeyParameters(SecureRandom())
            val pub = priv.generatePublicKey().encoded
            introKeyStore.save(
                IntroKeyEntry(
                    introPublicKey = pub,
                    introPrivateKey = priv.encoded,
                    ownerIdentityId = owner,
                    groupId = groupId,
                    createdAtMillis = 1_700_000_000_000L + seedByte,
                    label = label,
                ),
            )
            return pub
        }

        suspend fun seedJoiner(
            bls: Int,
            inbox: Int,
            alias: String,
            atSeconds: Long = 10,
            /** The link the request arrives on. Defaults to the
             *  group's shared link; pass an offer key to exercise the
             *  spent-offer-key path. */
            arrivesOn: ByteArray = introPub,
            /** The text this joiner signs, if any. Signed with a real
             *  key, whose public half the request announces — the same
             *  key the founder verifies against. */
            agreesTo: String? = null,
        ): SeededJoiner {
            val blsPub = ByteArray(48) { bls.toByte() }
            val inboxPub = ByteArray(32) { inbox.toByte() }
            val signingKey = Ed25519PrivateKeyParameters(ByteArray(32) { (bls + 3).toByte() }, 0)
            val sendingPub = if (agreesTo == null) {
                ByteArray(32) { (bls + 2).toByte() }
            } else {
                signingKey.generatePublicKey().encoded
            }
            val rulesHash = agreesTo?.let { GroupRules.hash(it) }
            val rulesSignature = rulesHash?.let { hash ->
                val statement = GroupRules.statement(groupId, hash, sendingPub)
                Ed25519Signer().apply {
                    init(true, signingKey)
                    update(statement, 0, statement.size)
                }.generateSignature()
            }
            val payload = JoinRequestPayload(
                joinerInboxPublicKey = inboxPub,
                joinerBlsPublicKey = blsPub,
                joinerLeafHash = ByteArray(32) { (bls + 1).toByte() },
                joinerSendingPublicKey = sendingPub,
                joinerDisplayLabel = alias,
                groupId = groupId,
                rulesHash = rulesHash,
                rulesSignature = rulesSignature,
            )
            val sealed = TestInvitationEncryptor.envelopeBytes(
                payload = Json.encodeToString(JoinRequestPayload.serializer(), payload)
                    .toByteArray(Charsets.UTF_8),
                recipientX25519Pubkey = arrivesOn,
            )
            val requestId = "req-${counter++}"
            introRequestStore.record(
                IntroRequest(
                    id = requestId,
                    targetIntroPublicKey = arrivesOn,
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
         * Same payload under a fresh event id — a retry or replay, each
         * distinct because every send uses a fresh ephemeral key.
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
        /** The group's rules — its invitation message, which is what a
         *  joiner is shown and asked to sign. */
        rules: String? = null,
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
                invitationMessage = rules,
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
