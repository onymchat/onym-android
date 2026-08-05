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
import app.onym.android.chain.StaticNetworkPreferenceProvider
import app.onym.android.identity.IdentityId
import app.onym.android.identity.InvitationEnvelopeSealer
import app.onym.android.support.ConfigurableInboxTransport
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.FakeContractsManifestFetcher
import app.onym.android.support.FakeKnownRelayersFetcher
import app.onym.android.support.FakeSepContractTransport
import app.onym.android.support.InMemoryAnchorSelectionStore
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryRelayerSelectionStore
import app.onym.android.support.StubGroupProofGenerator
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Behavioral tests for [GroupMemberRemover]: the admin gates, the
 * anchor → persist → fanout ordering, groupSecret rotation, the
 * tombstone semantics, the two payload variants, and mutual
 * exclusion with an in-flight approve via the shared mutex.
 *
 * JVM-safe: the JNI-backed commitment math is injected
 * ([computeMerkleRoot] / [computePublicKey] stand-ins) and the PLONK
 * prover is [StubGroupProofGenerator].
 */
class GroupMemberRemoverTest {

    private val ownerIdentity = IdentityId("owner")
    private val groupId = ByteArray(32) { 0xAA.toByte() }
    private val groupIdHex = groupId.toHexLower()

    private val adminBls = ByteArray(48) { 0x0A }
    private val adminHex = adminBls.toHexLower()
    private val adminSecret = ByteArray(32) { 0x0A }
    private val victimBls = ByteArray(48) { 0xBB.toByte() }
    private val victimHex = victimBls.toHexLower()
    private val otherBls = ByteArray(48) { 0xCC.toByte() }
    private val otherHex = otherBls.toHexLower()

    private val victimInbox = ByteArray(32) { 0x31 }
    private val otherInbox = ByteArray(32) { 0x41 }

    private lateinit var groupStore: InMemoryGroupStore
    private lateinit var groupRepository: GroupRepository
    private lateinit var inboxTransport: ConfigurableInboxTransport
    private lateinit var contractTransport: FakeSepContractTransport
    private lateinit var relayers: RelayerRepository
    private lateinit var contracts: ContractsRepository

