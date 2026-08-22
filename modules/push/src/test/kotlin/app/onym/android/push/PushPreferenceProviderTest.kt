@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The DataStore-backed provider (the `PreferenceDataStoreFactory` +
 * `TemporaryFolder` pattern from RelayerSelectionStoreTest) and the
 * in-memory double, held to the same contract: OFF by default, the
 * registration record moves as one unit, and the pending debt slot
 * survives independently of it.
 */
class PushPreferenceProviderTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var datastoreScopeJob: Job
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var provider: DataStorePushPreferenceProvider

    @Before
    fun setUp() {
        datastoreScopeJob = SupervisorJob()
        val scope = CoroutineScope(UnconfinedTestDispatcher() + datastoreScopeJob)
        val file = tempFolder.newFile("push-${System.nanoTime()}.preferences_pb")
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        provider = DataStorePushPreferenceProvider(dataStore)
    }

    @After
    fun tearDown() {
        datastoreScopeJob.cancel()
    }

    @Test
    fun `fresh store answers off and empty`() = runTest {
        assertFalse(provider.enabled())
        assertFalse(provider.enabledFlow.first())
        assertFalse(provider.registeredFlow.first())
        assertNull(provider.lastRegistrationFingerprint())
        assertNull(provider.lastRegisteredAt())
        assertNull(provider.registrationExpiresAt())
        assertNull(provider.lastRegisteredToken())
        assertNull(provider.pendingUnregisterToken())
    }

    @Test
    fun `the registration record round-trips as one unit`() = runTest {
        provider.setEnabled(true)
        provider.recordRegistration(
            fingerprint = "aa55",
            registeredAt = Instant.parse("2026-08-22T12:00:00Z"),
            expiresAt = Instant.parse("2026-09-21T12:00:00Z"),
            token = "token-a",
        )

        assertTrue(provider.enabled())
        assertTrue(provider.registeredFlow.first())
        assertEquals("aa55", provider.lastRegistrationFingerprint())
        assertEquals(Instant.parse("2026-08-22T12:00:00Z"), provider.lastRegisteredAt())
        assertEquals(Instant.parse("2026-09-21T12:00:00Z"), provider.registrationExpiresAt())
        assertEquals("token-a", provider.lastRegisteredToken())

        provider.clearRegistration()
        assertNull(provider.lastRegistrationFingerprint())
        assertNull(provider.lastRegisteredAt())
        assertNull(provider.registrationExpiresAt())
        assertNull(provider.lastRegisteredToken())
        assertFalse(provider.registeredFlow.first())
        // Clearing the record does not flip the preference.
        assertTrue(provider.enabled())
    }

    @Test
    fun `a missing expiry stores as absent not epoch`() = runTest {
        provider.recordRegistration(
            fingerprint = "aa55",
            registeredAt = Instant.parse("2026-08-22T12:00:00Z"),
            expiresAt = null,
            token = "token-a",
        )
        assertNull(provider.registrationExpiresAt())
        // And a later record with an expiry replaces it...
        provider.recordRegistration(
            fingerprint = "aa66",
            registeredAt = Instant.parse("2026-08-23T12:00:00Z"),
            expiresAt = Instant.parse("2026-09-22T12:00:00Z"),
            token = "token-a",
        )
        assertEquals(Instant.parse("2026-09-22T12:00:00Z"), provider.registrationExpiresAt())
        // ...and one WITHOUT an expiry removes the stale one instead
        // of letting it govern a fresh registration.
        provider.recordRegistration(
            fingerprint = "aa77",
            registeredAt = Instant.parse("2026-08-24T12:00:00Z"),
            expiresAt = null,
            token = "token-a",
        )
        assertNull(provider.registrationExpiresAt())
    }

    @Test
    fun `the pending debt slot is independent of the record`() = runTest {
        provider.setPendingUnregisterToken("token-old")
        provider.clearRegistration()
        assertEquals("token-old", provider.pendingUnregisterToken())
        provider.setPendingUnregisterToken(null)
        assertNull(provider.pendingUnregisterToken())
    }

    // ─── the in-memory double, held to the same contract ──────────

    @Test
    fun `the double defaults off and mirrors the contract`() = runTest {
        val double = StaticPushPreferenceProvider()
        assertFalse(double.enabled())
        assertFalse(double.enabledFlow.first())
        assertFalse(double.registeredFlow.first())

        double.setEnabled(true)
        assertTrue(double.enabled())
        assertTrue(double.enabledFlow.first())

        double.recordRegistration(
            fingerprint = "aa55",
            registeredAt = Instant.parse("2026-08-22T12:00:00Z"),
            expiresAt = null,
            token = "token-a",
        )
        assertTrue(double.registeredFlow.first())
        assertEquals("token-a", double.lastRegisteredToken())

        double.setPendingUnregisterToken("token-a")
        double.clearRegistration()
        assertFalse(double.registeredFlow.first())
        assertEquals("token-a", double.pendingUnregisterToken())
        assertTrue(double.enabled())
    }
}
