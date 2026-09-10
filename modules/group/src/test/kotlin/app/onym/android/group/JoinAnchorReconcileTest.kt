package app.onym.android.group

import app.onym.android.chain.GovernanceMember
import app.onym.android.chain.SepCommitmentEntry
import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * What the approver does when the contract answers
 * `PUBLIC_INPUTS_MISMATCH` (`Error(Contract, #10)`) — the refusal that
 * means "you proved a step out of a state I am not in".
 *
 * The case that produces it in the field: an `update_commitment` whose
 * transaction reached the ledger and whose *answer* did not reach the
 * phone. The founder sees the first Accept fail, taps again, and the
 * second tap proves from an epoch the chain has already left.
 *
 * The salt those transactions moved to is random — it has to be, it is
 * what stops a chain observer confirming a guessed roster — so the only
 * thing that can identify the one that landed is the record written
 * before it was submitted. These pin that identification, the epoch
 * rebase that needs no record, and, just as deliberately, the cases
 * where nothing can be recovered and the approver has to say so
 * instead of re-proving into the same wall.
 *
 * Pure by construction: the real commitment goes through the OnymSDK
 * JNI, so the recompute is injected here (JNI is androidTest-only).
 */
class JoinAnchorReconcileTest {

    /**
     * Stands in for `Poseidon(Poseidon(root, epoch), salt)`. Only two
     * properties matter to the code under test — it is a function of
     * exactly `(roster, tier, epoch, salt)`, and distinct inputs give
     * distinct bytes — and SHA-256 over the same tuple has both.
     */
    private val commitmentOf: CommitmentRecomputing = { members, tier, epoch, salt ->
        val md = MessageDigest.getInstance("SHA-256")
        for (m in members.sortedWith(compareBy(byteArrayLexComparator()) { it.publicKeyCompressed })) {
            md.update(m.publicKeyCompressed)
            md.update(m.leafHash)
        }
        md.update(tier.depth.toByte())
        md.update(epoch.toString().toByteArray())
        md.update(salt)
        md.digest()
    }

    private val admin = GovernanceMember(
        publicKeyCompressed = ByteArray(48) { 0x0A },
        leafHash = ByteArray(32) { 0x0B },
    )
    private val joiner = GovernanceMember(
        publicKeyCompressed = ByteArray(48) { 0x1A },
        leafHash = ByteArray(32) { 0x1B },
    )

    private fun group(
        epoch: ULong = 3uL,
        salt: ByteArray = ByteArray(32) { 0x66 },
        members: List<GovernanceMember> = listOf(admin),
    ) = ChatGroup(
        id = "aabb",
        name = "Montelibero",
        groupSecret = ByteArray(32) { 0x55 },
        createdAtMillis = 1_700_000_000_000L,
        members = members,
        memberProfiles = emptyMap(),
        epoch = epoch,
        salt = salt,
        commitment = commitmentOf(members, SepTier.SMALL, epoch, salt),
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = admin.publicKeyCompressed.joinToString("") { "%02x".format(it) },
        ownerIdentityId = "owner",
        isPublishedOnChain = true,
    )

    /** The salt the lost transaction drew. Random in production; fixed
     *  here so the test can play both the chain and the record. */
    private val lostSalt = ByteArray(32) { 0x5C }

    /** What the chain holds after the lost transaction landed. */
    private fun landedEntry(
        g: ChatGroup,
        member: GovernanceMember = joiner,
        salt: ByteArray = lostSalt,
    ): SepCommitmentEntry {
        val newMembers = (g.members + member)
            .sortedWith(compareBy(byteArrayLexComparator()) { it.publicKeyCompressed })
        val epochNew = g.epoch + 1uL
        return SepCommitmentEntry(
            commitment = commitmentOf(newMembers, g.tier, epochNew, salt),
            epoch = epochNew,
        )
    }

