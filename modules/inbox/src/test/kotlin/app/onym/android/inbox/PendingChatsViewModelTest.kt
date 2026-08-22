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
import org.junit.Assert.assertFalse
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

        h.acceptThroughTheScreen(chat.id)

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

        h.acceptThroughTheScreen(chat.id)

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

        h.acceptThroughTheScreen(chat.id)

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

        h.acceptThroughTheScreen(chat.id)

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

        h.acceptThroughTheScreen(malformed.id)

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
        //
        // Seeded through the screen, because that is the only way a row
        // reaches Requested — and the name it asked under comes with it.
        val h = harness()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)
        h.acceptThroughTheScreen(chat.id)
        assertTrue(h.viewModel.rows.value.single().state.isRetryable)

        h.viewModel.retry(chat.id)

        assertEquals(2, h.sender.calls.size)
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
    fun aLinkWithRules_showsThemAndSignsTheOnesItShowed() = runTest {
        // The screen shows this text and Send signs it — the same
        // string, because a signature over anything else is not an
        // agreement to what was read.
        val h = harness()
        h.viewModel.start()
        val confirm = h.viewModel.prepareJoin(h.capability(rules = "Be kind."))
            as PendingChatsViewModel.JoinDestination.Confirm
        assertEquals("Be kind.", confirm.confirmation.rules)

        h.viewModel.confirmJoin(confirm.confirmation, "Bobby")

        assertEquals("Be kind.", h.sender.calls.single().agreedRules)
    }

    @Test
    fun aLinksRulesWinOverAStoredOffers() = runTest {
        // Both should say the same thing; when they don't, the rules the
        // person is about to read have to be the ones that came with the
        // invitation they actually opened.
        val h = harness()
        h.viewModel.start()
        h.repository.record(h.chat().copy(invitationMessage = "Older wording."))

        val confirm = h.viewModel.prepareJoin(h.capability(rules = "Newer wording."))
            as PendingChatsViewModel.JoinDestination.Confirm

        assertEquals("Newer wording.", confirm.confirmation.rules)
    }

    @Test
    fun anOfferWithRules_carriesThemIntoItsConfirmation() = runTest {
        // A pushed invitation has no capability to read from — the rules
        // live on the stored offer.
        val h = harness()
        h.viewModel.start()
        val offered = h.chat().copy(invitationMessage = "House rules.")
        h.repository.record(offered)

        val confirmation = h.viewModel.prepareAccept(offered.id)

        assertEquals("House rules.", confirmation?.rules)
    }

    @Test
    fun askAgain_reSignsTheSameRules() = runTest {
        // Months later the capability is gone, and a re-send that signed
        // different words would arrive as agreement to something the
        // person never read.
        val h = harness()
        h.viewModel.start()
        val confirm = h.viewModel.prepareJoin(h.capability(rules = "Be kind."))
            as PendingChatsViewModel.JoinDestination.Confirm
        h.viewModel.confirmJoin(confirm.confirmation, "Bobby")

        h.viewModel.retry(h.chat().id)

        assertEquals(listOf("Be kind.", "Be kind."), h.sender.calls.map { it.agreedRules })
    }

    @Test
    fun aLinkWithoutRules_fallsBackToAStoredOffers() = runTest {
        // The other half of the resolution: a link that carries nothing
        // must not erase the rules a pushed offer already delivered, or
        // the tick disappears and the founder reads a joiner who
        // declined.
        val h = harness()
        h.viewModel.start()
        h.repository.record(h.chat().copy(invitationMessage = "House rules."))

        val confirm = h.viewModel.prepareJoin(h.capability())
            as PendingChatsViewModel.JoinDestination.Confirm

        assertEquals("House rules.", confirm.confirmation.rules)
    }

    @Test
    fun aLinkOverAStoredOffer_signsWhatTheScreenShowed() = runTest {
        // The row already exists, so the send goes down the
        // already-present path — which used to read the rules back off
        // the row and sign the offer's older wording instead of the
        // words on screen.
        val h = harness()
        h.viewModel.start()
        h.repository.record(h.chat().copy(invitationMessage = "Older wording."))
        val confirm = h.viewModel.prepareJoin(h.capability(rules = "Newer wording."))
            as PendingChatsViewModel.JoinDestination.Confirm

        h.viewModel.confirmJoin(confirm.confirmation, "Bobby")

        assertEquals("Newer wording.", h.sender.calls.single().agreedRules)
    }

    @Test
    fun askAgainAfterALinkOverAnOffer_reSignsWhatWasRead() = runTest {
        // And the row is brought up to it, so every later "Ask again" —
        // which has only the row to read from — attests to the same
        // words rather than to the draft nobody saw.
        val h = harness()
        h.viewModel.start()
        h.repository.record(h.chat().copy(invitationMessage = "Older wording."))
        val confirm = h.viewModel.prepareJoin(h.capability(rules = "Newer wording."))
            as PendingChatsViewModel.JoinDestination.Confirm
        h.viewModel.confirmJoin(confirm.confirmation, "Bobby")

        h.viewModel.retry(h.chat().id)

        assertEquals(
            listOf("Newer wording.", "Newer wording."),
            h.sender.calls.map { it.agreedRules },
        )
    }

    @Test
    fun rulesThatAreOnlyWhitespace_areNoRulesAtAll() = runTest {
        // "   " is non-null, so it drew an empty card over a mandatory
        // tick and then signed nothing, because the sender normalizes
        // before signing. The screen and the send agree now.
        val h = harness()
        h.viewModel.start()
        h.repository.record(h.chat().copy(invitationMessage = "   \n  "))

        val confirmation = h.viewModel.prepareAccept(h.chat().id)
        assertNull(confirmation?.rules)
        h.viewModel.confirmJoin(confirmation!!, "Bobby")

        assertNull(h.sender.calls.single().agreedRules)
    }

    @Test
    fun anOffersGreeting_isNotDrawnTwice() = runTest {
        // A group's invitation message *is* its rules since the rename,
        // so the confirmation must not also offer it as a greeting.
        val h = harness()
        h.viewModel.start()
        h.repository.record(h.chat().copy(invitationMessage = "House rules."))

        val confirmation = h.viewModel.prepareAccept(h.chat().id)

        assertEquals("House rules.", confirmation?.rules)
        assertNull(confirmation?.invitationMessage)
    }

    @Test
    fun theCapabilityHandedToTheSender_carriesTheRulesBeingSigned() = runTest {
        // Not decoration: the sender refuses a capability that carries
        // rules with no agreement, and rebuilding it without them made
        // that guard unreachable from every path here.
        val h = harness()
        h.viewModel.start()
        val confirm = h.viewModel.prepareJoin(h.capability(rules = "Be kind."))
            as PendingChatsViewModel.JoinDestination.Confirm

        h.viewModel.confirmJoin(confirm.confirmation, "Bobby")

        assertEquals("Be kind.", h.sender.calls.single().capability.rules)
    }

    @Test
    fun aSendThatNeverGotAnAgreement_isNotWorthAskingAgain() = runTest {
        // The one send failure retrying cannot fix: nothing left the
        // device and nothing will until the caller passes what the
        // person agreed to.
        val h = harness()
        h.viewModel.start()
        h.sender.outcome = JoinRequestSender.Outcome.RulesAgreementMissing
        val confirm = h.viewModel.prepareJoin(h.capability(rules = "Be kind."))
            as PendingChatsViewModel.JoinDestination.Confirm

        h.viewModel.confirmJoin(confirm.confirmation, "Bobby")

        val row = h.viewModel.rows.value.single()
        assertEquals(
            PendingChatsViewModel.State.SendFailed(PendingChat.SendFailure.RULES_MISSING),
            row.state,
        )
        assertFalse("Ask again would ship the same nothing", row.state.isRetryable)
    }

    @Test
    fun aGroupWithoutRules_signsNothing() = runTest {
        val h = harness()
        h.viewModel.start()
        val confirm = h.viewModel.prepareJoin(h.capability())
            as PendingChatsViewModel.JoinDestination.Confirm
        assertNull(confirm.confirmation.rules)

        h.viewModel.confirmJoin(confirm.confirmation, "Bobby")

        assertNull(h.sender.calls.single().agreedRules)
    }

    @Test
    fun aTappedLink_recordsNothingAndSendsNothing() = runTest {
        // The rule this screen exists for: MainActivity is exported, so
        // anything on the device — or a browsable link — can deliver a
        // capability. Delivery must not disclose this identity's name or
        // keys, and must not leave a row behind either.
        val h = harness()
        h.viewModel.start()

        val destination = h.viewModel.prepareJoin(h.capability())

        val confirm = destination as? PendingChatsViewModel.JoinDestination.Confirm
        assertNotNull("a link must resolve to a screen", confirm)
        assertEquals("Maple Garden", confirm!!.confirmation.groupName)
        assertTrue(
            confirm.confirmation.introPublicKey.contentEquals(h.capability().introPublicKey),
        )
        assertTrue(h.sender.calls.isEmpty())
        assertTrue(
            "nothing may be persisted before a person says so",
            h.repository.currentChats().isEmpty(),
        )
        assertTrue(h.viewModel.rows.value.isEmpty())
    }

    @Test
    fun confirmingALink_recordsTheRowAndSendsUnderTheTypedName() = runTest {
        val h = harness()
        h.viewModel.start()
        val confirm = h.viewModel.prepareJoin(h.capability())
            as PendingChatsViewModel.JoinDestination.Confirm
        assertEquals(
            "pre-filled with the identity's own alias",
            "Bob",
            confirm.confirmation.suggestedLabel,
        )

        val outcome = h.viewModel.confirmJoin(confirm.confirmation, "Bobby")

        assertEquals(PendingChatsViewModel.JoinDestination.Waiting(h.chat().id), outcome)
        assertEquals(listOf("Bobby"), h.sender.calls.map { it.label })
        assertEquals(PendingChatsViewModel.State.Waiting, h.viewModel.rows.value.single().state)
    }

    @Test
    fun theNameAskedUnderIsRememberedForTheNextAsk() = runTest {
        // A re-send that fell back to the identity's alias would arrive
        // from a stranger — the founder is deciding partly on the name.
        val h = harness()
        h.viewModel.start()
        val confirm = h.viewModel.prepareJoin(h.capability())
            as PendingChatsViewModel.JoinDestination.Confirm
        h.viewModel.confirmJoin(confirm.confirmation, "Bobby")

        h.viewModel.retry(h.chat().id)

        assertEquals(listOf("Bobby", "Bobby"), h.sender.calls.map { it.label })
    }

    @Test
    fun aSecondLinkAfterAsking_opensTheWaitWithoutAskingAgain() = runTest {
        val h = harness()
        h.viewModel.start()
        val confirm = h.viewModel.prepareJoin(h.capability())
            as PendingChatsViewModel.JoinDestination.Confirm
        h.viewModel.confirmJoin(confirm.confirmation, "Bobby")

        val second = h.viewModel.prepareJoin(h.capability())

        assertEquals(PendingChatsViewModel.JoinDestination.Waiting(h.chat().id), second)
        assertEquals("a second delivery is not a second request", 1, h.sender.calls.size)
    }

    @Test
    fun aLinkOnAnUnansweredOffer_confirmsAgainstTheOffersDetails() = runTest {
        // The dispatcher got there first, so the screen can name who
        // invited and what they wrote — and confirming answers that
        // offer rather than starting a second one.
        val h = harness()
        h.viewModel.start()
        val offered = h.chat()
        h.repository.record(offered)

        val confirm = h.viewModel.prepareJoin(h.capability())
            as PendingChatsViewModel.JoinDestination.Confirm
        assertEquals("Alice", confirm.confirmation.inviterAlias)
        h.viewModel.confirmJoin(confirm.confirmation, "Bobby")

        assertEquals(1, h.sender.calls.size)
        assertEquals("one waiting room, not two", 1, h.viewModel.rows.value.size)
        assertEquals(PendingChatsViewModel.State.Waiting, h.viewModel.rows.value.single().state)
    }

    @Test
    fun aLinkIntoAChatYouAreIn_opensItWithoutDisclosingAnything() = runTest {
        val h = harness()
        val capability = h.capability()
        val hex = capability.groupId.joinToString("") { "%02x".format(it) }
        h.groups.insert(group(hex))
        h.viewModel.start()

        val outcome = h.viewModel.prepareJoin(capability)

        assertEquals(PendingChatsViewModel.JoinDestination.AlreadyJoined(hex), outcome)
        assertTrue(h.sender.calls.isEmpty())
    }

    @Test
    fun confirmJoin_withNoIdentity_reportsItRatherThanClosingSilently() = runTest {
        // The person tapping the button that discloses their keys is the
        // last one who should be told nothing.
        val h = harness(owner = null)
        h.viewModel.start()
        val confirmation = PendingChatsViewModel.JoinConfirmation(
            rowId = "row",
            owner = IdentityId("owner"),
            identityName = "Bob",
            rules = null,
            groupIdHex = "11".repeat(32),
            groupName = "Maple Garden",
            inviterAlias = "",
            invitationMessage = null,
            introPublicKey = ByteArray(32) { 0x44 },
            suggestedLabel = "Bob",
            origin = PendingChatsViewModel.JoinConfirmation.Origin.Link(h.capability()),
        )

        val outcome = h.viewModel.confirmJoin(confirmation, "Bobby")

        assertEquals(
            PendingChatsViewModel.JoinDestination.Failed(
                PendingChatsViewModel.JoinOutcome.FailureReason.NO_IDENTITY,
            ),
            outcome,
        )
        assertTrue(h.sender.calls.isEmpty())
    }

    @Test
    fun confirmJoin_onAnOfferThatIsGone_reportsItRatherThanSending() = runTest {
        val h = harness()
        h.viewModel.start()
        val confirmation = PendingChatsViewModel.JoinConfirmation(
            rowId = "gone",
            owner = IdentityId("owner"),
            identityName = "Bob",
            rules = null,
            groupIdHex = "11".repeat(32),
            groupName = "Maple Garden",
            inviterAlias = "Alice",
            invitationMessage = null,
            introPublicKey = ByteArray(32) { 0x44 },
            suggestedLabel = "Bob",
            origin = PendingChatsViewModel.JoinConfirmation.Origin.Offer("gone"),
        )

        val outcome = h.viewModel.confirmJoin(confirmation, "Bobby")

        assertEquals(
            PendingChatsViewModel.JoinDestination.Failed(
                PendingChatsViewModel.JoinOutcome.FailureReason.NOT_SAVED,
            ),
            outcome,
        )
        assertTrue(h.sender.calls.isEmpty())
    }

    @Test
    fun prepareAccept_onARowThatIsGone_offersNothing() = runTest {
        // The Accept button's edge: a re-delivery or a second tap can
        // move the row on between render and tap. The thread re-renders
        // without the button, so a null here is the screen already
        // telling the truth.
        val h = harness()
        h.viewModel.start()

        assertNull(h.viewModel.prepareAccept("nobody:home"))
    }

    @Test
    fun askAgain_afterTheLabelWriteFailed_stillAsks() = runTest {
        // An encryption failure drops the label silently. Bailing on the
        // missing label left an enabled button that did nothing forever;
        // asking again under the identity's current name is the lesser
        // wrong.
        val h = harness(store = LabelDroppingStore())
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)
        h.acceptThroughTheScreen(chat.id)

        h.viewModel.retry(chat.id)

        assertEquals(2, h.sender.calls.size)
        assertEquals("Bob", h.sender.calls.last().label)
    }

    @Test
    fun aLinkOnARowWhoseSendFailed_confirmsAgainRatherThanCallingItAsked() = runTest {
        // A failed row has nothing outstanding, so the link is allowed
        // to ask again — and asking again is a fresh disclosure, which
        // means the screen, not a silent re-send.
        val h = harness()
        h.viewModel.start()
        val chat = h.chat()
        h.repository.record(chat)
        h.repository.markFailed(chat.id, PendingChat.SendFailure.TRANSPORT)

        val destination = h.viewModel.prepareJoin(h.capability())

        assertTrue(
            "a failed row is confirmable, not already-asked",
            destination is PendingChatsViewModel.JoinDestination.Confirm,
        )
        assertTrue(h.sender.calls.isEmpty())
    }

    @Test
    fun confirmingAfterTheIdentityChanged_refusesRatherThanDisclosingTheWrongKeys() = runTest {
        // The screen was opened for one identity and agreed to for that
        // one. Sending as whoever is selected by the time the button is
        // tapped would disclose keys the person was never shown.
        val h = harness()
        h.viewModel.start()
        val confirm = h.viewModel.prepareJoin(h.capability())
            as PendingChatsViewModel.JoinDestination.Confirm

        val outcome = h.viewModel.confirmJoin(
            confirm.confirmation.copy(owner = IdentityId("someone-else")),
            "Bobby",
        )

        assertEquals(
            PendingChatsViewModel.JoinDestination.Failed(
                PendingChatsViewModel.JoinOutcome.FailureReason.NO_IDENTITY,
            ),
            outcome,
        )
        assertTrue(h.sender.calls.isEmpty())
    }

    @Test
    fun aLinkWithNoIdentity_saysSoRatherThanWaitingSilently() = runTest {
        val h = harness(owner = null)
        h.viewModel.start()

        val outcome = h.viewModel.prepareJoin(h.capability())

        assertEquals(
            PendingChatsViewModel.JoinDestination.Failed(
                PendingChatsViewModel.JoinOutcome.FailureReason.NO_IDENTITY,
            ),
            outcome,
        )
        assertTrue(h.sender.calls.isEmpty())
    }

    @Test
    fun confirmJoin_returnsWhileTheRelaySendIsStillInFlight() = runTest {
        val h = harness()
        h.viewModel.start()
        h.sender.gate = CompletableDeferred()
        val confirm = h.viewModel.prepareJoin(h.capability())
            as PendingChatsViewModel.JoinDestination.Confirm

        val outcome = h.viewModel.confirmJoin(confirm.confirmation, "Bobby")

        assertEquals(PendingChatsViewModel.JoinDestination.Waiting(h.chat().id), outcome)
        assertTrue("the send is in flight", h.viewModel.rows.value.single().isSending)
        assertEquals(1, h.sender.calls.size)

        h.sender.gate?.complete(Unit)

        assertEquals(PendingChatsViewModel.State.Waiting, h.viewModel.rows.value.single().state)
    }

    // ─── Harness ─────────────────────────────────────────────────

    private suspend fun harness(
        owner: IdentityId? = this.owner,
        store: PendingChatStore = InMemoryPendingChatStore(),
    ): Harness {
        val repository = PendingChatRepository(store)
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
        /** Accept as a person performs it: open the screen, then Send. */
        suspend fun acceptThroughTheScreen(rowId: String, label: String = "Bob") {
            val confirmation = viewModel.prepareAccept(rowId) ?: return
            viewModel.confirmJoin(confirmation, label)
        }

        fun capability(rules: String? = null) = IntroCapability(
            introPublicKey = ByteArray(32) { 0x44 },
            groupId = ByteArray(32) { 0x11 },
            groupName = "Maple Garden",
            rules = rules,
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

/**
 * A store whose label write silently does nothing — what
 * `RoomPendingChatStore` does when the encryption throws. The row asks,
 * but no name sticks to it.
 */
private class LabelDroppingStore(
    private val delegate: PendingChatStore = InMemoryPendingChatStore(),
) : PendingChatStore by delegate {
    override suspend fun setJoinerLabel(id: String, label: String) = Unit
}

/** Stands in for `JoinRequestSender`. */
private class SpyJoinSender {
    data class Call(
        val capability: IntroCapability,
        val label: String,
        /** The rules text the signature covers, when the group has any. */
        val agreedRules: String? = null,
    )

    val calls = mutableListOf<Call>()
    var outcome: JoinRequestSender.Outcome = JoinRequestSender.Outcome.Sent

    /** Holds a send open, so a test can interrupt one mid-flight. */
    var gate: CompletableDeferred<Unit>? = null

    suspend fun send(
        capability: IntroCapability,
        label: String,
        @Suppress("UNUSED_PARAMETER") owner: IdentityId,
        agreedRules: String? = null,
    ): JoinRequestSender.Outcome {
        calls.add(Call(capability, label, agreedRules))
        gate?.await()
        return outcome
    }
}
