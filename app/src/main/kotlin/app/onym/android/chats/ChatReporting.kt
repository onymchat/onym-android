package app.onym.android.chats

import app.onym.android.group.ChatGroup
import app.onym.android.moderation.AuthorityRejectedException
import app.onym.android.moderation.ModerationRepository
import app.onym.android.moderation.ReportFilingException
import app.onym.android.moderation.ReportableEvidence
import app.onym.android.moderation.ViolationClass
import kotlinx.coroutines.CancellationException

/**
 * The chat thread's seam onto the moderation report vertical —
 * lambdas-shaped like every other [ChatThreadViewModel] dependency,
 * so the VM stays constructible in tests without the moderation
 * stack.
 */
class ChatReporting(
    /** Sync context-menu gate ([ReportableMessageFactory.isReportable]). */
    val isReportable: (ChatMessage, ChatGroup) -> Boolean,
    /** Full verification + evidence assembly; null = not reportable. */
    val prepare: suspend (ChatMessage, ChatGroup) -> ReportableEvidence?,
    /** The classes the user may report under (consented manifest ∩
     *  mandate), or why none can be offered — the two reasons are
     *  different problems with different fixes, so they must not
     *  collapse into one empty list. */
    val classes: suspend () -> ReportClassesOutcome,
    /** File with the authority; never throws — outcomes are values. */
    val submit: suspend (ReportableEvidence, classId: String) -> ReportSubmitOutcome,
) {
    companion object {
        /** Production wiring over the real factory + repository. */
        fun production(
            repository: ModerationRepository,
            imageLoader: ChatImageLoader?,
        ) = ChatReporting(
            isReportable = ReportableMessageFactory::isReportable,
            prepare = prepare@{ message, group ->
                val attachment = message.imageAttachment
                val bytes = if (attachment != null) {
                    // The exact plaintext or nothing: a photo whose
                    // bytes can't be fetched is unreportable right
                    // now, not reportable-without-the-photo.
                    imageLoader?.fetchPlaintext(attachment) ?: return@prepare null
                } else {
                    null
                }
                ReportableMessageFactory.make(message, group, bytes)
            },
            classes = {
                try {
                    val available = repository.availableReportClasses()
                    if (available.isEmpty()) {
                        ReportClassesOutcome.NoClasses
                    } else {
                        ReportClassesOutcome.Available(available)
                    }
                } catch (e: ReportFilingException.ReportingUnavailable) {
                    ReportClassesOutcome.MandateRequired
                } catch (e: CancellationException) {
                    // Cancellation is not a failure to report on — it
                    // is this coroutine being torn down, and swallowing
                    // it would let a dismissed prepare keep running and
                    // then write its result to the screen.
                    throw e
                } catch (e: Exception) {
                    // Store IO, a manifest that won't decode: retryable,
                    // and must not render as the terminal "your
                    // authority offers no reportable classes".
                    ReportClassesOutcome.TransientFailure
                }
            },
            submit = { evidence, classId ->
                try {
                    repository.fileReport(evidence, classId)
                    ReportSubmitOutcome.Filed
                } catch (e: ReportFilingException.AlreadyFiled) {
                    ReportSubmitOutcome.AlreadyFiled
                } catch (e: ReportFilingException.AuthenticityUnverified) {
                    ReportSubmitOutcome.Failed(ReportError.NoValidProof)
                } catch (e: ReportFilingException.ReportingUnavailable) {
                    ReportSubmitOutcome.Failed(ReportError.MandateRequired)
                } catch (e: ReportFilingException.ClassOutsideMandate) {
                    ReportSubmitOutcome.Failed(ReportError.MandateRequired)
                } catch (e: ReportFilingException.AuthorityUnavailable) {
                    ReportSubmitOutcome.Failed(ReportError.AuthorityUnavailable)
                } catch (e: AuthorityRejectedException) {
                    // Authority prose, bounded — its message names the
                    // precise refusal (class refused, media missing…).
                    ReportSubmitOutcome.Failed(
                        ReportError.Rejected(e.message.take(280)),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ReportSubmitOutcome.Failed(ReportError.Delivery)
                }
            },
        )
    }
}

/** Why the class picker can (or can't) be shown. */
sealed interface ReportClassesOutcome {
    data class Available(val classes: List<ViolationClass>) : ReportClassesOutcome

    /** No active, registered mandate — actionable by the user
     *  (consent, or finish a pending registration), and distinct from
     *  an authority that simply publishes nothing this user consented
     *  to. */
    data object MandateRequired : ReportClassesOutcome

    /** Mandated, but the consented terms name no class to report
     *  under. Nothing the user can do from here. */
    data object NoClasses : ReportClassesOutcome

    /** The terms couldn't be read this time (store IO, a decode
     *  failure). Retryable — deliberately distinct from [NoClasses],
     *  whose copy is a dead end. */
    data object TransientFailure : ReportClassesOutcome
}

sealed interface ReportSubmitOutcome {
    /** The authority acknowledged the filing (a case opened or joined). */
    data object Filed : ReportSubmitOutcome

    /** This exact filing is already on a case — success, not an error. */
    data object AlreadyFiled : ReportSubmitOutcome

    data class Failed(val error: ReportError) : ReportSubmitOutcome
}

/** Error kinds the report sheet renders as copy. */
sealed interface ReportError {
    /** The message has no proof that verifies — permanently unfilable. */
    data object NoValidProof : ReportError

    /** No active registered mandate / class not consented. */
    data object MandateRequired : ReportError

    /** Mandated, but the consented terms name nothing to report
     *  under — not the user's problem to fix. */
    data object NoReportableClasses : ReportError

    /** Couldn't read the authority's terms just now; trying again may
     *  well work. */
    data object Transient : ReportError

    /** The mandate's authority has no current directory listing. */
    data object AuthorityUnavailable : ReportError

    /** The authority's own refusal text, bounded. */
    data class Rejected(val message: String) : ReportError

    /** Transient delivery failure — the exact signed report is kept
     *  and resent on the next Submit. */
    data object Delivery : ReportError
}

/** State of the report sheet. */
sealed interface ReportUiState {
    /** Verifying the proof / fetching the photo bytes. */
    data object Preparing : ReportUiState

    /** Nothing can be filed. [error] names why when there is
     *  something the user could act on (no mandate); `null` is the
     *  plain "this message can't be reported" — no verifying proof,
     *  or an authority publishing nothing reportable. */
    data class Unavailable(val error: ReportError? = null) : ReportUiState

    data class Form(
        val evidence: ReportableEvidence,
        val displayBody: String,
        val hasPhoto: Boolean,
        val classes: List<ViolationClass>,
        val selectedClassId: String?,
        val submitting: Boolean = false,
        val error: ReportError? = null,
    ) : ReportUiState

    data class Done(val alreadyFiled: Boolean) : ReportUiState
}
