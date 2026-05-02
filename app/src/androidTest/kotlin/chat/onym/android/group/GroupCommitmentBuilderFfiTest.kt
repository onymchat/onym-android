package chat.onym.android.group

import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.onym.android.chain.SepTier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FFI-backed tests for [GroupCommitmentBuilder]. Lives in
 * `androidTest/` because each method here calls into
 * `chat.onym.sdk.Common`, which loads its native `.so` from the SDK
 * AAR's `jni/<abi>/` — only available on the device runtime.
 *
 * The pure-Kotlin helpers (salt generation + derivation) are covered
 * by the unit-test twin under `app/src/test/.../group/GroupCommitmentBuilderTest.kt`.
 *
 * Mirrors the FFI portion of `GroupCommitmentBuilderTests.swift` from
 * onym-ios PR #24.
 */
@RunWith(AndroidJUnit4::class)
class GroupCommitmentBuilderFfiTest {

    @Test
    fun computePublicKeyAndLeafHash_returnExpectedSizes() {
        val secret = ByteArray(32) { 0x42 }
        val pub = GroupCommitmentBuilder.computePublicKey(secret)
        val leaf = GroupCommitmentBuilder.computeLeafHash(secret)
        assertEquals("compressed BLS12-381 G1 is 48 bytes", 48, pub.size)
        assertEquals("Poseidon Fr leaf is 32 bytes", 32, leaf.size)
    }

    @Test
    fun computeMerkleRoot_isOrderInvariant() {
        // Lex-sort behaviour: building the same roster in two orders
        // must produce the same Poseidon root. Anchors the cross-
        // platform sort contract — both peers MUST sort identically.
        val a = memberFromSecret(ByteArray(32) { 0x10 })
        val b = memberFromSecret(ByteArray(32) { 0x20 })
        val c = memberFromSecret(ByteArray(32) { 0x30 })

        val root1 = GroupCommitmentBuilder.computeMerkleRoot(listOf(a, b, c), SepTier.SMALL)
        val root2 = GroupCommitmentBuilder.computeMerkleRoot(listOf(c, a, b), SepTier.SMALL)
        assertArrayEquals(root1, root2)
        assertEquals(32, root1.size)
    }

    @Test
    fun computePoseidonCommitment_changesWithEpoch() {
        val member = memberFromSecret(ByteArray(32) { 0x42 })
        val root = GroupCommitmentBuilder.computeMerkleRoot(listOf(member), SepTier.SMALL)
        val salt = ByteArray(32) { 0x77 }

        val c0 = GroupCommitmentBuilder.computePoseidonCommitment(root, 0uL, salt)
        val c1 = GroupCommitmentBuilder.computePoseidonCommitment(root, 1uL, salt)
        assertEquals(32, c0.size)
        assertFalse("epoch participates in the commitment", c0.contentEquals(c1))
    }

    private fun memberFromSecret(secret: ByteArray): GovernanceMember = GovernanceMember(
        publicKeyCompressed = GroupCommitmentBuilder.computePublicKey(secret),
        leafHash = GroupCommitmentBuilder.computeLeafHash(secret),
    )
}
