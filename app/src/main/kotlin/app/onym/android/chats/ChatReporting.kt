package app.onym.android.chats

import app.onym.android.group.ChatGroup
import app.onym.android.moderation.AuthorityRejectedException
import app.onym.android.moderation.ModerationRepository
import app.onym.android.moderation.ReportFilingException
import app.onym.android.moderation.ReportableEvidence
import app.onym.android.moderation.ViolationClass

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
     *  mandate). */
    val classes: suspend () -> List<ViolationClass>,
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
            classes = { repository.availableReportClasses() },
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
                } catch (e: Exception) {
                    ReportSubmitOutcome.Failed(ReportError.Delivery)
                }
            },
        )
    }
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

    /** The message can't be reported (no verifying proof, no mandate,
     *  or no classes on offer). */
    data object Unavailable : ReportUiState

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
