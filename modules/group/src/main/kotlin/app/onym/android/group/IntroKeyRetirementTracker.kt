package app.onym.android.group

/**
 * Turns successive snapshots of the device's live intro keys into the
 * set that just retired, which is what
 * [IntroRequestStore.pruneTombstones] wants.
 *
 * ## Feed this EVERY owner's keys
 *
 * The tombstone log spans the process; it is not scoped to an
 * identity. So "absent from the snapshot" only means "retired" when
 * the snapshot covers every owner. Diffing an owner-scoped stream —
 * the one [IntroInboxPump] reconciles on — reintroduces exactly the
 * wipe that phrasing the prune as *retiring* was meant to make
 * inexpressible:
 *
 *  - switch identity A → B and the diff is all of A's live keys, so
 *    every one of A's tombstones is deleted;
 *  - sign out and the active set empties, so the diff is everything.
 *
 * Either way the next reconnect replays an inbox with no `since`,
 * `record` no longer sees a tombstone, and already-handled joiners
 * come back as pending rows.
 *
 * Stateful and single-consumer by design: one instance per collector,
 * never shared.
 */
class IntroKeyRetirementTracker {

    private var live: Set<String> = emptySet()

    /**
     * The keys present in the previous snapshot and absent from
     * [currentHex]. Empty on the first call — with nothing to compare
     * against, nothing can be known to have retired, which is also
     * what makes a cold start (or a keystore read that fails closed
     * and yields nothing) a no-op rather than a wipe.
     */
    fun retired(currentHex: Set<String>): Set<String> {
        val gone = live - currentHex
        live = currentHex
        return gone
    }
}
