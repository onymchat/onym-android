package app.onym.android.group

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The composition root used to diff successive snapshots of the
 * PUMP's stream, which is filtered to the active identity — so
 * "absent from the snapshot" was read as "retired" for links that had
 * merely gone out of scope. That deleted other identities' tombstones
 * and brought their handled joiners back as pending rows, which is
 * the regression `pruneTombstones(retiring:)` was phrased to make
 * inexpressible. It survived because the store's own test drives the
 * method directly with `emptySet()`; nothing exercised the diff.
 *
 * These pin the diff itself. The wiring's remaining obligation is to
 * feed it every owner's keys — stated on the tracker, and the reason
 * the collector reads `entriesFlow` unfiltered.
 */
class IntroKeyRetirementTrackerTest {

    private val a1 = "a1".repeat(32)
    private val a2 = "a2".repeat(32)
    private val b1 = "b1".repeat(32)

    @Test
    fun firstSnapshot_retiresNothing() {
        val tracker = IntroKeyRetirementTracker()
        // Nothing to compare against — a cold start, or a keystore
        // read that failed closed, must not read as a mass retirement.
        assertEquals(emptySet<String>(), tracker.retired(setOf(a1, a2)))
    }

    @Test
    fun aKeyLeavingTheSnapshot_isRetired() {
        val tracker = IntroKeyRetirementTracker()
        tracker.retired(setOf(a1, a2))
        assertEquals(setOf(a2), tracker.retired(setOf(a1)))
    }

    @Test
    fun anUnchangedSnapshot_retiresNothing() {
        val tracker = IntroKeyRetirementTracker()
        tracker.retired(setOf(a1, a2))
        assertEquals(emptySet<String>(), tracker.retired(setOf(a1, a2)))
    }

    @Test
    fun aNewKeyAppearing_retiresNothing() {
        val tracker = IntroKeyRetirementTracker()
        tracker.retired(setOf(a1))
        assertEquals(emptySet<String>(), tracker.retired(setOf(a1, b1)))
    }

    /**
     * The bug this exists for, expressed at the tracker: fed the whole
     * device's keys, an identity switch is not a retirement — both
     * owners' keys are in every snapshot, so the diff is empty. Fed an
     * owner-scoped stream it would have been all of A's keys.
     */
    @Test
    fun identitySwitch_overTheUnfilteredSnapshot_retiresNothing() {
        val tracker = IntroKeyRetirementTracker()
        // A active; the snapshot still carries B's key, because the
        // store is not owner-scoped.
        tracker.retired(setOf(a1, b1))
        // Switch to B. Same device-wide set.
        assertEquals(emptySet<String>(), tracker.retired(setOf(a1, b1)))
    }

    /**
     * Sign-out. The owner-scoped stream emitted an empty list here and
     * the old diff read it as "everything retired". Over the
     * unfiltered flow the keys are still on the device, so nothing is.
     */
    @Test
    fun signOut_doesNotRetireTheDevicesKeys() {
        val tracker = IntroKeyRetirementTracker()
        tracker.retired(setOf(a1, a2, b1))
        assertEquals(emptySet<String>(), tracker.retired(setOf(a1, a2, b1)))
    }

    /** A genuine revoke while several identities hold links retires
     *  only the revoked one. */
    @Test
    fun revokeAmongManyOwners_retiresOnlyThatKey() {
        val tracker = IntroKeyRetirementTracker()
        tracker.retired(setOf(a1, a2, b1))
        assertEquals(setOf(b1), tracker.retired(setOf(a1, a2)))
    }
}
