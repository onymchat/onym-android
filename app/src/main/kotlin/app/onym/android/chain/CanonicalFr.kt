package app.onym.android.chain

import java.security.SecureRandom

/**
 * BLS12-381 scalar-field (`Fr`) canonical-encoding helpers.
 *
 * The `sep-tyranny` Soroban contract (`onym-contracts/plonk/sep-tyranny/
 * src/lib.rs:299`) rejects any `group_id` whose 32-byte BE encoding is
 * `>= r` with `Error::InvalidCommitmentEncoding` (#15). The check
 * exists by design — it closes a `group_id_A` vs `group_id_A + p
 * (mod 2^256)` collision in `group_id_fr` (see the contract comment
 * at lines 290–298). With `groupID = randomBytes(32)` the failure
 * rate is `(2^256 - r) / 2^256 ≈ 0.547`; this helper rejection-
 * samples until the draw is canonical, accept rate ≈ 0.453, so the
 * loop terminates in ~2.2 iterations on average.
 *
 * Mirrors `CreateGroupInteractor.randomCanonicalFr()` /
 * `isCanonicalFr(_:)` from onym-ios PR #36.
 *
 * `r = 0x73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001`
 */
internal object CanonicalFr {

    /** BLS12-381 Fr modulus, big-endian. 32 bytes. */
    private val MODULUS_BE: ByteArray = byteArrayOf(
        0x73.toByte(), 0xed.toByte(), 0xa7.toByte(), 0x53.toByte(),
        0x29.toByte(), 0x9d.toByte(), 0x7d.toByte(), 0x48.toByte(),
        0x33.toByte(), 0x39.toByte(), 0xd8.toByte(), 0x08.toByte(),
        0x09.toByte(), 0xa1.toByte(), 0xd8.toByte(), 0x05.toByte(),
        0x53.toByte(), 0xbd.toByte(), 0xa4.toByte(), 0x02.toByte(),
        0xff.toByte(), 0xfe.toByte(), 0x5b.toByte(), 0xfe.toByte(),
        0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
    )

    /**
     * `true` iff [bytes] is exactly 32 bytes long and, interpreted as
     * a big-endian integer, is strictly less than the BLS12-381 Fr
     * modulus. Mirrors the contract's `is_canonical_fr` predicate
     * (sep-tyranny/src/lib.rs:688).
     */
    fun isCanonical(bytes: ByteArray): Boolean {
        if (bytes.size != 32) return false
        for (i in 0 until 32) {
            val a = bytes[i].toInt() and 0xFF
            val b = MODULUS_BE[i].toInt() and 0xFF
            if (a < b) return true
            if (a > b) return false
        }
        // bytes == MODULUS → not strictly less → not canonical.
        return false
    }

    /**
     * Uniformly-random 32-byte BE value strictly less than the
     * BLS12-381 scalar field order `r`. Rejection-samples until a
     * canonical value falls out (accept rate ≈ 0.453, so ~2.2
     * iterations on average).
     *
     * Why we can't just take `randomBytes(32) mod r`: the contract
     * rejects any non-canonical encoding outright, and the SDK's
     * silent mod-r reduction would diverge from the contract's check
     * on ~25% of inputs. Generating canonically at the source removes
     * the reduction question entirely.
     *
     * @param random Injectable for tests. Production callers can omit.
     */
    fun randomCanonicalFr32(random: SecureRandom = SecureRandom()): ByteArray {
        val buf = ByteArray(32)
        while (true) {
            random.nextBytes(buf)
            if (isCanonical(buf)) return buf.copyOf()
        }
    }
}
