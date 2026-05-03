package chat.onym.android.group

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.onym.android.chain.ContractEntry
import chat.onym.android.chain.ContractNetwork
import chat.onym.android.chain.ContractRelease
import chat.onym.android.chain.ContractsManifest
import chat.onym.android.chain.ContractsRepository
import chat.onym.android.chain.GovernanceType
import chat.onym.android.chain.RelayerEndpoint
import chat.onym.android.chain.RelayerRepository
import chat.onym.android.chain.RelayerStrategy
import chat.onym.android.chain.SepContractError
import chat.onym.android.chain.SepSubmissionResponse
import chat.onym.android.identity.IdentityRepository
import chat.onym.android.identity.IdentitySecretStore
import chat.onym.android.support.ConfigurableContractTransport
import chat.onym.android.support.ConfigurableInboxTransport
import chat.onym.android.support.FakeContractsManifestFetcher
import chat.onym.android.support.FakeKnownRelayersFetcher
import chat.onym.android.support.InMemoryAnchorSelectionStore
import chat.onym.android.support.InMemoryGroupStore
import chat.onym.android.support.InMemoryRelayerSelectionStore
import chat.onym.android.support.StubGroupProofGenerator
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security
import java.time.Instant
import java.util.UUID

/**
 * End-to-end tests for [CreateGroupInteractor]. Real
 * [IdentityRepository] (per-test-isolated EncryptedSharedPreferences),
 * real [RelayerRepository] + [ContractsRepository] seeded with
 * in-memory fakes, real [InMemoryGroupStore]. Proof generation +
 * chain transport are stubbed so the suite finishes fast — the
 * full real-proof path is exercised by `GroupProofGeneratorFfiTest`
 * from PR-B.
 *
 * Lives in `androidTest/` for the same reasons as
 * `IdentityRepositorySealInvitationTest` — needs the real Android
 * Keystore + the OnymSDK JNI .so for `Common.publicKey` /
 * `Common.leafHash` (called inside `IdentityRepository.restore` and
 * `GroupCommitmentBuilder.computeLeafHash`).
 *
 * Mirrors `CreateGroupInteractorTests.swift` from onym-ios PR #26.
 */
@RunWith(AndroidJUnit4::class)
class CreateGroupInteractorTest {

    private val mnemonic =
        "legal winner thank year wave sausage worth useful legal winner thank yellow"

    private lateinit var ctx: Context
    private lateinit var identityStore: IdentitySecretStore
    private lateinit var identity: IdentityRepository
    private lateinit var relayers: RelayerRepository
    private lateinit var contracts: ContractsRepository
    private lateinit var groups: GroupRepository
    private lateinit var inboxTransport: ConfigurableInboxTransport
    private lateinit var contractTransport: ConfigurableContractTransport

