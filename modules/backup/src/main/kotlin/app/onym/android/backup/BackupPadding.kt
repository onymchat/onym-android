package app.onym.android.backup

/**
 * Padmé padding (onym-system `backup/UI-Backup-Object-HTTP.md` §7):
 * for length `L`, with `E = floor(log2 L)` and `S = floor(log2 E) + 1`,
 * round `L` up to the next multiple of `2^(E-S)`. Caps overhead at
 * ~12% while leaving the padded size a coarse bucket rather than a
 * measurement of a person's history.
 *
 * Worked example (pinned by the profile): `paddedLength(41_000_000) ==
 * 41_943_040`.
 *
 * Uses [Long] throughout — a device history can plausibly exceed the
 * 2 GiB `Int` range, and this padding decision determines how many
 * zero bytes actually get written to disk, so silent truncation here
 * would be a real, not theoretical, bug.
 *
 * Mirrors `BackupPadding` in onym-ios OnymBackup.
 */
object BackupPadding {
    fun paddedLength(length: Long): Long {
        if (length <= 1L) return maxOf(length, 0L)
        val e = highestBitPosition(length) - 1 // floor(log2(length))
        if (e <= 0L) return length
        val s = highestBitPosition(e) // floor(log2(e)) + 1
        val bits = e - s
        if (bits <= 0L) return length
        val step = 1L shl bits.toInt()
        val remainder = length % step
        return if (remainder == 0L) length else length + (step - remainder)
    }

    fun paddingCount(length: Long): Long = paddedLength(length) - length

    private fun highestBitPosition(value: Long): Long =
        if (value <= 0L) 0L else (64 - java.lang.Long.numberOfLeadingZeros(value)).toLong()
}
