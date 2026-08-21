package app.onym.android.inbox

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import app.onym.android.group.ChatGroup
import app.onym.android.group.GroupRepository
import app.onym.android.group.IntroCapability
import app.onym.android.group.JoinRequestSender
import app.onym.android.identity.IdentityId
import app.onym.android.support.FakeActiveIdentityProvider
import app.onym.android.support.InMemoryGroupStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Where the three sources meet. Most of what matters here is which one
 * wins and when a row stops existing.
 *
 * Mirrors `PendingChatsFlowTests` in onym-ios.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingChatsViewModelTest {

    private val owner = IdentityId("owner")
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    // ─── Offers ──────────────────────────────────────────────────

    @Test
    fun offer_becomesAnAcceptableRow() = runTest {
        val h = harness()
        h.viewModel.start()
        h.repository.record(h.chat())

        val row = h.viewModel.rows.value.single()
        assertEquals(PendingChatsViewModel.State.Offered, row.state)
        assertEquals("Maple Garden", row.name)
        assertEquals("Alice", row.inviterAlias)
        assertTrue(row.isDismissable)
    }

    @Test
    fun accept_shipsTheRequestAndTheRowStartsWaiting() = runTest {
        val h = harness()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)

        h.viewModel.accept(chat.id)

        assertEquals(1, h.sender.calls.size)
        assertEquals(
            "the joiner is introduced by the active identity's name",
            "Bob",
            h.sender.calls.single().label,
        )
        assertEquals(PendingChatsViewModel.State.Waiting, h.viewModel.rows.value.single().state)
    }

    @Test
    fun accept_onARowThatAlreadyAsked_isANoOp() = runTest {
        val h = harness()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)
        h.repository.markRequested(chat.id)

        h.viewModel.accept(chat.id)

        assertTrue(h.sender.calls.isEmpty())
    }

    @Test
    fun failedSend_showsTheCodeAndRetryResends() = runTest {
        val h = harness()
        // The transport's own words are deliberately not kept: the row
        // is on disk and outlives the language it was written in.
        h.sender.outcome = JoinRequestSender.Outcome.TransportFailed("relay rejected")
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)

        h.viewModel.accept(chat.id)

        assertEquals(
            PendingChatsViewModel.State.SendFailed(PendingChat.SendFailure.TRANSPORT),
            h.viewModel.rows.value.single().state,
        )
        assertTrue(h.viewModel.rows.value.single().state.isRetryable)

        h.sender.outcome = JoinRequestSender.Outcome.Sent
        h.viewModel.retry(chat.id)

        assertEquals(PendingChatsViewModel.State.Waiting, h.viewModel.rows.value.single().state)
        assertEquals(2, h.sender.calls.size)
    }

    @Test
    fun noIdentityLoaded_leavesTheRowRetryableWithSomethingToRead() = runTest {
        val h = harness()
        h.sender.outcome = JoinRequestSender.Outcome.NoIdentityLoaded()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)

        h.viewModel.accept(chat.id)

        assertEquals(
            PendingChatsViewModel.State.SendFailed(PendingChat.SendFailure.NO_IDENTITY),
            h.viewModel.rows.value.single().state,
        )
    }

    @Test
    fun malformedInvite_saysSoWithoutTouchingTheSender() = runTest {
        // A capability that can't be rebuilt from the stored row: there
        // is nothing to send and nothing a Retry could fix, so the row
        // keeps its state and the error is the whole message.
        val h = harness()
        h.viewModel.start()
        val malformed = h.chat(introPublicKey = ByteArray(1))
        h.repository.record(malformed)

        h.viewModel.accept(malformed.id)

        assertEquals(PendingChatError.MalformedInvite, h.viewModel.lastError.value)
        assertTrue(h.sender.calls.isEmpty())
        assertEquals(PendingChatsViewModel.State.Offered, h.viewModel.rows.value.single().state)

        h.viewModel.dismissError()
        assertNull(h.viewModel.lastError.value)
    }

    @Test
    fun dismiss_dropsTheRow() = runTest {
        val h = harness()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)

        h.viewModel.dismiss(chat.id)

        assertTrue(h.viewModel.rows.value.isEmpty())
    }

    // ─── Verification overlay ────────────────────────────────────

    @Test
    fun verification_winsOverTheStoredStatus() = runTest {
        // Past the founder's approval, what the person is waiting on has
        // moved on from them — showing "waiting on the founder" then
        // would name someone who has already done their part.
        val h = harness()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)
        h.repository.markRequested(chat.id)

        h.verifications.record(verification(chat.groupIdHex, PendingGroupVerification.Status.UNREACHABLE))

        assertEquals(
            PendingChatsViewModel.State.FounderUnreachable,
            h.viewModel.rows.value.single().state,
        )
        assertEquals("one row, not one per source", 1, h.viewModel.rows.value.size)
    }

    @Test
    fun anUnansweredOffer_keepsItsAcceptEvenWhileVerifying() = runTest {
        // A verification describes a join that was asked for. An offer
        // has asked for nothing, and letting the overlay win there took
        // the Accept button away with no way to say yes.
        val h = harness()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)

        h.verifications.record(verification(chat.groupIdHex, PendingGroupVerification.Status.UNREACHABLE))

        assertEquals(PendingChatsViewModel.State.Offered, h.viewModel.rows.value.single().state)
    }

    @Test
    fun retry_onAStuckVerification_redrivesTheVerifier() = runTest {
        val h = harness()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)
        h.repository.markRequested(chat.id)
        h.verifications.record(
            verification(chat.groupIdHex, PendingGroupVerification.Status.CHAIN_UNREACHABLE),
        )

        h.viewModel.retry(chat.id)

        assertEquals(listOf(chat.groupIdHex), h.verifierRetries)
        assertTrue("a verification retry is not a re-send", h.sender.calls.isEmpty())
    }

    @Test
    fun chainSettling_offersNoAction() = runTest {
        // It clears itself. Offering an action implies the person is
        // holding it up — so a Retry tapped here must reach neither the
        // verifier nor the sender.
        val h = harness()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)
        h.repository.markRequested(chat.id)
        h.verifications.record(
            verification(chat.groupIdHex, PendingGroupVerification.Status.CHAIN_SETTLING),
        )

        h.viewModel.retry(chat.id)

        assertEquals(
            PendingChatsViewModel.State.ChainSettling,
            h.viewModel.rows.value.single().state,
        )
        assertTrue(h.verifierRetries.isEmpty())
        assertTrue(h.sender.calls.isEmpty())
    }

    @Test
    fun verificationWithNoOffer_stillGetsARow() = runTest {
        // A stale invitation replayed onto a device that never asked in
        // this install. Without a row the group is stuck forever, hidden
        // from the list by design, with no screen left to surface it.
        val h = harness()
        h.viewModel.start()

        h.verifications.record(verification("ab".repeat(32), PendingGroupVerification.Status.UNREACHABLE))

        val row = h.viewModel.rows.value.single()
        assertEquals(PendingChatsViewModel.State.FounderUnreachable, row.state)
        assertTrue("there is no stored offer under it to drop", !row.isDismissable)
    }

    @Test
    fun verificationWithNoOffer_isIdentifiedLikeEveryOtherRow() = runTest {
        // Its thread asks `materializedGroupId(rowId)` to tell "the
        // group landed, open it" from "the row was dismissed, go back".
        // Materialization is recorded under `<hex>:<owner>`, so a row
        // that identified itself by bare hex was never found there and
        // the chat's arrival looked exactly like a dismissal — Back,
        // with the chat sitting in the list behind it.
        val h = harness()
        h.viewModel.start()
        val hex = "ab".repeat(32)
        h.verifications.record(verification(hex, PendingGroupVerification.Status.VERIFYING))

        val row = h.viewModel.rows.value.single()
        assertEquals("$hex:${owner.value}", row.id)

        h.groups.insert(group(hex))
        h.verifications.resolveMaterialized(setOf(hex))

        assertTrue("the wait is over, so the row goes", h.viewModel.rows.value.isEmpty())
        assertEquals(
            "and the thread behind it opens the chat instead of navigating Back",
            hex,
            h.viewModel.materializedGroupId(row.id),
        )
    }

    // ─── Ask again ───────────────────────────────────────────────

    @Test
    fun askAgain_onAWaitingRow_resendsToTheFounder() = runTest {
        // A request can be sent and never answered — a revoked link, or
        // one that died in a relay. Before this there was no way out but
        // swiping the row away.
        val h = harness()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)
        h.repository.markRequested(chat.id)
        assertTrue(h.viewModel.rows.value.single().state.isRetryable)

        h.viewModel.retry(chat.id)

        assertEquals(1, h.sender.calls.size)
        assertTrue("the founder is who this wait belongs to", h.verifierRetries.isEmpty())
    }

    @Test
    fun askAgain_whileVerifying_redrivesTheVerifierInstead() = runTest {
        // Past the approval the wait has changed hands: asking the
        // founder again would achieve nothing, because they already said
        // yes.
        val h = harness()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)
        h.repository.markRequested(chat.id)
        h.verifications.record(verification(chat.groupIdHex, PendingGroupVerification.Status.VERIFYING))

        h.viewModel.retry(chat.id)

        assertEquals(listOf(chat.groupIdHex), h.verifierRetries)
        assertTrue(h.sender.calls.isEmpty())
    }

    // ─── End of the wait ─────────────────────────────────────────

    @Test
    fun theRowDisappearsWhenTheGroupLandsAndTheGroupIsRemembered() = runTest {
        // The pending screen reads the mapping to know where to go, and
        // it is derived from the group snapshot rather than from the
        // rows — which may not have arrived when the first emission
        // lands.
        val h = harness()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)

        h.groups.insert(group(chat.groupIdHex))

        assertTrue(h.viewModel.rows.value.isEmpty())
        assertEquals(chat.groupIdHex, h.viewModel.materializedGroupId(chat.id))
    }

    @Test
    fun ordinaryGroupsDoNotAccumulateInTheMaterializedHandoff() = runTest {
        val h = harness()
        h.viewModel.start()
        val existing = group("cd".repeat(32))

        h.groups.insert(existing)

        assertNull(
            h.viewModel.materializedGroupId("${existing.id}:${existing.ownerIdentityId}"),
        )
    }

    // ─── Joining from a link ─────────────────────────────────────

    @Test
    fun join_recordsARowAndSendsWithoutAsking() = runTest {
        val h = harness()
        h.viewModel.start()

        val outcome = h.viewModel.join(h.capability())

        assertEquals(PendingChatsViewModel.JoinOutcome.Waiting(h.chat().id), outcome)
        assertEquals(1, h.sender.calls.size)
        assertEquals(PendingChatsViewModel.State.Waiting, h.viewModel.rows.value.single().state)
    }

    @Test
    fun join_twiceOnTheSameLink_doesNotAskTwice() = runTest {
        val h = harness()
        h.viewModel.start()

        h.viewModel.join(h.capability())
        val second = h.viewModel.join(h.capability())

        assertEquals(PendingChatsViewModel.JoinOutcome.Waiting(h.chat().id), second)
        assertEquals("a second tap is not a second request", 1, h.sender.calls.size)
    }

    @Test
    fun join_onAnUnansweredOffer_sendsInsteadOfAskingAgain() = runTest {
        // The dispatcher got there first. Tapping the link *is* the
        // answer, so the row must not be left sitting at Offered asking
        // for it a second time.
        val h = harness()
        h.viewModel.start()
        val offered = h.chat()
        h.repository.record(offered)

        val outcome = h.viewModel.join(h.capability())

        assertEquals(PendingChatsViewModel.JoinOutcome.Waiting(offered.id), outcome)
        assertEquals(1, h.sender.calls.size)
        assertEquals(PendingChatsViewModel.State.Waiting, h.viewModel.rows.value.single().state)
    }

    @Test
    fun join_whenAlreadyAMember_opensTheChatInstead() = runTest {
        val h = harness()
        val capability = h.capability()
        val hex = capability.groupId.joinToString("") { "%02x".format(it) }
        h.groups.insert(group(hex))
        h.viewModel.start()

        val outcome = h.viewModel.join(capability)

        assertEquals(PendingChatsViewModel.JoinOutcome.AlreadyJoined(hex), outcome)
        assertTrue(h.sender.calls.isEmpty())
    }

    @Test
    fun join_withNoIdentity_saysSoRatherThanWaitingSilently() = runTest {
        val h = harness(owner = null)
        h.viewModel.start()

        val outcome = h.viewModel.join(h.capability())

        assertEquals(
            PendingChatsViewModel.JoinOutcome.Failed(
                PendingChatsViewModel.JoinOutcome.FailureReason.NO_IDENTITY,
            ),
            outcome,
        )
        assertTrue(h.sender.calls.isEmpty())
        assertNotNull(h.viewModel)
    }

    @Test
    fun join_returnsWhileTheRelaySendIsStillInFlight() = runTest {
        val h = harness()
        h.viewModel.start()
        h.sender.gate = CompletableDeferred()

        val outcome = h.viewModel.join(h.capability())

        assertEquals(PendingChatsViewModel.JoinOutcome.Waiting(h.chat().id), outcome)
        assertTrue("the send is in flight", h.viewModel.rows.value.single().isSending)
        assertEquals(1, h.sender.calls.size)

        h.sender.gate?.complete(Unit)

        assertEquals(PendingChatsViewModel.State.Waiting, h.viewModel.rows.value.single().state)
    }

    // ─── Harness ─────────────────────────────────────────────────

    private suspend fun harness(owner: IdentityId? = this.owner): Harness {
        val repository = PendingChatRepository(InMemoryPendingChatStore())
        val verifications = PendingVerificationStore()
        val groups = GroupRepository(
            store = InMemoryGroupStore(),
            identity = FakeActiveIdentityProvider(owner ?: IdentityId("nobody")),
            scope = CoroutineScope(dispatcher),
        )
        groups.start()
        repository.setCurrentIdentity(owner)
        verifications.setCurrentIdentity(owner)
        val sender = SpyJoinSender()
        val retries = mutableListOf<String>()
        val viewModel = PendingChatsViewModel(
            repository = repository,
            verificationStore = verifications,
            groupRepository = groups,
            submitJoin = sender::send,
            displayLabel = { _ -> "Bob" },
            retryVerification = { hex -> retries.add(hex) },
            currentIdentityId = { owner },
        )
        return Harness(repository, verifications, groups, sender, retries, viewModel, owner)
    }

    private class Harness(
        val repository: PendingChatRepository,
        val verifications: PendingVerificationStore,
        val groups: GroupRepository,
        val sender: SpyJoinSender,
        val verifierRetries: MutableList<String>,
        val viewModel: PendingChatsViewModel,
        val owner: IdentityId?,
    ) {
        fun capability() = IntroCapability(
            introPublicKey = ByteArray(32) { 0x44 },
            groupId = ByteArray(32) { 0x11 },
            groupName = "Maple Garden",
        )

        fun chat(introPublicKey: ByteArray = ByteArray(32) { 0x44 }) = PendingChat(
            groupId = ByteArray(32) { 0x11 },
            ownerIdentityId = owner ?: IdentityId("nobody"),
            introPublicKey = introPublicKey,
            groupName = "Maple Garden",
            inviterAlias = "Alice",
            invitationMessage = "come in",
            receivedAt = Instant.ofEpochSecond(1_000),
            status = PendingChat.Status.Offered,
            offerReceivedAt = Instant.ofEpochSecond(1_000),
        )
    }

    private fun verification(groupIdHex: String, status: PendingGroupVerification.Status) =
        PendingGroupVerification(
            groupIdHex = groupIdHex,
            ownerIdentityId = owner,
            groupName = "Maple Garden",
            status = status,
            receivedAt = Instant.ofEpochSecond(1_000),
        )

    private fun group(hex: String) = ChatGroup(
        id = hex,
        name = "Maple Garden",
        groupSecret = ByteArray(32) { 0x55 },
        createdAtMillis = 0L,
        members = emptyList(),
        memberProfiles = emptyMap(),
        epoch = 1uL,
        salt = ByteArray(32) { 0x66 },
        commitment = ByteArray(32) { 0x77 },
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = "aa".repeat(48),
        isPublishedOnChain = true,
        ownerIdentityId = owner.value,
    )
}

/** Stands in for `JoinRequestSender`. */
private class SpyJoinSender {
    data class Call(val capability: IntroCapability, val label: String)

    val calls = mutableListOf<Call>()
    var outcome: JoinRequestSender.Outcome = JoinRequestSender.Outcome.Sent

    /** Holds a send open, so a test can interrupt one mid-flight. */
    var gate: CompletableDeferred<Unit>? = null

    suspend fun send(
        capability: IntroCapability,
        label: String,
        @Suppress("UNUSED_PARAMETER") owner: IdentityId,
    ): JoinRequestSender.Outcome {
        calls.add(Call(capability, label))
        gate?.await()
        return outcome
    }
}
