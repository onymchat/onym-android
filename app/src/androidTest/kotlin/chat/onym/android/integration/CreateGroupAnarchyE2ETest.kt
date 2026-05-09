package chat.onym.android.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.onym.android.chain.AnchorSelectionKey
import chat.onym.android.chain.AppNetwork
import chat.onym.android.chain.BearerAuthInterceptor
import chat.onym.android.chain.ContractNetwork
import chat.onym.android.chain.ContractsRepository
import chat.onym.android.chain.GitHubReleasesContractsManifestFetcher
import chat.onym.android.chain.GovernanceType
import chat.onym.android.chain.OkHttpSepContractTransport
import chat.onym.android.chain.OnymGroupProofGenerator
import chat.onym.android.chain.RelayerEndpoint
import chat.onym.android.chain.RelayerRepository
import chat.onym.android.chain.RelayerStrategy
import chat.onym.android.chain.SepContractClient
import chat.onym.android.chain.SepContractTransport
import chat.onym.android.chain.SepGroupType
import chat.onym.android.chain.SepNetwork
import chat.onym.android.chain.StaticNetworkPreferenceProvider
import chat.onym.android.group.CreateGroupInteractor
import chat.onym.android.group.GroupRepository
import chat.onym.android.identity.IdentityRepository
import chat.onym.android.identity.IdentitySecretStore
import chat.onym.android.support.ConfigurableInboxTransport
import chat.onym.android.support.FakeKnownRelayersFetcher
import chat.onym.android.support.InMemoryAnchorSelectionStore
import chat.onym.android.support.InMemoryGroupStore
import chat.onym.android.support.InMemoryRelayerSelectionStore
import chat.onym.android.transport.InboxTransport
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security
import java.util.UUID

/**
 * End-to-end integration test for the Anarchy (open-governance)
 * Create Group flow against the **deployed** `onym-relayer` + Stellar
 * testnet `sep-anarchy` contract. Sibling of
 * [CreateGroupTyrannyE2ETest] / [CreateGroupOneOnOneE2ETest]; same
 * skip semantics, same instrumentation args:
 *
 * ```sh
 * ONYM_RELAYER_AUTH_TOKEN=<bearer> \
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.ONYM_INTEGRATION=1 \
 *   -Pandroid.testInstrumentationRunnerArguments.ONYM_RELAYER_URL=https://relayer.onym.chat \
 *   -Pandroid.testInstrumentationRunnerArguments.ONYM_RELAYER_AUTH_TOKEN="$ONYM_RELAYER_AUTH_TOKEN" \
 *   -Pandroid.testInstrumentationRunnerArguments.class=chat.onym.android.integration.CreateGroupAnarchyE2ETest
 * ```
 *
 * ## What this test exercises
 *
 * The full PR-3 Anarchy pipeline: real [IdentityRepository] (BIP39
 * restored), real [OnymGroupProofGenerator] taking the Anarchy branch
 * (calls `chat.onym.sdk.Anarchy.proveMembership(depth=5,
 * memberLeafHashes, sk, index, epoch=0L, salt)` — depth-5 PLONK proof,
 * ~3.5s on a Pixel-class CPU), real [OkHttpSepContractTransport]
 * posting to the deployed relayer with [BearerAuthInterceptor], real
 * [GitHubReleasesContractsManifestFetcher] picking up the published
 * `sep-anarchy` contract.
 *
 * ## What this test does NOT exercise
 *
 *  - Real Nostr inbox delivery — [ConfigurableInboxTransport]
 *    substitutes (default `acceptedBy = 1`).
 *  - Receiver-side invitation decryption flow.
 *  - Anarchy `update_commitment` — Anarchy supports it on chain but
 *    the create-only MVP doesn't drive it yet.
 *
 * ## Skip semantics
 *
 *  - `ONYM_INTEGRATION` ≠ `1` → @Before assumeTrue gate skips.
 *  - `ONYM_RELAYER_AUTH_TOKEN` unset → buildEnvironment skips.
 *  - Manifest has no Anarchy contract → buildEnvironment skips.
 */
@RunWith(AndroidJUnit4::class)
class CreateGroupAnarchyE2ETest {

    private val args by lazy { InstrumentationRegistry.getArguments() }

    /** Cross-platform fixture mnemonic. Same as the Tyranny / OneOnOne
     *  E2E suites + `IdentityRepositoryInvitationDecryptTest`. */
    private val testMnemonic =
        "legal winner thank year wave sausage worth useful legal winner thank yellow"

