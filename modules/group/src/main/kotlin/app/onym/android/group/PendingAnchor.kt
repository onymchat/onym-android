package app.onym.android.group

/**
 * One `update_commitment` this device is about to submit, written down
 * before it goes out so the attempt survives losing its answer.
 *
 * ## Why this exists
 *
 * A member-add moves the group to a commitment over a *fresh random
 * salt*. The salt is random on purpose: it is the blinding factor that
 * stops anyone reading the chain from confirming a guessed roster by
 * recomputation, and deriving it from state other members hold would
 * hand that power to everyone who has ever been in the group.
 *
 * The cost of randomness is that the value exists nowhere but the
 * memory of the process that drew it. If the transaction reaches the
 * ledger and the answer does not reach the phone — a relayer 502, a
 * dropped connection, a kill — the chain is now committed to a salt
 * this device cannot name. Every later member-add proves from a state
 * the contract no longer holds and is refused `PUBLIC_INPUTS_MISMATCH`
 * (`Error(Contract, #10)`), forever: the group's roster is frozen with
 * no way back.
 *
 * So the salt is written to disk *before* the submit. Then a refusal is
 * recoverable — [adoptLandedAnchor] recomputes the commitment each
 * recorded attempt would have produced and compares it to what the
 * chain actually holds, which identifies the transaction that landed
 * and hands back the state to adopt.
 *
 * Records are kept per `(group, local identity)` and swept only once
 * the group has *persisted* an advance past them: after the chain
 * leaves epoch N, no proof from N can ever be accepted again, so
 * nothing at or below N can still be waiting to land. Only then —
 * sweeping against an advance the device has not yet written down is
 * how the evidence gets lost.
 */
data class PendingAnchor(
    val groupId: ByteArray,
    /** Which local identity's copy of the group this was proved from;
     *  two identities can hold rows for the same on-chain group. */
    val ownerIdentityId: String,
    /** The epoch this attempt proved *from*. What makes a record
     *  answerable: it can only have landed if the chain now sits at
     *  exactly `epochOld + 1`. */
    val epochOld: ULong,
    val joinerPublicKey: ByteArray,
    val joinerLeafHash: ByteArray,
    /** The value the whole record exists to keep. */
    val saltNew: ByteArray,
    val createdAtMillis: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAnchor) return false
        return groupId.contentEquals(other.groupId) &&
            ownerIdentityId == other.ownerIdentityId &&
            epochOld == other.epochOld &&
            joinerPublicKey.contentEquals(other.joinerPublicKey) &&
            joinerLeafHash.contentEquals(other.joinerLeafHash) &&
            saltNew.contentEquals(other.saltNew) &&
            createdAtMillis == other.createdAtMillis
    }

    override fun hashCode(): Int {
        var h = groupId.contentHashCode()
        h = 31 * h + ownerIdentityId.hashCode()
        h = 31 * h + epochOld.hashCode()
        h = 31 * h + joinerPublicKey.contentHashCode()
        h = 31 * h + joinerLeafHash.contentHashCode()
        h = 31 * h + saltNew.contentHashCode()
        h = 31 * h + createdAtMillis.hashCode()
        return h
    }
}

/**
 * Durable record of in-flight anchor attempts.
 *
 * The write in [record] has to reach disk before the transaction
 * reaches the relayer, or the crash window it exists to cover is
 * exactly the window it misses.
 */
interface PendingAnchorStore {

    /**
     * Write down an attempt about to be submitted.
     *
     * Adds only. Sweeping older epochs from here looks tempting — the
     * chain has left them, so they read as dead — but the epoch this
     * attempt proves *from* is not always one this device has
     * persisted. The reconcile retries from an adopted state before
     * `settleAnchor` writes it down, so a sweep here would delete the
     * record identifying the landed transaction while the group on disk
     * still says the epoch before it. A crash in that window and the
     * salt is gone for good — the exact state this store exists to
     * prevent.
     *
     * So only [clear], called after the advance is persisted, may
     * delete. Rows do accumulate while a group is stuck: one or two per
     * Accept the founder taps at an epoch that never advances. That is
     * deliberate. Every one of them is a candidate for having landed —
     * the *oldest* most of all, since the first tap is usually the one
     * whose answer went missing — so there is no row here that can be
     * dropped on age or count without risking the roster it would have
     * unfrozen.
     */
    suspend fun record(anchor: PendingAnchor)

    /** Every attempt recorded for this group that could still be
     *  waiting to land, newest first. */
    suspend fun pending(groupId: ByteArray, ownerIdentityId: String): List<PendingAnchor>

    /** Drop every attempt that proved from [throughEpoch] or earlier —
     *  the chain has moved past them and none can land now. */
    suspend fun clear(groupId: ByteArray, ownerIdentityId: String, throughEpoch: ULong)
}

/**
 * Keeps nothing. The default for call sites that don't anchor
 * (non-Tyranny groups, unit tests that never reach the chain leg) —
 * recovery then degrades to what it was before this existed: a refusal
 * the founder can read, rather than a silent one.
 */
object NoopPendingAnchorStore : PendingAnchorStore {
    override suspend fun record(anchor: PendingAnchor) = Unit
    override suspend fun pending(groupId: ByteArray, ownerIdentityId: String): List<PendingAnchor> =
        emptyList()
    override suspend fun clear(groupId: ByteArray, ownerIdentityId: String, throughEpoch: ULong) =
        Unit
}
