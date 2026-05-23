@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.onym.android.group

import app.onym.android.chain.SepGroupType
import app.onym.android.chain.SepTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Form-state + intent-dispatch tests for [CreateGroupViewModel]. The
 * interactor is a stub — these tests verify the VM's intent
 * routing + validation rather than the pipeline mechanics (which
 * `CreateGroupInteractorTest` covers end-to-end).
 *
 * Mirrors `CreateGroupFlowTests.swift` from onym-ios PR #26.
 */
class CreateGroupViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ─── Step 1 → Step 2 ──────────────────────────────────────────
    //
    // PR-C follow-up: the name field is no longer required — submit
    // falls back to the generated placeholder if the user leaves it
    // blank. canAdvanceToStep2 only gates on governance availability.

    @Test
    fun canAdvanceToStep2_isTrueOnFreshInit_withTyrannyGovernance() = runTest {
        val vm = makeViewModel()
        // Default governance is Tyranny → can advance immediately,
        // regardless of name (which is the placeholder until first
        // focus).
        assertTrue(vm.state.value.canAdvanceToStep2)
    }

    @Test
    fun canAdvanceToStep2_remainsTrue_evenWithBlankName() = runTest {
        val vm = makeViewModel()
        vm.setName("")
        assertTrue(vm.state.value.canAdvanceToStep2)
        vm.setName("   ")
        assertTrue(vm.state.value.canAdvanceToStep2)
    }

    @Test
    fun onlyTyranny_isSelectable_oneOnOneAndAnarchyShowSoon() = runTest {
        // The picker offers Tyranny only; OneOnOne and Anarchy render a
        // "Soon" badge and are non-interactive — mirrors the iOS
        // create-group picker. `setGovernance` enforces the same gate
        // (a tap on a disabled card is a no-op), so governance stays on
        // the default. The chain layer still supports all three (see the
        // CreateGroup*E2ETest suites) — this is purely which flavours
        // the picker exposes today.
        val vm = makeViewModel()

        vm.setGovernance(OnymUIGovernance.Tyranny)
        assertEquals(OnymUIGovernance.Tyranny, vm.state.value.governance)
        assertTrue(vm.state.value.canAdvanceToStep2)

        assertTrue("OneOnOne not offered yet", !OnymUIGovernance.OneOnOne.isAvailable)
        assertTrue("Anarchy not offered yet", !OnymUIGovernance.Anarchy.isAvailable)
        vm.setGovernance(OnymUIGovernance.OneOnOne)
        assertEquals(OnymUIGovernance.Tyranny, vm.state.value.governance)
        vm.setGovernance(OnymUIGovernance.Anarchy)
        assertEquals(OnymUIGovernance.Tyranny, vm.state.value.governance)
    }

    @Test
    fun tappedNext_advancesToStep2() = runTest {
        val vm = makeViewModel()
        vm.tappedNext()
        assertEquals(CreateGroupRoute.Step2, vm.state.value.route)
    }

    // ─── Placeholder name (PR-C follow-up) ────────────────────────

    @Test
    fun init_prePopulatesNameWithGeneratedPlaceholder() = runTest {
        val vm = makeViewModel()
        val state = vm.state.value
        assertTrue("generatedName is non-empty", state.generatedName.isNotEmpty())
        // The bound TextField value mirrors the generated placeholder
        // until the user focuses + types.
        assertEquals(state.generatedName, state.name)
        assertTrue("placeholder follows 'Adjective Noun' shape",
            state.generatedName.contains(" "))
    }

    @Test
    fun firstFocus_clearsPlaceholder() = runTest {
        val vm = makeViewModel()
        val placeholder = vm.state.value.generatedName

        vm.nameFieldFocused()

        val state = vm.state.value
        assertEquals("", state.name)
        assertEquals(placeholder, state.generatedName)
        assertTrue(state.nameFieldHasBeenFocused)
    }

    @Test
    fun secondFocus_doesNotClearUserInput() = runTest {
        val vm = makeViewModel()
        vm.nameFieldFocused()  // first focus clears
        vm.setName("Family Trip")

        vm.nameFieldFocused()  // second focus must NOT stomp

        assertEquals("Family Trip", vm.state.value.name)
    }

    @Test
    fun focus_doesNotStomp_ifNameAlreadyEdited() = runTest {
        val vm = makeViewModel()
        // The user types BEFORE the field receives focus (rare but
        // possible; e.g. paste from clipboard intent). The first
        // focus event must not blow away their input.
        vm.setName("Already Typed")
        vm.nameFieldFocused()
        assertEquals("Already Typed", vm.state.value.name)
        assertTrue(vm.state.value.nameFieldHasBeenFocused)
    }

    @Test
    fun effectiveName_fallsBackToPlaceholder_whenEmpty() = runTest {
        val vm = makeViewModel()
        vm.nameFieldFocused()
        // Field is now empty; effectiveName should resolve to the
        // placeholder so submit has something to send.
        assertEquals("", vm.state.value.name)
        assertEquals(vm.state.value.generatedName, vm.state.value.effectiveName)
    }

    @Test
    fun effectiveName_returnsTrimmedNameWhenSet() = runTest {
        val vm = makeViewModel()
        vm.nameFieldFocused()
        vm.setName("  Spaced Name  ")
        assertEquals("Spaced Name", vm.state.value.effectiveName)
    }

    // ─── InviteByKey ──────────────────────────────────────────────

    @Test
    fun addInvitee_validHex_appendsAndReturnsToStep2() = runTest {
        val vm = makeViewModel()
        vm.tappedInviteByKey()
        vm.setInviteeInput("ab".repeat(32))  // 64 chars
        vm.tappedAddInvitee()
        val state = vm.state.value
        assertEquals(1, state.invitees.size)
        assertArrayEquals(ByteArray(32) { 0xAB.toByte() }, state.invitees.single().inboxPublicKey)
        assertEquals(CreateGroupRoute.Step2, state.route)
        assertNull(state.inviteeError)
        assertEquals("", state.inviteeInput)
    }

    @Test
    fun addInvitee_emptyInput_setsError_doesNotAppend() = runTest {
        val vm = makeViewModel()
        vm.tappedAddInvitee()
        val state = vm.state.value
        assertEquals(0, state.invitees.size)
        assertNotNull(state.inviteeError)
        assertTrue("error mentions paste", state.inviteeError!!.contains("Paste"))
    }

    @Test
    fun addInvitee_wrongLength_setsError_mentionsSixtyFour() = runTest {
        val vm = makeViewModel()
        vm.setInviteeInput("abc")
        vm.tappedAddInvitee()
        val state = vm.state.value
        assertEquals(0, state.invitees.size)
        assertTrue(state.inviteeError!!.contains("64"))
    }

    @Test
    fun addInvitee_nonHex_setsError() = runTest {
        val vm = makeViewModel()
        vm.setInviteeInput("z".repeat(64))
        vm.tappedAddInvitee()
        val state = vm.state.value
        assertEquals(0, state.invitees.size)
        assertNotNull(state.inviteeError)
    }

    @Test
    fun addInvitee_stripsWhitespace() = runTest {
        val vm = makeViewModel()
        // Hex with embedded whitespace — should be cleaned before
        // the length check.
        val raw = "ab".repeat(32)
        val withSpaces = raw.toCharArray().withIndex().joinToString("") { (i, c) ->
            if (i % 8 == 0) " $c" else "$c"
        }
        vm.setInviteeInput(withSpaces)
        assertEquals(64, vm.state.value.inviteeInputCleanedLength)
        assertTrue(vm.state.value.inviteeInputIsValid)
        vm.tappedAddInvitee()
        assertEquals(1, vm.state.value.invitees.size)
    }

    @Test
    fun removeInvitee_removesByIndex() = runTest {
        val vm = makeViewModel()
        vm.setInviteeInput("aa".repeat(32))
        vm.tappedAddInvitee()
        vm.tappedInviteByKey()
        vm.setInviteeInput("bb".repeat(32))
        vm.tappedAddInvitee()
        assertEquals(2, vm.state.value.invitees.size)
        vm.removeInvitee(0)
        assertEquals(1, vm.state.value.invitees.size)
        assertArrayEquals(
            ByteArray(32) { 0xBB.toByte() },
            vm.state.value.invitees.single().inboxPublicKey,
        )
    }

    // ─── routing ──────────────────────────────────────────────────

    @Test
    fun tappedInviteByKey_clearsInputAndNavigates() = runTest {
        val vm = makeViewModel()
        vm.setInviteeInput("leftover")
        // Force a stale error in via tappedAddInvitee on garbage.
        vm.tappedAddInvitee()
        assertNotNull(vm.state.value.inviteeError)

        vm.tappedInviteByKey()

        val state = vm.state.value
        assertEquals(CreateGroupRoute.InviteByKey, state.route)
        assertEquals("", state.inviteeInput)
        assertNull(state.inviteeError)
    }

    @Test
    fun createCtaLabel_reflectsInviteeCount() = runTest {
        val vm = makeViewModel()
        assertEquals(CreateCtaLabel.Empty, vm.state.value.createCtaLabel)
        vm.setInviteeInput("aa".repeat(32))
        vm.tappedAddInvitee()
        assertEquals(CreateCtaLabel.OnePerson, vm.state.value.createCtaLabel)
        vm.tappedInviteByKey()
        vm.setInviteeInput("bb".repeat(32))
        vm.tappedAddInvitee()
        assertEquals(CreateCtaLabel.NPeople(2), vm.state.value.createCtaLabel)
    }

    // ─── OneOnOne governance ──────────────────────────────────────
    //
    // OneOnOne is gated out of the picker for now (see
    // `onlyTyranny_isSelectable_oneOnOneAndAnarchyShowSoon`), so its
    // selected-state behaviour — the exactly-one-invitee submit gate
    // ([CreateGroupState.canSubmit]) and the OneOnOneAddOther/Start/
    // TooMany CTA labels ([CreateGroupState.createCtaLabel]) — is
    // unreachable through the VM's public API while `setGovernance`
    // refuses unavailable flavours. The logic stays in CreateGroupState
    // for when the OneOnOne create-group UX is re-enabled; the three
    // selected-state tests that exercised it were removed alongside the
    // gate (recoverable from git history when OneOnOne flips back on).

    @Test
    fun tyranny_canSubmit_evenWithZeroInvitees() = runTest {
        val vm = makeViewModel()
        // Default is Tyranny — zero invitees still enables submit
        // (creator-only group is the canonical Tyranny zero case).
        assertTrue(vm.state.value.canSubmit)
    }

    // ─── close / reset ────────────────────────────────────────────

    @Test
    fun tappedDone_resetsAndCallsOnClose() = runTest {
        val vm = makeViewModel()
        var closedCount = 0
        vm.onClose = { closedCount++ }
        // Dirty the state.
        vm.nameFieldFocused()
        vm.setName("stale")
        vm.setInviteeInput("aa".repeat(32))
        vm.tappedAddInvitee()

        vm.tappedDone()

        assertEquals(1, closedCount)
        val state = vm.state.value
        assertEquals(CreateGroupRoute.Step1, state.route)
        assertTrue(state.invitees.isEmpty())
        // After reset the name is back to a fresh placeholder
        // (re-rolled), and `nameFieldHasBeenFocused` is cleared.
        assertEquals(state.generatedName, state.name)
        assertTrue("reset clears the focus flag", !state.nameFieldHasBeenFocused)
    }

    @Test
    fun tappedShareInvite_handsOffGroupIdAndClosesFlow() = runTest {
        val createdGroupId = "ab".repeat(32)
        val vm = makeViewModelReturning(makeFakeGroup(id = createdGroupId))
        var closedCount = 0
        var sharedId: String? = null
        vm.onClose = { closedCount++ }
        vm.onShareInvite = { sharedId = it }

        vm.submit()
        // Sanity: pipeline succeeded.
        assertNotNull(vm.state.value.createdGroup)

        vm.tappedShareInvite()

        // Order is "close → share" so the host can pop the create
        // screen first then push share-invite. Both happen.
        assertEquals(1, closedCount)
        assertEquals(createdGroupId, sharedId)
        // State is reset back to a fresh Step1 — re-entering the
        // create flow doesn't carry the prior group along.
        val state = vm.state.value
        assertEquals(CreateGroupRoute.Step1, state.route)
        assertNull(state.createdGroup)
    }

    @Test
    fun submit_forwardsPickedAvatarToCreator() = runTest {
        var receivedAvatar: ByteArray? = null
        var creatorCalled = false
        val avatar = ByteArray(32) { 0x7A }
        val vm = CreateGroupViewModel(
            createGroup = { _, _, _, a, _ ->
                creatorCalled = true
                receivedAvatar = a
                makeFakeGroup(id = "cd".repeat(32))
            },
        )

        vm.setAvatar(avatar)
        assertArrayEquals(avatar, vm.state.value.avatar)

        vm.submit()

        assertTrue("creator must run", creatorCalled)
        assertArrayEquals("picked avatar must reach the creator", avatar, receivedAvatar)
    }

    @Test
    fun setAvatar_nullClearsThePickedPhoto() = runTest {
        val vm = makeViewModel()
        vm.setAvatar(ByteArray(8) { 0x1 })
        assertNotNull(vm.state.value.avatar)
        vm.setAvatar(null)
        assertNull(vm.state.value.avatar)
    }

    @Test
    fun tappedShareInvite_isNoOpWhenNoGroupCreated() = runTest {
        val vm = makeViewModel()
        var closedCount = 0
        var sharedId: String? = null
        vm.onClose = { closedCount++ }
        vm.onShareInvite = { sharedId = it }

        vm.tappedShareInvite()

        // Without a createdGroup the call is a no-op — protects
        // against a stray tap before submit() resolves.
        assertEquals(0, closedCount)
        assertNull(sharedId)
    }

    @Test
    fun cancelFromError_closesAndResets() = runTest {
        val vm = makeViewModel()
        var closedCount = 0
        vm.onClose = { closedCount++ }
        vm.nameFieldFocused()
        vm.setName("soon-to-be-discarded")

        vm.cancelFromError()

        assertEquals(1, closedCount)
        val state = vm.state.value
        assertEquals(CreateGroupRoute.Step1, state.route)
        assertEquals(state.generatedName, state.name)
    }

    @Test
    fun tappedDismissError_clearsErrorAndReturnsToStep2() = runTest {
        val vm = makeViewModel()
        // There's no direct error setter, so we exercise the dismiss
        // path on a clean state: tappedDismissError clears any error and
        // unconditionally lands on Step2.
        vm.tappedDismissError()
        assertEquals(CreateGroupRoute.Step2, vm.state.value.route)
        assertNull(vm.state.value.error)
        yield()
    }

    // ─── helpers ──────────────────────────────────────────────────

    /** None of these tests call `tappedCreate`, so the createGroup
     *  lambda just throws if reached — the screen flow is still fully
     *  exercised via intent dispatch. */
    private fun makeViewModel(): CreateGroupViewModel = CreateGroupViewModel(
        createGroup = { _, _, _, _, _ ->
            error("createGroup must not be invoked from VM tests")
        },
    )

    /** For tests that need a populated [CreateGroupState.createdGroup].
     *  The lambda ignores its inputs and returns a canned [ChatGroup]
     *  so the VM can be driven through `submit()` without standing up
     *  the real interactor. */
    private fun makeViewModelReturning(group: ChatGroup): CreateGroupViewModel =
        CreateGroupViewModel(
            createGroup = { _, _, _, _, _ -> group },
        )

    private fun makeFakeGroup(id: String): ChatGroup = ChatGroup(
        id = id,
        name = "Fake group",
        groupSecret = ByteArray(32) { 0x11 },
        createdAtMillis = 1_700_000_000_000L,
        members = emptyList(),
        epoch = 0uL,
        salt = ByteArray(32) { 0x22 },
        commitment = null,
        tier = SepTier.SMALL,
        groupType = SepGroupType.TYRANNY,
        adminPubkeyHex = null,
        isPublishedOnChain = false,
        ownerIdentityId = "test-identity",
    )
}
