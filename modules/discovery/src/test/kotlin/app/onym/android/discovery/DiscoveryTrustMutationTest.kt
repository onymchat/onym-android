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
        extraDescriptorField: Boolean = false,
        secondCatalog: Boolean = false,
        omitPrivacyProfile: Boolean = false,
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
                put("policyUri", "https://discovery.example/policies/main.md")
                if (extraDescriptorField) put("surprise", true)
            }
            if (secondCatalog) addJsonObject {
                put("catalogId", "second")
                put("snapshot", "https://discovery.example/catalogs/second.json")
                put("audience", "public")
                putJsonArray("seatTypes") { add("notary") }
                put("policy", "sha256:" + "44".repeat(32))
                put("policyUri", "https://discovery.example/policies/second.md")
            }
        }
        putJsonArray("capabilities") {
            add("signed-snapshot-v1")
            add("local-filtering-v1")
        }
        if (!omitPrivacyProfile) put("privacyProfile", "sha256:" + "55".repeat(32))
        put("privacyProfileUri", "https://discovery.example/privacy.md")
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

    private fun verifiedManifest(): VerifiedProviderManifest =
        DiscoveryTrust.verifyProviderManifest(
            signAndPublish(manifestDocument()), publicKeyHex, now,
        )

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
    fun manifest_withHttpSnapshotUri_hasZeroSurvivingDescriptors() {
        // §4.1: the malformed descriptor is skipped (not document-
        // fatal), but a manifest whose only descriptor was skipped
        // has zero survivors — provider_manifest_invalid.
        val raw = signAndPublish(
            manifestDocument(snapshotUri = "http://discovery.example/catalogs/main.json")
        )
        try {
            DiscoveryTrust.verifyProviderManifest(raw, publicKeyHex, now)
            fail("expected provider_manifest_invalid")
        } catch (e: DiscoveryTrustError.ProviderManifestInvalid) {
            assertTrue(e.message!!.contains("no decodable catalog descriptors"))
        }
    }

    // ─── lossy descriptor decoding (§4.1) ─────────────────────────

    @Test
    fun manifest_descriptorWithUnknownKey_isSkippedAndCounted() {
        val raw = signAndPublish(
            manifestDocument(extraDescriptorField = true, secondCatalog = true)
        )
        val verified = DiscoveryTrust.verifyProviderManifest(raw, publicKeyHex, now)
        assertEquals(listOf("second"), verified.catalogs.map { it.catalogId })
        assertEquals(listOf(0), verified.skippedCatalogIndexes)
    }

    @Test
    fun manifest_withoutPolicyUri_descriptorIsSkipped() {
        val document = manifestDocument(secondCatalog = true)
        val catalogs = (document["catalogs"] as kotlinx.serialization.json.JsonArray)
        val stripped = JsonObject(
            (catalogs[0] as JsonObject).filterKeys { it != "policyUri" }
        )
        val mutated = JsonObject(
            document + ("catalogs" to buildJsonArray {
                add(stripped)
                add(catalogs[1])
            })
        )
        val verified = DiscoveryTrust.verifyProviderManifest(
            signAndPublish(mutated), publicKeyHex, now,
        )
        assertEquals(listOf("second"), verified.catalogs.map { it.catalogId })
        assertEquals(listOf(0), verified.skippedCatalogIndexes)
    }

    @Test
    fun manifest_withoutPrivacyProfile_isProviderManifestInvalid() {
        val raw = signAndPublish(manifestDocument(omitPrivacyProfile = true))
        try {
            DiscoveryTrust.verifyProviderManifest(raw, publicKeyHex, now)
            fail("expected provider_manifest_invalid")
        } catch (e: DiscoveryTrustError.ProviderManifestInvalid) {
            assertTrue(e.message!!.contains("schema"))
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
    fun snapshot_bornExpired_equalTimestamps_isSnapshotInvalid() {
        // §4.2: expiresAt must be STRICTLY after generatedAt —
        // equality is snapshot_invalid (born expired), not merely
        // snapshot_expired.
        val manifest = verifiedManifest()
        val raw = signAndPublish(
            snapshotDocument(
                generatedAt = "2026-08-13T00:00:00Z",
                expiresAt = "2026-08-13T00:00:00Z",
            )
        )
        try {
            DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
            fail("expected snapshot_invalid")
        } catch (e: DiscoveryTrustError.SnapshotInvalid) {
            assertTrue(e.message!!.contains("after generatedAt"))
        }
    }

    @Test
    fun snapshot_sameSequenceDifferentBytes_isFork() {
        val manifest = verifiedManifest()
        val published = signAndPublish(snapshotDocument())
        val republished = signAndPublish(
            snapshotDocument(entries = listOf(entryDocument(componentId = "onym:component:other")))
        )
        val anchor = AcceptedSnapshotRef(
            digest = DiscoveryTrust.sha256Digest(published),
            sequence = 1,
        )
        try {
            DiscoveryTrust.verifySnapshot(republished, manifest, previous = anchor, now = now)
            fail("expected snapshot_invalid")
        } catch (e: DiscoveryTrustError.SnapshotInvalid) {
            assertTrue(e.message!!.contains("fork"))
        }
    }

    @Test
    fun snapshot_entryWithNonEmptyEvidence_isSkipped() {
        // §4.2: evidence must be absent or empty in v1 — an entry with
        // a non-empty array is skipped, never passed through as an
        // unrenderable attestation.
        val manifest = verifiedManifest()
        val withEvidence = entryDocument(componentId = "onym:component:audited") {
            it["evidence"] = buildJsonArray {
                addJsonObject {
                    put("type", "audit")
                    put("uri", "https://auditor.example/report.pdf")
                    put("digest", "sha256:" + "33".repeat(32))
                }
            }
        }
        val raw = signAndPublish(snapshotDocument(entries = listOf(entryDocument(), withEvidence)))
        val result = DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
        assertEquals(1, result.entries.size)
        assertEquals(listOf(1), result.skippedEntryIndexes)
    }

    @Test
    fun snapshot_entryStatus_validSurvives_invalidSkips() {
        // §4.2: a VALID status never skips the entry (skipping it is
        // the exact warning-dropping failure the field exists to
        // prevent); an undecodable status skips the entry.
        val manifest = verifiedManifest()
        val warned = entryDocument(componentId = "onym:component:warned") {
            it["status"] = buildJsonObject {
                put("state", "warning")
                put("uri", "https://discovery.example/status/warned.md")
            }
        }
        val reviewed = entryDocument(componentId = "onym:component:reviewed") {
            it["status"] = buildJsonObject { put("state", "review") }
        }
        val unknownState = entryDocument(componentId = "onym:component:suspended") {
            it["status"] = buildJsonObject { put("state", "suspended") }
        }
        val httpUri = entryDocument(componentId = "onym:component:http-status") {
            it["status"] = buildJsonObject {
                put("state", "warning")
                put("uri", "http://discovery.example/status.md")
            }
        }
        val raw = signAndPublish(
            snapshotDocument(entries = listOf(warned, reviewed, unknownState, httpUri))
        )
        val result = DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
        assertEquals(2, result.entries.size)
        assertEquals(listOf(2, 3), result.skippedEntryIndexes)
        assertEquals("warning", result.entries[0].status?.state)
        assertEquals("review", result.entries[1].status?.state)
        assertEquals(null, result.entries[1].status?.uri)
    }

    @Test
    fun snapshot_minimalEntryWithoutProfilesOrEvidence_survives() {
        // §4.1 lists profiles and evidence as optional: a minimal
        // conforming entry must not be skipped.
        val manifest = verifiedManifest()
        val minimal = entryDocument(componentId = "onym:component:minimal") {
            it.remove("profiles")
            it.remove("evidence")
        }
        val raw = signAndPublish(snapshotDocument(entries = listOf(minimal)))
        val result = DiscoveryTrust.verifySnapshot(raw, manifest, previousRaw = null, now = now)
        assertEquals(1, result.entries.size)
        assertTrue(result.skippedEntryIndexes.isEmpty())
    }

    @Test
    fun manifest_invalidSeatTypesMember_skipsDescriptor() {
        // §4.1: members are tokens in [a-z0-9.-]{1,64} or the lone
        // "*" wildcard; anything else skips the descriptor.
        val base = manifestDocument(secondCatalog = true)
        val catalogs = base["catalogs"] as kotlinx.serialization.json.JsonArray
        val good = catalogs[0] as JsonObject
        val badMember = JsonObject(
            (catalogs[1] as JsonObject) + ("seatTypes" to buildJsonArray { add("Notary!") })
        )
        val wildcardNotAlone = JsonObject(
            (catalogs[1] as JsonObject) +
                ("catalogId" to JsonPrimitive("third")) +
                ("seatTypes" to buildJsonArray { add("*"); add("notary") })
        )
        val mutated = JsonObject(
            base + ("catalogs" to buildJsonArray { add(good); add(badMember); add(wildcardNotAlone) })
        )
        val verified = DiscoveryTrust.verifyProviderManifest(
            signAndPublish(mutated), publicKeyHex, now,
        )
        assertEquals(listOf("main"), verified.catalogs.map { it.catalogId })
        assertEquals(listOf(1, 2), verified.skippedCatalogIndexes)
    }

    @Test
    fun manifest_loneWildcardSeatTypes_isAccepted() {
        val base = manifestDocument()
        val catalogs = base["catalogs"] as kotlinx.serialization.json.JsonArray
        val wildcard = JsonObject(
            (catalogs[0] as JsonObject) + ("seatTypes" to buildJsonArray { add("*") })
        )
        val mutated = JsonObject(base + ("catalogs" to buildJsonArray { add(wildcard) }))
        val verified = DiscoveryTrust.verifyProviderManifest(
            signAndPublish(mutated), publicKeyHex, now,
        )
        assertEquals(listOf("main"), verified.catalogs.map { it.catalogId })
    }

    @Test
    fun manifest_rawStringPortAndIntegerIpv4Uris_areRejected() {
        // §7: the RAW string must not carry a port component (URL
        // libraries normalize a redundant :443 away before any
        // parsed-port check), and integer/hex IPv4 literal hosts are
        // IP literals, not DNS names.
        for (uri in listOf(
            "https://discovery.example:443/catalogs/main.json",
            "https://3232235777/catalogs/main.json",
            "https://0xc0a80101/catalogs/main.json",
        )) {
            val raw = signAndPublish(manifestDocument(snapshotUri = uri))
            try {
                DiscoveryTrust.verifyProviderManifest(raw, publicKeyHex, now)
                fail("expected provider_manifest_invalid for $uri")
            } catch (_: DiscoveryTrustError.ProviderManifestInvalid) {
                // expected: the descriptor was skipped, zero survive
            }
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