    @Before
    fun setUp() = runTest {
        groupStore = InMemoryGroupStore()
        groupRepository = GroupRepository(
            store = groupStore,
            identity = FakeActiveIdentityProvider(ownerIdentity),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        groupRepository.insert(makeGroup())
        groupRepository.reload()

        inboxTransport = ConfigurableInboxTransport()
        contractTransport = FakeSepContractTransport("""{"accepted": true}""")

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

    // ─── happy path ────────────────────────────────────────────────

    @Test
    fun remove_anchorsPersistsAndFansOut() = runTest {
        val rotatedSecret = ByteArray(32) { 0x77 }
        val remover = makeRemover(randomBytes = { rotatedSecret.copyOf() })

        val outcome = remover.remove(groupIdHex, victimHex)

        assertTrue(outcome is GroupMemberRemover.Outcome.Sent)
        // Anchored: the fake transport saw exactly one update_commitment.
        assertEquals("update_commitment", contractTransport.lastFunction)

        // Persisted: roster shrunk, victim tombstoned, secrets rotated.
        val updated = groupRepository.snapshots.value.single()
        assertTrue(updated.members.none { it.publicKeyCompressed.contentEquals(victimBls) })
        assertEquals(2, updated.members.size)
        assertTrue(updated.memberProfiles[victimHex]!!.revoked)
        assertFalse(updated.memberProfiles[otherHex]!!.revoked)
        assertEquals(2uL, updated.epoch)
        assertArrayEquals(rotatedSecret, updated.groupSecret)
        // c_new from StubGroupProofGenerator's PI bundle (index 2).
        assertArrayEquals(ByteArray(32) { 0xC1.toByte() }, updated.commitment)

        // Fanned out: one envelope to the remaining member, one to the
        // victim — none to self (admin).
        val sends = inboxTransport.sends()
        assertEquals(2, sends.size)
    }

    @Test
    fun remove_victimEnvelopeOmitsSecrets_memberEnvelopeCarriesThem() = runTest {
        val rotatedSecret = ByteArray(32) { 0x77 }
        val remover = makeRemover(randomBytes = { rotatedSecret.copyOf() })
        remover.remove(groupIdHex, victimHex)

        // The pass-through sealer keeps payloads readable; recipients
        // are distinguishable by inbox tag.
        val sends = inboxTransport.sends()
        val decoded = sends.map {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(MemberRemovalPayload.serializer(), it.payload.toString(Charsets.UTF_8))
        }
        val victimCopy = decoded.single { it.groupSecretNew == null }
        val memberCopy = decoded.single { it.groupSecretNew != null }
        assertNull(victimCopy.saltNew)
        assertArrayEquals(rotatedSecret, memberCopy.groupSecretNew)
        assertEquals(victimHex, victimCopy.removedBlsHex)
        assertEquals(victimHex, memberCopy.removedBlsHex)
        assertEquals(2uL, memberCopy.epoch)
        // Raw wire text of the victim's copy must not even contain the keys.
        val victimWire = sends.map { it.payload.toString(Charsets.UTF_8) }
            .single { !it.contains("group_secret_new") }
        assertFalse(victimWire.contains("salt_new"))
    }

    // ─── gates ─────────────────────────────────────────────────────

    @Test
    fun remove_unknownGroup() = runTest {
        assertTrue(
            makeRemover().remove("ff".repeat(32), victimHex)
                is GroupMemberRemover.Outcome.UnknownGroup,
        )
    }

    @Test
    fun remove_notTyranny() = runTest {
        groupRepository.insert(
            makeGroup().copy(groupType = SepGroupType.ANARCHY, adminPubkeyHex = null),
        )
        groupRepository.reload()
        assertTrue(
            makeRemover().remove(groupIdHex, victimHex)
                is GroupMemberRemover.Outcome.NotTyrannyGroup,
        )
    }

    @Test
    fun remove_selfIsRejected() = runTest {
        assertTrue(
            makeRemover().remove(groupIdHex, adminHex)
                is GroupMemberRemover.Outcome.CannotRemoveSelf,
        )
    }

    @Test
    fun remove_unknownMember() = runTest {
        assertTrue(
            makeRemover().remove(groupIdHex, "ee".repeat(48))
                is GroupMemberRemover.Outcome.UnknownMember,
        )
    }

    @Test
    fun remove_alreadyRemoved() = runTest {
        val remover = makeRemover()
        remover.remove(groupIdHex, victimHex)
        assertTrue(
            remover.remove(groupIdHex, victimHex)
                is GroupMemberRemover.Outcome.AlreadyRemoved,
        )
        // Only the first call anchored + fanned out (2 envelopes).
        assertEquals(2, inboxTransport.sends().size)
    }

    @Test
    fun remove_memberNotInRoster() = runTest {
        // In the directory (memberProfiles) but not in the on-chain
        // members tree — the divergence case removal must surface.
        val strayHex = "dd".repeat(48)
        groupRepository.insert(
            makeGroup().copy(
                memberProfiles = makeGroup().memberProfiles +
                    (strayHex to MemberProfile(
                        alias = "Stray",
                        inboxPublicKey = ByteArray(32) { 0x51 },
                        sendingPubkey = ByteArray(32) { 0x52 },
                    )),
            ),
        )
        groupRepository.reload()
        assertTrue(
            makeRemover().remove(groupIdHex, strayHex)
                is GroupMemberRemover.Outcome.MemberNotInRoster,
        )
    }

    @Test
    fun remove_notAdmin_whenSecretDerivesDifferentKey() = runTest {
        // The BLS secret derives a pubkey ≠ the roster's admin leaf —
        // the "switched identities since create" pre-flight.
        val remover = makeRemover(
            computePublicKey = { ByteArray(48) { 0x99.toByte() } },
        )
        assertTrue(
            remover.remove(groupIdHex, victimHex)
                is GroupMemberRemover.Outcome.NotAdminOfThisGroup,
        )
        // Nothing anchored, nothing persisted, nothing sent.
        assertNull(contractTransport.lastInvocationJson)
        assertFalse(groupRepository.snapshots.value.single().memberProfiles[victimHex]!!.revoked)
        assertTrue(inboxTransport.sends().isEmpty())
    }

    @Test
    fun remove_anchorRejected_persistsNothing() = runTest {
        val remover = makeRemover(
            transport = FakeSepContractTransport("""{"accepted": false, "message": "stale epoch"}"""),
        )
        val outcome = remover.remove(groupIdHex, victimHex)
        assertTrue(outcome is GroupMemberRemover.Outcome.AnchorRejected)
        assertEquals("stale epoch", (outcome as GroupMemberRemover.Outcome.AnchorRejected).reason)
        val group = groupRepository.snapshots.value.single()
        assertFalse(group.memberProfiles[victimHex]!!.revoked)
        assertEquals(1uL, group.epoch)
        assertTrue(inboxTransport.sends().isEmpty())
    }

    @Test
    fun remove_noRelayer() = runTest {
        val emptyRelayers = RelayerRepository(
            store = InMemoryRelayerSelectionStore(),
            fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(emptyList())),
        )
        emptyRelayers.bootstrap()
        assertTrue(
            makeRemover(relayersOverride = emptyRelayers).remove(groupIdHex, victimHex)
                is GroupMemberRemover.Outcome.NoActiveRelayer,
        )
    }

    // ─── serialization with the approver ───────────────────────────

    @Test
    fun remove_waitsForSharedMutexHolder() = runTest {
        val shared = Mutex()
        val remover = makeRemover(mutex = shared)

        shared.withLock {
            // While "an approve" holds the anchor mutex, the removal
            // must not have started (no contract call yet).
            val removal = launch { remover.remove(groupIdHex, victimHex) }
            advanceUntilIdle()
            assertNull(contractTransport.lastInvocationJson)
            removal.cancel()
        }
    }

    // ─── helpers ───────────────────────────────────────────────────

    private fun makeRemover(
        randomBytes: (Int) -> ByteArray = { ByteArray(it) { 0x66 } },
        computePublicKey: (ByteArray) -> ByteArray = { adminBls.copyOf() },
        transport: FakeSepContractTransport = contractTransport,
        relayersOverride: RelayerRepository = relayers,
        mutex: Mutex = Mutex(),
    ) = GroupMemberRemover(
        activeIdentity = FakeActiveIdentityProvider(ownerIdentity),
        envelopeSealer = PassThroughSealer(),
        blsSecretKey = { adminSecret },
        groupRepository = groupRepository,
        inboxTransport = inboxTransport,
        relayers = relayersOverride,
        contracts = contracts,
        networkPreference = StaticNetworkPreferenceProvider(AppNetwork.Testnet),
        proofGenerator = StubGroupProofGenerator(),
        makeContractTransport = { transport },
        mutex = mutex,
        randomBytes = randomBytes,
        computeMerkleRoot = { members, _ -> ByteArray(32) { members.size.toByte() } },
        computePublicKey = computePublicKey,
    )

    private fun makeGroup(): ChatGroup = ChatGroup(
        id = groupIdHex,
        name = "Family",
        groupSecret = ByteArray(32) { 0x01 },
        createdAtMillis = 0L,
        members = listOf(
            GovernanceMember(publicKeyCompressed = adminBls, leafHash = ByteArray(32) { 0x0D }),
            GovernanceMember(publicKeyCompressed = victimBls, leafHash = ByteArray(32) { 0x0B }),
            GovernanceMember(publicKeyCompressed = otherBls, leafHash = ByteArray(32) { 0x0C }),
        ),
        memberProfiles = mapOf(
            adminHex to MemberProfile(
                alias = "Admin",
                inboxPublicKey = ByteArray(32) { 0x21 },
                sendingPubkey = ByteArray(32) { 0x22 },
            ),
            victimHex to MemberProfile(
                alias = "Victim",
                inboxPublicKey = victimInbox,
                sendingPubkey = ByteArray(32) { 0x32 },
            ),
            otherHex to MemberProfile(
                alias = "Other",
                inboxPublicKey = otherInbox,
                sendingPubkey = ByteArray(32) { 0x42 },
            ),
        ),
        epoch = 1uL,
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
        fun ByteArray.toHexLower(): String = joinToString("") {
            "%02x".format(it.toInt() and 0xFF)
        }
    }
}

/** Sealer that returns the plaintext unchanged so tests can decode
 *  what would have been sealed to each recipient. */
private class PassThroughSealer : InvitationEnvelopeSealer {
    override suspend fun sealInvitation(
        payload: ByteArray,
        recipientInboxPublicKey: ByteArray,
    ): ByteArray = payload
}