    /** The row [JoinRequestApprover] writes before it submits. */
    private fun record(
        g: ChatGroup,
        member: GovernanceMember = joiner,
        salt: ByteArray = lostSalt,
        epochOld: ULong = g.epoch,
        atMillis: Long = 1_700_000_000_000L,
    ) = PendingAnchor(
        groupId = g.groupIdBytes,
        ownerIdentityId = g.ownerIdentityId,
        epochOld = epochOld,
        joinerPublicKey = member.publicKeyCompressed,
        joinerLeafHash = member.leafHash,
        saltNew = salt,
        createdAtMillis = atMillis,
    )

    // ── adopt: the earlier attempt actually landed ────────────────

    /**
     * The heart of it. The chain is at exactly the state one recorded
     * attempt would have produced, so that attempt is the one that
     * landed — and there is nothing left to submit. The device adopts
     * it, salt included, and the approval carries on to the invitation
     * the joiner never received.
     */
    @Test
    fun adoptsTheChainStateWhenARecordedAttemptLanded() {
        val g = group()
        val adopted = adoptLandedAnchor(g, landedEntry(g), listOf(record(g)), commitmentOf)

        assertNotNull(adopted)
        assertEquals(4uL, adopted!!.group.epoch)
        assertEquals(2, adopted.group.members.size)
        assertArrayEquals(joiner.publicKeyCompressed, adopted.joinerPublicKey)
        // The salt is the point: without it back in hand the next
        // member-add could never prove from this state.
        assertArrayEquals(lostSalt, adopted.group.salt)
        assertArrayEquals(landedEntry(g).commitment, adopted.group.commitment)
    }

    /**
     * Two approvals from the same epoch, one of them landed. The
     * founder is trying to approve the *other* one, and the reconcile
     * has to name whichever transaction actually made it — the
     * approver then re-proves the join it was asked for from there.
     */
    @Test
    fun namesWhicheverRecordedAttemptLandedEvenIfItIsNotThisJoiner() {
        val other = GovernanceMember(
            publicKeyCompressed = ByteArray(48) { 0x2A },
            leafHash = ByteArray(32) { 0x2B },
        )
        val otherSalt = ByteArray(32) { 0x3C }
        val g = group()

        val adopted = adoptLandedAnchor(
            g,
            landedEntry(g, member = other, salt = otherSalt),
            // Newest first, as the store returns them: this joiner's
            // attempt is the more recent one and is *not* the one that
            // landed.
            listOf(record(g), record(g, member = other, salt = otherSalt)),
            commitmentOf,
        )

        assertNotNull(adopted)
        assertArrayEquals(other.publicKeyCompressed, adopted!!.joinerPublicKey)
        assertArrayEquals(otherSalt, adopted.group.salt)
        assertTrue(
            "the landed joiner is in the adopted roster",
            adopted.group.members.any { it.publicKeyCompressed.contentEquals(other.publicKeyCompressed) },
        )
        assertFalse(
            "the joiner still being approved is not",
            adopted.group.members.any { it.publicKeyCompressed.contentEquals(joiner.publicKeyCompressed) },
        )
    }

    /** The roster it adopts is the canonical lex ordering, not
     *  append-order — the same ordering the proof and the contract
     *  agree on, or the next update commits to a different tree. */
    @Test
    fun theAdoptedRosterIsLexSorted() {
        val early = GovernanceMember(
            publicKeyCompressed = ByteArray(48) { 0x01 },
            leafHash = ByteArray(32) { 0x02 },
        )
        val base = group()
        val g = base.copy(
            members = listOf(admin, early),
            commitment = commitmentOf(listOf(admin, early), SepTier.SMALL, base.epoch, base.salt),
        )

        val adopted = adoptLandedAnchor(g, landedEntry(g), listOf(record(g)), commitmentOf)

        assertNotNull(adopted)
        assertArrayEquals(early.publicKeyCompressed, adopted!!.group.members[0].publicKeyCompressed)
        assertArrayEquals(joiner.publicKeyCompressed, adopted.group.members[2].publicKeyCompressed)
    }

