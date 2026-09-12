package app.onym.android

import app.onym.android.identity.IdentityId
import app.onym.android.identity.IdentitySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModerationIdentityRemoval] — the guard in front of an irreversible
 * delete. `purgeForRemovedIdentities` drops every mandate, report and
 * appeal row outside the keep-set it is given, so a keep-set derived
 * from an unloaded identity list wipes the ledgers of every identity
 * on the device, including the one a `restore()` is landing on.
 *
 * The distinction being tested is the whole point: an empty summary
 * list is "nobody has loaded identity storage yet" as often as it is
 * "this device holds no identities", and only a list still carrying
 * the identity being removed proves it is the second.
 */
class ModerationIdentityRemovalTest {

    private fun summary(id: String, sendingKey: ByteArray) = IdentitySummary(
        id = IdentityId(id),
        name = id,
        blsPublicKey = ByteArray(48),
        inboxPublicKey = ByteArray(32),
        sendingPublicKey = sendingKey,
    )

    @Test
    fun loadedList_carriesTheIdentityBeingRemoved() {
        val removed = IdentityId("a")
        val summaries = listOf(
            summary("a", ByteArray(32) { 0x01 }),
            summary("b", ByteArray(32) { 0x02 }),
        )
        assertTrue(ModerationIdentityRemoval.listIsLoaded(summaries, removed))
    }

    @Test
    fun emptyList_declines() {
        // The `restore()`-before-bootstrap shape: the listener fires
        // with a real id and a StateFlow nobody has populated.
        assertFalse(ModerationIdentityRemoval.listIsLoaded(emptyList(), IdentityId("a")))
    }

    @Test
    fun listWithoutTheRemovedIdentity_declines() {
        // Listeners run BEFORE the wipe, so a loaded list always still
        // carries the id. Without it, this is some other stale read —
        // not evidence that the rows are orphaned.
        val summaries = listOf(summary("b", ByteArray(32) { 0x02 }))
        assertFalse(ModerationIdentityRemoval.listIsLoaded(summaries, IdentityId("a")))
    }

    @Test
    fun keepSet_isTheKeyReferencesTheLedgersAreKeyedBy() {
        val summaries = listOf(
            summary("a", ByteArray(32) { 0x01 }),
            summary("b", ByteArray(32) { 0xAB.toByte() }),
        )
        assertEquals(
            setOf(
                "onym:key:" + "01".repeat(32),
                "onym:key:" + "ab".repeat(32),
            ),
            ModerationIdentityRemoval.keepSet(summaries),
        )
    }

    @Test
    fun keepSet_ofNoIdentities_isEmpty_whichDeletesEverything() {
        // Not a degenerate case: wiping the last identity leaves a
        // genuinely empty device, and every remaining row is orphaned.
        // It is only safe because `listIsLoaded` gated the call.
        assertTrue(ModerationIdentityRemoval.keepSet(emptyList()).isEmpty())
    }
}
