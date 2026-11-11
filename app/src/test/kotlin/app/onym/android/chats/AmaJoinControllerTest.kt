package app.onym.android.chats

import app.onym.android.group.IntroCapability
import app.onym.android.identity.IdentityId
import app.onym.android.inbox.PendingChatsViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The empty chats list's automatic attempt: once per identity, single
 * flight, and never a send — the person still taps Send on the
 * confirmation. The fake `prepareJoin` scripts the destination, so each
 * of the four outcomes is exercised without a repository.
 */
class AmaJoinControllerTest {

    private val capability: IntroCapability =
        requireNotNull(IntroCapability.fromLink(AmaInvite.LINK)) { "the AMA invite must decode" }

    private class FakeAttempts : AmaJoinAttempts {
        val seen = mutableSetOf<String>()
        var marks = 0
        override suspend fun hasAttempted(identityKey: String) = identityKey in seen
        override suspend fun markAttempted(identityKey: String) {
            marks += 1
            seen += identityKey
        }
    }

    private fun confirmation(): PendingChatsViewModel.JoinConfirmation =
        PendingChatsViewModel.JoinConfirmation(
            rowId = "aa:me",
            owner = IdentityId("owner-1"),
            identityName = "me",
            groupIdHex = "aa",
            groupName = "Onym AMA",
            inviterAlias = "",
            invitationMessage = null,
            introPublicKey = byteArrayOf(1, 2, 3),
            suggestedLabel = "me",
            rules = "be kind",
            origin = PendingChatsViewModel.JoinConfirmation.Origin.Link(capability),
        )

    private class Harness(
        destination: PendingChatsViewModel.JoinDestination,
        attempts: FakeAttempts = FakeAttempts(),
        invite: () -> IntroCapability? = { null },
    ) {
        var prepareCalls = 0
        val openedChats = mutableListOf<String>()
        val openedPending = mutableListOf<String>()
        val controller = AmaJoinController(
            attempts = attempts,
            invite = { invite() },
            prepareJoin = {
                prepareCalls += 1
                destination
            },
            onOpenChat = { openedChats += it },
            onOpenPending = { openedPending += it },
        )
    }

    private fun harness(
        destination: PendingChatsViewModel.JoinDestination,
        attempts: FakeAttempts = FakeAttempts(),
        invite: () -> IntroCapability? = { capability },
    ) = Harness(destination, attempts, invite)

    @Test
    fun resolveOnce_opensTheConfirmation_andRemembers() = runTest {
        val attempts = FakeAttempts()
        val h = harness(PendingChatsViewModel.JoinDestination.Confirm(confirmation()), attempts)

        h.controller.resolveOnce("identity-a")

        assertEquals(1, h.prepareCalls)
        assertTrue(h.controller.phase.value is AmaJoinPhase.Confirm)
        assertEquals(1, attempts.marks)
        assertTrue(attempts.hasAttempted("identity-a"))
    }

    @Test
    fun resolveOnce_isOncePerIdentity() = runTest {
        val attempts = FakeAttempts()
        val h = harness(PendingChatsViewModel.JoinDestination.Confirm(confirmation()), attempts)

        h.controller.resolveOnce("identity-a")
        h.controller.onConfirmationDismissed()
        h.controller.resolveOnce("identity-a")

        assertEquals("a dismissal must not be re-asked", 1, h.prepareCalls)

        // A different identity on the same device is its own first launch.
        h.controller.resolveOnce("identity-b")
        assertEquals(2, h.prepareCalls)
    }

    @Test
    fun alreadyJoined_opensTheChat() = runTest {
        val h = harness(PendingChatsViewModel.JoinDestination.AlreadyJoined(groupIdHex = "ab"))

        h.controller.resolveOnce("identity-a")

        assertEquals(listOf("ab"), h.openedChats)
        assertEquals(AmaJoinPhase.HandedOff, h.controller.phase.value)
    }

    @Test
    fun waiting_opensThePendingRow() = runTest {
        val h = harness(PendingChatsViewModel.JoinDestination.Waiting(rowId = "ab:me"))

        h.controller.resolveOnce("identity-a")

        assertEquals(listOf("ab:me"), h.openedPending)
        assertEquals(AmaJoinPhase.HandedOff, h.controller.phase.value)
    }

    @Test
    fun failure_fallsBack_andIsNotRetriedAutomatically() = runTest {
        val attempts = FakeAttempts()
        val h = harness(
            PendingChatsViewModel.JoinDestination.Failed(
                PendingChatsViewModel.JoinOutcome.FailureReason.NO_IDENTITY,
            ),
            attempts,
        )

        h.controller.resolveOnce("identity-a")
        assertTrue(h.controller.phase.value is AmaJoinPhase.Failed)
        h.controller.resolveOnce("identity-a")
        assertEquals("a failed attempt must not loop", 1, h.prepareCalls)
    }

    @Test
    fun malformedInvite_failsWithoutAskingTheRelayer() = runTest {
        val h = harness(
            PendingChatsViewModel.JoinDestination.Confirm(confirmation()),
            invite = { null },
        )

        h.controller.resolveOnce("identity-a")

        val phase = h.controller.phase.value
        assertTrue(phase is AmaJoinPhase.Failed)
        assertNull((phase as AmaJoinPhase.Failed).reason)
        assertEquals(0, h.prepareCalls)
    }

    @Test
    fun manualOpen_ignoresTheRememberedAttempt() = runTest {
        val attempts = FakeAttempts()
        val h = harness(PendingChatsViewModel.JoinDestination.Confirm(confirmation()), attempts)
        attempts.markAttempted("identity-a")

        h.controller.resolveOnce("identity-a")
        assertEquals("the automatic path stays quiet", 0, h.prepareCalls)

        h.controller.openManually("identity-a")
        assertEquals(1, h.prepareCalls)
        assertTrue(h.controller.phase.value is AmaJoinPhase.Confirm)
    }
}
