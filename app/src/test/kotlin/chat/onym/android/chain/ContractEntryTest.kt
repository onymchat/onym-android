package chat.onym.android.chain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Wire-format → typed-domain mapping for [ContractsManifest]. The
 * raw decoder ([RawContractsManifest]) tolerates unknown enum
 * values (kotlinx.serialization throws on enums by default); the
 * typed [ContractsManifest.fromRaw] mapping then drops contract
 * entries whose `network` or `type` doesn't match a known case.
 */
class ContractEntryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decode_happyPath_twoReleaseFixture() {
        val src = """
            {
              "version": 1,
              "releases": [
                {
                  "release": "v0.0.2",
                  "publishedAt": "2026-05-01T15:29:00Z",
                  "contracts": [
                    { "network": "testnet", "type": "anarchy",   "id": "C-A-2" },
                    { "network": "testnet", "type": "democracy", "id": "C-D-2" }
                  ]
                },
                {
                  "release": "v0.0.1",
                  "publishedAt": "2026-05-01T11:43:00Z",
                  "contracts": [
                    { "network": "testnet", "type": "anarchy", "id": "C-A-1" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val raw = json.decodeFromString(RawContractsManifest.serializer(), src)
        val typed = ContractsManifest.fromRaw(raw)

        assertEquals(2, typed.releases.size)
        // Sorted newest-first by publishedAt.
        assertEquals("v0.0.2", typed.releases[0].release)
        assertEquals("v0.0.1", typed.releases[1].release)
        assertEquals(Instant.parse("2026-05-01T15:29:00Z"), typed.releases[0].publishedAt)
        assertEquals(2, typed.releases[0].contracts.size)
        val anarchy = typed.releases[0].contracts.first {
            it.network == ContractNetwork.Testnet && it.type == GovernanceType.Anarchy
        }
        assertEquals("C-A-2", anarchy.id)
    }

    @Test
    fun decode_unknownNetwork_dropsEntry() {
        val src = """
            {
              "version": 1,
              "releases": [
                {
                  "release": "v0.0.2",
                  "publishedAt": "2026-05-01T15:29:00Z",
                  "contracts": [
                    { "network": "futurenet", "type": "anarchy",   "id": "C-A-FUTURE" },
                    { "network": "testnet",   "type": "democracy", "id": "C-D-2" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val typed = ContractsManifest.fromRaw(json.decodeFromString(RawContractsManifest.serializer(), src))
        assertEquals(1, typed.releases.single().contracts.size)
        assertEquals(GovernanceType.Democracy, typed.releases.single().contracts.single().type)
    }

    @Test
    fun decode_unknownType_dropsEntry() {
        val src = """
            {
              "version": 1,
              "releases": [
                {
                  "release": "v0.0.2",
                  "publishedAt": "2026-05-01T15:29:00Z",
                  "contracts": [
                    { "network": "testnet", "type": "monarchy", "id": "C-M" },
                    { "network": "testnet", "type": "tyranny",  "id": "C-T-2" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val typed = ContractsManifest.fromRaw(json.decodeFromString(RawContractsManifest.serializer(), src))
        assertEquals(1, typed.releases.single().contracts.size)
        assertEquals(GovernanceType.Tyranny, typed.releases.single().contracts.single().type)
    }

    @Test
    fun decode_outOfOrderReleases_areReSorted() {
        val src = """
            {
              "version": 1,
              "releases": [
                { "release": "v0.0.1", "publishedAt": "2026-05-01T11:43:00Z", "contracts": [] },
                { "release": "v0.0.3", "publishedAt": "2026-05-02T08:00:00Z", "contracts": [] },
                { "release": "v0.0.2", "publishedAt": "2026-05-01T15:29:00Z", "contracts": [] }
              ]
            }
        """.trimIndent()
        val typed = ContractsManifest.fromRaw(json.decodeFromString(RawContractsManifest.serializer(), src))
        assertEquals(listOf("v0.0.3", "v0.0.2", "v0.0.1"), typed.releases.map { it.release })
    }

    @Test
    fun decode_unparseablePublishedAt_dropsRelease() {
        val src = """
            {
              "version": 1,
              "releases": [
                { "release": "v0.0.bad", "publishedAt": "not a date", "contracts": [] },
                { "release": "v0.0.2",   "publishedAt": "2026-05-01T15:29:00Z", "contracts": [] }
              ]
            }
        """.trimIndent()
        val typed = ContractsManifest.fromRaw(json.decodeFromString(RawContractsManifest.serializer(), src))
        assertEquals(1, typed.releases.size)
        assertEquals("v0.0.2", typed.releases.single().release)
    }
}
