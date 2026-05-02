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
import chat.onym.android.chain.SepTier
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
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security
import java.util.UUID

/**
 * End-to-end integration test for the Create Group flow against the
 * **deployed** `onym-relayer` + Stellar testnet contract. Skipped by
 * default — opt in by passing instrumentation arguments:
 *
 * ```sh
 * ONYM_RELAYER_AUTH_TOKEN=<bearer> \
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.ONYM_INTEGRATION=1 \
 *   -Pandroid.testInstrumentationRunnerArguments.ONYM_RELAYER_URL=https://relayer.onym.chat \
 *   -Pandroid.testInstrumentationRunnerArguments.ONYM_RELAYER_AUTH_TOKEN="$ONYM_RELAYER_AUTH_TOKEN" \
 *   -Pandroid.testInstrumentationRunnerArguments.class=chat.onym.android.integration.CreateGroupTyrannyE2ETest
 * ```
 *
 * `ONYM_RELAYER_URL` defaults to the production URL if unset.
 * `ONYM_RELAYER_AUTH_TOKEN` is required — without it every relayer
 * call returns 401.
 *
 * ## What this test exercises
 *
 * The full pipeline from PR-A/B/C: real [IdentityRepository] (BIP39-
 * restored, isolated EncryptedSharedPreferences), real
 * [OnymGroupProofGenerator] (Tyranny PLONK proof, ~3.5s on a Pixel-
 * class CPU), real [OkHttpSepContractTransport] posting to the
 * deployed relayer with [BearerAuthInterceptor] authenticating each
 * call, real [GitHubReleasesContractsManifestFetcher] picking up
 * whichever Tyranny contract is currently published. Inbox transport
 * is faked because the relayer + chain leg is what we're verifying —
 * the invitation send is covered by `CreateGroupInteractorTest`
 * already.
 *
 * ## What this test does NOT exercise
 *
 *  - Real Nostr inbox delivery — [ConfigurableInboxTransport]
 *    substitutes (default `acceptedBy = 1`).
 *  - Receiver-side invitation decryption flow.
 *  - Anything off the Tyranny path (Anarchy / OneOnOne / Democracy
 *    stubs throw `NotYetSupported` per PR-B).
 *  - `update_commitment` / member-add — Tyranny supports it on chain
 *    but the create-only MVP doesn't drive it yet.
 *
 * ## Skip semantics
 *
 *  - `ONYM_INTEGRATION` ≠ `1` → @Before assumeTrue gate skips.
 *  - `ONYM_RELAYER_AUTH_TOKEN` unset → buildEnvironment skips.
 *  - Manifest has no Tyranny contract → buildEnvironment skips.
 *
 * Mirrors `CreateGroupTyrannyE2ETests.swift` from onym-ios PR #32.
 */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore(
    "Disabled pending the cross-repo BLS Fr canonical-encoding fix between " +
        "OnymSDK + the deployed Tyranny contract. Run #25262987336 hit " +
        "`Error(Contract, #15) InvalidCommitmentEncoding` — the contract's " +
        "`is_canonical_fr` (sep-tyranny/src/lib.rs:284-300) rejects either the " +
        "`commitment`, `admin_pubkey_commitment`, or `group_id_fr` arg because " +
        "the SDK emits Fr scalars in a byte order Soroban's `Fr::from_bytes` " +
        "treats as non-canonical. The fix lives in onym-sdk or at the contract's " +
        "Fr encoding boundary — not in this test's wiring. Re-enable + " +
        "re-dispatch a release once a contracts release pins the byte order.",
)
class CreateGroupTyrannyE2ETest {

    private val args by lazy { InstrumentationRegistry.getArguments() }

    /** Cross-platform fixture mnemonic. Recovery yields the same
     *  identity on every platform — same value
     *  `IdentityRepositoryInvitationDecryptTest` uses. */
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
            // Per-test-isolated EncryptedSharedPreferences alias. Same
            // pattern `IdentityRepositoryInvitationDecryptTest` uses
            // so concurrent test-runs (CI matrix) don't collide.
            prefsFileName = "chat.onym.android.identity.tests.e2e.${UUID.randomUUID()}",
        )
        identity = IdentityRepository(identityStore)
        runBlocking { identity.restore(testMnemonic) }
    }

    @After
    fun tearDown() {
        try { identityStore.wipe() } catch (_: Throwable) { /* best-effort */ }
    }

    // ─── Tests ───────────────────────────────────────────────────

    /**
     * Happy path with zero invitees. Verifies the group anchors on
     * chain and the relayer's `get_commitment` returns the same
     * commitment back — closes the loop on the wire format without
     * depending on the receiver-side flow.
     */
    @Test
    fun create_zeroInvitees_anchorsOnTestnet() = runBlocking {
        val env = buildEnvironment()
        val interactor = env.makeInteractor(inboxTransport = ConfigurableInboxTransport())

        val group = interactor.create(
            name = "e2e-zero-${shortId()}",
            invitees = emptyList(),
        )

        assertTrue(
            "group must be flagged as anchored after a successful create_group",
            group.isPublishedOnChain,
        )
        assertEquals(SepGroupType.TYRANNY, group.groupType)
        assertEquals(SepTier.SMALL, group.tier)
        assertEquals(0uL, group.epoch)
        assertEquals("creator-only roster at create time", 1, group.members.size)
        assertNotNull("commitment must be set", group.commitment)
        assertEquals(
            "commitment must be the 32B Poseidon scalar",
            32, group.commitment!!.size,
        )

        // Round-trip the on-chain state: read the commitment back via
        // get_commitment and assert it matches what we stored locally.
        val onChain = env.client.getCommitment(groupId = group.groupIdBytes)
        assertArrayEquals(group.commitment, onChain.commitment)
        assertEquals(0uL, onChain.epoch)
        assertTrue(onChain.active)
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

        val inviteeKey = ByteArray(32) { 0xAA.toByte() }
        val group = interactor.create(
            name = "e2e-one-${shortId()}",
            invitees = listOf(inviteeKey),
        )

        assertTrue(group.isPublishedOnChain)
        assertNotNull("commitment must be set after create", group.commitment)
        // ConfigurableInboxTransport.send returns acceptedBy = 1 by
        // default. The interactor would have thrown
        // `InvitationSendFailed` if it hadn't satisfied that, so the
        // assertions above failing-to-throw is the at-least-one-OK
        // check. Also verify the inbox saw exactly one send.
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
        /** Bound to the resolved tyranny contract — used by the
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

        // Real GitHub Releases fetch — picks up whichever tyranny
        // contract is currently published. Robust to contracts
        // releases without recompiling the test.
        val contracts = ContractsRepository(
            store = InMemoryAnchorSelectionStore(),
            fetcher = GitHubReleasesContractsManifestFetcher(httpClient = githubHttpClient),
        )
        contracts.bootstrap()
        contracts.refresh()

        // Resolve the tyranny binding for the post-create read-back.
        // Skip cleanly with a useful message if no tyranny contract
        // is published yet.
        val key = AnchorSelectionKey(
            network = ContractNetwork.Testnet,
            type = GovernanceType.Tyranny,
        )
        val binding = contracts.snapshots.value.binding(key)
        Assume.assumeTrue(
            "No tyranny contract is published in the manifest yet — " +
                "cut a contracts release with at least one testnet tyranny entry.",
            binding != null,
        )

        val groups = GroupRepository(InMemoryGroupStore())
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
            contractType = SepGroupType.TYRANNY,
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
