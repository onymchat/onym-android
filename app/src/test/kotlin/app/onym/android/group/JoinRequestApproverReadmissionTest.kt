package app.onym.android.group

import app.onym.android.chain.AppNetwork
import app.onym.android.chain.ContractEntry
import app.onym.android.chain.ContractNetwork
import app.onym.android.chain.ContractRelease
import app.onym.android.chain.ContractsManifest
import app.onym.android.chain.ContractsRepository
import app.onym.android.chain.GovernanceType
import app.onym.android.chain.RelayerConfiguration
import app.onym.android.chain.RelayerEndpoint
import app.onym.android.chain.RelayerRepository
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.chain.StaticNetworkPreferenceProvider
import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.IdentitySummary
import app.onym.android.identity.InvitationEnvelopeSealer
import app.onym.android.support.ConfigurableInboxTransport
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.FakeContractsManifestFetcher
import app.onym.android.support.FakeKnownRelayersFetcher
import app.onym.android.support.FakeSepContractTransport
import app.onym.android.support.InMemoryAnchorSelectionStore
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryIntroKeyStore
import app.onym.android.support.InMemoryRelayerSelectionStore
import app.onym.android.support.StubGroupProofGenerator
import app.onym.android.support.TestInvitationEncryptor
import app.onym.android.transport.TransportInboxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.time.Instant

/**
 * Behavioral test for [JoinRequestApprover.approve] on the
 * **re-admission** path — the ordering the self-removal replay guard
 * depends on.
 *
 * A re-admitted member's own [MemberProfile] is still their removal
 * tombstone at approve time. The invitation snapshot filters
 * `revoked` profiles off the wire (they'd be pure disclosure to a
 * fresh joiner), so if the joiner were recorded AFTER the snapshot
 * were built, they'd materialize with a null
 * [MemberProfile.statusEpoch] and the next relay replay of the old
 * removal would re-lock their composer for good. Recording first
 * un-revokes + epoch-stamps the entry so it survives the filter.
 *
 * JVM-safe: the JNI-backed commitment math is injected
 * ([computeMerkleRoot] / [computePublicKey] stand-ins), the PLONK
 * prover is [StubGroupProofGenerator], and the identity stack is the
 * [app.onym.android.identity.ActiveIdentityProvider] +
 * [InvitationEnvelopeSealer] + `blsSecretKey` seam rather than the
 * real keychain.
 */
class JoinRequestApproverReadmissionTest {

    private val ownerIdentity = IdentityId("owner")
    private val groupId = ByteArray(32) { 0xAA.toByte() }
    private val groupIdHex = groupId.toHexLower()

    private val adminBls = ByteArray(48) { 0x0A }
    private val adminHex = adminBls.toHexLower()
    private val adminSecret = ByteArray(32) { 0x0A }
    private val adminInbox = ByteArray(32) { 0x21 }

    /** The re-admitted member: removed at epoch 2, now coming back. */
    private val joinerBls = ByteArray(48) { 0xBB.toByte() }
    private val joinerHex = joinerBls.toHexLower()
    private val joinerInbox = ByteArray(32) { 0x31 }
    private val joinerSending = ByteArray(32) { 0x32 }
    private val joinerLeaf = ByteArray(32) { 0x0B }

    private lateinit var groupRepository: GroupRepository
    private lateinit var inboxTransport: ConfigurableInboxTransport
    private lateinit var introKeyStore: InMemoryIntroKeyStore
    private lateinit var introRequestStore: InMemoryIntroRequestStore
    private lateinit var relayers: RelayerRepository
    private lateinit var contracts: ContractsRepository

