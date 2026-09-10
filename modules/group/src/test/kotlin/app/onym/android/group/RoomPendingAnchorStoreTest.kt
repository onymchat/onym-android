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
import javax.crypto.spec.SecretKeySpec

/**
 * Round-trip tests for [RoomPendingAnchorStore] — the store whose only
 * job is to still have a salt after the process that drew it is gone.
 *
 * Uses `Room.inMemoryDatabaseBuilder` so the on-disk store isn't
 * touched, with a real [StorageEncryption] over a fresh AES key.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomPendingAnchorStoreTest {

    private lateinit var db: GroupDatabase
    private lateinit var store: RoomPendingAnchorStore
    private lateinit var encryption: StorageEncryption

    private val groupId = ByteArray(32) { 0xAB.toByte() }
    private val owner = "owner-1"

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, GroupDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        encryption = StorageEncryption(
            SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES"),
        )
        store = RoomPendingAnchorStore(db.pendingAnchorDao(), encryption)
    }

    @After
    fun tearDown() { db.close() }

    private fun anchor(
        epochOld: ULong = 3uL,
        joiner: Int = 0xC1,
        salt: Int = 0x5C,
        atMillis: Long = 1_700_000_000_000L,
        group: ByteArray = groupId,
        ownerId: String = owner,
    ) = PendingAnchor(
        groupId = group,
        ownerIdentityId = ownerId,
        epochOld = epochOld,
        joinerPublicKey = ByteArray(48) { joiner.toByte() },
        joinerLeafHash = ByteArray(32) { (joiner + 1).toByte() },
        saltNew = ByteArray(32) { salt.toByte() },
        createdAtMillis = atMillis,
    )

    @Test
    fun aRecordedAnchorComesBackWhole() = runTest {
        val written = anchor()
        store.record(written)

        val read = store.pending(groupId, owner)

        assertEquals(1, read.size)
        assertEquals(written, read.first())
    }

    /**
     * Each retry of the same join draws its own salt, and any of them
     * could be the one that landed. Collapsing them on
     * `(group, joiner, epoch)` would keep only the last — reliably the
     * one that did not.
     */
    @Test
    fun retriesOfTheSameJoinAreKeptSideBySide() = runTest {
        store.record(anchor(salt = 0x11, atMillis = 1_000))
        store.record(anchor(salt = 0x22, atMillis = 2_000))

        val read = store.pending(groupId, owner)

        assertEquals(2, read.size)
        // Newest first: the likeliest candidate is checked against the
        // chain before the older one.
        assertArrayEquals(ByteArray(32) { 0x22 }, read[0].saltNew)
        assertArrayEquals(ByteArray(32) { 0x11 }, read[1].saltNew)
    }

    /** Recording from a later epoch sweeps what the group has already
     *  moved past — a group that keeps failing must not accumulate rows
     *  that can no longer explain anything. */
    @Test
    fun recordingFromALaterEpochSweepsTheEarlierOnes() = runTest {
        store.record(anchor(epochOld = 3uL, salt = 0x11))
        store.record(anchor(epochOld = 4uL, salt = 0x22))

        val read = store.pending(groupId, owner)

        assertEquals(1, read.size)
        assertEquals(4uL, read.first().epochOld)
    }

    /** …but never the epoch it is recording from. Two approvals from
     *  the same state are both live candidates. */
    @Test
    fun recordingFromTheSameEpochKeepsTheOthers() = runTest {
        store.record(anchor(epochOld = 3uL, joiner = 0xC1))
        store.record(anchor(epochOld = 3uL, joiner = 0xD1))

        assertEquals(2, store.pending(groupId, owner).size)
    }

    /**
     * The sweep boundary is inclusive of the epoch named and nothing
     * past it. That is what lets the caller settle an advance by naming
     * the epoch just left, while an attempt made *from* the new state —
     * which may still be in flight — survives.
     */
    @Test
    fun clearingIsInclusiveOfTheEpochNamedAndStopsThere() = runTest {
        store.record(anchor(epochOld = 5uL, joiner = 0xC1))
        store.record(anchor(epochOld = 5uL, joiner = 0xD1))

        store.clear(groupId, owner, throughEpoch = 4uL)
        assertEquals("epoch 5 is past the boundary", 2, store.pending(groupId, owner).size)

        store.clear(groupId, owner, throughEpoch = 5uL)
        assertTrue(store.pending(groupId, owner).isEmpty())
    }

    /** Rows are scoped to one identity's copy of the group. Two local
     *  identities can each hold a row for the same on-chain group, and
     *  one settling must not sweep the other's evidence. */
    @Test
    fun recordsAreScopedToTheOwningIdentity() = runTest {
        store.record(anchor(ownerId = "owner-1"))
        store.record(anchor(ownerId = "owner-2"))

        store.clear(groupId, "owner-1", throughEpoch = 99uL)

        assertTrue(store.pending(groupId, "owner-1").isEmpty())
        assertEquals(1, store.pending(groupId, "owner-2").size)
    }

    /** Same, for two different groups held by one identity. */
    @Test
    fun recordsAreScopedToTheGroup() = runTest {
        val other = ByteArray(32) { 0xCD.toByte() }
        store.record(anchor(group = groupId))
        store.record(anchor(group = other))

        store.clear(groupId, owner, throughEpoch = 99uL)

        assertTrue(store.pending(groupId, owner).isEmpty())
        assertEquals(1, store.pending(other, owner).size)
    }

    /**
     * The salt is the blinding factor for a commitment that is already
     * public. In the clear on disk, it would let anyone holding the
     * database confirm a guessed roster against the chain — the one
     * thing a random salt exists to prevent.
     */
    @Test
    fun theSaltIsNotOnDiskInTheClear() = runTest {
        val salt = ByteArray(32) { 0x5C }
        store.record(anchor(salt = 0x5C))

        val row = db.pendingAnchorDao().pending(
            groupIdHex = groupId.joinToString("") { "%02x".format(it) },
            ownerIdentityId = owner,
        ).single()

        assertFalse(row.encryptedSaltNew.contentEquals(salt))
        assertArrayEquals(salt, encryption.decrypt(row.encryptedSaltNew))
    }
}
