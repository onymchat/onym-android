package app.onym.android.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.onym.android.chain.AnchorSelectionKey
import app.onym.android.chain.AppNetwork
import app.onym.android.chain.BearerAuthInterceptor
import app.onym.android.chain.ContractNetwork
import app.onym.android.chain.ContractsRepository
import app.onym.android.chain.GitHubReleasesContractsManifestFetcher
import app.onym.android.chain.GovernanceType
import app.onym.android.chain.GroupProofUpdateInput
import app.onym.android.chain.OkHttpSepContractTransport
import app.onym.android.chain.OnymGroupProofGenerator
import app.onym.android.chain.RelayerEndpoint
import app.onym.android.chain.RelayerRepository
import app.onym.android.chain.RelayerStrategy
import app.onym.android.chain.SepContractClient
import app.onym.android.chain.SepContractTransport
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepNetwork
import app.onym.android.chain.StaticNetworkPreferenceProvider
import app.onym.android.chain.TyrannyUpdateCommitmentPayload
import app.onym.android.group.CreateGroupInteractor
import app.onym.android.group.GovernanceMember
import app.onym.android.group.GroupCommitmentBuilder
import app.onym.android.group.GroupMemberRemover
import app.onym.android.group.GroupRepository
import app.onym.android.group.InviteIntroducer
import app.onym.android.group.MemberProfile
import app.onym.android.group.MemberRemovalPayload
import app.onym.android.identity.IdentityRepository
import app.onym.android.identity.IdentitySecretStore
import app.onym.android.support.ConfigurableInboxTransport
import app.onym.android.support.FakeKnownRelayersFetcher
import app.onym.android.support.InMemoryAnchorSelectionStore
import app.onym.android.support.InMemoryGroupStore
import app.onym.android.support.InMemoryIntroKeyStore
import app.onym.android.support.InMemoryRelayerSelectionStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * End-to-end integration test for **member removal with groupSecret
 * rotation** against the deployed `onym-relayer` + Stellar testnet
 * contract. Same opt-in gate + environment shape as
 * [CreateGroupTyrannyE2ETest]:
 *
 * ```sh
 * ONYM_RELAYER_AUTH_TOKEN=<bearer> \
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.ONYM_INTEGRATION=1 \
 *   -Pandroid.testInstrumentationRunnerArguments.ONYM_RELAYER_URL=https://relayer.onym.chat \
 *   -Pandroid.testInstrumentationRunnerArguments.ONYM_RELAYER_AUTH_TOKEN="$ONYM_RELAYER_AUTH_TOKEN" \
 *   -Pandroid.testInstrumentationRunnerArguments.class=app.onym.android.integration.RemoveMemberTyrannyE2ETest
 * ```
 *
 * ## What this test exercises
 *
 * The first on-chain `update_commitment` integration coverage:
 *
 *  1. Create a Tyranny group (epoch 0) — real PLONK create proof.
 *  2. Admit a second member by driving `Tyranny.proveUpdate` +
 *     `update_commitment` directly (epoch 1) — the approver's anchor
 *     leg is private, and this is the same primitive it uses.
 *  3. Remove that member via [GroupMemberRemover] (epoch 2) — real
 *     update proof over the shrunken roster + real relayer POST.
 *  4. Read `get_commitment` back: epoch 2 + byte-equal commitment.
 *  5. Assert the local rotation: fresh `groupSecret`, tombstoned
 *     victim, and the victim's sealed envelope (decrypted with the
 *     victim's own real identity) carries NO rotated secrets.
 */
@RunWith(AndroidJUnit4::class)
class RemoveMemberTyrannyE2ETest {

    private val args by lazy { InstrumentationRegistry.getArguments() }

    /** Admin fixture mnemonic — same as [CreateGroupTyrannyE2ETest]. */
    private val adminMnemonic =
        "legal winner thank year wave sausage worth useful legal winner thank yellow"

    /** Victim fixture mnemonic (distinct BIP39 test vector). */
    private val victimMnemonic =
        "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"

    private lateinit var ctx: Context
    private lateinit var adminStore: IdentitySecretStore
    private lateinit var victimStore: IdentitySecretStore
    private lateinit var admin: IdentityRepository
    private lateinit var victim: IdentityRepository