    private lateinit var ctx: Context
    private lateinit var identityStore: IdentitySecretStore
    private lateinit var identity: IdentityRepository

    @Before
    fun requireIntegrationGateAndRestoreIdentity() {
        Assume.assumeTrue(
            "Pass -Pandroid.testInstrumentationRunnerArguments.ONYM_INTEGRATION=1 (and ONYM_RELAYER_AUTH_TOKEN) to run this test.",
            arg("ONYM_INTEGRATION") == "1",
        )
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 2)
        }
        ctx = ApplicationProvider.getApplicationContext()
        identityStore = IdentitySecretStore(
            ctx,
            prefsFileName = "chat.onym.android.identity.tests.e2e.anarchy.${UUID.randomUUID()}",
        )
        identity = IdentityRepository(identityStore)
        runBlocking { identity.restore(testMnemonic) }
    }

    @After
    fun tearDown() {
        try { identityStore.wipeAll() } catch (_: Throwable) { /* best-effort */ }
    }

    // ─── Tests ───────────────────────────────────────────────────

    /**
     * Happy path with zero invitees. Verifies the group anchors on
     * chain and `get_commitment` round-trips the same commitment
     * back. Anarchy tolerates a creator-only roster; production sends
     * `member_count = 0` (the contract's documented "not tracked"
     * sentinel) so the chain only learns the tier.
     */
    @Test
    fun create_zeroInvitees_anchorsOnTestnet() = runBlocking {
        val env = buildEnvironment()
        val interactor = env.makeInteractor(inboxTransport = ConfigurableInboxTransport())

        val group = interactor.create(
            name = "e2e-anarchy-zero-${shortId()}",
            invitees = emptyList(),
            groupType = SepGroupType.ANARCHY,
        )

        assertTrue(
            "group must be flagged as anchored after a successful create_group",
            group.isPublishedOnChain,
        )
        assertEquals(SepGroupType.ANARCHY, group.groupType)
        assertEquals(0uL, group.epoch)
        assertEquals("creator-only roster at create time", 1, group.members.size)
        assertNull("Anarchy has no admin role on chain", group.adminPubkeyHex)
        assertNotNull("commitment must be set", group.commitment)
        assertEquals(
            "commitment must be the 32B Poseidon scalar",
            32, group.commitment!!.size,
        )

        // Round-trip the on-chain state. Anarchy's `CommitmentEntry`
        // shape varies — `commitment` + `epoch` are always present;
        // the rest are decoded if present. We assert only what's
        // guaranteed across deploys. (Run #25277102111 surfaced that
        // the testnet anarchy contract DOES ship `active=true`,
        // contrary to the iOS-imported table that called it
        // democracy/oligarchy-only — leaving it unasserted here so
        // the test stays green if a future contract bump flips the
        // shape.)
        val onChain = env.client.getCommitment(groupId = group.groupIdBytes)
        assertArrayEquals(group.commitment, onChain.commitment)
        assertEquals(0uL, onChain.epoch)
    }

    /**
     * Happy path with one invitee. Verifies the chain leg succeeds
     * AND the inbox transport is asked to deliver one invitation
     * (its receipt counts as the at-least-one-OK check).
     */
    @Test
    fun create_oneInvitee_sendsInvitation_andAnchors() = runBlocking {
        val env = buildEnvironment()
        val inbox = ConfigurableInboxTransport()
        val interactor = env.makeInteractor(inboxTransport = inbox)

        val inviteeKey = ByteArray(32) { 0xCC.toByte() }
        val group = interactor.create(
            name = "e2e-anarchy-one-${shortId()}",
            invitees = listOf(inviteeKey),
            groupType = SepGroupType.ANARCHY,
        )

        assertTrue(group.isPublishedOnChain)
        assertNotNull("commitment must be set after create", group.commitment)
        // `ConfigurableInboxTransport.send` returns `acceptedBy = 1` by
        // default. The interactor would have thrown
        // `InvitationSendFailed` if it hadn't satisfied that, so the
        // assertions above failing-to-throw is the at-least-one-OK
        // check. Verify the inbox saw exactly one send.
        assertEquals(1, inbox.sends().size)
    }

    // ─── Environment ─────────────────────────────────────────────

    private class E2EEnvironment(
        private val identity: IdentityRepository,
        private val relayers: RelayerRepository,
        private val contracts: ContractsRepository,
        private val groups: GroupRepository,
        private val networkPreference: chat.onym.android.chain.NetworkPreferenceProvider,
        private val proofGenerator: chat.onym.android.chain.GroupProofGenerator,
        private val makeContractTransport: (String) -> SepContractTransport,
        /** Bound to the resolved Anarchy contract — used by the
         *  post-create read-back assertion. */
        val client: SepContractClient,
    ) {
        fun makeInteractor(inboxTransport: InboxTransport): CreateGroupInteractor =
            CreateGroupInteractor(
                identity = identity,
                relayers = relayers,
                contracts = contracts,
                groups = groups,
                networkPreference = networkPreference,
                proofGenerator = proofGenerator,
                inboxTransport = inboxTransport,
                makeContractTransport = makeContractTransport,
            )
    }

    private suspend fun buildEnvironment(): E2EEnvironment {
        val token = requireArg("ONYM_RELAYER_AUTH_TOKEN")
        val relayerUrl = arg("ONYM_RELAYER_URL") ?: DEFAULT_RELAYER_URL

        // Two separate OkHttpClients: the relayer one carries the
        // Bearer interceptor (every contract-call POST needs it), the
        // GitHub one doesn't (sending our relayer token to GitHub
        // would leak it to a third party).
        val relayerHttpClient = OkHttpClient.Builder()
            .addInterceptor(BearerAuthInterceptor(token))
            .build()
        val githubHttpClient = OkHttpClient()

        val relayers = RelayerRepository(
            store = InMemoryRelayerSelectionStore(),
            fetcher = FakeKnownRelayersFetcher(
                FakeKnownRelayersFetcher.Mode.Succeeds(emptyList()),
            ),
        )
        relayers.bootstrap()
        relayers.addEndpoint(
            RelayerEndpoint(
                name = "e2e",
                url = relayerUrl,
                networks = listOf("testnet"),
            ),
        )
        relayers.setStrategy(RelayerStrategy.PRIMARY)
        relayers.setPrimary(relayerUrl)

        val contracts = ContractsRepository(
            store = InMemoryAnchorSelectionStore(),
            fetcher = GitHubReleasesContractsManifestFetcher(httpClient = githubHttpClient),
        )
        contracts.bootstrap()
        contracts.refresh()

        // Resolve the Anarchy binding for the post-create read-back.
        // Skip cleanly if no Anarchy contract is published yet.
        val key = AnchorSelectionKey(
            network = ContractNetwork.Testnet,
            type = GovernanceType.Anarchy,
        )
        val binding = contracts.snapshots.value.binding(key)
        Assume.assumeTrue(
            "No Anarchy contract is published in the manifest yet — " +
                "cut a contracts release with at least one testnet anarchy entry.",
            binding != null,
        )

        val groups = GroupRepository(
            store = InMemoryGroupStore(),
            identity = identity,
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined,
            ),
        )
        val networkPreference = StaticNetworkPreferenceProvider(AppNetwork.Testnet)

        val makeContractTransport: (String) -> SepContractTransport = { url ->
            OkHttpSepContractTransport(
                httpClient = relayerHttpClient,
                endpointUrl = url,
            )
        }

        // Direct client for the read-back assertion.
        val client = SepContractClient(
            contractID = binding!!.contractId,
            contractType = SepGroupType.ANARCHY,
            network = SepNetwork.TESTNET,
            transport = makeContractTransport(relayerUrl),
        )

        return E2EEnvironment(
            identity = identity,
            relayers = relayers,
            contracts = contracts,
            groups = groups,
            networkPreference = networkPreference,
            proofGenerator = OnymGroupProofGenerator(),
            makeContractTransport = makeContractTransport,
            client = client,
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private fun arg(name: String): String? = args.getString(name)?.takeIf { it.isNotEmpty() }

    private fun requireArg(name: String): String {
        val value = arg(name)
        Assume.assumeTrue(
            "$name instrumentation arg is required for this test " +
                "(pass via -Pandroid.testInstrumentationRunnerArguments.$name=…).",
            value != null,
        )
        return value!!
    }

    private fun shortId(): String =
        UUID.randomUUID().toString().take(8).lowercase()

    private companion object {
        const val DEFAULT_RELAYER_URL = "https://relayer.onym.chat"
    }
}
