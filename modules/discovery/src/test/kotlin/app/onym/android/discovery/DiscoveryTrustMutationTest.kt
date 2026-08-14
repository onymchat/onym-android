package app.onym.android.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * Tamper / mutation coverage that the immutable conformance fixtures
 * can't express: documents are built fresh and re-signed with an
 * in-test Ed25519 key (deterministic seed), so a single field can be
 * flipped while everything else stays valid — proving each check
 * fires for *its* reason, not because the signature broke first.
 */
class DiscoveryTrustMutationTest {

    private val now: Instant = Instant.parse("2026-08-14T00:00:00Z")

    // Deterministic in-test key — never a fixture key.
    private val privateKey = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)
    private val publicKeyHex = privateKey.generatePublicKey().encoded
        .joinToString("") { "%02x".format(it) }
    private val operator = "onym:key:$publicKeyHex"

    // ─── document builders ────────────────────────────────────────

    /** Canonicalize [document] (which must not carry a signature),
     *  sign, embed, and return published bytes. */
    private fun signAndPublish(document: JsonObject): ByteArray {
        val unsignedBytes = Json.encodeToString(JsonElement.serializer(), document)
            .toByteArray(Charsets.UTF_8)
        val signingBytes = DiscoveryCanonical.signingBytes(unsignedBytes)
        val signer = Ed25519Signer().apply { init(true, privateKey) }
        signer.update(signingBytes, 0, signingBytes.size)
        val signature = Base64.getEncoder().encodeToString(signer.generateSignature())
        val published = JsonObject(document + ("signature" to JsonPrimitive(signature)))
        return Json.encodeToString(JsonElement.serializer(), published)
            .toByteArray(Charsets.UTF_8)
    }

    private fun manifestDocument(
        snapshotUri: String = "https://discovery.example/catalogs/main.json",
        extraTopLevelField: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("version", 1)
        put("implementationProfileId", DiscoveryProfile.IMPLEMENTATION_PROFILE)
        put("providerId", "onym:component:test-discovery")
        put("operator", operator)
        put("seat", "discovery")
        putJsonArray("catalogs") {
            addJsonObject {
                put("catalogId", "main")
                put("snapshot", snapshotUri)
                put("audience", "public")
                putJsonArray("seatTypes") { add("notary") }
                put("policy", "sha256:" + "11".repeat(32))
            }
        }
        putJsonArray("capabilities") {
            add("signed-snapshot-v1")
            add("local-filtering-v1")
        }
        putJsonArray("offers") {}
        put("validUntil", "2026-12-31T23:59:59Z")
        if (extraTopLevelField) put("extra", 1)
    }

    private fun entryDocument(
        componentId: String = "onym:component:test-notary",
        relationship: String = "none",
        mutate: (MutableMap<String, JsonElement>) -> Unit = {},
    ): JsonObject {
        val base = buildJsonObject {
            put("componentId", componentId)
            put("seatType", "notary")
            put("manifest", buildJsonObject {
                put("uri", "https://notary.example/manifest.json")
                put("digest", "sha256:" + "22".repeat(32))
            })
            put("operator", operator)
            putJsonArray("profiles") { add("onym:notary-implementation:test-v1") }
            putJsonArray("evidence") {}
            put("listedAt", "2026-08-13T00:00:00Z")
            put("relationship", relationship)
            put("placement", "policy-ranked")
        }
        val map = base.toMutableMap()
        mutate(map)
        return JsonObject(map)
    }

    private fun snapshotDocument(
        sequence: Long = 1,
        previousDigest: String? = null,
        entries: List<JsonObject> = listOf(entryDocument()),
        generatedAt: String = "2026-08-13T00:00:00Z",
        expiresAt: String = "2026-09-12T00:00:00Z",
        extraTopLevelField: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("version", 1)
        put("implementationProfileId", DiscoveryProfile.IMPLEMENTATION_PROFILE)
        put("catalogId", "main")
        put("providerId", "onym:component:test-discovery")
        put("sequence", sequence)
        previousDigest?.let { put("previousDigest", it) }
        put("policyDigest", "sha256:" + "11".repeat(32))
        put("generatedAt", generatedAt)
        put("expiresAt", expiresAt)
        put("entries", buildJsonArray { entries.forEach { add(it) } })
        if (extraTopLevelField) put("extra", 1)
    }

    private fun verifiedManifest(): DiscoveryProviderManifest =
        DiscoveryTrust.verifyProviderManifest(
            signAndPublish(manifestDocument()), publicKeyHex, now,
        ).manifest

    // ─── sanity: the builders produce verifying documents ─────────

    @Test
    fun freshlyBuiltManifestAndSnapshot_verify() {
        val manifest = verifiedManifest()
        val result = DiscoveryTrust.verifySnapshot(
            signAndPublish(snapshotDocument()), manifest, previousRaw = null, now = now,
        )
        assertEquals(1, result.entries.size)
        assertTrue(result.skippedEntryIndexes.isEmpty())
    }

    // ─── signature tampers ────────────────────────────────────────

    @Test
    fun manifest_withFlippedSignature_isProviderManifestInvalid() {
        val document = manifestDocument()
        // Embed a signature over *different* content (all-zero bytes) —
        // structurally valid base64, cryptographically wrong.
        val forged = JsonObject(
            document + ("signature" to JsonPrimitive(
                Base64.getEncoder().encodeToString(ByteArray(64))
            ))
        )
        val raw = Json.encodeToString(JsonElement.serializer(), forged).toByteArray()
        try {
            DiscoveryTrust.verifyProviderManifest(raw, publicKeyHex, now)
            fail("expected provider_manifest_invalid")
        } catch (e: DiscoveryTrustError.ProviderManifestInvalid) {
            assertTrue(e.message!!.contains("signature"))
        }
    }

    @Test
    fun snapshot_withFlippedSignature_isSnapshotInvalid() {
        val manifest = verifiedManifest()
        val published = signAndPublish(snapshotDocument())
        // Flip one byte inside the document body: the embedded
        // signature no longer matches the canonical bytes.
        val tampered = String(published, Charsets.UTF_8)
            .replace("policy-ranked", "policy-rankeD")
            .toByteArray(Charsets.UTF_8)
        try {
            DiscoveryTrust.verifySnapshot(tampered, manifest, previousRaw = null, now = now)
            fail("expected snapshot_invalid")
        } catch (e: DiscoveryTrustError.SnapshotInvalid) {
            assertTrue(e.message!!.contains("signature"))
        }
    }

    // ─── strict top-level decode ──────────────────────────────────

    @Test
    fun manifest_withUnknownTopLevelField_isProviderManifestInvalid() {
        // Signed over the extra field, so only strictness can reject it.
        val raw = signAndPublish(manifestDocument(extraTopLevelField = true))
        try {
            DiscoveryTrust.verifyProviderManifest(raw, publicKeyHex, now)
            fail("expected provider_manifest_invalid")
        } catch (e: DiscoveryTrustError.ProviderManifestInvalid) {
            assertTrue(e.message!!.contains("schema"))
        }
    }

    @Test
    fun snapshot_withUnknownTopLevelField_isSnapshotInvalid() {
        val manifest = verifiedManifest()
        val raw = signAndPublish(snapshotDocument(extraTopLevelField = true))
        try {
            DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
            fail("expected snapshot_invalid")
        } catch (e: DiscoveryTrustError.SnapshotInvalid) {
            assertTrue(e.message!!.contains("schema"))
        }
    }

    // ─── URI rules (§7) ───────────────────────────────────────────

    @Test
    fun manifest_withHttpSnapshotUri_isProviderManifestInvalid() {
        val raw = signAndPublish(
            manifestDocument(snapshotUri = "http://discovery.example/catalogs/main.json")
        )
        try {
            DiscoveryTrust.verifyProviderManifest(raw, publicKeyHex, now)
            fail("expected provider_manifest_invalid")
        } catch (e: DiscoveryTrustError.ProviderManifestInvalid) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun manifest_withIpLiteralOrQueryUri_isProviderManifestInvalid() {
        for (uri in listOf(
            "https://192.168.1.10/catalogs/main.json",
            "https://[::1]/catalogs/main.json",
            "https://discovery.example/catalogs/main.json?tracking=1",
            "https://user:pw@discovery.example/catalogs/main.json",
            "https://discovery.example:8443/catalogs/main.json",
        )) {
            val raw = signAndPublish(manifestDocument(snapshotUri = uri))
            try {
                DiscoveryTrust.verifyProviderManifest(raw, publicKeyHex, now)
                fail("expected provider_manifest_invalid for $uri")
            } catch (_: DiscoveryTrustError.ProviderManifestInvalid) {
                // expected
            }
        }
    }

    // ─── lossy entry decoding ─────────────────────────────────────

    @Test
    fun snapshot_withOneMalformedEntry_skipsItAndKeepsTheRest() {
        val manifest = verifiedManifest()
        val goodA = entryDocument(componentId = "onym:component:good-a")
        val malformed = entryDocument(componentId = "onym:component:bad") {
            it["unknownEntryField"] = JsonPrimitive(true)
        }
        val goodB = entryDocument(componentId = "onym:component:good-b")
        val raw = signAndPublish(snapshotDocument(entries = listOf(goodA, malformed, goodB)))

        val result = DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
        assertEquals(listOf("onym:component:good-a", "onym:component:good-b"),
            result.entries.map { it.componentId })
        assertEquals(listOf(1), result.skippedEntryIndexes)
    }

    @Test
    fun snapshot_entryWithUnknownRelationship_isSkippedNotDefaulted() {
        val manifest = verifiedManifest()
        val raw = signAndPublish(
            snapshotDocument(entries = listOf(
                entryDocument(componentId = "onym:component:good-a"),
                entryDocument(
                    componentId = "onym:component:paid-actor",
                    relationship = "totally-organic-trust-me",
                ),
            ))
        )
        val result = DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
        assertEquals(listOf("onym:component:good-a"), result.entries.map { it.componentId })
        assertEquals(listOf(1), result.skippedEntryIndexes)
    }

    @Test
    fun snapshot_entryWithHttpManifestUri_isSkipped() {
        val manifest = verifiedManifest()
        val badUriEntry = entryDocument(componentId = "onym:component:bad-uri") {
            it["manifest"] = buildJsonObject {
                put("uri", "http://notary.example/manifest.json")
                put("digest", "sha256:" + "22".repeat(32))
            }
        }
        val raw = signAndPublish(
            snapshotDocument(entries = listOf(entryDocument(), badUriEntry))
        )
        val result = DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
        assertEquals(1, result.entries.size)
        assertEquals(listOf(1), result.skippedEntryIndexes)
    }

    // ─── fatal snapshot rules ─────────────────────────────────────

    @Test
    fun snapshot_withDuplicateComponentId_isSnapshotInvalid() {
        val manifest = verifiedManifest()
        val raw = signAndPublish(
            snapshotDocument(entries = listOf(entryDocument(), entryDocument()))
        )
        try {
            DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
            fail("expected snapshot_invalid")
        } catch (e: DiscoveryTrustError.SnapshotInvalid) {
            assertTrue(e.message!!.contains("duplicate componentId"))
        }
    }

    @Test
    fun snapshot_withMoreThan512Entries_isSnapshotInvalid() {
        val manifest = verifiedManifest()
        val entries = (0..DiscoveryProfile.MAX_ENTRIES).map {
            entryDocument(componentId = "onym:component:e$it")
        }
        val raw = signAndPublish(snapshotDocument(entries = entries))
        try {
            DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
            fail("expected snapshot_invalid")
        } catch (e: DiscoveryTrustError.SnapshotInvalid) {
            assertTrue(e.message!!.contains("entries exceeds"))
        }
    }

    @Test
    fun snapshot_expiryWindowOver90Days_isSnapshotInvalid() {
        val manifest = verifiedManifest()
        val raw = signAndPublish(
            snapshotDocument(
                generatedAt = "2026-08-13T00:00:00Z",
                expiresAt = "2026-11-12T00:00:01Z", // 91 days + 1s
            )
        )
        try {
            DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
            fail("expected snapshot_invalid")
        } catch (e: DiscoveryTrustError.SnapshotInvalid) {
            assertTrue(e.message!!.contains("90"))
        }
    }

    @Test
    fun snapshot_sequenceOne_mustNotCarryPreviousDigest() {
        val manifest = verifiedManifest()
        val raw = signAndPublish(
            snapshotDocument(sequence = 1, previousDigest = "sha256:" + "33".repeat(32))
        )
        try {
            DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
            fail("expected snapshot_invalid")
        } catch (e: DiscoveryTrustError.SnapshotInvalid) {
            assertTrue(e.message!!.contains("sequence 1"))
        }
    }

    @Test
    fun snapshot_sequenceAboveOne_requiresPreviousDigest() {
        val manifest = verifiedManifest()
        val raw = signAndPublish(snapshotDocument(sequence = 2, previousDigest = null))
        try {
            DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
            fail("expected snapshot_invalid")
        } catch (e: DiscoveryTrustError.SnapshotInvalid) {
            assertTrue(e.message!!.contains("previousDigest"))
        }
    }
}
