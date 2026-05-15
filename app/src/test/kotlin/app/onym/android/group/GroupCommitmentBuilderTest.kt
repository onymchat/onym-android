package app.onym.android.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.MessageDigest

/**
 * Pure-Kotlin tests for [GroupCommitmentBuilder]. Only the helpers
 * that don't hit the OnymSDK FFI (`generateSalt`, `deriveSalt`) live
 * here — the FFI-backed `computeLeafHash` / `computePublicKey` /
 * `computeMerkleRoot` / `computePoseidonCommitment` need the JNI .so
 * to be loaded, which only the `androidTest` source-set provides.
 * Their coverage lives in `app/src/androidTest/.../group/GroupCommitmentBuilderFfiTest.kt`.
 *
 * Mirrors the `generateSalt` + `deriveSalt` portion of
 * `GroupCommitmentBuilderTests.swift` from onym-ios PR #24.
 */
class GroupCommitmentBuilderTest {

    @Test
    fun generateSalt_returns32RandomBytes() {
        val a = GroupCommitmentBuilder.generateSalt()
        val b = GroupCommitmentBuilder.generateSalt()
        assertEquals(32, a.size)
        assertEquals(32, b.size)
        assertFalse(
            "two consecutive calls must produce different salts",
            a.contentEquals(b),
        )
    }

    @Test
    fun deriveSalt_isDeterministic_andMatchesSha256() {
        val prev = ByteArray(32) { 0xAA.toByte() }
        val memberKey = ByteArray(48) { 0xBB.toByte() }

        val first = GroupCommitmentBuilder.deriveSalt(prev, memberKey)
        val second = GroupCommitmentBuilder.deriveSalt(prev, memberKey)
        assertArrayEquals("deterministic for the same inputs", first, second)

        val md = MessageDigest.getInstance("SHA-256")
        md.update(prev)
        md.update(memberKey)
        assertArrayEquals(md.digest(), first)
    }

    @Test
    fun deriveSalt_differentMemberKeysDiverge() {
        val prev = ByteArray(32) { 0xAA.toByte() }
        val m1 = ByteArray(48) { 0x01 }
        val m2 = ByteArray(48) { 0x02 }
        assertFalse(
            GroupCommitmentBuilder.deriveSalt(prev, m1)
                .contentEquals(GroupCommitmentBuilder.deriveSalt(prev, m2))
        )
    }
}
