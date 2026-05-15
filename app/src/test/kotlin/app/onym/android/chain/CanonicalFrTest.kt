package app.onym.android.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Regression coverage for the bls12-381 canonical-Fr predicate +
 * rejection sampler that [app.onym.android.group.CreateGroupInteractor]
 * uses to mint `groupId`. The contract (`sep-tyranny/src/lib.rs:299`)
 * rejects non-canonical `group_id` with
 * `Error::InvalidCommitmentEncoding` (#15); these tests pin the
 * client-side guarantee that we never hand the contract a value `>= r`.
 *
 * Mirrors `CanonicalFrTests.swift` from onym-ios PR #36.
 *
 * `r = 0x73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001`
 */
class CanonicalFrTest {

    // ─── isCanonical boundary cases ───────────────────────────────

    @Test
    fun isCanonical_zero_isCanonical() {
        assertTrue(CanonicalFr.isCanonical(ByteArray(32)))
    }

    @Test
    fun isCanonical_one_isCanonical() {
        val bytes = ByteArray(32)
        bytes[31] = 1
        assertTrue(CanonicalFr.isCanonical(bytes))
    }

    @Test
    fun isCanonical_rMinusOne_isCanonical() {
        val rMinusOne = byteArrayOf(
            0x73.toByte(), 0xed.toByte(), 0xa7.toByte(), 0x53.toByte(),
            0x29.toByte(), 0x9d.toByte(), 0x7d.toByte(), 0x48.toByte(),
            0x33.toByte(), 0x39.toByte(), 0xd8.toByte(), 0x08.toByte(),
            0x09.toByte(), 0xa1.toByte(), 0xd8.toByte(), 0x05.toByte(),
            0x53.toByte(), 0xbd.toByte(), 0xa4.toByte(), 0x02.toByte(),
            0xff.toByte(), 0xfe.toByte(), 0x5b.toByte(), 0xfe.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        )
        assertTrue(CanonicalFr.isCanonical(rMinusOne))
    }

    @Test
    fun isCanonical_r_isNotCanonical() {
        val r = byteArrayOf(
            0x73.toByte(), 0xed.toByte(), 0xa7.toByte(), 0x53.toByte(),
            0x29.toByte(), 0x9d.toByte(), 0x7d.toByte(), 0x48.toByte(),
            0x33.toByte(), 0x39.toByte(), 0xd8.toByte(), 0x08.toByte(),
            0x09.toByte(), 0xa1.toByte(), 0xd8.toByte(), 0x05.toByte(),
            0x53.toByte(), 0xbd.toByte(), 0xa4.toByte(), 0x02.toByte(),
            0xff.toByte(), 0xfe.toByte(), 0x5b.toByte(), 0xfe.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
        )
        assertFalse(
            "the field order itself is NOT a canonical Fr",
            CanonicalFr.isCanonical(r),
        )
    }

    @Test
    fun isCanonical_rPlusOne_isNotCanonical() {
        val rPlusOne = byteArrayOf(
            0x73.toByte(), 0xed.toByte(), 0xa7.toByte(), 0x53.toByte(),
            0x29.toByte(), 0x9d.toByte(), 0x7d.toByte(), 0x48.toByte(),
            0x33.toByte(), 0x39.toByte(), 0xd8.toByte(), 0x08.toByte(),
            0x09.toByte(), 0xa1.toByte(), 0xd8.toByte(), 0x05.toByte(),
            0x53.toByte(), 0xbd.toByte(), 0xa4.toByte(), 0x02.toByte(),
            0xff.toByte(), 0xfe.toByte(), 0x5b.toByte(), 0xfe.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(),
        )
        assertFalse(CanonicalFr.isCanonical(rPlusOne))
    }

    @Test
    fun isCanonical_allOnes_isNotCanonical() {
        // High byte 0xFF >> 0x73 — same shape as the testnet failure.
        assertFalse(CanonicalFr.isCanonical(ByteArray(32) { 0xFF.toByte() }))
    }

    @Test
    fun isCanonical_observedFailingValue_isNotCanonical() {
        // The literal bytes from the diagnostic event in the failing
        // testnet run (mirrored from onym-ios PR #36) — kept as a
        // regression anchor.
        val observed = byteArrayOf(
            0xfd.toByte(), 0xfb.toByte(), 0xc9.toByte(), 0x7d.toByte(),
            0x06.toByte(), 0x85.toByte(), 0xf2.toByte(), 0xb9.toByte(),
            0xf3.toByte(), 0xad.toByte(), 0x24.toByte(), 0xf7.toByte(),
            0xa4.toByte(), 0xc7.toByte(), 0x57.toByte(), 0x41.toByte(),
            0x6b.toByte(), 0xd5.toByte(), 0x3a.toByte(), 0x87.toByte(),
            0x11.toByte(), 0x47.toByte(), 0x7c.toByte(), 0xe2.toByte(),
            0xc3.toByte(), 0x59.toByte(), 0x77.toByte(), 0x80.toByte(),
            0x24.toByte(), 0xdb.toByte(), 0xfb.toByte(), 0x0d.toByte(),
        )
        assertFalse(
            "exact value that tripped Error #15 on the v0.0.5 tyranny contract",
            CanonicalFr.isCanonical(observed),
        )
    }

    @Test
    fun isCanonical_justBelowR_highByte0x73_isCanonical() {
        // High byte == 0x73 but second byte < r's second byte (0xed).
        val bytes = ByteArray(32)
        bytes[0] = 0x73
        bytes[1] = 0xec.toByte()
        assertTrue(CanonicalFr.isCanonical(bytes))
    }

    @Test
    fun isCanonical_wrongLength_returnsFalse() {
        assertFalse(CanonicalFr.isCanonical(ByteArray(31)))
        assertFalse(CanonicalFr.isCanonical(ByteArray(33)))
    }

    // ─── randomCanonicalFr32 sampling ─────────────────────────────

    @Test
    fun randomCanonicalFr32_alwaysCanonical_over10kSamples() {
        // Statistically, the sampler's accept rate is `r / 2^256 ≈ 0.453`.
        // 10k samples give a generous floor on rejected-path coverage
        // (~5.5k rejections), and the assertion is unconditional.
        val rng = SecureRandom()
        repeat(10_000) {
            val draw = CanonicalFr.randomCanonicalFr32(rng)
            assertEquals(32, draw.size)
            assertTrue(
                "sampler returned non-canonical bytes: ${draw.joinToString("") { "%02x".format(it.toInt() and 0xFF) }}",
                CanonicalFr.isCanonical(draw),
            )
        }
    }

    @Test
    fun randomCanonicalFr32_isNonZeroAndDistinct() {
        // Sanity: the sampler isn't returning a constant. 100 draws
        // should yield 100 distinct values with overwhelming probability.
        val rng = SecureRandom()
        val seen = HashSet<List<Byte>>(100)
        repeat(100) {
            seen.add(CanonicalFr.randomCanonicalFr32(rng).toList())
        }
        assertEquals("sampler should not collide over 100 draws", 100, seen.size)
        assertFalse(
            "all-zero would be a giveaway of a broken RNG",
            seen.contains(List(32) { 0.toByte() }),
        )
    }
}
