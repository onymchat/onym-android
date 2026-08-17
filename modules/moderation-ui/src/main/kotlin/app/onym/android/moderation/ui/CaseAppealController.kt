package app.onym.android.moderation.ui

import app.onym.android.moderation.AppealFilingException
import app.onym.android.moderation.AppealReceipt
import app.onym.android.moderation.AuthorityRejectedException
import app.onym.android.moderation.AuthorityUnreachableException
import app.onym.android.moderation.ModerationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives one case's appeal surface: the statement the accused writes,
 * and the single delivery it turns into.
 *
 * One controller per case per surface — [state] is the whole surface's
 * state, so sharing one across two cases would show one case's outcome
 * on the other's screen.
 *
 * Retry safety is the repository's, not this class's: the signed
 * artifact is persisted before delivery and the same statement resolves
 * to the same filing, so tapping Submit again after a timeout returns
 * the stored receipt rather than filing twice. That is also why
 * [submit] does not clear [State.Failed] optimistically — the user's
 * text has to survive a failure for the retry to be the same statement.
 */
class CaseAppealController(
    val caseId: String,
    private val repository: ModerationRepository,
    private val scope: CoroutineScope,
) {

    sealed interface State {
        /** Composing. [failure] is the previous attempt's reason, kept
         *  alongside the editable statement rather than replacing it. */
        data class Editing(val failure: Failure? = null) : State

        data object Submitting : State

        /** Filed. [note] is the authority's own remark when it sent
         *  one — never invented locally. */
        data class Filed(val note: String?) : State
    }

    /**
     * Why an attempt failed, and — the part that matters — what the
     * user can actually do next.
     *
     * This was a boolean, and the boolean was wrong twice. It folded
     * "the directory doesn't list your authority right now" (transient;
     * nothing was even signed) in with "the authority already has this
     * on file" (terminal), telling the user to give up on an outage.
     * And it left a malformed statement in the same bucket as a
     * post-acceptance hazard, so one keystroke re-armed both.
     */
    enum class Recourse {
        /** The same signed statement may yet land — an outage, a 429,
         *  or an authority the directory isn't serving at the moment.
         *  Submit stays live; the repository replays the artifact. */
        RETRY,

        /** The statement itself is what was refused. Submit re-arms
         *  once it changes, because a different statement genuinely
         *  gets a different answer. */
        EDIT,

        /** Nothing to do here. Either the authority refused this case
         *  deterministically, or it answered 200 and the artifact was
         *  discarded — so a resubmission would file a SECOND appeal
         *  against an endpoint that does not deduplicate. Editing must
         *  not re-arm this one.
         */
        NONE,
    }

    data class Failure(val reason: String, val recourse: Recourse)

    private val _state = MutableStateFlow<State>(State.Editing())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Appeals already on file for this case, from the ledger.
     *
     * The surface warns on this. Idempotency matches the EXACT
     * statement, so a user who returns — after a rotation, or a
     * process death — and edits one character before resubmitting files
     * a second appeal with nothing to tell them. Zero until the load
     * lands, which reads as "no warning yet" rather than a false
     * reassurance being replaced by one.
     */
    private val _priorFilings = MutableStateFlow(0)
    val priorFilings: StateFlow<Int> = _priorFilings.asStateFlow()

    init {
        // Best-effort: a ledger that cannot be read (corrupt blob reads
        // as empty by design) must not stop the user appealing.
        scope.launch {
            val onFile = runCatching { repository.appeals(caseId).size }.getOrDefault(0)
            // `update`+max, not assignment: a fast submit can land its
            // own `+= 1` before this read returns, and a plain write
            // would stomp it with a pre-filing snapshot.
            _priorFilings.update { maxOf(it, onFile) }
        }
    }

    /** Bytes the statement occupies on the wire, for the counter — a
     *  Cyrillic statement hits the cap at half the character count. */
    fun statementBytes(statement: String): Int =
        statement.trim().toByteArray(Charsets.UTF_8).size

    val maxStatementBytes: Int get() = ModerationRepository.MAX_STATEMENT_BYTES

    /**
     * File the appeal. A no-op while one is in flight or already filed
     * — the repository would dedup a double-tap anyway, but a second
     * spinner is still a lie about what is happening.
     */
    fun submit(statement: String) {
        val current = _state.value
        if (current is State.Submitting || current is State.Filed) return
        // A terminal failure bars resubmission here, not just in the
        // screen's button-enabled logic — this class owns the invariant.
        // It matters most after AcknowledgedDifferentSubmission /
        // RefusedWithoutFiling, where the artifact was already
        // discarded: the ledger can no longer dedup, so a resubmit
        // genuinely files a second appeal against an endpoint that does
        // not deduplicate.
        if (current is State.Editing && current.failure?.recourse == Recourse.NONE) return
        _state.value = State.Submitting
        scope.launch {
            val outcome = runCatching { repository.appeal(caseId, statement) }
            outcome.fold(
                onSuccess = { receipt ->
                    _priorFilings.update { it + 1 }
                    _state.value = State.Filed(receipt.noteOrNull())
                },
                onFailure = { failure ->
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    _state.value = State.Editing(classify(failure))
                },
            )
        }
    }

    /**
     * Drop the previous attempt's reason because the user edited the
     * text.
     *
     * [Recourse.NONE] survives an edit. It has to: the artifact behind
     * a post-200 failure was discarded, so nothing downstream can dedup,
     * and letting one keystroke re-arm Submit would hand the user a
     * second filing dressed as a retry — which is exactly what the
     * guard in [submit] exists to prevent. Clearing it here would have
     * routed around that guard rather than honouring it.
     */
    fun clearFailure() {
        val current = _state.value
        if (current is State.Editing &&
            current.failure != null &&
            current.failure.recourse != Recourse.NONE
        ) {
            _state.value = State.Editing()
        }
    }

    private fun AppealReceipt.noteOrNull(): String? = note?.takeIf { it.isNotBlank() }

    /**
     * Map a failure to copy plus a retry verdict.
     *
     * The authority's own `{error, message}` text is preferred over
     * anything local when it sent one: it is the only party that knows
     * why THIS case refused an appeal, and paraphrasing it into
     * "something went wrong" is how a user ends up unable to act.
     */
    private fun classify(failure: Throwable): Failure = when (failure) {
        is AuthorityRejectedException -> Failure(
            reason = failure.message,
            // A deterministic 4xx (window closed, case decided) refuses
            // the CASE, not the wording — editing changes nothing.
            recourse = if (failure.isDeterministic) Recourse.NONE else Recourse.RETRY,
        )
        is AuthorityUnreachableException -> Failure(
            reason = failure.message ?: failure.toString(),
            recourse = Recourse.RETRY,
        )
        // Nothing was signed and nothing persisted before this is
        // thrown, and on the replay path the artifact is deliberately
        // KEPT — so an unlisted authority is an outage, not a verdict.
        // Same for absent standing: a mandate registration that has not
        // landed yet can land.
        is AppealFilingException.AuthorityUnavailable,
        is AppealFilingException.AppealsUnavailable,
        -> Failure(reason = failure.message ?: failure.toString(), recourse = Recourse.RETRY)
        is AppealFilingException.StatementInvalid -> Failure(
            reason = failure.message ?: failure.toString(),
            recourse = Recourse.EDIT,
        )
        // The two post-200 hazards. The authority answered success and
        // the artifact was discarded, so the ledger can no longer dedup
        // and any resubmission — edited or not — is a second filing.
        is AppealFilingException.AcknowledgedDifferentSubmission,
        is AppealFilingException.RefusedWithoutFiling,
        -> Failure(reason = failure.message ?: failure.toString(), recourse = Recourse.NONE)
        else -> Failure(reason = failure.message ?: failure.toString(), recourse = Recourse.RETRY)
    }
}
