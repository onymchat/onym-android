package app.onym.android.group

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.persistence.StorageEncryption
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
import javax.crypto.spec.SecretKeySpec

/**
 * Round-trip tests for [RoomGroupStore]. Uses
 * `Room.inMemoryDatabaseBuilder` so the on-disk store isn't touched.
 * Encrypted columns go through a real [StorageEncryption] backed by
 * a fresh AES key per test (no Robolectric Keystore dance).
 *
 * Mirrors `SwiftDataGroupStoreTests.swift` from onym-ios PR #25.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomGroupStoreTest {

    private lateinit var db: GroupDatabase
    private lateinit var store: RoomGroupStore
    private lateinit var encryption: StorageEncryption

    @Before
    fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, GroupDatabase::class.java)
            .allowMainThreadQueries()  // tests run sync; no UI thread to protect
            .build()
        encryption = StorageEncryption(
            SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
        )
        store = RoomGroupStore(db.groupDao(), encryption)
    }

    @After
    fun tearDown() { db.close() }

    // ─── round-trip ───────────────────────────────────────────────

    @Test
    fun insertOrUpdate_thenList_roundtripsAllFields() = runTest {
        val group = makeGroup(
            id = "aa".repeat(32),
            name = "Family",
            adminPubkeyHex = "ee".repeat(48),
        )
        assertTrue("first insertOrUpdate must report insert", store.insertOrUpdate(group))

        val listed = store.list()
        assertEquals(1, listed.size)
        val first = listed[0]
        assertEquals(group.id, first.id)
        assertEquals("Family", first.name)
        assertArrayEquals(group.groupSecret, first.groupSecret)
        assertArrayEquals(group.salt, first.salt)
        assertArrayEquals(group.commitment, first.commitment)
        assertEquals(SepTier.SMALL, first.tier)
        assertEquals(SepGroupType.TYRANNY, first.groupType)
        assertEquals("ee".repeat(48), first.adminPubkeyHex)
        assertEquals(group.epoch, first.epoch)
        assertFalse(first.isPublishedOnChain)
        assertEquals(group.members.size, first.members.size)
        assertArrayEquals(
            group.members.first().publicKeyCompressed,
            first.members.first().publicKeyCompressed,
        )
    }

    // ─── encryption-at-rest ───────────────────────────────────────

    @Test
    fun encryptedColumns_doNotContainPlaintext() = runTest {
        val plaintextName = "Family".toByteArray(Charsets.UTF_8)
        val plaintextSecret = ByteArray(32) { 0x33 }
        val group = makeGroup(id = "ab".repeat(32), name = "Family")
        store.insertOrUpdate(group)

        val raw = db.groupDao().findById(group.id)!!
        assertNotEquals(
            "encryptedName must not contain the plaintext name",
            plaintextName.toList(),
            raw.encryptedName.toList(),
        )
        assertNotEquals(
            "encryptedGroupSecret must not contain the plaintext secret",
            plaintextSecret.toList(),
            raw.encryptedGroupSecret.toList(),
        )
        // …but the seam decrypts it back to the plaintext.
        assertEquals("Family", store.list().single().name)
        // Layout sanity: nonce(12) + ciphertext("Family", 6 bytes) + tag(16) = 34B.
        assertEquals(
            StorageEncryption.NONCE_SIZE + plaintextName.size + StorageEncryption.TAG_SIZE,
            raw.encryptedName.size,
        )
    }

    // ─── idempotence ──────────────────────────────────────────────

    @Test
    fun insertOrUpdate_secondCallUpdatesInPlace() = runTest {
        val original = makeGroup(id = "bb".repeat(32), name = "Old name")
        assertTrue(store.insertOrUpdate(original))

        val updated = original.copy(
            epoch = 7uL,
            commitment = ByteArray(32) { 0x99.toByte() },
        )
        assertFalse(
            "second insertOrUpdate on same id is an update",
            store.insertOrUpdate(updated),
        )

        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals(7uL, listed[0].epoch)
        assertArrayEquals(ByteArray(32) { 0x99.toByte() }, listed[0].commitment)
        // createdAt preserved across update — the post-anchor flow
        // mustn't reset the user-visible "this group was created at"
        // timestamp.
        assertEquals(original.createdAtMillis, listed[0].createdAtMillis)
    }

    // ─── markPublished ────────────────────────────────────────────

    @Test
    fun markPublished_flipsFlagAndStoresCommitment() = runTest {
        val group = makeGroup(id = "cc".repeat(32), name = "G")
        store.insertOrUpdate(group)

        val onchainCommitment = ByteArray(32) { 0x42 }
        store.markPublished(group.id, onchainCommitment)

        val listed = store.list()
        assertTrue(listed[0].isPublishedOnChain)
        assertArrayEquals(onchainCommitment, listed[0].commitment)
    }

    @Test
    fun markPublished_unknownIdIsNoOp() = runTest {
        store.markPublished("ff".repeat(32), null)
        assertTrue("no group existed; list must stay empty", store.list().isEmpty())
    }

    @Test
    fun markPublished_nullCommitment_preservesExistingCommitment() = runTest {
        val group = makeGroup(id = "ed".repeat(32), name = "G")
        store.insertOrUpdate(group)
        val originalCommitment = group.commitment

        store.markPublished(group.id, null)

        val listed = store.list().single()
        assertTrue(listed.isPublishedOnChain)
        assertArrayEquals(
            "null commitment update must preserve the existing column",
            originalCommitment,
            listed.commitment,
        )
    }

    // ─── delete ───────────────────────────────────────────────────

    @Test
    fun delete_removesRow() = runTest {
        val group = makeGroup(id = "dd".repeat(32), name = "G")
        store.insertOrUpdate(group)
        assertEquals(1, store.list().size)

        store.delete(group.id)
        assertTrue(store.list().isEmpty())
    }

    // ─── ordering ─────────────────────────────────────────────────

    @Test
    fun list_sortsByCreatedAtDescending() = runTest {
        val older = makeGroup(
            id = "01".repeat(32),
            name = "older",
            createdAtMillis = 1_700_000_000_000L,
        )
        val newer = makeGroup(
            id = "02".repeat(32),
            name = "newer",
            createdAtMillis = 1_700_000_500_000L,
        )
        store.insertOrUpdate(older)
        store.insertOrUpdate(newer)

        assertEquals(listOf(newer.id, older.id), store.list().map { it.id })
    }

    // ─── memberProfiles ───────────────────────────────────────────

    @Test
    fun memberProfiles_roundTripsThroughEncryptedColumn() = runTest {
        val profile = MemberProfile(
            alias = "Alice",
            inboxPublicKey = ByteArray(32) { 0x77 },
            sendingPubkey = ByteArray(32) { 0x66 },
        )
        val group = makeGroup(
            id = "fa".repeat(32),
            name = "G",
            memberProfiles = mapOf("aa".repeat(48) to profile),
        )
        store.insertOrUpdate(group)

        val listed = store.list().single()
        assertEquals(1, listed.memberProfiles.size)
        val restored = listed.memberProfiles["aa".repeat(48)]!!
        assertEquals("Alice", restored.alias)
        assertArrayEquals(profile.inboxPublicKey, restored.inboxPublicKey)
    }

    @Test
    fun memberProfiles_emptyMapPersistsAsNullColumn() = runTest {
        val group = makeGroup(id = "fb".repeat(32), name = "G", memberProfiles = emptyMap())
        store.insertOrUpdate(group)
        val raw = db.groupDao().findById(group.id)!!
        assertNull(raw.encryptedMemberProfilesJson)
        // Decode side maps null → emptyMap.
        assertEquals(emptyMap<String, MemberProfile>(), store.list().single().memberProfiles)
    }

    // ─── helpers ──────────────────────────────────────────────────

    private fun makeGroup(
        id: String,
        name: String,
        adminPubkeyHex: String? = null,
        createdAtMillis: Long = 1_700_000_000_000L,
        memberProfiles: Map<String, MemberProfile> = emptyMap(),
    ): ChatGroup {
        val member = GovernanceMember(
            publicKeyCompressed = ByteArray(48) { 0x11 },
            leafHash = ByteArray(32) { 0x22 },
        )
        return ChatGroup(
            id = id,
            name = name,
            groupSecret = ByteArray(32) { 0x33 },
            createdAtMillis = createdAtMillis,
            members = listOf(member),
            memberProfiles = memberProfiles,
            epoch = 0uL,
            salt = ByteArray(32) { 0x44 },
            commitment = ByteArray(32) { 0x55 },
            tier = SepTier.SMALL,
            groupType = SepGroupType.TYRANNY,
            adminPubkeyHex = adminPubkeyHex,
            isPublishedOnChain = false,
            ownerIdentityId = "test-owner",
        )
    }

    @Suppress("unused")
    private fun assertNullOk(value: Any?) { assertNull(value) }
}
