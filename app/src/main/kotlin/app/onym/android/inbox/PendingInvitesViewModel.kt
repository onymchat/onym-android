package app.onym.android.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.onym.android.group.GroupRepository
import app.onym.android.group.IntroCapability
import app.onym.android.group.JoinRequestSender
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * View-model for the invitee-side "you've been invited" surface — the
 * push counterpart to [app.onym.android.group.JoinViewModel] (which
 * handles deeplink joins). Mirrors [app.onym.android.group.ApproveRequestsViewModel]'s
 * shape: a shared instance whose [pending] list backs both the Chats
 * toolbar badge and the modal list.
 *
 * Accept is the *explicit* step the design requires: it turns a
 * [PendingInvite] (an offer) into a [app.onym.android.group.JoinRequestPayload]
 * shipped to the admin's intro key via the same [JoinRequestSender] the
 * deeplink path uses. Nothing here anchors the roster — the admin still
 * has to explicitly approve the resulting request. The group appears on
 * accept only after that approval lands and materializes it, at which
 * point [start]'s group watcher consumes the spent invite.
 *
 * Mirrors `PendingInvitesFlow` from onym-ios PR #158.
 */
class PendingInvitesViewModel(
    private val store: PendingInvitesStore,
    private val verificationStore: PendingVerificationStore,
    private val groupRepository: GroupRepository,
    /** Mirrors [app.onym.android.group.JoinViewModel]'s injected
     *  `submitRequest` — seals + sends a join request for the given
     *  capability. Returns the sender outcome so the VM can surface
     *  errors. */
    private val submitJoin: suspend (IntroCapability, String) -> JoinRequestSender.Outcome,
    /** Resolves the joiner's display label at accept time (the active
     *  identity's alias). Read lazily so an identity rename is picked
     *  up without re-wiring. */
    private val displayLabel: () -> String,
    /** Re-send a stuck verification's refresh request (Retry button).
     *  Backed by [GroupStateVerifier.retry]. */
    private val retryVerification: suspend (String) -> Unit,
) : ViewModel() {

    /** Pending invites for the current identity, newest first. */
    private val _pending = MutableStateFlow<List<PendingInvite>>(emptyList())
    val pending: StateFlow<List<PendingInvite>> = _pending.asStateFlow()

    /** Groups that were accepted but couldn't be verified at an exact
     *  epoch yet — awaiting (or unable to get) the current state from
     *  the admin. Hidden from the chats list until verified; surfaced
     *  here so the user knows a join is in flight or stuck. */
    private val _verifying = MutableStateFlow<List<PendingGroupVerification>>(emptyList())
    val verifying: StateFlow<List<PendingGroupVerification>> = _verifying.asStateFlow()

    /** IDs whose accept call is in flight (drives the per-row spinner). */
    private val _inFlightIds = MutableStateFlow<Set<String>>(emptySet())
    val inFlightIds: StateFlow<Set<String>> = _inFlightIds.asStateFlow()

    /** IDs whose join request has been sent and is awaiting the admin's
     *  approval. Relabels the row and disables Accept so the user
     *  doesn't double-request. */
    private val _requestedIds = MutableStateFlow<Set<String>>(emptySet())
    val requestedIds: StateFlow<Set<String>> = _requestedIds.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var streamJob: Job? = null
    private var verifyingJob: Job? = null
    private var groupWatchJob: Job? = null

    fun isInFlight(id: String): Boolean = _inFlightIds.value.contains(id)
    fun isRequested(id: String): Boolean = _requestedIds.value.contains(id)

    /**
     * Mirror the store's snapshot into [pending] and watch groups so a
     * spent invite is dropped once its group materializes. Idempotent.
     */
    fun start() {
        if (streamJob != null) return
        streamJob = viewModelScope.launch {
            store.snapshots.collect { snapshot ->
                _pending.value = snapshot
                // Forget request / in-flight bookkeeping for invites
                // that are no longer present (consumed / identity
                // switch).
                val live = snapshot.mapTo(mutableSetOf()) { it.id }
                _requestedIds.value = _requestedIds.value.intersect(live)
                _inFlightIds.value = _inFlightIds.value.intersect(live)
            }
        }
        verifyingJob = viewModelScope.launch {
            verificationStore.snapshots.collect { snapshot -> _verifying.value = snapshot }
        }
        groupWatchJob = viewModelScope.launch {
            groupRepository.snapshots.collect { groups ->
                store.consumeForMaterializedGroups(
                    groups.mapTo(mutableSetOf()) { it.groupIdBytes.toList() },
                )
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        verifyingJob?.cancel()
        verifyingJob = null
        groupWatchJob?.cancel()
        groupWatchJob = null
    }

    /** Retry a verification that got stuck because the admin was
     *  unreachable for the current-state refresh. */
    fun retry(groupIdHex: String) {
        viewModelScope.launch { retryVerification(groupIdHex) }
    }

    /**
     * Explicit Accept: ship a join request to the offer's intro key.
     * No-op while already in flight or already requested.
     */
    fun accept(id: String) {
        if (_inFlightIds.value.contains(id) || _requestedIds.value.contains(id)) return
        val invite = _pending.value.firstOrNull { it.id == id } ?: return
        val capability = try {
            IntroCapability(
                introPublicKey = invite.introPublicKey,
                groupId = invite.groupId,
                groupName = invite.groupName,
            )
        } catch (_: Throwable) {
            _lastError.value = "This invite is malformed and can't be accepted."
            return
        }
        _inFlightIds.value = _inFlightIds.value + id
        _lastError.value = null
        val label = displayLabel()
        viewModelScope.launch {
            val outcome = submitJoin(capability, label)
            _inFlightIds.value = _inFlightIds.value - id
            when (outcome) {
                is JoinRequestSender.Outcome.Sent ->
                    _requestedIds.value = _requestedIds.value + id
                is JoinRequestSender.Outcome.NoIdentityLoaded ->
                    _lastError.value = "Sign in first."
                is JoinRequestSender.Outcome.TransportFailed ->
                    _lastError.value = "Couldn’t send request: ${outcome.reason}"
            }
        }
    }

    /**
     * Drop an invite the user doesn't want. Local-only — no NACK to the
     * admin (their outstanding intro key just goes unused).
     */
    fun dismiss(id: String) {
        viewModelScope.launch { store.consume(id) }
    }

    fun dismissError() {
        _lastError.value = null
    }
}