    @Before
    fun setUp() = runTest {
        groupRepository = GroupRepository(
            store = InMemoryGroupStore(),
            identity = FakeActiveIdentityProvider(ownerIdentity),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        groupRepository.insert(makeGroupWithTombstonedJoiner())
        groupRepository.reload()

        inboxTransport = ConfigurableInboxTransport()
        introKeyStore = InMemoryIntroKeyStore()
        introRequestStore = InMemoryIntroRequestStore()

        val relayerStore = InMemoryRelayerSelectionStore()
        relayerStore.saveConfiguration(
            RelayerConfiguration(
                endpoints = listOf(
                    RelayerEndpoint("test", "https://relayer.test.invalid", listOf("testnet")),
                ),
                hasUserInteracted = true,
            ),
        )
        relayers = RelayerRepository(
            store = relayerStore,
            fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(emptyList())),
        )
        relayers.bootstrap()

        contracts = ContractsRepository(
            store = InMemoryAnchorSelectionStore(),
            fetcher = FakeContractsManifestFetcher(
                FakeContractsManifestFetcher.Mode.Succeeds(
                    ContractsManifest(
                        version = 1,
                        releases = listOf(
                            ContractRelease(
                                release = "v0.0.2",
                                publishedAt = Instant.parse("2023-11-14T00:00:02Z"),
                                contracts = listOf(
                                    ContractEntry(
                                        network = ContractNetwork.Testnet,
                                        type = GovernanceType.Tyranny,
                                        id = "CTESTTYRANNY000000000000000000000000000000000000000000000",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        contracts.bootstrap()
        contracts.start()
    }

    /**
     * The invitation shipped to a re-admitted joiner must carry their
     * own un-revoked profile, stamped with the post-anchor epoch.
     *
     * Neutering the fix (recording the joiner after the payload is
     * built) drops them from `member_profiles` entirely — the
     * `!revoked` filter still sees the tombstone — and this fails on
     * the `assertNotNull` below.
     */
    @Test
    fun approve_readmission_shipsOwnProfileUnrevokedAndEpochStamped() = runTest {
        val approver = makeApprover()
        val requestId = enqueueJoinRequest()
        approver.pumpOnce()

        assertEquals(
            JoinRequestApprover.ApproveOutcome.Sent,
            approver.approve(requestId),
        )

        val invitation = shippedInvitation()
        val ownProfile = invitation.memberProfiles?.get(joinerHex)
        assertNotNull("re-admitted joiner must be in the shipped snapshot", ownProfile)
        assertFalse("their tombstone must be cleared before the snapshot", ownProfile!!.revoked)
        // Anchor bumped 2 → 3; the stamp has to match the snapshot the
        // receiver materializes from, otherwise a replayed epoch-2
        // removal still looks fresh to them.
        assertEquals(3uL, invitation.epoch)
        assertEquals(3uL, ownProfile.statusEpoch)
    }

    /** The local roster is un-revoked too — the admin's own view of
     *  the re-admitted member has to agree with what it just shipped. */
    @Test
    fun approve_readmission_unrevokesTheLocalTombstone() = runTest {
        val approver = makeApprover()
        val requestId = enqueueJoinRequest()
        approver.pumpOnce()
        approver.approve(requestId)

        val stored = groupRepository.snapshots.value.first { it.id == groupIdHex }
        val profile = stored.memberProfiles.getValue(joinerHex)
        assertFalse(profile.revoked)
        assertEquals(3uL, profile.statusEpoch)
    }

    // ─── helpers ───────────────────────────────────────────────────

    /** Decode the [GroupInvitationPayload] sent to the joiner's inbox.
     *  [PassThroughSealer] leaves the plaintext intact. */
    private suspend fun shippedInvitation(): GroupInvitationPayload {
        val tag = TransportInboxId(IdentityRepository.inboxTag(joinerInbox))
        val send = inboxTransport.sends().first { it.inbox == tag }
        return jsonFormat.decodeFromString(
            GroupInvitationPayload.serializer(),
            send.payload.toString(Charsets.UTF_8),
        )
    }

    /** Mint an intro slot, seal a [JoinRequestPayload] to it, and
     *  record it as an inbound [IntroRequest]. Returns the request id. */
    private suspend fun enqueueJoinRequest(): String {
        val introPrivate = X25519PrivateKeyParameters(SecureRandom())
        val introPublic = introPrivate.generatePublicKey().encoded
        introKeyStore.save(
            IntroKeyEntry(
                introPublicKey = introPublic,
                introPrivateKey = introPrivate.encoded,
                ownerIdentityId = ownerIdentity,
                groupId = groupId,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        val request = JoinRequestPayload(
            joinerInboxPublicKey = joinerInbox,
            joinerBlsPublicKey = joinerBls,
            joinerLeafHash = joinerLeaf,
            joinerSendingPublicKey = joinerSending,
            joinerDisplayLabel = "Bob",
            groupId = groupId,
        )
        val sealed = TestInvitationEncryptor.envelopeBytes(
            payload = jsonFormat
                .encodeToString(JoinRequestPayload.serializer(), request)
                .toByteArray(Charsets.UTF_8),
            recipientX25519Pubkey = introPublic,
        )
        introRequestStore.record(
            IntroRequest(
                id = "req-1",
                targetIntroPublicKey = introPublic,
                payload = sealed,
                receivedAt = Instant.parse("2026-08-05T00:00:00Z"),
            ),
        )
        return "req-1"
    }

    private fun makeApprover() = JoinRequestApprover(
        activeIdentity = FakeActiveIdentityProvider(ownerIdentity),
        identitiesFlow = MutableStateFlow(
            listOf(
                IdentitySummary(
                    id = ownerIdentity,
                    name = "Admin",
                    blsPublicKey = adminBls,
                    inboxPublicKey = adminInbox,
                    sendingPublicKey = ByteArray(32) { 0x22 },
                ),
            ),
        ),
        envelopeSealer = PassThroughInvitationSealer(),
        blsSecretKey = { adminSecret },
        introKeyStore = introKeyStore,
        introRequestStore = introRequestStore,
        groupRepository = groupRepository,
        inboxTransport = inboxTransport,
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        relayers = relayers,
        contracts = contracts,
        networkPreference = StaticNetworkPreferenceProvider(AppNetwork.Testnet),
        proofGenerator = StubGroupProofGenerator(),
        makeContractTransport = { FakeSepContractTransport("""{"accepted": true}""") },
        computeMerkleRoot = { members, _ -> ByteArray(32) { members.size.toByte() } },
        computePublicKey = { adminBls.copyOf() },
    )

    /** Admin-only roster: the joiner was removed at epoch 2, so their
     *  leaf is gone and their profile is a tombstone. */
    private fun makeGroupWithTombstonedJoiner(): ChatGroup = ChatGroup(
        id = groupIdHex,
        name = "Family",
        groupSecret = ByteArray(32) { 0x01 },
        createdAtMillis = 0L,
        members = listOf(
            GovernanceMember(publicKeyCompressed = adminBls, leafHash = ByteArray(32) { 0x0D }),
        ),
        memberProfiles = mapOf(
            adminHex to MemberProfile(
                alias = "Admin",
                inboxPublicKey = adminInbox,
                sendingPubkey = ByteArray(32) { 0x22 },
            ),
            joinerHex to MemberProfile(
                alias = "Bob",
                inboxPublicKey = joinerInbox,
                sendingPubkey = joinerSending,
                revoked = true,
                statusEpoch = 2uL,
            ),
        ),
        epoch = 2uL,
        salt = ByteArray(32) { 0x02 },
        commitment = ByteArray(32) { 0x50 },
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = adminHex,
        adminEd25519PubkeyHex = "10".repeat(32),
        isPublishedOnChain = true,
        ownerIdentityId = ownerIdentity.value,
    )

    private companion object {
        private val jsonFormat = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun ByteArray.toHexLower(): String = joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }
    }
}

/** Sealer that returns the plaintext unchanged so the test can decode
 *  what would have been sealed to each recipient. */
private class PassThroughInvitationSealer : InvitationEnvelopeSealer {
    override suspend fun sealInvitation(
        payload: ByteArray,
        recipientInboxPublicKey: ByteArray,
    ): ByteArray = payload
}
