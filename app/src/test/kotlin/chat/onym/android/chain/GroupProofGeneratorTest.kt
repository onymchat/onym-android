package chat.onym.android.chain

import chat.onym.android.group.GovernanceMember
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure-Kotlin tests for [OnymGroupProofGenerator] — only the
 * short-circuit branches that don't hit `chat.onym.sdk.Tyranny.proveCreate`.
 * The real proof-generation case (`proveCreate_tyranny_returnsParsedProofAndCommitment`)
 * needs the JNI .so loaded and lives in
 * `app/src/androidTest/.../GroupProofGeneratorFfiTest.kt`.
 *
 * Mirrors the synchronous error-path assertions from
 * `GroupProofGeneratorTests.swift` in onym-ios PR #25.
 */
class GroupProofGeneratorTest {

    @Test
    fun proveCreate_anarchy_outOfRangeIndex_shortCircuitsBeforeJni() = runTest {
        // Anarchy is now wired (no longer NotYetSupported), but the
        // adminIndex bounds check still short-circuits before the JNI
        // call — same as the Tyranny path. Cheapest test that proves
        // we entered the Anarchy branch without needing the SDK loaded.
        val input = GroupProofCreateInput(
            groupType = SepGroupType.ANARCHY,
            tier = SepTier.SMALL,
            members = listOf(
                GovernanceMember(
                    publicKeyCompressed = ByteArray(48) { 0xAA.toByte() },
                    leafHash = ByteArray(32) { 0xBB.toByte() },
                ),
            ),
            adminBlsSecretKey = ByteArray(32) { 0x01 },
            adminIndex = 5,
            groupId = ByteArray(32),
            salt = ByteArray(32),
        )
        val thrown = assertThrows(GroupProofGeneratorError.AdminIndexOutOfRange::class.java) {
            kotlinx.coroutines.runBlocking { OnymGroupProofGenerator().proveCreate(input) }
        }
        assertEquals(5, thrown.index)
        assertEquals(1, thrown.count)
    }

    @Test
    fun proveCreate_democracy_throwsNotYetSupported() = runTest {
        val input = stubInput(SepGroupType.DEMOCRACY)
        val thrown = assertThrows(GroupProofGeneratorError.NotYetSupported::class.java) {
            kotlinx.coroutines.runBlocking { OnymGroupProofGenerator().proveCreate(input) }
        }
        assertEquals(SepGroupType.DEMOCRACY, thrown.type)
    }

    @Test
    fun proveCreate_oneOnOne_withoutSecondaryKey_throws() = runTest {
        // OneOnOne is now wired (no longer NotYetSupported), but the
        // secondaryBlsSecretKey is mandatory and short-circuits before
        // any FFI call when missing.
        val input = stubInput(SepGroupType.ONE_ON_ONE)
        val thrown = assertThrows(GroupProofGeneratorError.SecondaryBlsSecretKeyRequired::class.java) {
            kotlinx.coroutines.runBlocking { OnymGroupProofGenerator().proveCreate(input) }
        }
        // Sanity: object-singleton, not a class with state to inspect.
        assertEquals(GroupProofGeneratorError.SecondaryBlsSecretKeyRequired, thrown)
    }

    @Test
    fun proveCreate_adminIndexOutOfRange_shortCircuitsBeforeJni() = runTest {
        // Single-member roster + adminIndex=5 — must throw before any
        // FFI call (the SDK isn't loaded in unit tests).
        val input = GroupProofCreateInput(
            groupType = SepGroupType.TYRANNY,
            tier = SepTier.SMALL,
            members = listOf(
                GovernanceMember(
                    publicKeyCompressed = ByteArray(48) { 0xAA.toByte() },
                    leafHash = ByteArray(32) { 0xBB.toByte() },
                ),
            ),
            adminBlsSecretKey = ByteArray(32) { 0x01 },
            adminIndex = 5,
            groupId = ByteArray(32),
            salt = ByteArray(32),
        )
        val thrown = assertThrows(GroupProofGeneratorError.AdminIndexOutOfRange::class.java) {
            kotlinx.coroutines.runBlocking { OnymGroupProofGenerator().proveCreate(input) }
        }
        assertEquals(5, thrown.index)
        assertEquals(1, thrown.count)
    }

    @Test
    fun proveCreate_negativeAdminIndex_shortCircuitsBeforeJni() = runTest {
        val input = GroupProofCreateInput(
            groupType = SepGroupType.TYRANNY,
            tier = SepTier.SMALL,
            members = listOf(
                GovernanceMember(
                    publicKeyCompressed = ByteArray(48),
                    leafHash = ByteArray(32),
                ),
            ),
            adminBlsSecretKey = ByteArray(32),
            adminIndex = -1,
            groupId = ByteArray(32),
            salt = ByteArray(32),
        )
        val thrown = assertThrows(GroupProofGeneratorError.AdminIndexOutOfRange::class.java) {
            kotlinx.coroutines.runBlocking { OnymGroupProofGenerator().proveCreate(input) }
        }
        assertEquals(-1, thrown.index)
    }

    private fun stubInput(groupType: SepGroupType) = GroupProofCreateInput(
        groupType = groupType,
        tier = SepTier.SMALL,
        members = emptyList(),
        adminBlsSecretKey = ByteArray(32) { 0x01 },
        adminIndex = 0,
        groupId = ByteArray(32),
        salt = ByteArray(32),
    )
}
