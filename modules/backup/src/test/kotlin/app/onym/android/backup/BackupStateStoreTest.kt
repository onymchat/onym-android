@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A corrupt or undecodable state blob must fail loud
 * ([BackupError.LocalFailure] with [LocalFailureReason.StateUnreadable]),
 * never read as "never enrolled" — an undecodable blob silently
 * treated as first-run drops the pinned terms, any pending
 * operation/payment record, and the last erasure receipt, none of
 * which is recoverable. Genuinely absent state (the key was never
 * written) still returns `null`. Same `PreferenceDataStoreFactory` +
 * `TemporaryFolder` pattern as `PinnedConsentStoreTest` in
 * `:foundation`.
 */
class BackupStateStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var datastoreScopeJob: Job
    private lateinit var datastoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStorePreferencesBackupStateStore

    private val componentId = "onym:component:op-1"

    @Before
    fun setUp() {
        datastoreScopeJob = SupervisorJob()
        datastoreScope = CoroutineScope(UnconfinedTestDispatcher() + datastoreScopeJob)
        val file = tempFolder.newFile("backup-state-${System.nanoTime()}.preferences_pb")
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(scope = datastoreScope) { file }
        store = DataStorePreferencesBackupStateStore(dataStore, componentId)
    }

    @After
    fun tearDown() {
        datastoreScopeJob.cancel()
    }

    @Test
    fun load_returns_null_when_genuinely_absent() = runTest {
        assertNull(store.load())
    }

    @Test
    fun load_throws_state_unreadable_on_an_undecodable_blob() = runTest {
        val key = stringPreferencesKey("backup_state.$componentId")
        dataStore.edit { it[key] = "{ not json at all" }

        val failure = try {
            store.load()
            null
        } catch (e: BackupError.LocalFailure) {
            e
        }

        assertEquals(LocalFailureReason.StateUnreadable, failure?.reason)
    }

    @Test
    fun load_throws_state_unreadable_on_valid_json_of_the_wrong_shape() = runTest {
        val key = stringPreferencesKey("backup_state.$componentId")
        // Well-formed JSON, but not a `PersistedBackupState` — the
        // decode failure this guards is a schema mismatch, not just
        // syntactically broken JSON.
        dataStore.edit { it[key] = """{"unexpectedField": 42}""" }

        val failure = try {
            store.load()
            null
        } catch (e: BackupError.LocalFailure) {
            e
        }

        assertEquals(LocalFailureReason.StateUnreadable, failure?.reason)
    }

    @Test
    fun round_trips_a_valid_state() = runTest {
        val state = PersistedBackupState(componentId = componentId, acceptedTermsId = "sha256:" + "a".repeat(64))
        store.save(state)
        assertEquals(state, store.load())
    }
}
