package app.onym.android.group

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.foundation.StorageEncryption
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * Join requests used to live in memory only — process death dropped
 * them and the joiner had to re-share. Now that a request renders as a
 * row inside the founder's chat thread, it has to survive a relaunch
 * like any other message would.
 *
 * Mirrors `SwiftDataIntroRequestStoreTests.swift` in onym-ios.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomIntroRequestStoreTest {

    private lateinit var db: GroupDatabase
    private lateinit var encryption: StorageEncryption
    private var nowMillis: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, GroupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        encryption = StorageEncryption(
            SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        )
    }

    @After
    fun tearDown() { db.close() }

    private fun store() = RoomIntroRequestStore(
        dao = db.introRequestDao(),
        encryption = encryption,
        now = { nowMillis },
    )

    private fun request(
        id: String,
        receivedAtMillis: Long = nowMillis,
    ) = IntroRequest(
        id = id,
        targetIntroPublicKey = ByteArray(32) { 0xAB.toByte() },
        payload = ByteArray(64) { 0xCD.toByte() },
        receivedAt = Instant.ofEpochMilli(receivedAtMillis),
    )

    @Test
    fun recordThenRead_roundTrips() = runTest {
        val store = store()
        assertTrue(store.record(request("evt-1")))

        val current = store.requests.value
        assertEquals(1, current.size)
        assertEquals("evt-1", current.first().id)
        assertArrayEquals(ByteArray(32) { 0xAB.toByte() }, current.first().targetIntroPublicKey)
    }

    /** Every relay reconnect replays the inbox, so a repeat of the same
     *  Nostr event id is the common path, not an edge case. */
    @Test
    fun record_dedupesOnEventId() = runTest {
        val store = store()
        assertTrue(store.record(request("evt-dup")))
        assertFalse(store.record(request("evt-dup")))
        assertEquals(1, store.requests.value.size)
    }

    @Test
    fun consume_removesTheRequest() = runTest {
        val store = store()
        store.record(request("evt-a"))
        store.record(request("evt-b"))

        store.consume("evt-a")

        assertEquals(listOf("evt-b"), store.requests.value.map { it.id })
    }

    /** The point of persisting: a new store over the same database sees
     *  what the previous process wrote. */
    @Test
    fun requestsSurviveAStoreReopen() = runTest {
        store().record(request("evt-restored"))

        val reopened = store()
        reopened.start()

        assertEquals(listOf("evt-restored"), reopened.requests.value.map { it.id })
    }

    @Test
    fun requestsPastRetention_areSwept() = runTest {
        val store = store()
        store.record(request("evt-stale"))

        // Advance past the window rather than back-dating the row: the
        // clock this measures is the store's own, stamped at `record`.
        nowMillis += RoomIntroRequestStore.RETENTION_MILLIS + 60_000
        store.start()

        assertTrue(store.requests.value.isEmpty())
    }

    @Test
    fun requestJustInsideRetention_isKept() = runTest {
        val store = store()
        store.record(request("evt-edge"))

        nowMillis += RoomIntroRequestStore.RETENTION_MILLIS - 60_000
        store.start()

        assertEquals(listOf("evt-edge"), store.requests.value.map { it.id })
    }

    /**
     * Retention must not read `receivedAt`.
     *
     * That timestamp comes from the Nostr event's `ms` tag, which the
     * *joiner* writes — so it is a claim, not an observation. Pruning on
     * it would mean a founder offline longer than the window had every
     * replayed request swept on the first read, never seeing a request
     * the joiner was still waiting on.
     */
    @Test
    fun anOldSenderTimestampDoesNotAgeOutAFreshlySeenRequest() = runTest {
        val store = store()
        val ancient = nowMillis - RoomIntroRequestStore.RETENTION_MILLIS * 10
        store.record(request("evt-backdated", receivedAtMillis = ancient))

        store.start()

        assertEquals(
            listOf("evt-backdated"),
            store.requests.value.map { it.id },
        )
    }

    /** The other direction: a joiner stamping `ms` far in the future
     *  must not mint a row that outlives every sweep. */
    @Test
    fun aFutureSenderTimestampStillExpires() = runTest {
        val store = store()
        val farFuture = nowMillis + RoomIntroRequestStore.RETENTION_MILLIS * 10
        store.record(request("evt-future", receivedAtMillis = farFuture))

        nowMillis += RoomIntroRequestStore.RETENTION_MILLIS + 60_000
        store.start()

        assertTrue(store.requests.value.isEmpty())
    }
}