    @Before
    fun setUp() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 2)
        }
        ctx = ApplicationProvider.getApplicationContext()
        identityStore = IdentitySecretStore(
            ctx,
            prefsFileName = "chat.onym.android.identity.create-group.${UUID.randomUUID()}",
        )
        identity = IdentityRepository(identityStore)
        runBlocking { identity.restore(mnemonic) }

        relayers = RelayerRepository(
            store = InMemoryRelayerSelectionStore(),
            fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(emptyList())),
        )
        contracts = ContractsRepository(
            store = InMemoryAnchorSelectionStore(),
            fetcher = FakeContractsManifestFetcher(
                FakeContractsManifestFetcher.Mode.Succeeds(makeManifest(includeTyranny = true)),
            ),
        )
        runBlocking {
            relayers.bootstrap()
            relayers.addEndpoint(
                RelayerEndpoint(
                    name = "test",
                    url = "https://relayer.test.example",
                    networks = listOf("testnet"),
                ),
            )
            relayers.setStrategy(RelayerStrategy.PRIMARY)
            relayers.setPrimary("https://relayer.test.example")
            contracts.bootstrap()
            contracts.refresh()
        }

        groups = GroupRepository(
            store = InMemoryGroupStore(),
            identity = identity,
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined,
            ),
        )
        inboxTransport = ConfigurableInboxTransport()
        contractTransport = ConfigurableContractTransport()
    }

    @After
    fun tearDown() {
        try { identityStore.wipeAll() } catch (_: Throwable) { /* best-effort */ }
    }

    // ─── happy path ───────────────────────────────────────────────

    @Test
    fun create_withNoInvitees_savesGroupAndAnchorsOnChain() = runBlocking {
        val interactor = makeInteractor()

        val group = interactor.create(name = "My Group", invitees = emptyList())

        assertEquals("My Group", group.name)
        assertTrue(group.isPublishedOnChain)
        assertEquals(chat.onym.android.chain.SepGroupType.TYRANNY, group.groupType)
        assertEquals(chat.onym.android.chain.SepTier.SMALL, group.tier)
        assertEquals(0uL, group.epoch)
        assertEquals("creator-only roster at create", 1, group.members.size)

        // Group landed in the repository.
        assertEquals(group.id, groups.snapshots.value.singleOrNull()?.id)

        // No invitations sent.
        assertTrue(inboxTransport.sends().isEmpty())

        // Chain anchor was POSTed exactly once.
        val invocations = contractTransport.invocations()
        assertEquals(1, invocations.size)
        assertEquals("create_group", invocations.single().function)
    }

    @Test
    fun create_withTwoInvitees_sendsOneInvitationPerInvitee() = runBlocking {
        val interactor = makeInteractor()

        val invitee1 = ByteArray(32) { 0xAA.toByte() }
        val invitee2 = ByteArray(32) { 0xBB.toByte() }

        val group = interactor.create(
            name = "Friends",
            invitees = listOf(invitee1, invitee2),
        )

        assertTrue(group.isPublishedOnChain)
        val sends = inboxTransport.sends()
        assertEquals("one invitation per invitee", 2, sends.size)
        assertEquals(
            "each invitee gets a distinct inbox tag",
            2,
            sends.map { it.inbox.rawValue }.toSet().size,
        )
    }

    // ─── validation ───────────────────────────────────────────────

    @Test
    fun create_emptyName_throwsInvalidName() = runBlocking {
        val interactor = makeInteractor()
        try {
            interactor.create(name = "   ", invitees = emptyList())
            error("expected InvalidName")
        } catch (e: CreateGroupError.InvalidName) {
            assertSame(CreateGroupError.InvalidName, e)
        }
    }

    @Test
    fun create_inviteeWrongLength_throwsInvalidInviteeKey() = runBlocking {
        val interactor = makeInteractor()
        try {
            interactor.create(
                name = "G",
                invitees = listOf(ByteArray(16) { 0x01 }),  // 16 ≠ 32
            )
            error("expected InvalidInviteeKey")
        } catch (e: CreateGroupError.InvalidInviteeKey) {
            assertEquals(0, e.index)
        }
    }

    // ─── resolution failures ──────────────────────────────────────

    @Test
    fun create_noActiveRelayer_throws() = runBlocking {
        // Replace the relayer repo with one that has no endpoints.
        val emptyRelayers = RelayerRepository(
            store = InMemoryRelayerSelectionStore(),
            fetcher = FakeKnownRelayersFetcher(FakeKnownRelayersFetcher.Mode.Succeeds(emptyList())),
        )
        emptyRelayers.bootstrap()
        val interactor = CreateGroupInteractor(
            identity = identity,
            relayers = emptyRelayers,
            contracts = contracts,
            groups = groups,
            networkPreference = chat.onym.android.chain.StaticNetworkPreferenceProvider(
                chat.onym.android.chain.AppNetwork.Testnet,
            ),
            proofGenerator = StubGroupProofGenerator(),
            inboxTransport = inboxTransport,
            makeContractTransport = { contractTransport },
        )

        try {
            interactor.create(name = "G", invitees = emptyList())
            error("expected NoActiveRelayer")
        } catch (e: CreateGroupError.NoActiveRelayer) {
            assertSame(CreateGroupError.NoActiveRelayer, e)
        }
    }

    @Test
    fun create_noContractBinding_throws() = runBlocking {
        // Swap contracts for a manifest that has no Tyranny entry.
        val contractsNoTyranny = ContractsRepository(
            store = InMemoryAnchorSelectionStore(),
            fetcher = FakeContractsManifestFetcher(
                FakeContractsManifestFetcher.Mode.Succeeds(makeManifest(includeTyranny = false)),
            ),
        )
        contractsNoTyranny.bootstrap()
        contractsNoTyranny.refresh()
        val interactor = CreateGroupInteractor(
            identity = identity,
            relayers = relayers,
            contracts = contractsNoTyranny,
            groups = groups,
            networkPreference = chat.onym.android.chain.StaticNetworkPreferenceProvider(
                chat.onym.android.chain.AppNetwork.Testnet,
            ),
            proofGenerator = StubGroupProofGenerator(),
            inboxTransport = inboxTransport,
            makeContractTransport = { contractTransport },
        )

        try {
            interactor.create(name = "G", invitees = emptyList())
            error("expected NoContractBinding")
        } catch (e: CreateGroupError.NoContractBinding) {
            assertEquals(GovernanceType.Tyranny, e.type)
        }
    }

    @Test
    fun create_mainnetSelected_withNoMainnetBinding_throwsNoContractBinding() = runBlocking {
        // The shared `contracts` only has a Testnet binding; selecting
        // Mainnet via the network preference must fall through to
        // NoContractBinding(Tyranny).
        val mainnetPref = chat.onym.android.chain.StaticNetworkPreferenceProvider(
            chat.onym.android.chain.AppNetwork.Mainnet,
        )
        try {
            makeInteractor(networkPreference = mainnetPref).create(
                name = "G",
                invitees = emptyList(),
            )
            error("expected NoContractBinding")
        } catch (e: CreateGroupError.NoContractBinding) {
            assertEquals(GovernanceType.Tyranny, e.type)
        }
    }

    // ─── chain failures ───────────────────────────────────────────

    @Test
    fun create_anchorTransportError_throws() = runBlocking {
        contractTransport.setBehavior(
            ConfigurableContractTransport.Behavior.Throws(
                SepContractError.BadStatus(code = 502, body = "bad gateway"),
            ),
        )
        try {
            makeInteractor().create(name = "G", invitees = emptyList())
            error("expected AnchorTransport")
        } catch (e: CreateGroupError.AnchorTransport) {
            assertTrue("error message should mention status code", e.message!!.contains("502"))
        }
    }

    @Test
    fun create_anchorRejected_throws() = runBlocking {
        contractTransport.setBehavior(
            ConfigurableContractTransport.Behavior.Response(
                SepSubmissionResponse(
                    accepted = false,
                    transactionHash = null,
                    message = "duplicate group ID",
                ),
            ),
        )
        try {
            makeInteractor().create(name = "G", invitees = emptyList())
            error("expected AnchorRejected")
        } catch (e: CreateGroupError.AnchorRejected) {
            assertEquals("duplicate group ID", e.serverMessage)
        }
    }

    @Test
    fun create_invitationSendNotAccepted_throws() = runBlocking {
        inboxTransport.setReceiptAcceptedBy(0)
        try {
            makeInteractor().create(
                name = "G",
                invitees = listOf(ByteArray(32) { 0xAA.toByte() }),
            )
            error("expected InvitationSendFailed")
        } catch (e: CreateGroupError.InvitationSendFailed) {
            assertEquals(0, e.index)
        }
    }

    // ─── OneOnOne ─────────────────────────────────────────────────

    @Test
    fun create_oneOnOne_zeroInvitees_throwsExactCountError() = runBlocking {
        // Manifest needs a OneOnOne entry for the binding lookup to
        // get past `NoContractBinding` — but the validation fails
        // before that anyway. Re-seed contracts with both bindings
        // so the test stays focused on the count check.
        val contractsBoth = ContractsRepository(
            store = InMemoryAnchorSelectionStore(),
            fetcher = FakeContractsManifestFetcher(
                FakeContractsManifestFetcher.Mode.Succeeds(makeManifest(includeTyranny = true, includeOneOnOne = true)),
            ),
        )
        contractsBoth.bootstrap()
        contractsBoth.refresh()
        val interactor = makeInteractor(contracts = contractsBoth)
        try {
            interactor.create(
                name = "1v1",
                invitees = emptyList(),
                groupType = chat.onym.android.chain.SepGroupType.ONE_ON_ONE,
            )
            error("expected OneOnOneRequiresExactlyOneInvitee")
        } catch (e: CreateGroupError.OneOnOneRequiresExactlyOneInvitee) {
            assertEquals(0, e.actual)
        }
    }

    @Test
    fun create_oneOnOne_twoInvitees_throwsExactCountError() = runBlocking {
        val contractsBoth = ContractsRepository(
            store = InMemoryAnchorSelectionStore(),
            fetcher = FakeContractsManifestFetcher(
                FakeContractsManifestFetcher.Mode.Succeeds(makeManifest(includeTyranny = true, includeOneOnOne = true)),
            ),
        )
        contractsBoth.bootstrap()
        contractsBoth.refresh()
        val interactor = makeInteractor(contracts = contractsBoth)
        try {
            interactor.create(
                name = "1v1",
                invitees = listOf(ByteArray(32), ByteArray(32) { 0xAA.toByte() }),
                groupType = chat.onym.android.chain.SepGroupType.ONE_ON_ONE,
            )
            error("expected OneOnOneRequiresExactlyOneInvitee")
        } catch (e: CreateGroupError.OneOnOneRequiresExactlyOneInvitee) {
            assertEquals(2, e.actual)
        }
    }

    @Test
    fun create_oneOnOne_oneInvitee_anchorsViaOneOnOnePayload_andShipsInviteeBlsSecret() = runBlocking {
        val contractsBoth = ContractsRepository(
            store = InMemoryAnchorSelectionStore(),
            fetcher = FakeContractsManifestFetcher(
                FakeContractsManifestFetcher.Mode.Succeeds(makeManifest(includeTyranny = true, includeOneOnOne = true)),
            ),
        )
        contractsBoth.bootstrap()
        contractsBoth.refresh()
        val interactor = makeInteractor(contracts = contractsBoth)

        val invitee = ByteArray(32) { 0xCC.toByte() }
        val group = interactor.create(
            name = "Talk",
            invitees = listOf(invitee),
            groupType = chat.onym.android.chain.SepGroupType.ONE_ON_ONE,
        )

        assertEquals(chat.onym.android.chain.SepGroupType.ONE_ON_ONE, group.groupType)
        assertTrue(group.isPublishedOnChain)
        // OneOnOne has no admin role.
        assertEquals(null, group.adminPubkeyHex)

        // The invocation went out as a OneOnOne payload (no `tier`,
        // no `admin_pubkey_commitment`, 2-element PI).
        val invocations = contractTransport.invocations()
        assertEquals(1, invocations.size)
        assertEquals("create_group", invocations.single().function)

        // Exactly one invitation envelope was sealed + sent.
        val sends = inboxTransport.sends()
        assertEquals(1, sends.size)
    }

    @Test
    fun create_oneOnOne_noOneOnOneBinding_throwsNoContractBinding() = runBlocking {
        // Default contracts only has Tyranny — selecting OneOnOne
        // must raise NoContractBinding(OneOnOne), not silently fall
        // through to the Tyranny binding.
        val interactor = makeInteractor()
        try {
            interactor.create(
                name = "1v1",
                invitees = listOf(ByteArray(32) { 0xCC.toByte() }),
                groupType = chat.onym.android.chain.SepGroupType.ONE_ON_ONE,
            )
            error("expected NoContractBinding(OneOnOne)")
        } catch (e: CreateGroupError.NoContractBinding) {
            assertEquals(GovernanceType.OneOnOne, e.type)
        }
    }

    // ─── Anarchy ──────────────────────────────────────────────────

    @Test
    fun create_anarchy_zeroInvitees_anchorsViaAnarchyPayload() = runBlocking {
        // Anarchy tolerates any invitee count including zero (creator-
        // only group is fine — the contract's `member_count` is
        // informational, not enforced).
        val contractsAnarchy = ContractsRepository(
            store = InMemoryAnchorSelectionStore(),
            fetcher = FakeContractsManifestFetcher(
                FakeContractsManifestFetcher.Mode.Succeeds(makeManifest(includeTyranny = true, includeAnarchy = true)),
            ),
        )
        contractsAnarchy.bootstrap()
        contractsAnarchy.refresh()
        val interactor = makeInteractor(contracts = contractsAnarchy)

        val group = interactor.create(
            name = "Anarchic group",
            invitees = emptyList(),
            groupType = chat.onym.android.chain.SepGroupType.ANARCHY,
        )

        assertEquals(chat.onym.android.chain.SepGroupType.ANARCHY, group.groupType)
        assertTrue(group.isPublishedOnChain)
        // Anarchy has no admin role on chain — adminPubkeyHex stays null.
        assertEquals(null, group.adminPubkeyHex)

        val invocations = contractTransport.invocations()
        assertEquals(1, invocations.size)
        assertEquals("create_group", invocations.single().function)

        // No invitees → no inbox sends.
        assertTrue(inboxTransport.sends().isEmpty())
    }

    @Test
    fun create_anarchy_withInvitees_anchorsAndShipsInvitations() = runBlocking {
        val contractsAnarchy = ContractsRepository(
            store = InMemoryAnchorSelectionStore(),
            fetcher = FakeContractsManifestFetcher(
                FakeContractsManifestFetcher.Mode.Succeeds(makeManifest(includeTyranny = true, includeAnarchy = true)),
            ),
        )
        contractsAnarchy.bootstrap()
        contractsAnarchy.refresh()
        val interactor = makeInteractor(contracts = contractsAnarchy)

        val invitee1 = ByteArray(32) { 0x10 }
        val invitee2 = ByteArray(32) { 0x20 }
        val group = interactor.create(
            name = "Open group",
            invitees = listOf(invitee1, invitee2),
            groupType = chat.onym.android.chain.SepGroupType.ANARCHY,
        )

        assertTrue(group.isPublishedOnChain)
        assertEquals(2, inboxTransport.sends().size)
    }

    @Test
    fun create_anarchy_noAnarchyBinding_throwsNoContractBinding() = runBlocking {
        // Default contracts only has Tyranny — selecting Anarchy must
        // raise NoContractBinding(Anarchy), not silently fall through.
        val interactor = makeInteractor()
        try {
            interactor.create(
                name = "Anarchic",
                invitees = emptyList(),
                groupType = chat.onym.android.chain.SepGroupType.ANARCHY,
            )
            error("expected NoContractBinding(Anarchy)")
        } catch (e: CreateGroupError.NoContractBinding) {
            assertEquals(GovernanceType.Anarchy, e.type)
        }
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun makeInteractor(
        networkPreference: chat.onym.android.chain.NetworkPreferenceProvider =
            chat.onym.android.chain.StaticNetworkPreferenceProvider(
                chat.onym.android.chain.AppNetwork.Testnet,
            ),
        contracts: ContractsRepository = this.contracts,
    ) = CreateGroupInteractor(
        identity = identity,
        relayers = relayers,
        contracts = contracts,
        groups = groups,
        networkPreference = networkPreference,
        proofGenerator = StubGroupProofGenerator(),
        inboxTransport = inboxTransport,
        makeContractTransport = { contractTransport },
    )

    private fun makeManifest(
        includeTyranny: Boolean,
        includeOneOnOne: Boolean = false,
        includeAnarchy: Boolean = false,
    ): ContractsManifest {
        val entries = buildList<ContractEntry> {
            if (includeTyranny) {
                add(ContractEntry(
                    network = ContractNetwork.Testnet,
                    type = GovernanceType.Tyranny,
                    id = "CTYRANNYTEST00000000000000000000000000000000000000000000",
                ))
            }
            if (includeOneOnOne) {
                add(ContractEntry(
                    network = ContractNetwork.Testnet,
                    type = GovernanceType.OneOnOne,
                    id = "CONEONONETEST0000000000000000000000000000000000000000000",
                ))
            }
            if (includeAnarchy) {
                add(ContractEntry(
                    network = ContractNetwork.Testnet,
                    type = GovernanceType.Anarchy,
                    id = "CANARCHYTEST00000000000000000000000000000000000000000000",
                ))
            }
        }
        return ContractsManifest(
            version = 1,
            releases = listOf(
                ContractRelease(
                    release = "v0.0.3",
                    publishedAt = Instant.ofEpochSecond(1_700_000_000),
                    contracts = entries,
                ),
            ),
        )
    }
}
