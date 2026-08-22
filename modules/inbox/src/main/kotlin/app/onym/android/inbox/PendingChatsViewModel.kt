package app.onym.android.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.onym.android.group.GroupRepository
import app.onym.android.identity.IdentityId
import app.onym.android.group.IntroCapability
import app.onym.android.group.JoinRequestSender
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Drives the chats a person is waiting to be let into. It replaces the
 * Invitations screen: there is no separate surface any more, so this
 * feeds rows straight into the chats list and the pending thread behind
 * them.
 *
 * It merges three sources, because the wait has three owners:
 *
 *  - [PendingChatRepository] — the offer, and whether we have asked yet;
 *  - [PendingVerificationStore] — a group that was approved but can't be
 *    verified against chain state yet ([GroupStateVerifier] owns that
 *    state machine; nothing here duplicates it);
 *  - [GroupRepository] — the end of the wait. When the group lands, the
 *    pending row is consumed and the real chat takes its place, opening
 *    on the "You joined X" notice the dispatcher already mints.
 *
 * A verification with no pending row of its own still gets a row here.
 * That case is a stale invitation replayed by a relay onto a device that
 * never asked in this install, and it is the one the old screen existed
 * to show: without a row it would be a group stuck forever, hidden from
 * the list by design and with no screen left to surface it.
 *
 * Mirrors `PendingChatsFlow` in onym-ios.
 */
