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
import chat.onym.android.group.CreateGroupError
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security
import java.util.UUID

/**
 * End-to-end integration test for the OneOnOne (1-on-1) Create Group
 * flow against the **deployed** `onym-relayer` + Stellar testnet
 * `sep-oneonone` contract. Sibling of [CreateGroupTyrannyE2ETest];
 * same skip semantics, same instrumentation args:
 *
 * ```sh
 * ONYM_RELAYER_AUTH_TOKEN=<bearer> \
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.ONYM_INTEGRATION=1 \
 *   -Pandroid.testInstrumentationRunnerArguments.ONYM_RELAYER_URL=https://relayer.onym.chat \
 *   -Pandroid.testInstrumentationRunnerArguments.ONYM_RELAYER_AUTH_TOKEN="$ONYM_RELAYER_AUTH_TOKEN" \
 *   -Pandroid.testInstrumentationRunnerArguments.class=chat.onym.android.integration.CreateGroupOneOnOneE2ETest
 * ```
 *
 * ## What this test exercises
 *
 * The full PR-3 OneOnOne pipeline: real [IdentityRepository] (BIP39
 * restored), real [OnymGroupProofGenerator] taking the OneOnOne branch
 * (calls `chat.onym.sdk.OneOnOne.proveCreate(sk_0, sk_1, salt)` —
 * depth-5 PLONK proof, ~3.5s on a Pixel-class CPU), real
 * [OkHttpSepContractTransport] posting to the deployed relayer with
 * [BearerAuthInterceptor], real [GitHubReleasesContractsManifestFetcher]
 * picking up the published OneOnOne contract. The interactor's internal
 * ephemeral-sk_1 generation is exercised here — the FFI rejects
 * `sk_0 == sk_1`, so a busted canonical-Fr sampler would surface as a
 * proof generation failure on this test.
 *
 * ## What this test does NOT exercise
 *
 *  - Real Nostr inbox delivery — [ConfigurableInboxTransport]
 *    substitutes (default `acceptedBy = 1`).
 *  - Receiver-side invitation decryption + sk_1 extraction.
 *  - `update_commitment` — the OneOnOne contract is immutable
 *    post-create, so there's nothing to test here. (Compare Tyranny,
 *    which exposes update but the create-only MVP doesn't drive it.)
 *
 * ## Skip semantics
 *
 *  - `ONYM_INTEGRATION` ≠ `1` → @Before assumeTrue gate skips.
 *  - `ONYM_RELAYER_AUTH_TOKEN` unset → buildEnvironment skips.
 *  - Manifest has no OneOnOne contract → buildEnvironment skips.
 */
@RunWith(AndroidJUnit4::class)
class CreateGroupOneOnOneE2ETest {

    private val args by lazy { InstrumentationRegistry.getArguments() }

    /** Cross-platform fixture mnemonic. Recovery yields the same
     *  identity on every platform — same value the Tyranny E2E +
     *  `IdentityRepositoryInvitationDecryptTest` use. */
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
            // Per-test-isolated EncryptedSharedPreferences alias so
            // concurrent test-runs (CI matrix) don't collide.
            prefsFileName = "chat.onym.android.identity.tests.e2e.oneonone.${UUID.randomUUID()}",
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
     * Happy path: exactly one invitee. Verifies the OneOnOne create
     * lands on testnet (no tier in the payload, 2-element PI), the
     * relayer accepts, the local group records the OneOnOne shape
     * (no admin pubkey), exactly one invitation is sent to the inbox
     * transport, and `get_commitment` round-trips the same commitment.
     */
    @Test
    fun create_oneInvitee_anchorsOnTestnet() = runBlocking {
        val env = buildEnvironment()
        val inbox = ConfigurableInboxTransport()
        val interactor = env.makeInteractor(inboxTransport = inbox)

        val inviteeKey = ByteArray(32) { 0xCC.toByte() }
        val group = interactor.create(
            name = "e2e-oneonone-${shortId()}",
            invitees = listOf(inviteeKey),
            groupType = SepGroupType.ONE_ON_ONE,
        )

        assertTrue(
            "group must be flagged as anchored after a successful create_group",
            group.isPublishedOnChain,
        )
        assertEquals(SepGroupType.ONE_ON_ONE, group.groupType)
        assertEquals(0uL, group.epoch)
        assertEquals(
            "OneOnOne local roster is creator-only at create time " +
                "(invitee receives sk_1 in the sealed envelope)",
            1, group.members.size,
        )
        assertNull("OneOnOne has no admin role on chain", group.adminPubkeyHex)
        assertNotNull("commitment must be set", group.commitment)
        assertEquals(
            "commitment must be the 32B Poseidon scalar",
            32, group.commitment!!.size,
        )

        // Exactly one invitation envelope went out — the inbox transport
        // would have made the interactor throw `InvitationSendFailed` if
        // it hadn't accepted (default `acceptedBy = 1` accepts). A second
        // send would mean the OneOnOne validation didn't fire.
        assertEquals(1, inbox.sends().size)

        // Round-trip the on-chain state. OneOnOne's `CommitmentEntry`
        // ships `commitment` + `epoch` + `timestamp` only — no `tier`
        // (depth-5 hardcoded in the circuit), no `active` (immutable
        // post-create, no `update_commitment` to flip the bit). See
        // SepCommitmentEntry's per-governance table.
        val onChain = env.client.getCommitment(groupId = group.groupIdBytes)
        assertArrayEquals(group.commitment, onChain.commitment)
        assertEquals(0uL, onChain.epoch)
        assertNull("OneOnOne doesn't ship `tier`", onChain.tier)
        assertNull("OneOnOne doesn't ship `active`", onChain.active)
    }

