package chat.onym.android.chain

import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.onym.android.group.GovernanceMember
import chat.onym.sdk.Common
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real proof generation against the OnymSDK Tyranny circuit. Tier
 * is `SMALL` (depth 5) — fastest to prove (~3.5s on a Pixel 6) but
 * still goes through the full circuit so the byte-size assertions
 * here would catch any drift in `Tyranny.CreateProof.publicInputs`
 * layout.
 *
 * Lives in `androidTest/` because the JNI .so for `chat.onym.sdk.*`
 * is only loaded by the on-device runtime; the unit-test runner
 * doesn't see it.
 *
 * Mirrors `test_proveCreate_tyranny_returnsParsedProofAndCommitment`
 * from onym-ios PR #25.
 */
@RunWith(AndroidJUnit4::class)
class GroupProofGeneratorFfiTest {

    @Test
    fun proveCreate_tyranny_returnsParsedProofAndCommitment() = runBlocking {
        // Three distinct member secrets (1, 2, 3 as 32-byte BE Frs).
        val secrets = (1uL..3uL).map(::frFromU64)
        val members = secrets.map { sk ->
            GovernanceMember(
                publicKeyCompressed = Common.publicKey(sk),
                leafHash = Common.leafHash(sk),
            )
        }
        val sorted = members.sortedWith { a, b -> compareLex(a.publicKeyCompressed, b.publicKeyCompressed) }
        val adminSecret = secrets[0]
        val adminLeaf = Common.leafHash(adminSecret)
        val adminIndex = sorted.indexOfFirst { it.leafHash.contentEquals(adminLeaf) }

        val input = GroupProofCreateInput(
            groupType = SepGroupType.TYRANNY,
            tier = SepTier.SMALL,
            members = sorted,
            adminBlsSecretKey = adminSecret,
            adminIndex = adminIndex,
            groupId = frFromU64(0x7777uL),
            salt = ByteArray(32) { 0xEE.toByte() },
        )

        val result = OnymGroupProofGenerator().proveCreate(input)
        assertEquals(
            "Common.parsePlonkProof trims the 1601-byte raw output to 1568 bytes",
            1568,
            result.proof.size,
        )
        assertEquals(32, result.publicInputs.commitment.size)
        assertEquals("create-group is always epoch 0", 0uL, result.publicInputs.epoch)
    }

    /** 32-byte big-endian encoding of a small u64 — copied from the
     *  iOS test helpers (can't import OnymSDK test fixtures here). */
    private fun frFromU64(value: ULong): ByteArray {
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[31 - i] = ((value shr (i * 8)) and 0xFFuL).toByte()
        }
        return out
    }

    private fun compareLex(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size - b.size
    }
}