    @Before
    fun requireIntegrationGateAndRestoreIdentities() {
        Assume.assumeTrue(
            "Pass -Pandroid.testInstrumentationRunnerArguments.ONYM_INTEGRATION=1 (and ONYM_RELAYER_AUTH_TOKEN) to run this test.",
            arg("ONYM_INTEGRATION") == "1",
        )
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 2)
        }
        ctx = ApplicationProvider.getApplicationContext()
        adminStore = IdentitySecretStore(
            ctx,
            prefsFileName = "app.onym.android.identity.tests.e2e.admin.${UUID.randomUUID()}",
        )
        victimStore = IdentitySecretStore(
            ctx,
            prefsFileName = "app.onym.android.identity.tests.e2e.victim.${UUID.randomUUID()}",
        )
        admin = IdentityRepository(adminStore)
        victim = IdentityRepository(victimStore)
        runBlocking {
            admin.restore(adminMnemonic)
            victim.restore(victimMnemonic)
        }
    }

    @After
    fun tearDown() {
        try { adminStore.wipeAll() } catch (_: Throwable) { }
        try { victimStore.wipeAll() } catch (_: Throwable) { }
    }

    @Test
    fun createAdmitRemove_rotatesSecretAndAnchorsEpoch2() = runBlocking {
        val env = buildEnvironment()
        val inbox = ConfigurableInboxTransport()

        // ── 1. Create (epoch 0) ──────────────────────────────────
        val interactor = CreateGroupInteractor(
            identity = admin,
            relayers = env.relayers,
            contracts = env.contracts,
            groups = env.groups,
            networkPreference = env.networkPreference,
            proofGenerator = env.proofGenerator,
            inboxTransport = inbox,
            introducer = InviteIntroducer(InMemoryIntroKeyStore()),
            makeContractTransport = env.makeContractTransport,
        )
        val created = interactor.create(
            name = "e2e-remove-${shortId()}",
            invitees = emptyList(),
        )
        assertTrue(created.isPublishedOnChain)
        assertEquals(0uL, created.epoch)
        val originalSecret = created.groupSecret.copyOf()

        // ── 2. Admit the victim (epoch 0 → 1), driving the same
        //       primitive the approver's anchor leg uses. ─────────
        val victimSummary = victim.identities.value.single()
        val victimBls = victimSummary.blsPublicKey
        val victimHex = victimBls.toHexLower()
        val victimLeafHash = GroupCommitmentBuilder.computeLeafHash(victim.blsSecretKey())

        val adminBlsSecret = admin.blsSecretKey()
        val newMembers = (created.members + GovernanceMember(
            publicKeyCompressed = victimBls,
            leafHash = victimLeafHash,
        )).sortedWith(compareBy(byteArrayLexComparator()) { it.publicKeyCompressed })
        val rootAfterAdd = GroupCommitmentBuilder.computeMerkleRoot(newMembers, created.tier)
        val saltAfterAdd = GroupCommitmentBuilder.generateSalt()
        val addProof = env.proofGenerator.proveUpdate(
            GroupProofUpdateInput(
                groupType = SepGroupType.TYRANNY,
                tier = created.tier,
                oldMembers = created.members,
                adminBlsSecretKey = adminBlsSecret,
                adminIndexOld = 0,
                epochOld = created.epoch,
                memberRootNew = rootAfterAdd,
                groupId = created.groupIdBytes,
                saltOld = created.salt,
                saltNew = saltAfterAdd,
            ),
        )
        val addResponse = env.client.updateCommitmentTyranny(
            TyrannyUpdateCommitmentPayload(
                groupId = created.groupIdBytes,
                proof = addProof.proof,
                publicInputs = addProof.publicInputs,
            ),
        )
        assertTrue("admit update_commitment must anchor", addResponse.accepted)

        val afterAdd = created.copy(
            members = newMembers,
            commitment = addProof.commitmentNew,
            epoch = created.epoch + 1uL,
            salt = saltAfterAdd,
            memberProfiles = created.memberProfiles + (victimHex to MemberProfile(
                alias = "Victim",
                inboxPublicKey = victimSummary.inboxPublicKey,
                sendingPubkey = victimSummary.sendingPublicKey,
            )),
        )
        env.groups.insert(afterAdd)

        // ── 3. Remove the victim (epoch 1 → 2) ───────────────────
        val remover = GroupMemberRemover(
            activeIdentity = admin,
            envelopeSealer = admin,
            blsSecretKey = admin::blsSecretKey,
            groupRepository = env.groups,
            inboxTransport = inbox,
            relayers = env.relayers,
            contracts = env.contracts,
            networkPreference = env.networkPreference,
            proofGenerator = env.proofGenerator,
            makeContractTransport = env.makeContractTransport,
        )
        val outcome = remover.remove(afterAdd.id, victimHex)
        assertTrue(
            "removal must anchor + fan out, got $outcome",
            outcome is GroupMemberRemover.Outcome.Sent,
        )

        // ── 4. On-chain read-back ────────────────────────────────
        val local = env.groups.snapshots.value.single { it.id == afterAdd.id }
        assertEquals(2uL, local.epoch)
        val onChain = env.client.getCommitment(groupId = created.groupIdBytes)
        assertEquals(2uL, onChain.epoch)
        assertArrayEquals(local.commitment, onChain.commitment)

        // ── 5. Local rotation + victim-envelope assertions ───────
        assertFalse(
            "groupSecret must rotate on removal",
            local.groupSecret.contentEquals(originalSecret),
        )
        assertTrue(local.memberProfiles[victimHex]!!.revoked)
        assertTrue(
            "victim must leave the on-chain roster",
            local.members.none { it.publicKeyCompressed.contentEquals(victimBls) },
        )

        // Exactly one removal envelope (to the victim — the group had
        // no other members). Decrypt it with the victim's REAL
        // identity and assert it carries no rotated secrets.
        val removalSend = inbox.sends().last()
        val victimId = victim.currentIdentityId.value!!
        val envelope = victim.decryptInvitationWithSender(
            envelopeBytes = removalSend.payload,
            asIdentity = victimId,
        )
        val payload = Json { ignoreUnknownKeys = true }.decodeFromString(
            MemberRemovalPayload.serializer(),
            envelope.plaintext.toString(Charsets.UTF_8),
        )
        assertEquals(victimHex, payload.removedBlsHex)
        assertEquals(2uL, payload.epoch)
        assertNull("victim copy must not carry the rotated secret", payload.groupSecretNew)
        assertNull("victim copy must not carry the rotated salt", payload.saltNew)
        assertNotNull(envelope.senderEd25519PublicKey)
    }

    // ─── Environment (mirrors CreateGroupTyrannyE2ETest) ─────────

    private class E2EEnvironment(
        val relayers: RelayerRepository,
        val contracts: ContractsRepository,
        val groups: GroupRepository,
        val networkPreference: app.onym.android.chain.NetworkPreferenceProvider,
        val proofGenerator: OnymGroupProofGenerator,
        val makeContractTransport: (String) -> SepContractTransport,
        val client: SepContractClient,
    )

    private suspend fun buildEnvironment(): E2EEnvironment {
        val token = requireArg("ONYM_RELAYER_AUTH_TOKEN")
        val relayerUrl = arg("ONYM_RELAYER_URL") ?: DEFAULT_RELAYER_URL

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
            RelayerEndpoint(name = "e2e", url = relayerUrl, networks = listOf("testnet")),
        )
        relayers.setStrategy(RelayerStrategy.PRIMARY)
        relayers.setPrimary(relayerUrl)

        val contracts = ContractsRepository(
            store = InMemoryAnchorSelectionStore(),
            fetcher = GitHubReleasesContractsManifestFetcher(httpClient = githubHttpClient),
        )
        contracts.bootstrap()
        contracts.refresh()

        val key = AnchorSelectionKey(
            network = ContractNetwork.Testnet,
            type = GovernanceType.Tyranny,
        )
        val binding = contracts.snapshots.value.binding(key)
        Assume.assumeTrue(
            "No tyranny contract is published in the manifest yet.",
            binding != null,
        )

        val groups = GroupRepository(
            store = InMemoryGroupStore(),
            identity = admin,
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined,
            ),
        )
        val networkPreference = StaticNetworkPreferenceProvider(AppNetwork.Testnet)
        val makeContractTransport: (String) -> SepContractTransport = { url ->
            OkHttpSepContractTransport(httpClient = relayerHttpClient, endpointUrl = url)
        }
        val client = SepContractClient(
            contractID = binding!!.contractId,
            contractType = SepGroupType.TYRANNY,
            network = SepNetwork.TESTNET,
            transport = makeContractTransport(relayerUrl),
        )

        return E2EEnvironment(
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

    private fun byteArrayLexComparator(): Comparator<ByteArray> =
        Comparator { a, b ->
            val len = minOf(a.size, b.size)
            for (i in 0 until len) {
                val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (cmp != 0) return@Comparator cmp
            }
            a.size - b.size
        }

    private fun ByteArray.toHexLower(): String = joinToString("") {
        "%02x".format(it.toInt() and 0xFF)
    }

    private companion object {
        const val DEFAULT_RELAYER_URL = "https://relayer.onym.chat"
    }
}