class PendingChatsViewModel(
    private val repository: PendingChatRepository,
    private val verificationStore: PendingVerificationStore,
    private val groupRepository: GroupRepository,
    /** Seals + sends a join request for the given capability — the same
     *  [JoinRequestSender] the deeplink path uses. */
    private val submitJoin: suspend (IntroCapability, String, IdentityId) -> JoinRequestSender.Outcome,
    /**
     * The joiner's display label.
     *
     * Asked of the identity repository, for the same reason
     * [currentIdentityId] is: a link that launches the app sends its
     * request before any screen has populated a UI-level identity cache,
     * and reading that copy shipped the request with an empty name — the
     * founder seeing an unnamed stranger asking to come in.
     */
    private val displayLabel: suspend (IdentityId) -> String,
    /** Re-drive a stuck verification ([GroupStateVerifier.retry]). */
    private val retryVerification: suspend (String) -> Unit,
    /** The identity a link tapped right now would join as. */
    private val currentIdentityId: suspend () -> IdentityId?,
) : ViewModel() {

    /**
     * What a row is waiting on. Prose lives in the screen — the same
     * "structured data, not prose" rule the system notices follow, so
     * re-wording the copy touches one file.
     */
    sealed interface State {
        /** A pushed offer nobody has answered. Accept sends the request. */
        data object Offered : State

        /** Asked, and waiting on the founder — or on the verification
         *  round-trip that follows their approval. One state, because
         *  they are one wait to the person doing the waiting. */
        data object Waiting : State

        /** The group's anchoring transaction hasn't settled yet. Not
         *  stuck, early: it clears itself, so no Retry. */
        data object ChainSettling : State

        /** The founder couldn't be reached for the current-state reply. */
        data object FounderUnreachable : State

        /** This device couldn't read the chain. */
        data object ChainUnreachable : State

        /** This device has no relayer or contract binding yet — usually
         *  a cold-launch race rather than a wrong setting. */
        data object ChainNotConfigured : State

        /** The join request itself couldn't be sent. */
        data class SendFailed(val failure: PendingChat.SendFailure) : State

        /**
         * Whether this state has an action behind it — the Retry (or
         * "Ask again") the pending thread offers.
         *
         * [Waiting] is included, and that is the point: a request that
         * was sent and never answered had no way out at all. The link
         * may have been revoked, or the request may have died in a
         * relay, and re-tapping the link is a deliberate no-op for a row
         * that already asked. Asking again is cheap and cannot spam:
         * [app.onym.android.group.JoinRequestApprover] collapses repeats
         * by (joiner, group), and a decline stays declined.
         *
         * [ChainSettling] is excluded on purpose: offering an action for
         * something that resolves itself implies the person is holding
         * it up. [Offered] is excluded because its action is Accept.
         */
        val isRetryable: Boolean
            get() = when (this) {
                FounderUnreachable, ChainUnreachable, ChainNotConfigured, Waiting -> true
                // Every send failure can be asked again except the one
                // that never got as far as sending: a missing agreement
                // stays missing however many times the button is tapped.
                is SendFailed -> failure != PendingChat.SendFailure.RULES_MISSING
                Offered, ChainSettling -> false
            }
    }

    /** One row in the chats list, and the whole content of the pending
     *  thread behind it. */
    data class Row(
        val id: String,
        val groupIdHex: String,
        /** Null when the invite carried no name — the screen supplies
         *  the placeholder, so history isn't frozen into one language. */
        val name: String?,
        val inviterAlias: String,
        val invitationMessage: String?,
        val receivedAt: Instant,
        val state: State,
        /** True while this row's Accept is in flight. */
        val isSending: Boolean,
        /** Whether the person can swipe this row away. False for a row
         *  synthesised from a verification alone: there is no stored
         *  offer under it to drop, and the group is still on its way, so
         *  a swipe that silently did nothing would be worse than no
         *  swipe at all. */
        val isDismissable: Boolean,
    )

    /**
     * What a tapped invite link (or scanned QR) resolves to.
     *
     * Note what is *not* here: sending. A link arrives through an
     * exported entry point — any app on the device can open it, and so
     * can a page in a browser — so the arrival is a request to show
     * something, never permission to speak. Every case below is
     * therefore a screen; the request goes out from [confirmJoin],
     * which only a person can reach.
     */
    sealed interface JoinDestination {
        /** Already a member — the link was an old one, or a second tap. */
        data class AlreadyJoined(val groupIdHex: String) : JoinDestination

        /** This device has already asked. Nothing more to send, and
         *  nothing more to disclose: open the wait. */
        data class Waiting(val rowId: String) : JoinDestination

        /** Show the confirmation screen. */
        data class Confirm(val confirmation: JoinConfirmation) : JoinDestination

        /** Nothing could be resolved, and the caller has to say so. */
        data class Failed(val reason: JoinOutcome.FailureReason) : JoinDestination
    }

    /**
     * Everything the confirmation screen shows, and everything
     * [confirmJoin] needs to act on it.
     *
     * It exists so the screen can name *who* is about to learn *what*
     * before anything leaves the device: the group, the person who
     * invited (when one did), and the invite key the request would be
     * sealed to.
     */
    data class JoinConfirmation(
        /** The row this will create or act on — `<group hex>:<owner>`. */
        val rowId: String,
        /**
         * The identity this screen was opened for.
         *
         * Carried rather than re-resolved at Send, because the identity
         * can be switched while the screen is open — and Send would then
         * disclose a *different* identity's keys than the ones the
         * person was shown and agreed to.
         */
        val owner: IdentityId,
        /** That identity's own alias, for the disclosure. It is the one
         *  fact the screen otherwise doesn't show: whose keys these are. */
        val identityName: String,
        val groupIdHex: String,
        val groupName: String?,
        /** Empty for a link: nobody introduced themselves. */
        val inviterAlias: String,
        val invitationMessage: String?,
        /** The invite key the request is sealed to, for display. Whoever
         *  holds its private half is who this discloses to, and that is
         *  worth showing rather than describing. */
        val introPublicKey: ByteArray,
        /** Pre-filled into the name field: the identity's own alias, or
         *  the name a previous attempt on this row used. */
        val suggestedLabel: String,
        val origin: Origin,
    ) {
        /** Where the confirmation came from — a link this device just
         *  received, or an offer already sitting in the list. */
        sealed interface Origin {
            data class Link(val capability: IntroCapability) : Origin
            data class Offer(val rowId: String) : Origin
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is JoinConfirmation) return false
            return rowId == other.rowId &&
                groupIdHex == other.groupIdHex &&
                groupName == other.groupName &&
                inviterAlias == other.inviterAlias &&
                invitationMessage == other.invitationMessage &&
                introPublicKey.contentEquals(other.introPublicKey) &&
                suggestedLabel == other.suggestedLabel &&
                origin == other.origin
        }

        override fun hashCode(): Int {
            var h = rowId.hashCode()
            h = 31 * h + groupIdHex.hashCode()
            h = 31 * h + (groupName?.hashCode() ?: 0)
            h = 31 * h + inviterAlias.hashCode()
            h = 31 * h + (invitationMessage?.hashCode() ?: 0)
            h = 31 * h + introPublicKey.contentHashCode()
            h = 31 * h + suggestedLabel.hashCode()
            h = 31 * h + origin.hashCode()
            return h
        }
    }

    /** Where a tapped invite link (or scanned QR) leaves the person. */
    sealed interface JoinOutcome {
        /** Already a member — the link was an old one, or a second tap.
         *  Carries the hex group id so the caller can open the chat. */
        data class AlreadyJoined(val groupIdHex: String) : JoinOutcome

        /** A pending row exists and the wait is under way. The request
         *  is either on its way or already out — an unanswered offer for
         *  the same group is sent here, one already asked for is not
         *  asked twice. */
        data class Waiting(val rowId: String) : JoinOutcome

        /** Nothing could be recorded, so there is nothing to show and
         *  nothing to come back to. The caller has to say so out loud. */
        data class Failed(val reason: FailureReason) : JoinOutcome

        enum class FailureReason { NO_IDENTITY, NOT_SAVED }
    }

    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()
    private val _rowsReady = MutableStateFlow(false)
    val rowsReady: StateFlow<Boolean> = _rowsReady.asStateFlow()

    /**
     * Where a pending row's wait ended, mapped from the row's id to the
     * group that replaced it.
     *
     * Kept after the row itself is gone, because the row disappearing is
     * exactly the moment a screen showing it needs to know where to go
     * instead. Bounded by the number of chats this identity has — the
     * same order as the chats list.
     */
    private val _materialized = MutableStateFlow<Map<String, String>>(emptyMap())
    val materialized: StateFlow<Map<String, String>> = _materialized.asStateFlow()

    /**
     * The confirmation currently on screen, if any.
     *
     * In memory only, and deliberately: it is the state of something
     * that has *not* been agreed to yet. Nothing about it is written
     * until [confirmJoin].
     */
    private val _confirmation = MutableStateFlow<JoinConfirmation?>(null)
    val confirmation: StateFlow<JoinConfirmation?> = _confirmation.asStateFlow()

    private val _lastError = MutableStateFlow<PendingChatError?>(null)
    val lastError: StateFlow<PendingChatError?> = _lastError.asStateFlow()

    private var pending: List<PendingChat> = emptyList()
    private var verifying: List<PendingGroupVerification> = emptyList()
    private var sendingIds: Set<String> = emptySet()
    /** Independent handoff memory: verification can be resolved by a
     *  different collector before the group snapshot reaches us. */
    private var observedWaitingIds: Set<String> = emptySet()

    private var pendingJob: Job? = null
    private var verifyingJob: Job? = null
    private var groupWatchJob: Job? = null

    /** Drain all three sources. Idempotent. */
    fun start() {
        if (pendingJob != null) return
        pendingJob = viewModelScope.launch {
            combine(repository.snapshots, repository.isLoaded) { snapshot, loaded ->
                snapshot to loaded
            }.collect { (snapshot, loaded) ->
                pending = snapshot
                sendingIds = sendingIds.intersect(snapshot.mapTo(mutableSetOf()) { it.id })
                observedWaitingIds = observedWaitingIds + snapshot.map { it.id }
                rebuild()
                _rowsReady.value = loaded
            }
        }
        verifyingJob = viewModelScope.launch {
            verificationStore.snapshots.collect { snapshot ->
                verifying = snapshot
                observedWaitingIds = observedWaitingIds + snapshot.map {
                    "${it.groupIdHex}:${it.ownerIdentityId.value}"
                }
                rebuild()
            }
        }
        groupWatchJob = viewModelScope.launch {
            groupRepository.snapshots.collect { groups ->
                // Derived from the snapshot itself rather than from
                // `rows`, which may not have been filled yet when the
                // first group emission lands — the same ordering
                // `consumeForMaterialized` reads through the store to
                // survive. A pending row's id *is*
                // `<group hex>:<owner>`, so the mapping needs nothing
                // else to be exact.
                val currentGroups = groups.associate { group ->
                    "${group.id}:${group.ownerIdentityId}" to group.id
                }
                val activeOwner = currentIdentityId()
                observedWaitingIds = if (activeOwner == null) {
                    emptySet()
                } else {
                    observedWaitingIds.filterTo(mutableSetOf()) {
                        it.endsWith(":${activeOwner.value}")
                    }
                }
                observedWaitingIds = observedWaitingIds + buildSet {
                    repository.currentChats()
                        .filter { it.ownerIdentityId == activeOwner }
                        .mapTo(this) { it.id }
                    verificationStore.snapshots.value.mapTo(this) {
                        "${it.groupIdHex}:${it.ownerIdentityId.value}"
                    }
                    _rows.value.mapTo(this) { it.id }
                }
                // Keep prior landings only while their real group still
                // belongs to the selected identity, and add only groups
                // that actually replaced a waiting row. This preserves
                // the handoff after row deletion without accumulating
                // every group ever observed across identity switches.
                _materialized.value = _materialized.value
                    .filterKeys { it in currentGroups } +
                    currentGroups.filterKeys { it in observedWaitingIds }
                observedWaitingIds = observedWaitingIds - currentGroups.keys
                repository.consumeForMaterialized(groups.map { it.id to it.owner })
            }
        }
    }

    fun stop() {
        pendingJob?.cancel()
        pendingJob = null
        verifyingJob?.cancel()
        verifyingJob = null
        groupWatchJob?.cancel()
        groupWatchJob = null
    }

    fun row(id: String): Row? = _rows.value.firstOrNull { it.id == id }

    /** The group a pending row turned into, once it has. Null while the
     *  wait is still on. */
    fun materializedGroupId(rowId: String): String? = _materialized.value[rowId]

    /**
     * Whether the group behind a waiting row is already here.
     *
     * Only for suppressing a back-out. `GroupStateVerifier` clears a
     * verification from its *own* group collector, which can win the
     * race against this view-model's — leaving a moment where the row is
     * gone and the handoff hasn't been recorded yet. Popping then would
     * drop the person one screen back at the exact instant their chat
     * arrived; waiting a frame costs nothing, because the handoff is on
     * its way.
     */
    fun groupHasLanded(groupIdHex: String): Boolean =
        groupRepository.snapshots.value.any { it.id == groupIdHex }

    /** Hold a confirmation for the screen that is about to render it,
     *  and drop it when that screen goes away. */
    fun holdConfirmation(confirmation: JoinConfirmation?) {
        _confirmation.value = confirmation
    }

    /**
     * Resolve a capability into the next screen. Records nothing, sends
     * nothing — see [JoinDestination].
     */
    suspend fun prepareJoin(capability: IntroCapability): JoinDestination =
        // Wrapped so a cancelled caller can't abandon the read halfway:
        // the composable that asks is torn down by the very navigation
        // this answer triggers.
        viewModelScope.async { performPrepareJoin(capability) }.await()

    private suspend fun performPrepareJoin(capability: IntroCapability): JoinDestination {
        val owner = currentIdentityId()
            ?: return JoinDestination.Failed(JoinOutcome.FailureReason.NO_IDENTITY)
        val groupIdHex = capability.groupId.joinToString("") { "%02x".format(it) }
        // Already in? Then the link is stale or double-tapped, and the
        // honest answer is the chat itself rather than a second wait.
        // Reads the store directly rather than the identity-filtered
        // snapshot: a link can be tapped before any screen has
        // subscribed, and the answer must not depend on that.
        val alreadyJoined = groupRepository.findForOwner(
            ownerIdentityId = owner.value,
            groupId = groupIdHex,
        ) != null
        if (alreadyJoined) return JoinDestination.AlreadyJoined(groupIdHex)

        val rowId = "$groupIdHex:${owner.value}"
        val existing = repository.currentChats().firstOrNull { it.id == rowId }
        // Already asked and still waiting: one tap, one request, and the
        // wait is the whole answer. A row whose send *failed* is not
        // that — it has nothing outstanding, so it gets the screen and
        // a chance to go out again.
        if (existing != null &&
            existing.status != PendingChat.Status.Offered &&
            existing.status !is PendingChat.Status.Failed
        ) {
            return JoinDestination.Waiting(rowId)
        }
        return JoinDestination.Confirm(
            JoinConfirmation(
                rowId = rowId,
                owner = owner,
                identityName = displayLabel(owner),
                groupIdHex = groupIdHex,
                groupName = capability.groupName ?: existing?.groupName,
                inviterAlias = existing?.inviterAlias.orEmpty(),
                invitationMessage = existing?.invitationMessage,
                introPublicKey = capability.introPublicKey,
                suggestedLabel = existing?.joinerLabel ?: displayLabel(owner),
                origin = JoinConfirmation.Origin.Link(capability),
            ),
        )
    }

    /**
     * The same screen, reached from the Accept on a pushed offer.
     *
     * One path for both, so what a person is shown before their name and
     * keys leave the device does not depend on which door the invitation
     * came through.
     */
    suspend fun prepareAccept(rowId: String): JoinConfirmation? {
        val chat = pending.firstOrNull { it.id == rowId } ?: return null
        if (chat.status != PendingChat.Status.Offered) return null
        return JoinConfirmation(
            rowId = chat.id,
            owner = chat.ownerIdentityId,
            identityName = displayLabel(chat.ownerIdentityId),
            groupIdHex = chat.groupIdHex,
            groupName = chat.groupName,
            inviterAlias = chat.inviterAlias,
            invitationMessage = chat.invitationMessage,
            introPublicKey = chat.introPublicKey,
            suggestedLabel = chat.joinerLabel ?: displayLabel(chat.ownerIdentityId),
            origin = JoinConfirmation.Origin.Offer(chat.id),
        )
    }

    /**
     * The one place a join request is sent, and the only one a person
     * can reach: the Send on the confirmation screen.
     *
     * [label] is what they typed. It rides with this join and does not
     * touch the identity — one identity can introduce itself differently
     * to different rooms — and it is stored on the row so a later
     * re-send says the same thing.
     */
    suspend fun confirmJoin(confirmation: JoinConfirmation, label: String): JoinDestination =
        // Wrapped for the same reason the read is: the screen that
        // called this is torn down by the navigation that follows, and a
        // cancelled caller must not abandon a half-written row or an
        // unsent request.
        viewModelScope.async { performConfirmJoin(confirmation, label) }.await()

    private suspend fun performConfirmJoin(
        confirmation: JoinConfirmation,
        label: String,
    ): JoinDestination {
        val trimmed = label.trim()
        val outcome = when (val origin = confirmation.origin) {
            is JoinConfirmation.Origin.Offer -> {
                val chat = pending.firstOrNull { it.id == origin.rowId }
                    ?: return JoinDestination.Failed(JoinOutcome.FailureReason.NOT_SAVED)
                repository.attachJoinerLabel(chat.id, trimmed)
                viewModelScope.launch { send(chat, trimmed) }
                JoinDestination.Waiting(chat.id)
            }
            is JoinConfirmation.Origin.Link -> {
                // The identity the screen was opened for, not whichever
                // is selected now: switching identities mid-screen must
                // not silently disclose a different one's keys. If the
                // switch happened, this Send is not the one that was
                // agreed to.
                val owner = confirmation.owner
                if (currentIdentityId() != owner) {
                    return JoinDestination.Failed(JoinOutcome.FailureReason.NO_IDENTITY)
                }
                val chat = PendingChat(
                    groupId = origin.capability.groupId,
                    ownerIdentityId = owner,
                    introPublicKey = origin.capability.introPublicKey,
                    groupName = origin.capability.groupName,
                    // Nobody introduced themselves over a link — the row
                    // shows the group, not a person who never said their
                    // name.
                    inviterAlias = "",
                    invitationMessage = null,
                    receivedAt = Instant.now(),
                    status = PendingChat.Status.Offered,
                    // A local clock is suitable for list ordering but
                    // cannot be compared with an authenticated Nostr
                    // event timestamp.
                    offerReceivedAt = null,
                    joinerLabel = trimmed,
                )
                when (repository.record(chat)) {
                    PendingChatWriteOutcome.INSERTED -> {
                        viewModelScope.launch { send(chat, trimmed) }
                        JoinDestination.Waiting(chat.id)
                    }
                    PendingChatWriteOutcome.ALREADY_PRESENT -> {
                        // A pushed offer for this group arrived first
                        // and is still unanswered — this Send is the
                        // answer to it.
                        repository.attachJoinerLabel(chat.id, trimmed)
                        val existing = repository.currentChats()
                            .firstOrNull { it.id == chat.id }
                        // A row whose send failed has nothing
                        // outstanding either, so this Send is its
                        // answer too.
                        if (existing != null &&
                            (
                                existing.status == PendingChat.Status.Offered ||
                                    existing.status is PendingChat.Status.Failed
                                )
                        ) {
                            viewModelScope.launch { send(existing, trimmed) }
                        }
                        JoinDestination.Waiting(chat.id)
                    }
                    PendingChatWriteOutcome.FAILED,
                    PendingChatWriteOutcome.NOT_RECORDED,
                    PendingChatWriteOutcome.NOT_ENCRYPTABLE,
                    ->
                        // All three leave the person with nothing to
                        // come back to, which is the only distinction
                        // this caller needs; which of them it was
                        // matters one layer down, where the store
                        // decides whether to keep using the disk.
                        JoinDestination.Failed(JoinOutcome.FailureReason.NOT_SAVED)
                }
            }
        }
        // Navigation may happen immediately after this return. Shape the
        // latest repository value synchronously so the pending screen
        // cannot observe a ready-but-not-yet-collected empty row list.
        pending = repository.snapshots.value
        observedWaitingIds = observedWaitingIds + pending.map { it.id }
        rebuild()
        return outcome
    }

    /**
     * Act on whatever this row is stuck on: re-drive a stalled
     * verification, or ask again — a request that never left, or one
     * that left and was never answered.
     *
     * Which of the two depends on who is being waited on. Past the
     * founder's approval the wait belongs to the verifier, and asking
     * them again would achieve nothing; before it, the founder is the
     * only one who can move it.
     */
    fun retry(id: String) {
        val row = row(id) ?: return
        val hasVerification = verifying.any { it.groupIdHex == row.groupIdHex }
        when (row.state) {
            State.FounderUnreachable, State.ChainUnreachable, State.ChainNotConfigured ->
                viewModelScope.launch { retryVerification(row.groupIdHex) }
            State.Waiting, is State.SendFailed -> {
                if (hasVerification) {
                    viewModelScope.launch { retryVerification(row.groupIdHex) }
                } else {
                    val chat = pending.firstOrNull { it.id == id } ?: return
                    viewModelScope.launch {
                        // Under the name this row already asked under —
                        // a re-send that renamed the asker would arrive
                        // from a stranger, and the founder is deciding
                        // partly on that name.
                        //
                        // The fallback is for the row whose label write
                        // failed (an encryption error drops it
                        // silently): bailing there left an enabled
                        // button that did nothing, forever. Asking again
                        // under the identity's current name is worse
                        // than asking under the same name, and far
                        // better than a button that lies.
                        val label = chat.joinerLabel ?: displayLabel(chat.ownerIdentityId)
                        send(chat, label)
                    }
                }
            }
            State.Offered, State.ChainSettling -> Unit
        }
    }

    /** Drop a row the person swiped away. */
    fun dismiss(id: String) {
        observedWaitingIds = observedWaitingIds - id
        viewModelScope.launch { repository.remove(id) }
    }

    fun dismissError() {
        _lastError.value = null
    }

    // ---- private ----

    /**
     * The one send path, shared by the confirmation screen's Send and by
     * a re-send. Debounced on [sendingIds] so a double tap ships one
     * request.
     *
     * [label] is always supplied by a caller a person reached: there is
     * no path from an inbound link or event to here.
     */
    private suspend fun send(chat: PendingChat, label: String) {
        val id = chat.id
        if (id in sendingIds) return
        val capability = try {
            IntroCapability(
                introPublicKey = chat.introPublicKey,
                groupId = chat.groupId,
                groupName = chat.groupName,
            )
        } catch (_: Throwable) {
            _lastError.value = PendingChatError.MalformedInvite
            return
        }
        sendingIds = sendingIds + id
        _lastError.value = null
        rebuild()
        // Any cancellation or unexpected sender failure must release the
        // in-flight marker; otherwise the row stays at "Sending…" with
        // Accept and Ask again disabled for the rest of the process.
        try {
            when (submitJoin(capability, label, chat.ownerIdentityId)) {
                is JoinRequestSender.Outcome.Sent -> repository.markRequested(id)
                is JoinRequestSender.Outcome.NoIdentityLoaded ->
                    repository.markFailed(id, PendingChat.SendFailure.NO_IDENTITY)
                // The transport's own wording doesn't survive a
                // translation or a re-read six months later, and it
                // tells the reader nothing they can act on. The code
                // carries what matters: it didn't go out.
                // Not retryable, and not the transport's fault: the
                // agreement never reached the sender, so asking again
                // would ship the same nothing.
                is JoinRequestSender.Outcome.RulesAgreementMissing ->
                    repository.markFailed(id, PendingChat.SendFailure.RULES_MISSING)
                is JoinRequestSender.Outcome.TransportFailed ->
                    repository.markFailed(id, PendingChat.SendFailure.TRANSPORT)
            }
        } finally {
            sendingIds = sendingIds - id
            rebuild()
        }
    }

    /**
     * Fold the three sources into [rows]. Verification status wins over
     * the stored status when both describe the same group: by then the
     * founder has approved and what the person is waiting on has moved
     * on from them.
     *
     * Except over an offer nobody has answered. A verification says
     * something about a join that was asked for, and an Offered row has
     * asked for nothing — letting it win there took away the Accept
     * button and left the person with no way to say yes.
     */
    private fun rebuild() {
        val verificationByHex = verifying.associateBy { it.groupIdHex }
        val built = pending.map { chat ->
            val state = if (chat.status == PendingChat.Status.Offered) {
                State.Offered
            } else {
                verificationByHex[chat.groupIdHex]?.let(::stateFor) ?: stateFor(chat.status)
            }
            Row(
                id = chat.id,
                groupIdHex = chat.groupIdHex,
                name = chat.groupName,
                inviterAlias = chat.inviterAlias,
                invitationMessage = chat.invitationMessage,
                receivedAt = chat.receivedAt,
                state = state,
                isSending = chat.id in sendingIds,
                isDismissable = true,
            )
        }.toMutableList()

        // Verifications with no pending row of their own — see the class
        // comment. Matched by hex, since there is no offer to match on,
        // but *identified* like every other row: `<group hex>:<owner>`.
        // A bare hex here would not be found in `materialized`, which is
        // keyed by the composite, so the thread behind such a row would
        // treat the group's arrival as a disappearance and navigate Back
        // instead of opening the chat that just landed.
        val covered = built.mapTo(mutableSetOf()) { it.groupIdHex }
        verifying.filterNot { it.groupIdHex in covered }.forEach { entry ->
            built.add(
                Row(
                    id = "${entry.groupIdHex}:${entry.ownerIdentityId.value}",
                    groupIdHex = entry.groupIdHex,
                    name = entry.groupName.ifEmpty { null },
                    inviterAlias = "",
                    invitationMessage = null,
                    receivedAt = entry.receivedAt,
                    state = stateFor(entry),
                    isSending = false,
                    isDismissable = false,
                ),
            )
        }
        _rows.value = built.sortedByDescending { it.receivedAt }
    }

    private fun stateFor(status: PendingChat.Status): State = when (status) {
        PendingChat.Status.Offered -> State.Offered
        PendingChat.Status.Requested -> State.Waiting
        is PendingChat.Status.Failed -> State.SendFailed(status.failure)
    }

    private fun stateFor(entry: PendingGroupVerification): State = when (entry.status) {
        PendingGroupVerification.Status.VERIFYING -> State.Waiting
        PendingGroupVerification.Status.CHAIN_SETTLING -> State.ChainSettling
        PendingGroupVerification.Status.UNREACHABLE -> State.FounderUnreachable
        PendingGroupVerification.Status.CHAIN_UNREACHABLE -> State.ChainUnreachable
        PendingGroupVerification.Status.CHAIN_NOT_CONFIGURED -> State.ChainNotConfigured
    }
}

/** Errors the pending surface can raise that aren't a row's own state.
 *  A code, not a sentence — the screen resolves the wording. */
enum class PendingChatError {
    /** The stored offer can't be rebuilt into a capability, so there is
     *  nothing to send and nothing a Retry could fix. */
    MalformedInvite,
}
