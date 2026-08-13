@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.foundation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.onym.android.foundation.ServiceManifestTestSigning.signedManifestBytes
import app.onym.android.foundation.ServiceManifestTestSigning.unsignedManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/**
 * DataStore-backed consent pinning: accept round-trip, re-consent
 * deactivation with history, exact-bytes snapshot survival. Same
 * `PreferenceDataStoreFactory` + `TemporaryFolder` pattern as
 * `RelayerSelectionStoreTest` in `:chain`.
 */
class PinnedConsentStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var datastoreScopeJob: Job
    private lateinit var datastoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStorePreferencesPinnedConsentStore

    private val reviewer = ServiceManifestReviewer()
    private val now = Instant.parse("2026-08-13T12:00:00Z")

    @Before
    fun setUp() {
        datastoreScopeJob = SupervisorJob()
        datastoreScope = CoroutineScope(UnconfinedTestDispatcher() + datastoreScopeJob)
        val file = tempFolder.newFile("consent-${System.nanoTime()}.preferences_pb")
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(scope = datastoreScope) { file }
        store = DataStorePreferencesPinnedConsentStore(dataStore)
    }

    @After
    fun tearDown() {
        datastoreScopeJob.cancel()
    }

    private fun reviewed(
        componentId: String = "onym:component:test-courier",
        seat: String = "courier",
    ): ReviewedServiceManifest = reviewer.review(
        signedManifestBytes(
            unsignedManifest(
                componentId = componentId,
                seat = seat,
                offers = listOf(
                    buildJsonObject {
                        put("offerId", "free-tier")
                        put("model", "free")
                    },
                ),
            ),
        ),
        now = now,
    )

    // ─── accept + round-trip ──────────────────────────────────────

    @Test
    fun load_emptyOnFreshStore() = runTest {
        assertEquals(emptyList<PinnedConsentRecord>(), store.load())
    }

    @Test
    fun accept_persistsRecordWithManifestSnapshot() = runTest {
        val token = reviewed()
        val record = store.accept(
            token,
            sourceLabel = "Onym Discovery",
            offerId = "free-tier",
            acceptedAt = now,
        )

        val loaded = store.load()
        assertEquals(listOf(record), loaded)
        val stored = loaded.single()
        assertEquals("onym:component:test-courier", stored.componentId)
        assertEquals("courier", stored.seatType)
        assertEquals(token.signedManifest.manifestHash, stored.manifestHash)
        assertTrue(stored.manifestBytes.contentEquals(token.signedManifest.rawBytes))
        assertEquals("Onym Discovery", stored.sourceLabel)
        assertEquals("free-tier", stored.offerId)
        assertEquals(now, stored.acceptedAt)
        assertTrue(stored.isActive)
    }

    @Test
    fun consentedManifest_reparsesFromPinnedBytes() = runTest {
        store.accept(reviewed(), acceptedAt = now)

        val manifest = store.load().single().consentedManifest()
        // The snapshot is re-verifiable and renderable offline.
        assertEquals("onym:component:test-courier", manifest?.componentId)
        assertEquals(listOf("free-tier"), manifest?.offers?.map { it.offerId })
        // And still passes full review — same bytes, same signature.
        reviewer.review(manifest!!.rawBytes, now = now)
    }

    // ─── re-consent ───────────────────────────────────────────────

    @Test
    fun accept_reconsentDeactivatesPreviousAndKeepsHistory() = runTest {
        val first = store.accept(reviewed(), acceptedAt = now)
        val second = store.accept(
            reviewed(),
            offerId = "free-tier",
            acceptedAt = now.plusSeconds(3600),
        )

        val records = store.load()
        assertEquals(2, records.size)
        // History kept, artifacts immutable apart from the flag.
        assertEquals(first.copy(isActive = false), records[0])
        assertFalse(records[0].isActive)
        assertEquals(second, records[1])
        assertTrue(records[1].isActive)

        assertEquals(second, store.activeRecord("onym:component:test-courier"))
    }

    @Test
    fun accept_leavesOtherComponentsUntouched() = runTest {
        val courier = store.accept(reviewed(), acceptedAt = now)
        val notary = store.accept(
            reviewed(componentId = "onym:component:test-notary", seat = "notary"),
            acceptedAt = now,
        )

        assertEquals(courier, store.activeRecord("onym:component:test-courier"))
        assertEquals(notary, store.activeRecord("onym:component:test-notary"))
        assertEquals(2, store.load().size)
    }

    @Test
    fun activeRecord_nullWhenNoConsent() = runTest {
        assertNull(store.activeRecord("onym:component:unknown"))
    }

    // ─── resilience ───────────────────────────────────────────────

    @Test
    fun load_corruptBlobFallsBackToEmpty() = runTest {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("consent.records")] = "{corrupt"
        }
        assertEquals(emptyList<PinnedConsentRecord>(), store.load())
    }

    @Test
    fun load_freshStoreInstanceSeesPersistedRecords() = runTest {
        // The JSON blob under "consent.records" is the persisted
        // format — a new store over the same DataStore round-trips
        // every field (base64 manifest bytes, ISO 8601 acceptedAt).
        val record = store.accept(reviewed(), sourceLabel = "Manual", acceptedAt = now)
        val secondStore = DataStorePreferencesPinnedConsentStore(dataStore)
        assertEquals(listOf(record), secondStore.load())
    }
}
