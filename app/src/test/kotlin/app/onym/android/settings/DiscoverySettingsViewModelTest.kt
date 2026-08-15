@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.settings

import app.onym.android.discovery.DiscoveryCanonical
import app.onym.android.discovery.DiscoveryProfile
import app.onym.android.discovery.DiscoveryRepository
import app.onym.android.discovery.DiscoverySource
import app.onym.android.support.FakeDiscoveryFetcher
import app.onym.android.support.InMemoryDiscoveryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Discovery settings ViewModel tests: URL validation, the TOFU
 * fingerprint format, and the add-provider flow (fetch → confirm →
 * pinned source) driven through a real [DiscoveryRepository] over the
 * :discovery testFixtures fakes. Provider manifests are signed
 * in-test with BouncyCastle Ed25519 over [DiscoveryCanonical] signing
 * bytes.
 */
class DiscoverySettingsViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ─── In-test provider-manifest signing ────────────────────────

    private val operatorPrivateKey = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)

    private val operatorKeyHex: String =
        operatorPrivateKey.generatePublicKey().encoded.joinToString("") { "%02x".format(it) }

    private fun signedProviderManifestBytes(): ByteArray {
        val unsigned = buildJsonObject {
            put("version", 1)
            put("implementationProfileId", DiscoveryProfile.IMPLEMENTATION_PROFILE)
            put("providerId", "onym:component:test-provider")
            put("operator", "onym:key:$operatorKeyHex")
            put("seat", "discovery")
            put(
                "catalogs",
                buildJsonArray {
                    addJsonObject {
                        put("catalogId", "main")
                        put("snapshot", "https://provider.example.com/snapshot.json")
                        put("audience", "public")
                        put("seatTypes", buildJsonArray { add("notary") })
                        put("policy", "sha256:" + "0".repeat(64))
                        put("policyUri", "https://provider.example.com/policy.md")
                    }
                },
            )
            put("capabilities", buildJsonArray {})
            put("privacyProfile", "sha256:" + "1".repeat(64))
            put("privacyProfileUri", "https://provider.example.com/privacy.md")
            put("offers", buildJsonArray {})
            put("validUntil", "2999-01-01T00:00:00Z")
        }
        val unsignedBytes = Json.encodeToString(JsonElement.serializer(), unsigned)
            .toByteArray(Charsets.UTF_8)
        val signer = Ed25519Signer().apply { init(true, operatorPrivateKey) }
        val signingBytes = DiscoveryCanonical.signingBytes(unsignedBytes)
        signer.update(signingBytes, 0, signingBytes.size)
        val signature = Base64.getEncoder().encodeToString(signer.generateSignature())
        val signed = JsonObject(unsigned + ("signature" to JsonPrimitive(signature)))
        return Json.encodeToString(JsonElement.serializer(), signed).toByteArray(Charsets.UTF_8)
    }

    private fun makeViewModel(
        fetcher: FakeDiscoveryFetcher = FakeDiscoveryFetcher(),
    ): Pair<DiscoveryRepository, DiscoverySettingsViewModel> {
        val repository = DiscoveryRepository(store = InMemoryDiscoveryStore(), fetcher = fetcher)
        return repository to DiscoverySettingsViewModel(repository)
    }

    private val manifestUrl = "https://provider.example.com/manifest.json"
    private val snapshotUrl = "https://provider.example.com/snapshot.json"

    // ─── URL validation ───────────────────────────────────────────

    @Test
    fun validate_acceptsHttpsOnly() {
        assertNotNull(DiscoverySettingsViewModel.validate("https://x.example/manifest.json"))
        assertEquals(
            "https://x.example",
            DiscoverySettingsViewModel.validate("  https://x.example  "),
        )
        assertNull(DiscoverySettingsViewModel.validate("http://x.example"))
        assertNull(DiscoverySettingsViewModel.validate("wss://x.example"))
        assertNull(DiscoverySettingsViewModel.validate(""))
        assertNull(DiscoverySettingsViewModel.validate("not a url"))
    }

    // ─── Fingerprint format ───────────────────────────────────────

    @Test
    fun fingerprint_isFirstSixteenHexCharsInGroupsOfFour() {
        assertEquals(
            "ea4a 6c63 e29c 520a",
            DiscoverySource.fingerprint(
                "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c",
            ),
        )
    }

    // ─── Add flow ─────────────────────────────────────────────────

    @Test
    fun tappedFetchProvider_invalidUrl_setsErrorAndStaysIdle() = runTest {
        val (_, vm) = makeViewModel()
        vm.addDraftChanged("http://insecure.example")

        vm.tappedFetchProvider(); yield()

        assertNotNull(vm.state.value.addError)
        assertEquals(DiscoverySettingsViewModel.AddPhase.Idle, vm.state.value.addPhase)
    }

    @Test
    fun tappedFetchProvider_verifiedManifest_movesToConfirming() = runTest {
        val fetcher = FakeDiscoveryFetcher().apply {
            respond(manifestUrl, signedProviderManifestBytes())
        }
        val (_, vm) = makeViewModel(fetcher)
        vm.addDraftChanged(manifestUrl)

        vm.tappedFetchProvider(); yield()

        val phase = vm.state.value.addPhase
        assertTrue(phase is DiscoverySettingsViewModel.AddPhase.Confirming)
        val pending = (phase as DiscoverySettingsViewModel.AddPhase.Confirming).pending
        assertEquals(operatorKeyHex, pending.operatorKeyHex)
        assertEquals("onym:component:test-provider", pending.manifest.providerId)
        // Nothing pinned yet — TOFU confirmation is outstanding.
        assertNull(vm.state.value.addError)
    }

    @Test
    fun tappedFetchProvider_badManifest_surfacesErrorAndReturnsToIdle() = runTest {
        val fetcher = FakeDiscoveryFetcher().apply {
            respond(manifestUrl, """{"not":"a manifest"}""".toByteArray())
        }
        val (repo, vm) = makeViewModel(fetcher)
        vm.addDraftChanged(manifestUrl)

        vm.tappedFetchProvider(); yield()

        assertEquals(DiscoverySettingsViewModel.AddPhase.Idle, vm.state.value.addPhase)
        assertNotNull(vm.state.value.addError)
        assertTrue(repo.snapshots.value.configuration.sources.isEmpty())
    }

    @Test
    fun tappedConfirmAdd_pinsKeyAndPersistsSource() = runTest {
        val fetcher = FakeDiscoveryFetcher().apply {
            respond(manifestUrl, signedProviderManifestBytes())
            // Snapshot unreachable: the pin must still land — the
            // catalog refresh failure surfaces per-source, it doesn't
            // roll back the add.
            fail(snapshotUrl)
        }
        val (repo, vm) = makeViewModel(fetcher)
        vm.addDraftChanged(manifestUrl)
        vm.tappedFetchProvider(); yield()

        vm.tappedConfirmAdd(); yield()

        assertEquals(DiscoverySettingsViewModel.AddPhase.Added, vm.state.value.addPhase)
        val source = repo.snapshots.value.configuration.sources.single()
        assertEquals("onym:component:test-provider", source.providerId)
        assertEquals(operatorKeyHex, source.pinnedOperatorKeyHex)
        assertEquals(manifestUrl, source.url)
        // The failed snapshot fetch is recorded per-source (network
        // failure, not an integrity finding).
        val error = repo.snapshots.value.sourceErrors["onym:component:test-provider"]
        assertNotNull(error)
        assertEquals(false, error!!.isIntegrity)
        // Per-source entry count for the freshly added provider is 0.
        assertEquals(0, vm.state.value.entryCount("onym:component:test-provider"))
    }

    @Test
    fun tappedCancelPreview_returnsToIdleWithoutPinning() = runTest {
        val fetcher = FakeDiscoveryFetcher().apply {
            respond(manifestUrl, signedProviderManifestBytes())
        }
        val (repo, vm) = makeViewModel(fetcher)
        vm.addDraftChanged(manifestUrl)
        vm.tappedFetchProvider(); yield()

        vm.tappedCancelPreview()

        assertEquals(DiscoverySettingsViewModel.AddPhase.Idle, vm.state.value.addPhase)
        assertTrue(repo.snapshots.value.configuration.sources.isEmpty())
    }

    @Test
    fun tappedRemove_dropsSourcePermanently() = runTest {
        val fetcher = FakeDiscoveryFetcher().apply {
            respond(manifestUrl, signedProviderManifestBytes())
            fail(snapshotUrl)
        }
        val (repo, vm) = makeViewModel(fetcher)
        vm.addDraftChanged(manifestUrl)
        vm.tappedFetchProvider(); yield()
        vm.tappedConfirmAdd(); yield()

        vm.tappedRemove("onym:component:test-provider"); yield()

        assertTrue(repo.snapshots.value.configuration.sources.isEmpty())
        assertTrue(repo.snapshots.value.sourceErrors.isEmpty())
    }
}
