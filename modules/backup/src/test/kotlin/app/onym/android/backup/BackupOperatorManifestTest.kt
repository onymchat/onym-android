package app.onym.android.backup

import app.onym.android.foundation.SignedServiceManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seat fields sit at the manifest's top level, per onym-system
 * `backup/UI-Backup.md` §5.3 — the shape the operator actually
 * publishes (`operator/src/documents.rs::manifest_document`). This
 * suite is written against that document verbatim rather than against
 * a fixture invented here: a decoder that only parses its own fixture
 * is exactly how a whole seat ends up unreachable behind a manifest
 * nobody can consent to.
 */
class BackupOperatorManifestTest {

    private val operatorKey = "onym:key:" + "a".repeat(64)
    private val termsDigest = "sha256:" + "b".repeat(64)

    /** Byte-for-byte the operator's `manifest_document`, minus the
     *  signature (this type never verifies one — it decodes bytes the
     *  reviewer already verified). */
    private fun operatorManifestJson(
        endpoints: String = """[{"uri": "https://backup.example.com", "role": "read-write"}]""",
        declaredTerms: String? = termsDigest,
    ): ByteArray {
        val terms = declaredTerms?.let { """"declaredTerms": "$it",""" } ?: ""
        return """
            {
              "version": 1,
              "componentId": "onym:component:backup-op-1",
              "seat": "storage.backup",
              "operator": "$operatorKey",
              "backupProfileId": "onym:backup-profile:sealed-device-archive-v1",
              "implementationProfileId": "onym:backup-implementation:object-http-v1",
              "endpoints": $endpoints,
              "capabilities": ["upload", "list", "download", "erase", "export"],
              "limits": {
                "maximumSealedSnapshotBytes": 1073741824,
                "maximumRetainedSnapshots": 3
              },
              $terms
              "entitlementIssuers": [],
              "offers": []
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
    }

    private fun decode(raw: ByteArray): BackupOperatorManifest =
        BackupOperatorManifest.from(SignedServiceManifest.parse(raw))

    @Test
    fun published_operator_manifest_decodes_every_seat_field() {
        val manifest = decode(operatorManifestJson())

        assertEquals("onym:component:backup-op-1", manifest.componentId)
        assertEquals("onym:backup-profile:sealed-device-archive-v1", manifest.backupProfileId)
        assertEquals("onym:backup-implementation:object-http-v1", manifest.implementationProfileId)
        assertEquals("https://backup.example.com", manifest.endpoint)
        assertEquals(termsDigest, manifest.declaredTermsDigest)
        // Free mode: no issuer named is itself the declaration that
        // this operator never asks for an entitlement.
        assertTrue(manifest.entitlementIssuers.isEmpty())
        // JSON numbers, not strings — the spec writes the limits as
        // placeholders in quotes but the operator emits numbers.
        assertEquals(1073741824L, manifest.maximumSealedSnapshotBytes)
        assertEquals(3L, manifest.maximumRetainedSnapshots)
    }

    @Test
    fun the_seat_type_is_the_spine_seat_not_a_container() {
        // Guards the decoder against sliding back to reading a
        // `storage: { backup: { … } }` object: a manifest whose fields
        // are nested that way declares none of them at the level §5.3
        // puts them, so it must not decode as if it did.
        val nested = """
            {
              "componentId": "onym:component:backup-op-1",
              "seat": "storage.backup",
              "operator": "$operatorKey",
              "storage": {"backup": {"declaredTerms": "$termsDigest"}}
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val error = runCatching { decode(nested) }.exceptionOrNull()
        assertTrue("nested manifest must not decode", error is BackupError.LocalFailure)
    }

    @Test
    fun a_manifest_without_declared_terms_is_refused() {
        val error = runCatching { decode(operatorManifestJson(declaredTerms = null)) }.exceptionOrNull()
        assertTrue(error is BackupError.LocalFailure)
    }

    @Test
    fun only_a_read_write_https_endpoint_is_taken() {
        // In published order: wrong role, wrong scheme, no host — then
        // the one usable entry. Malformed entries are skipped, never
        // rewritten into something usable.
        val manifest = decode(
            operatorManifestJson(
                endpoints = """
                    [
                      {"uri": "https://read-only.example.com", "role": "read"},
                      {"uri": "http://insecure.example.com", "role": "read-write"},
                      {"uri": "https://", "role": "read-write"},
                      {"role": "read-write"},
                      {"uri": "https://backup.example.com/v1", "role": "read-write"}
                    ]
                """.trimIndent(),
            ),
        )
        assertEquals("https://backup.example.com/v1", manifest.endpoint)
    }

    @Test
    fun no_usable_endpoint_yields_null_rather_than_a_guess() {
        val manifest = decode(operatorManifestJson(endpoints = """[{"uri": "wss://relay.example.com", "role": "read-write"}]"""))
        assertNull(manifest.endpoint)
        assertNull(manifest.termsUrl(termsDigest))
    }

    @Test
    fun terms_url_is_built_only_from_a_full_digest() {
        val manifest = decode(operatorManifestJson())
        assertEquals(
            "https://backup.example.com/terms/${termsDigest.removePrefix("sha256:")}.json",
            manifest.termsUrl(termsDigest),
        )
        // An operator response can never smuggle a path through here.
        assertNull(manifest.termsUrl("../../etc/passwd"))
        assertNull(manifest.termsUrl("sha256:short"))
    }
}