    /**
     * Validation gate: zero invitees must short-circuit BEFORE any
     * proof generation or chain interaction. Burning a real testnet
     * `group_id` slot for a definitely-invalid create would be wasteful
     * (and would noise up the relayer logs). PR-2's
     * `OneOnOneRequiresExactlyOneInvitee` is the gate.
     */
    @Test
    fun create_zeroInvitees_throwsValidationError() = runBlocking {
        val env = buildEnvironment()
        val interactor = env.makeInteractor(inboxTransport = ConfigurableInboxTransport())

        val thrown = assertThrows(CreateGroupError.OneOnOneRequiresExactlyOneInvitee::class.java) {
            kotlinx.coroutines.runBlocking {
                interactor.create(
                    name = "e2e-oneonone-zero-${shortId()}",
                    invitees = emptyList(),
                    groupType = SepGroupType.ONE_ON_ONE,
                )
            }
        }
        assertEquals(0, thrown.actual)
    }

    /**
     * Same gate, the other direction: two invitees must short-circuit
     * with the same error. Also verifies the gate fires for the EXACT
     * mismatch case the UI would prevent (the Step-2 CTA is gated on
     * `invitees.size == 1`, but if someone reaches the interactor
     * through a path that bypasses the UI, the interactor catches it).
     */
    @Test
    fun create_twoInvitees_throwsValidationError() = runBlocking {
        val env = buildEnvironment()
        val interactor = env.makeInteractor(inboxTransport = ConfigurableInboxTransport())

        val thrown = assertThrows(CreateGroupError.OneOnOneRequiresExactlyOneInvitee::class.java) {
            kotlinx.coroutines.runBlocking {
                interactor.create(
                    name = "e2e-oneonone-two-${shortId()}",
                    invitees = listOf(
                        ByteArray(32) { 0x11 },
                        ByteArray(32) { 0x22 },
                    ),
                    groupType = SepGroupType.ONE_ON_ONE,
                )
            }
        }
        assertEquals(2, thrown.actual)
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
        /** Bound to the resolved OneOnOne contract — used by the
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

        // RelayerRepository seeded with the env URL; production
        // fetcher is bypassed because the test wants a deterministic
        // endpoint.
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

        // Real GitHub Releases fetch — picks up whichever OneOnOne
        // contract is currently published. Robust to contracts releases
        // without recompiling the test.
        val contracts = ContractsRepository(
            store = InMemoryAnchorSelectionStore(),
            fetcher = GitHubReleasesContractsManifestFetcher(httpClient = githubHttpClient),
        )
        contracts.bootstrap()
        contracts.refresh()

        // Resolve the OneOnOne binding for the post-create read-back.
        // Skip cleanly with a useful message if no OneOnOne contract
        // is published yet (e.g., on a contracts release that ships
        // tyranny only).
        val key = AnchorSelectionKey(
            network = ContractNetwork.Testnet,
            type = GovernanceType.OneOnOne,
        )
        val binding = contracts.snapshots.value.binding(key)
        Assume.assumeTrue(
            "No OneOnOne contract is published in the manifest yet — " +
                "cut a contracts release with at least one testnet oneonone entry.",
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

        // Direct client for the read-back assertion. Bypasses
        // RelayerRepository.selectUrl because the test already has
        // the URL.
        val client = SepContractClient(
            contractID = binding!!.contractId,
            contractType = SepGroupType.ONE_ON_ONE,
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

    /** Short suffix to disambiguate concurrent test-runs against the
     *  same testnet without colliding on `groupId`. The interactor
     *  already generates a random 32-byte groupId; this is just to
     *  make the group `name` unique-ish for grep-able relayer logs. */
    private fun shortId(): String =
        UUID.randomUUID().toString().take(8).lowercase()

    private companion object {
        const val DEFAULT_RELAYER_URL = "https://relayer.onym.chat"
    }
}
