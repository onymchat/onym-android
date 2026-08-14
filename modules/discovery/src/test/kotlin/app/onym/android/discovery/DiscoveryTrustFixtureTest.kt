package app.onym.android.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * [DiscoveryTrust] against the shared conformance fixtures (see
 * [DiscoveryFixtures] for the byte-fixture convention note): the
 * signed provider manifest, the valid three-snapshot chain, rollback
 * / gap / expiry failures, and destination digest binding. All clock
 * checks run at an injected `now` — never the wall clock.
 */
class DiscoveryTrustFixtureTest {

    /** Inside the fixtures' validity window (validUntil 2026-12-31,
     *  snapshots expire 2026-09-12). */
    private val now: Instant = Instant.parse("2026-08-14T00:00:00Z")

    private val manifestRaw = DiscoveryFixtures.load("provider-manifest.json")
    private val snapshot1 = DiscoveryFixtures.load("snapshot-1.json")
    private val snapshot2 = DiscoveryFixtures.load("snapshot-2.json")
    private val snapshot3 = DiscoveryFixtures.load("snapshot-3.json")

    private fun verifiedManifest(): VerifiedProviderManifest =
        DiscoveryTrust.verifyProviderManifest(manifestRaw, null, now)

    // ─── provider manifest ────────────────────────────────────────

    @Test
    fun providerManifest_verifies_andReturnsOperatorKeyForPinning() {
        val verified = DiscoveryTrust.verifyProviderManifest(manifestRaw, null, now)
        assertEquals(DiscoveryFixtures.OPERATOR_KEY_HEX, verified.operatorKeyHex)
        assertEquals("onym:component:onym-discovery", verified.manifest.providerId)
        assertEquals(1, verified.catalogs.size)
        assertEquals("public-all-seats", verified.catalogs.single().catalogId)
        assertTrue(verified.skippedCatalogIndexes.isEmpty())
    }

    @Test
    fun providerManifest_verifiesAgainstMatchingPinnedKey() {
        DiscoveryTrust.verifyProviderManifest(
            manifestRaw,
            DiscoveryFixtures.OPERATOR_KEY_HEX,
            now,
        )
    }

    @Test
    fun providerManifest_pinnedKeyMismatch_isProviderManifestInvalid() {
        val wrongKey = "0".repeat(64)
        try {
            DiscoveryTrust.verifyProviderManifest(manifestRaw, wrongKey, now)
            fail("expected provider_manifest_invalid")
        } catch (e: DiscoveryTrustError.ProviderManifestInvalid) {
            assertEquals("provider_manifest_invalid", e.code)
            assertTrue(e.message!!.contains("pinned"))
        }
    }

    @Test
    fun providerManifest_pastValidUntil_isProviderManifestInvalid() {
        try {
            DiscoveryTrust.verifyProviderManifest(
                manifestRaw, null, Instant.parse("2027-01-01T00:00:00Z"),
            )
            fail("expected provider_manifest_invalid")
        } catch (e: DiscoveryTrustError.ProviderManifestInvalid) {
            assertTrue(e.message!!.contains("expired"))
        }
    }

    // ─── snapshot chain ───────────────────────────────────────────

    @Test
    fun snapshotChain_1_2_3_verifies() {
        val manifest = verifiedManifest()

        val v1 = DiscoveryTrust.verifySnapshot(snapshot1, manifest, previousRaw = null, now = now)
        assertEquals(1L, v1.snapshot.sequence)
        assertEquals(1, v1.entries.size)
        assertEquals(0, v1.skippedEntryIndexes.size)
        assertEquals("onym:component:onym-courier", v1.entries.single().componentId)
        assertEquals(EntryRelationship.CommonOwner, v1.entries.single().relationship)

        val v2 = DiscoveryTrust.verifySnapshot(snapshot2, manifest, previousRaw = snapshot1, now = now)
        assertEquals(2L, v2.snapshot.sequence)
        // previousDigest is over the previous snapshot's exact bytes.
        assertEquals(v1.digest, v2.snapshot.previousDigest)

        val v3 = DiscoveryTrust.verifySnapshot(snapshot3, manifest, previousRaw = snapshot2, now = now)
        assertEquals(3L, v3.snapshot.sequence)
        assertEquals(v2.digest, v3.snapshot.previousDigest)
    }

