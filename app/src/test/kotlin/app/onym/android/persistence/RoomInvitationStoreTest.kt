package app.onym.android.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * Exercises the seam contract against the real Room backend.
 * Companion to [app.onym.android.inbox.IncomingInvitationsRepositoryTest]
 * (which runs the same contract against
 * [app.onym.android.support.InMemoryInvitationStore]) — same shape,
 * two backends, both verified.
 *
 * Robolectric provides the [Context] Room needs to open the
 * in-memory DB. No Keystore touched (StorageEncryption is
 * constructed with a known key per test), so Robolectric's lack of
 * Keystore simulation doesn't bite.
 *
 * Mirrors `SwiftDataInvitationStoreTests.swift` from onym-ios PR #16.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])  // pin to a stable Robolectric SDK to avoid rev-dependent flakes
class RoomInvitationStoreTest {

    private lateinit var db: InvitationDatabase
    private lateinit var store: RoomInvitationStore
    private lateinit var encryption: StorageEncryption

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, InvitationDatabase::class.java)
            .allowMainThreadQueries()  // tests run sync; no UI thread to protect
            .build()
        encryption = StorageEncryption(
            SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        )
        store = RoomInvitationStore(db.invitationDao(), encryption)
    }

    @After
    fun tearDown() { db.close() }

    // ─── save + list ──────────────────────────────────────────────

    @Test
    fun save_then_list_returnsRecord() = runTest {
        val rec = sampleRecord(id = "ev1", payload = "hello")
        assertTrue("first save returns true", store.save(rec))

        val all = store.list()
        assertEquals(1, all.size)
        assertEquals("ev1", all[0].id)
        assertArrayEquals("hello".toByteArray(), all[0].payload)
        assertEquals(rec.receivedAt, all[0].receivedAt)
        assertEquals(IncomingInvitationStatus.Pending, all[0].status)
    }

    @Test
    fun save_dedupOnId_returnsFalseOnSecondSave() = runTest {
        val rec = sampleRecord(id = "ev1", payload = "first")
        assertTrue(store.save(rec))
        // Different payload, same id — IGNORE strategy means the
        // second save is a no-op AND the original row is preserved.
        assertFalse(
            "second save with same id must report dedup",
            store.save(rec.copy(payload = "second".toByteArray())),
        )
        assertArrayEquals(
            "original payload must be preserved on dedup",
            "first".toByteArray(),
            store.list().single().payload,
        )
    }

    @Test
    fun list_sortedByReceivedAtDesc() = runTest {
        val now = Instant.parse("2026-05-02T12:00:00Z")
        store.save(sampleRecord(id = "old", payload = "1", receivedAt = now.minusSeconds(60)))
        store.save(sampleRecord(id = "new", payload = "2", receivedAt = now))
        store.save(sampleRecord(id = "mid", payload = "3", receivedAt = now.minusSeconds(30)))
        assertEquals(listOf("new", "mid", "old"), store.list().map { it.id })
    }

    @Test
    fun payload_isEncryptedAtRest() = runTest {
        val plaintext = "sensitive sealed-box bytes".toByteArray(Charsets.UTF_8)
        store.save(sampleRecord(id = "ev1", payload = "sensitive sealed-box bytes"))
        // Read the raw row directly via the DAO (bypasses RoomInvitationStore's decrypt).
        val raw = db.invitationDao().list().single()
        assertNotEquals(
            "encryptedPayload column must not contain the plaintext",
            plaintext.toList(),
            raw.encryptedPayload.toList(),
        )
        // …but the seam decrypts it back to the plaintext.
        assertArrayEquals(plaintext, store.list().single().payload)
        // Layout sanity: nonce(12) + plaintext(26) + tag(16) = 54B.
        assertEquals(
            StorageEncryption.NONCE_SIZE + plaintext.size + StorageEncryption.TAG_SIZE,
            raw.encryptedPayload.size,
        )
    }

    // ─── updateStatus ─────────────────────────────────────────────

    @Test
    fun updateStatus_changesStoredStatus() = runTest {
        store.save(sampleRecord(id = "ev1", payload = "x"))
        store.updateStatus("ev1", IncomingInvitationStatus.Accepted)
        assertEquals(IncomingInvitationStatus.Accepted, store.list().single().status)
    }

    @Test
    fun updateStatus_unknownId_isNoOp() = runTest {
        store.save(sampleRecord(id = "ev1", payload = "x"))
        store.updateStatus("ev-does-not-exist", IncomingInvitationStatus.Accepted)
        // Existing row untouched; no exception.
        assertEquals(IncomingInvitationStatus.Pending, store.list().single().status)
    }

    // ─── delete ───────────────────────────────────────────────────

    @Test
    fun delete_removesRow() = runTest {
        store.save(sampleRecord(id = "ev1", payload = "x"))
        store.save(sampleRecord(id = "ev2", payload = "y"))
        store.delete("ev1")
        assertEquals(listOf("ev2"), store.list().map { it.id })
    }

    @Test
    fun delete_unknownId_isNoOp() = runTest {
        store.save(sampleRecord(id = "ev1", payload = "x"))
        store.delete("ev-does-not-exist")
        assertNull(
            "row must remain after no-op delete",
            store.list().firstOrNull { it.id != "ev1" },
        )
        assertEquals(1, store.list().size)
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun sampleRecord(
        id: String,
        payload: String,
        receivedAt: Instant = Instant.parse("2026-05-02T12:00:00Z"),
        status: IncomingInvitationStatus = IncomingInvitationStatus.Pending,
        ownerIdentityIdString: String = "test-owner",
    ) = IncomingInvitationRecord(
        id = id,
        payload = payload.toByteArray(Charsets.UTF_8),
        receivedAt = receivedAt,
        status = status,
        ownerIdentityIdString = ownerIdentityIdString,
    )
}
