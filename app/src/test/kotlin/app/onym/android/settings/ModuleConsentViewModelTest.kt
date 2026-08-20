@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.settings

import app.onym.android.discovery.AttributedCatalogEntry
import app.onym.android.discovery.CatalogEntry
import app.onym.android.discovery.EntryRelationship
import app.onym.android.discovery.ManifestRef
import app.onym.android.discovery.ModuleSelection
import app.onym.android.discovery.SeatSelectionStore
import app.onym.android.discovery.SourceAttribution
import app.onym.android.foundation.PinnedConsentRecord
import app.onym.android.foundation.PinnedConsentStore
import app.onym.android.foundation.ServiceManifestCanonical
import app.onym.android.foundation.SignedServiceManifest
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
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * Module-consent flow tests: hard-enforced review (catalog digest
 * pin, operator-key and seat equality), offer entitlement gating
 * (free selectable, paid not), and the accept path (consent pinned →
 * selection appended → endpoint applied). Manifests are signed
 * in-test with BouncyCastle Ed25519 over [ServiceManifestCanonical]
 * signing bytes — same helper pattern as `DiscoverySeatAdaptersTest`.
 */
class ModuleConsentViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ─── In-test Ed25519 manifest signing ─────────────────────────

    private val operatorPrivateKey = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)
    private val strangerPrivateKey =
        Ed25519PrivateKeyParameters(ByteArray(32) { (it + 100).toByte() }, 0)

    private fun Ed25519PrivateKeyParameters.publicKeyHex(): String =
        generatePublicKey().encoded.joinToString("") { "%02x".format(it) }

    private val operatorKey get() = "onym:key:${operatorPrivateKey.publicKeyHex()}"
    private val strangerKey get() = "onym:key:${strangerPrivateKey.publicKeyHex()}"

    private fun sign(message: ByteArray, key: Ed25519PrivateKeyParameters): ByteArray {
        val signer = Ed25519Signer().apply { init(true, key) }
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun signedManifestBytes(
        unsigned: JsonObject,
        key: Ed25519PrivateKeyParameters = operatorPrivateKey,
    ): ByteArray {
        val unsignedBytes = Json.encodeToString(JsonElement.serializer(), unsigned)
            .toByteArray(Charsets.UTF_8)
        val signature = sign(ServiceManifestCanonical.signingBytes(unsignedBytes), key)
        val signed = JsonObject(
            unsigned + ("signature" to JsonPrimitive(Base64.getEncoder().encodeToString(signature))),
        )
        return Json.encodeToString(JsonElement.serializer(), signed).toByteArray(Charsets.UTF_8)
    }

    private fun destinationManifest(
        componentId: String = "onym:component:test-notary",
        seat: String = "notary",
        operator: String = operatorKey,
        withOffers: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("version", 1)
        put("componentId", componentId)
        put("seat", seat)
        put("operator", operator)
        put("name", "Test Operator")
        put(
            "endpoints",
            buildJsonArray { addJsonObject { put("uri", "https://relayer.example.com") } },
        )
        if (withOffers) {
            put(
                "offers",
                buildJsonArray {
                    addJsonObject {
                        put("offerId", "free-tier")
                        put("model", "free")
                    }
                    addJsonObject {
                        put("offerId", "pro-monthly")
                        put("model", "subscription")
                        put("period", "monthly")
                    }
                },
            )
        }
        put("validUntil", "2999-12-31T23:59:59Z")
    }

    private fun sha256Digest(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ─── Entry + store fakes ──────────────────────────────────────

    private fun attributedEntry(
        componentId: String = "onym:component:test-notary",
        seatType: String = "notary",
        digest: String,
        operator: String = operatorKey,
    ): AttributedCatalogEntry = AttributedCatalogEntry(
        entry = CatalogEntry(
            componentId = componentId,
            seatType = seatType,
            manifest = ManifestRef(
                uri = "https://operator.example.com/manifest.json",
                digest = digest,
            ),
            operator = operator,
            listedAt = "2026-01-01T00:00:00Z",
            relationship = EntryRelationship.None,
            placement = "organic",
        ),
        attribution = SourceAttribution(
            providerId = "onym:component:test-provider",
            sourceLabel = "Test Provider",
            catalogId = "onym:catalog:test",
            snapshotDigest = "sha256:" + "0".repeat(64),
            relationship = EntryRelationship.None,
            placement = "organic",
        ),
    )

    private class InMemoryConsentStore : PinnedConsentStore {
        private var records = listOf<PinnedConsentRecord>()
        override suspend fun load(): List<PinnedConsentRecord> = records
        override suspend fun save(records: List<PinnedConsentRecord>) {
            this.records = records
        }
    }

    private class InMemorySelectionStore : SeatSelectionStore {
        val selections = mutableListOf<ModuleSelection>()
        override suspend fun appendSelection(selection: ModuleSelection) {
            selections.add(selection)
        }
        override suspend fun loadSelections(): List<ModuleSelection> = selections.toList()
        override suspend fun activeSelections(): Map<String, ModuleSelection> =
            selections.associateBy { it.seatType }
        override suspend fun clear() = selections.clear()
    }

    private class Harness(
        entry: AttributedCatalogEntry?,
        manifestBytes: ByteArray?,
    ) {
        val consentStore = InMemoryConsentStore()
        val selectionStore = InMemorySelectionStore()
        val applied = mutableListOf<SignedServiceManifest>()
        val viewModel = ModuleConsentViewModel(
            entry = entry,
            fetchManifestBytes = {
                manifestBytes ?: throw IOException("no manifest mapped")
            },
            consentStore = consentStore,
            selectionStore = selectionStore,
            applyToConfiguration = { applied.add(it) },
            now = { Instant.parse("2026-08-13T00:00:00Z") },
        )
    }

    // ─── Review ───────────────────────────────────────────────────

    @Test
    fun load_reviewsManifest_andPreselectsFreeOffer() = runTest {
        val bytes = signedManifestBytes(destinationManifest())
        val h = Harness(attributedEntry(digest = sha256Digest(bytes)), bytes)

        h.viewModel.start(); yield()

        val state = h.viewModel.state.value
        assertEquals(ModuleConsentViewModel.Step.Reviewing, state.step)
        assertNotNull(state.reviewed)
        assertNull(state.errorMessage)
        // Free offer entitled + preselected; paid offer visible but
        // not entitled (FreeTierEntitlementProvider refuses it).
        assertEquals(setOf("free-tier"), state.entitledOfferIds)
        assertEquals("free-tier", state.selectedOfferId)
        assertEquals("Test Operator", h.viewModel.displayName)
    }

    @Test
    fun selectOffer_refusesUnentitledOffer() = runTest {
        val bytes = signedManifestBytes(destinationManifest())
        val h = Harness(attributedEntry(digest = sha256Digest(bytes)), bytes)
        h.viewModel.start(); yield()

        h.viewModel.selectOffer("pro-monthly")

        assertEquals("free-tier", h.viewModel.state.value.selectedOfferId)
    }

    @Test
    fun load_digestMismatch_surfacesError() = runTest {
        val bytes = signedManifestBytes(destinationManifest())
        // Entry pins a digest for different bytes.
        val h = Harness(attributedEntry(digest = "sha256:" + "a".repeat(64)), bytes)

        h.viewModel.start(); yield()

        val state = h.viewModel.state.value
        assertEquals(ModuleConsentViewModel.Step.Loading, state.step)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("doesn't match"))
    }

    @Test
    fun load_operatorKeyMismatch_surfacesError() = runTest {
        // Manifest self-signed by a stranger; catalog entry names the
        // real operator. Digest pin matches (the entry pinned the
        // stranger's bytes) — the key equality check must still trip.
        val bytes = signedManifestBytes(
            destinationManifest(operator = strangerKey),
            key = strangerPrivateKey,
        )
        val h = Harness(
            attributedEntry(digest = sha256Digest(bytes), operator = operatorKey),
            bytes,
        )

        h.viewModel.start(); yield()

        val state = h.viewModel.state.value
        assertNull(state.reviewed)
        assertTrue(state.errorMessage!!.contains("different operator key"))
    }

    @Test
    fun load_seatMismatch_surfacesError() = runTest {
        val bytes = signedManifestBytes(destinationManifest(seat = "transport.message"))
        val h = Harness(attributedEntry(digest = sha256Digest(bytes), seatType = "notary"), bytes)

        h.viewModel.start(); yield()

        assertTrue(h.viewModel.state.value.errorMessage!!.contains("different seat"))
    }

    @Test
    fun load_moderationSeat_refusedWithoutFetch() = runTest {
        val bytes = signedManifestBytes(destinationManifest(seat = "moderation"))
        var fetched = false
        val vm = ModuleConsentViewModel(
            entry = attributedEntry(digest = sha256Digest(bytes), seatType = "moderation"),
            fetchManifestBytes = { fetched = true; bytes },
            consentStore = InMemoryConsentStore(),
            selectionStore = InMemorySelectionStore(),
            applyToConfiguration = {},
        )

        vm.start(); yield()

        assertTrue(vm.state.value.errorMessage!!.contains("Moderation"))
        assertEquals(false, fetched)
    }

    @Test
    fun load_missingEntry_surfacesStableError() = runTest {
        val h = Harness(entry = null, manifestBytes = null)

        h.viewModel.start(); yield()

        assertTrue(h.viewModel.state.value.errorMessage!!.contains("no longer available"))
    }

    // ─── Accept ───────────────────────────────────────────────────

    @Test
    fun accept_pinsConsent_recordsSelection_andApplies() = runTest {
        val bytes = signedManifestBytes(destinationManifest())
        val digest = sha256Digest(bytes)
        val h = Harness(attributedEntry(digest = digest), bytes)
        h.viewModel.start(); yield()

        h.viewModel.tappedAccept(); yield()

        assertEquals(ModuleConsentViewModel.Step.Done, h.viewModel.state.value.step)
        // Consent: active record pins the exact reviewed bytes.
        val record = h.consentStore.activeRecord("onym:component:test-notary")
        assertNotNull(record)
        assertEquals(digest, record!!.manifestHash)
        assertEquals("Test Provider", record.sourceLabel)
        assertEquals("free-tier", record.offerId)
        assertTrue(record.manifestBytes.contentEquals(bytes))
        // Selection provenance.
        val selection = h.selectionStore.selections.single()
        assertEquals("notary", selection.seatType)
        assertEquals("onym:component:test-notary", selection.componentId)
        assertEquals("onym:component:test-provider", selection.providerId)
        assertEquals(digest, selection.manifestHash)
        assertEquals("free-tier", selection.offerId)
        // Endpoint applied into the legacy configuration.
        assertEquals(1, h.applied.size)
        assertEquals("onym:component:test-notary", h.applied.single().componentId)
    }

    @Test
    fun accept_applyFailure_returnsToReviewingWithError() = runTest {
        val bytes = signedManifestBytes(destinationManifest())
        val consentStore = InMemoryConsentStore()
        val vm = ModuleConsentViewModel(
            entry = attributedEntry(digest = sha256Digest(bytes)),
            fetchManifestBytes = { bytes },
            consentStore = consentStore,
            selectionStore = InMemorySelectionStore(),
            applyToConfiguration = { throw IOException("apply failed") },
        )
        vm.start(); yield()

        vm.tappedAccept(); yield()

        assertEquals(ModuleConsentViewModel.Step.Reviewing, vm.state.value.step)
        assertNotNull(vm.state.value.errorMessage)
    }

    // ─── The backup seat's arrival ────────────────────────────────

    /**
     * `storage.backup` has no legacy configuration to apply into — the
     * pinned consent record IS the wiring. So the consent flow's job
     * for this seat is finished the moment the record exists, and what
     * matters is that the record is one the backup seat can actually
     * read: `BackupSeat.activeConsents` finds it by seat type, and
     * `BackupOperatorManifest.from` decodes the endpoint the client
     * will talk to out of the same pinned bytes.
     *
     * That whole path — catalog entry to a vendor Settings can show —
     * is asserted here in one test because every piece of it existed
     * separately before this and none of it was joined up: the flow
     * had no backup entry point, and the decoder was reading fields
     * the operator never published.
     */
    @Test
    fun accept_backupSeat_pinsARecordTheBackupSeatCanRead() = runTest {
        // The operator's published manifest shape, per onym-system
        // `backup/UI-Backup.md` §5.3: the seat's fields at the top
        // level, and `offers` as objects.
        val manifest = buildJsonObject {
            put("version", 1)
            put("componentId", "onym:component:test-backup")
            put("seat", "storage.backup")
            put("operator", operatorKey)
            put("backupProfileId", "onym:backup-profile:sealed-device-archive-v1")
            put("implementationProfileId", "onym:backup-implementation:object-http-v1")
            put(
                "endpoints",
                buildJsonArray {
                    addJsonObject {
                        put("uri", "https://backup.example.com")
                        put("role", "read-write")
                    }
                },
            )
            put("declaredTerms", "sha256:" + "b".repeat(64))
            // Free mode: no issuer named, so this operator never asks
            // for an entitlement, and there is no offer to select.
            put("entitlementIssuers", buildJsonArray { })
            put("offers", buildJsonArray { })
            put("validUntil", "2999-12-31T23:59:59Z")
        }
        val bytes = signedManifestBytes(manifest)
        val digest = sha256Digest(bytes)
        val h = Harness(
            attributedEntry(
                componentId = "onym:component:test-backup",
                seatType = "storage.backup",
                digest = digest,
            ),
            bytes,
        )

        h.viewModel.start(); yield()
        assertEquals(ModuleConsentViewModel.Step.Reviewing, h.viewModel.state.value.step)
        // Nothing to select, and nothing that blocks accepting: a free
        // operator publishes no offer, and consent must not require one.
        assertNull(h.viewModel.state.value.selectedOfferId)

        h.viewModel.tappedAccept(); yield()

        assertEquals(ModuleConsentViewModel.Step.Done, h.viewModel.state.value.step)

        val consents = app.onym.android.backup.BackupSeat.activeConsents(h.consentStore)
        assertEquals(1, consents.size)
        assertEquals(app.onym.android.backup.BACKUP_SEAT_TYPE, consents.single().seatType)
        assertTrue(consents.single().manifestBytes.contentEquals(bytes))

        val decoded = app.onym.android.backup.BackupSeat.manifest(consents.single())
        assertNotNull("the pinned record must decode as a backup operator manifest", decoded)
        assertEquals("https://backup.example.com", decoded!!.endpoint)
        assertEquals("sha256:" + "b".repeat(64), decoded.declaredTermsDigest)
        assertTrue(decoded.entitlementIssuers.isEmpty())
    }

    // ─── Static helpers ───────────────────────────────────────────

    @Test
    fun disclosureText_replacesHyphens_andPassesUnknownThrough() {
        assertEquals("common owner", ModuleConsentViewModel.disclosureText("common-owner"))
        assertEquals("weird new tag", ModuleConsentViewModel.disclosureText("weird-new-tag"))
        assertEquals("none", ModuleConsentViewModel.disclosureText("none"))
    }

    @Test
    fun shortComponentId_stripsPrefix() {
        assertEquals(
            "test-notary",
            ModuleConsentViewModel.shortComponentId("onym:component:test-notary"),
        )
        assertEquals("plain", ModuleConsentViewModel.shortComponentId("plain"))
    }
}
