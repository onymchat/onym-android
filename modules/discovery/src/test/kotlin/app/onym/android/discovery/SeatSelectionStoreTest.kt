@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.discovery

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [DataStorePreferencesSeatSelectionStore]: append-only history +
 * "active = last appended per seatType", on a real JVM temp-file
 * DataStore (same pattern as :chain's `RelayerSelectionStoreTest`).
 */
class SeatSelectionStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var datastoreScopeJob: Job
    private lateinit var datastoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStorePreferencesSeatSelectionStore

    private fun selection(
        seatType: String,
        componentId: String,
        selectedAt: String,
        offerId: String? = null,
    ) = ModuleSelection(
        seatType = seatType,
        componentId = componentId,
        providerId = "onym:component:onym-discovery",
        manifestHash = "sha256:" + "cc".repeat(32),
        offerId = offerId,
        selectedAt = selectedAt,
    )

    @Before
    fun setUp() {
        datastoreScopeJob = SupervisorJob()
        datastoreScope = CoroutineScope(UnconfinedTestDispatcher() + datastoreScopeJob)
        val file = tempFolder.newFile("seat-${System.nanoTime()}.preferences_pb")
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(scope = datastoreScope) { file }
        store = DataStorePreferencesSeatSelectionStore(dataStore)
    }

    @After
    fun tearDown() { datastoreScopeJob.cancel() }

    @Test
    fun loadSelections_returnsEmptyOnFreshStore() = runTest {
        assertTrue(store.loadSelections().isEmpty())
        assertTrue(store.activeSelections().isEmpty())
    }

    @Test
    fun appendSelection_roundTripsWithAllFields() = runTest {
        val picked = selection(
            seatType = "notary",
            componentId = "onym:component:onym-relayer",
            selectedAt = "2026-08-14T10:00:00Z",
            offerId = "notary-free-v1",
        )
        store.appendSelection(picked)
        assertEquals(listOf(picked), store.loadSelections())
    }

    @Test
    fun history_isAppendOnly_oldestFirst() = runTest {
        val first = selection("notary", "onym:component:notary-a", "2026-08-14T10:00:00Z")
        val second = selection("notary", "onym:component:notary-b", "2026-08-14T11:00:00Z")
        store.appendSelection(first)
        store.appendSelection(second)
        // Switching modules appends — the earlier selection stays in
        // the history as the audit trail.
        assertEquals(listOf(first, second), store.loadSelections())
    }

    @Test
    fun activeSelections_isLastAppendedPerSeatType() = runTest {
        val notaryOld = selection("notary", "onym:component:notary-a", "2026-08-14T10:00:00Z")
        val courier = selection("transport.message", "onym:component:onym-courier", "2026-08-14T10:30:00Z")
        val notaryNew = selection("notary", "onym:component:notary-b", "2026-08-14T11:00:00Z")
        store.appendSelection(notaryOld)
        store.appendSelection(courier)
        store.appendSelection(notaryNew)

        val active = store.activeSelections()
        assertEquals(2, active.size)
        assertEquals(notaryNew, active["notary"])
        assertEquals(courier, active["transport.message"])
    }

    @Test
    fun key_livesUnderTheDiscoveryPrefix() = runTest {
        store.appendSelection(selection("notary", "onym:component:notary-a", "2026-08-14T10:00:00Z"))
        val prefs = dataStore.data.first()
        assertNotNull(prefs[stringPreferencesKey("discovery.seat_selections")])
    }

    @Test
    fun corruptBlob_readsAsEmptyHistory() = runTest {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("discovery.seat_selections")] = "not json {{"
        }
        assertTrue(store.loadSelections().isEmpty())
        // Appending over a corrupt blob starts a fresh history.
        val picked = selection("notary", "onym:component:notary-a", "2026-08-14T10:00:00Z")
        store.appendSelection(picked)
        assertEquals(listOf(picked), store.loadSelections())
    }

    @Test
    fun clear_wipesTheHistory() = runTest {
        store.appendSelection(selection("notary", "onym:component:notary-a", "2026-08-14T10:00:00Z"))
        store.clear()
        assertTrue(store.loadSelections().isEmpty())
    }
}
