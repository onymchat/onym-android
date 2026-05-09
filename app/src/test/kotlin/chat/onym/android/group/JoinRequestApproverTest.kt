package chat.onym.android.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit-test coverage for [JoinRequestApprover]'s value types. The
 * full behavioral suite (decode pipeline + approve happy path +
 * fanout shape) needs the OnymSDK FFI for Common.publicKey /
 * Common.leafHash, which isn't loaded on the JVM unit-test path —
 * those tests live in [chat.onym.android.group.JoinRequestApproverBehaviorTest]
 * under `androidTest/`.
 *
 * What this file exercises (no FFI required):
 *   - [JoinRequestApprover.PendingRequest] equality / hashCode
 *     contract — including the new (PR 78) joinerBlsPublicKey field.
 *   - [JoinRequestApprover.ApproveOutcome] singleton equality.
 *
 * Mirrors the value-typed slice of `JoinRequestApproverTests.swift`
 * from onym-ios PR #85.
 */
class JoinRequestApproverTest {

    @Test
    fun pendingRequest_equals_includesJoinerBlsPublicKey() {
        val withBls = sample(blsPub = ByteArray(48) { 0x11 })
        val withDifferentBls = sample(blsPub = ByteArray(48) { 0x22 })
        val withNullBls = sample(blsPub = null)
        val sameAsWithBls = sample(blsPub = ByteArray(48) { 0x11 })

        assertEquals(withBls, sameAsWithBls)
        assertEquals(withBls.hashCode(), sameAsWithBls.hashCode())
        assertNotEquals(withBls, withDifferentBls)
        assertNotEquals(withBls, withNullBls)
    }

    @Test
    fun pendingRequest_equals_includesGroupName() {
        val a = sample(groupName = "Family")
        val b = sample(groupName = "Friends")
        val c = sample(groupName = null)
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(a, sample(groupName = "Family"))
    }

    @Test
    fun approveOutcome_singletonsAreEqualByReference() {
        assertEquals(
            JoinRequestApprover.ApproveOutcome.Sent,
            JoinRequestApprover.ApproveOutcome.Sent,
        )
        assertEquals(
            JoinRequestApprover.ApproveOutcome.UnknownGroup,
            JoinRequestApprover.ApproveOutcome.UnknownGroup,
        )
        val sent: JoinRequestApprover.ApproveOutcome = JoinRequestApprover.ApproveOutcome.Sent
        val unknown: JoinRequestApprover.ApproveOutcome = JoinRequestApprover.ApproveOutcome.UnknownGroup
        assertNotEquals(sent, unknown)
    }

    @Test
    fun approveOutcome_transportFailedCarriesReason() {
        val outcome = JoinRequestApprover.ApproveOutcome.TransportFailed("relays down")
        assertEquals("relays down", outcome.reason)
    }

    @Test
    fun pendingRequest_propagatesAllConstructorFields() {
        val pr = sample()
        assertEquals("req-1", pr.id)
        assertEquals("Bob", pr.joinerDisplayLabel)
        assertEquals("Family", pr.groupName)
        assertEquals(48, pr.joinerBlsPublicKey!!.size)
        assertEquals(32, pr.joinerInboxPublicKey.size)
        assertEquals(32, pr.groupId.size)
    }

    private fun sample(
        id: String = "req-1",
        blsPub: ByteArray? = ByteArray(48) { 0x11 },
        groupName: String? = "Family",
    ) = JoinRequestApprover.PendingRequest(
        id = id,
        joinerInboxPublicKey = ByteArray(32) { 0x22 },
        joinerBlsPublicKey = blsPub,
        joinerDisplayLabel = "Bob",
        groupId = ByteArray(32) { 0x33 },
        groupName = groupName,
    )
}