    /**
     * No record, nothing to recompute against — the shape every group
     * anchored by a build that kept none is in. The chain is one epoch
     * ahead and the device cannot say why, which is exactly what it has
     * to report rather than guess at.
     */
    @Test
    fun cannotAdoptWithoutARecordOfTheAttempt() {
        val g = group()
        assertNull(adoptLandedAnchor(g, landedEntry(g), emptyList(), commitmentOf))
    }

    /** A record whose salt isn't the one the chain committed to is not
     *  the transaction that landed, however close the epoch looks. */
    @Test
    fun refusesToAdoptARecordWhoseCommitmentDoesNotMatch() {
        val g = group()
        val entry = landedEntry(g)
        assertNull(
            adoptLandedAnchor(
                g,
                entry,
                listOf(record(g, salt = ByteArray(32) { 0x7E })),
                commitmentOf,
            ),
        )
    }

    /** Two epochs ahead is not "our attempt landed" — an accepted proof
     *  advances the chain by exactly one, so something else moved it. */
    @Test
    fun refusesToAdoptWhenTheChainIsFurtherAheadThanOneStep() {
        val g = group()
        val entry = landedEntry(g)
        assertNull(
            adoptLandedAnchor(
                g,
                entry.copy(epoch = entry.epoch + 1uL),
                listOf(record(g)),
                commitmentOf,
            ),
        )
    }

    /** A record left over from an epoch the group has already left
     *  cannot describe the step the chain just took. */
    @Test
    fun ignoresRecordsFromAnEpochTheGroupHasLeft() {
        val g = group(epoch = 3uL)
        assertNull(
            adoptLandedAnchor(g, landedEntry(g), listOf(record(g, epochOld = 2uL)), commitmentOf),
        )
    }

    /** A record for someone already in the roster was resolved and
     *  merely outlived its sweep. Re-adding the leaf would build a tree
     *  the contract never committed to. */
    @Test
    fun ignoresRecordsForMembersAlreadyInTheRoster() {
        val g = group(members = listOf(admin, joiner))
        assertNull(
            adoptLandedAnchor(g, landedEntry(g), listOf(record(g)), commitmentOf),
        )
    }

    // ── rebase: only the counter drifted ──────────────────────────

    /** Same roster, same salt, later epoch: re-prove from the chain's
     *  number rather than the device's. */
    @Test
    fun rebasesOntoTheChainEpochWhenOnlyTheCounterDrifted() {
        val g = group(epoch = 3uL)
        val entry = SepCommitmentEntry(
            commitment = commitmentOf(g.members, g.tier, 5uL, g.salt),
            epoch = 5uL,
        )

        val rebased = rebaseOnChainEpoch(g, entry, commitmentOf)

        assertNotNull(rebased)
        assertEquals(5uL, rebased!!.epoch)
        assertArrayEquals(entry.commitment, rebased.commitment)
        assertEquals(g.members.size, rebased.members.size)
        assertArrayEquals(g.salt, rebased.salt)
    }

    /**
     * Epochs already agree, so the mismatch was about something else —
     * the roster, the salt, the group id. Re-proving would spend the
     * founder's 3-5 seconds to be refused in exactly the same way.
     */
    @Test
    fun doesNotRebaseWhenTheEpochsAlreadyAgree() {
        val g = group(epoch = 3uL)
        assertNull(
            rebaseOnChainEpoch(
                g,
                SepCommitmentEntry(commitment = g.commitment!!, epoch = 3uL),
                commitmentOf,
            ),
        )
    }

    /** A later epoch over a roster this device can't reproduce is a
     *  real divergence, not a drifted counter. */
    @Test
    fun doesNotRebaseOntoACommitmentItCannotReproduce() {
        val g = group(epoch = 3uL)
        assertNull(
            rebaseOnChainEpoch(
                g,
                SepCommitmentEntry(commitment = ByteArray(32) { 0x5C }, epoch = 5uL),
                commitmentOf,
            ),
        )
    }

}
