@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [DataStorePreferencesOnboardingStore]: completion-flag round-trips
 * on a real JVM temp-file DataStore (same pattern as
 * `SeatSelectionStoreTest` / :chain's `RelayerSelectionStoreTest`).
 */
class OnboardingStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var datastoreScopeJob: Job
    private lateinit var datastoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStorePreferencesOnboardingStore

    @Before
    fun setUp() {
        datastoreScopeJob = SupervisorJob()
        datastoreScope = CoroutineScope(UnconfinedTestDispatcher() + datastoreScopeJob)
        val file = tempFolder.newFile("onboarding-${System.nanoTime()}.preferences_pb")
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(scope = datastoreScope) { file }
        store = DataStorePreferencesOnboardingStore(dataStore)
    }

    @After
    fun tearDown() { datastoreScopeJob.cancel() }

    @Test
    fun freshStore_hasNotCompleted() = runTest {
        assertFalse(store.hasCompleted())
    }

    @Test
    fun markCompleted_roundTrips() = runTest {
        store.markCompleted()
        assertTrue(store.hasCompleted())
    }

    @Test
    fun markCompleted_isIdempotent() = runTest {
        store.markCompleted()
        store.markCompleted()
        assertTrue(store.hasCompleted())
    }

    @Test
    fun reset_clearsTheFlag() = runTest {
        store.markCompleted()
        store.reset()
        assertFalse(store.hasCompleted())
    }

    @Test
    fun reset_onAFreshStore_isIdempotent() = runTest {
        store.reset()
        assertFalse(store.hasCompleted())
    }

    @Test
    fun flag_livesUnderTheDocumentedKey() = runTest {
        // The app's test reset path and PR 3's wiring depend on this
        // exact key — pin it.
        assertEquals(
            booleanPreferencesKey("onboarding.completed"),
            DataStorePreferencesOnboardingStore.KEY_COMPLETED,
        )
        store.markCompleted()
        val prefs = dataStore.data.first()
        assertEquals(true, prefs[booleanPreferencesKey("onboarding.completed")])
    }

    @Test
    fun flag_persistsAcrossStoreInstances_overTheSameDataStore() = runTest {
        store.markCompleted()
        val second = DataStorePreferencesOnboardingStore(dataStore)
        assertTrue(second.hasCompleted())
    }

    // ── Restart request (Settings → Restart Onboarding) ───────────

    @Test
    fun freshStore_hasNoRestartRequest() = runTest {
        assertFalse(store.isRestartRequested())
    }

    @Test
    fun requestRestart_setsTheBit_andClearsTheCompletedFlag() = runTest {
        store.markCompleted()
        store.requestRestart()
        assertTrue(store.isRestartRequested())
        assertFalse(store.hasCompleted())
    }

    @Test
    fun markCompleted_clearsTheRestartRequest() = runTest {
        store.requestRestart()
        store.markCompleted()
        assertFalse(store.isRestartRequested())
        assertTrue(store.hasCompleted())
    }

    @Test
    fun reset_leavesTheRestartRequestAlone() = runTest {
        // reset() is the completion-flag wipe (test harness path) —
        // an explicit restart request is separate state.
        store.requestRestart()
        store.reset()
        assertTrue(store.isRestartRequested())
    }

    @Test
    fun restartRequest_persistsAcrossStoreInstances() = runTest {
        // Mid-walk process death: the next launch's store instance
        // still sees the request.
        store.requestRestart()
        val second = DataStorePreferencesOnboardingStore(dataStore)
        assertTrue(second.isRestartRequested())
        assertFalse(second.hasCompleted())
    }

    @Test
    fun restartBit_livesUnderTheDocumentedKey() = runTest {
        store.requestRestart()
        val prefs = dataStore.data.first()
        assertEquals(true, prefs[booleanPreferencesKey("onboarding.restartRequested")])
    }
}