    @Test
    fun snapshotChain_alsoVerifiesAgainstRetainedDigestAnchor() {
        // §8 retention: the client keeps only digest + sequence, not
        // the previous bytes — the AcceptedSnapshotRef overload is
        // what DiscoveryRepository actually uses.
        val manifest = verifiedManifest()
        val v1 = DiscoveryTrust.verifySnapshot(snapshot1, manifest, previousRaw = null, now = now)
        DiscoveryTrust.verifySnapshot(
            snapshot2,
            manifest,
            previous = AcceptedSnapshotRef(digest = v1.digest, sequence = v1.snapshot.sequence),
            now = now,
        )
    }

    @Test
    fun snapshotRollback_s1AfterS2_isSnapshotInvalid() {
        val manifest = verifiedManifest()
        try {
            DiscoveryTrust.verifySnapshot(snapshot1, manifest, previousRaw = snapshot2, now = now)
            fail("expected snapshot_invalid")
        } catch (e: DiscoveryTrustError.SnapshotInvalid) {
            assertEquals("snapshot_invalid", e.code)
            assertTrue(e.message!!.contains("sequence"))
        }
    }

    @Test
    fun snapshotGap_s3AfterS1_isSnapshotInvalid() {
        val manifest = verifiedManifest()
        try {
            DiscoveryTrust.verifySnapshot(snapshot3, manifest, previousRaw = snapshot1, now = now)
            fail("expected snapshot_invalid")
        } catch (e: DiscoveryTrustError.SnapshotInvalid) {
            assertTrue(e.message!!.contains("sequence"))
        }
    }

    @Test
    fun snapshot_pastExpiresAt_isSnapshotExpired() {
        val manifest = DiscoveryTrust.verifyProviderManifest(
            manifestRaw, null, Instant.parse("2026-12-01T00:00:00Z"),
        )
        try {
            DiscoveryTrust.verifySnapshot(
                snapshot1, manifest, previousRaw = null,
                now = Instant.parse("2026-12-01T00:00:00Z"),
            )
            fail("expected snapshot_expired")
        } catch (e: DiscoveryTrustError.SnapshotExpired) {
            assertEquals("snapshot_expired", e.code)
        }
    }

    @Test
    fun snapshot_expirySkewBoundary_tenMinutesGraceThenExpired() {
        // §4.2/§9: expired only when expiresAt (2026-09-12T00:00:00Z)
        // is MORE than 10 minutes in the past.
        val manifest = verifiedManifest()
        // Exactly 10 minutes past expiry: within the skew allowance.
        DiscoveryTrust.verifySnapshot(
            snapshot1, manifest, previousRaw = null,
            now = Instant.parse("2026-09-12T00:10:00Z"),
        )
        try {
            DiscoveryTrust.verifySnapshot(
                snapshot1, manifest, previousRaw = null,
                now = Instant.parse("2026-09-12T00:10:01Z"),
            )
            fail("expected snapshot_expired")
        } catch (e: DiscoveryTrustError.SnapshotExpired) {
            assertEquals("snapshot_expired", e.code)
        }
    }

    // ─── destination manifest ─────────────────────────────────────

    @Test
    fun destinationManifest_digestBinds() {
        val manifest = verifiedManifest()
        val entry = DiscoveryTrust
            .verifySnapshot(snapshot1, manifest, previousRaw = null, now = now)
            .entries.single()
        val destination = DiscoveryFixtures.load("destination-manifest.json")
        // The snapshot entry pins the destination manifest's exact bytes.
        DiscoveryTrust.verifyDestination(destination, entry.manifest.digest)
        assertEquals(entry.manifest.digest, DiscoveryTrust.sha256Digest(destination))
    }

    @Test
    fun destinationManifest_flippedByte_isEntryManifestMismatch() {
        val entryDigest = DiscoveryTrust.sha256Digest(
            DiscoveryFixtures.load("destination-manifest.json")
        )
        val tampered = DiscoveryFixtures.load("destination-manifest.json").also {
            it[10] = (it[10].toInt() xor 0x01).toByte()
        }
        try {
            DiscoveryTrust.verifyDestination(tampered, entryDigest)
            fail("expected entry_manifest_mismatch")
        } catch (e: DiscoveryTrustError.EntryManifestMismatch) {
            assertEquals("entry_manifest_mismatch", e.code)
        }
    }

    @Test
    fun destinationManifest_oversize_isEntryManifestUnavailable() {
        val oversize = ByteArray(DiscoveryProfile.MAX_DESTINATION_MANIFEST_BYTES + 1)
        try {
            DiscoveryTrust.verifyDestination(oversize, "sha256:" + "0".repeat(64))
            fail("expected entry_manifest_unavailable")
        } catch (e: DiscoveryTrustError.EntryManifestUnavailable) {
            assertEquals("entry_manifest_unavailable", e.code)
        }
    }
}
