package app.onym.android.moderation.ui

import app.onym.android.moderation.AuthorityListing
import app.onym.android.moderation.BackendRejectedException
import app.onym.android.moderation.AuthorityManifestFetcher
import app.onym.android.moderation.KnownAuthoritiesFetcher
import app.onym.android.moderation.MandateRecord
import app.onym.android.moderation.ModerationConsentException
import app.onym.android.moderation.ModerationRepository
import app.onym.android.moderation.ReviewedManifest
import app.onym.android.moderation.SignedManifest
import app.onym.android.moderation.displayJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives the one-snapshot consent review (profile §5.1): fetch the
 * directory and the authenticated manifest **once** for human review,
 * then consent with exactly that retained artifact — never a refetch.
 * If the hosted manifest changed since review, `agree` fails and the
 * flow re-presents the fresh artifact for a fresh review.
 */
class ModerationConsentController(
    private val authoritiesFetcher: KnownAuthoritiesFetcher,
    private val manifestFetcher: AuthorityManifestFetcher,
    private val moderation: ModerationRepository,
    /**
     * Whether an already-active mandate for the current identity
     * satisfies this surface ([UiState.Consented] without a fresh
     * review). TRUE for first-consent hosts (the onboarding step, the
     * never-mandated NeedsConsent) — a rotation right after a
     * NonCancellable agree() must re-emit Consented, not hand the
     * user a second live Agree that mints a second enrollment. FALSE
     * for the enrollment-lost re-consent host: a local record exists
     * there by definition, but the backend has lost the enrollment
     * and only a FRESH consent transaction repairs it — a
     * short-circuit would pin that surface on "consent recorded"
     * forever.
     */
    private val resumeExistingMandate: Boolean = true,
) {
    sealed interface UiState {
        data object Loading : UiState

        /** Nothing to consent to (or the directory is unreachable):
         * the step reports Unavailable, never a silent skip. */
        data class Unavailable(val message: String?) : UiState

        /** The reviewed snapshot on screen. [termsDisplay] is rendered
         * from the exact retained bytes. */
        data class Review(
            val listing: AuthorityListing,
            val termsDisplay: String,
            val error: String? = null,
        ) : UiState

        data object Consenting : UiState

        data class Consented(val record: MandateRecord) : UiState
    }

    private val mutex = Mutex()
    private val state = MutableStateFlow<UiState>(UiState.Loading)
    val snapshots: StateFlow<UiState> = state.asStateFlow()

    /** The retained review snapshot `agree` consents with. */
    private var reviewed: Pair<AuthorityListing, SignedManifest>? = null

    /**
     * Consecutive failed consent transactions that were TRANSIENT
     * (the backend unreachable, the network down). One keeps the
     * Review surface (a blip — retry Agree in place); reaching
     * [MAX_AGREE_FAILURES] routes to [UiState.Unavailable], the one
     * state whose host offers Continue — moderation genuinely could
     * not be reached, so consent is deferred, not dodged.
     *
     * A DETERMINISTIC refusal ([BackendRejectedException] — the
     * backend answered "no", e.g. a token-less enrollment from a
     * de-Googled device against a production backend) never routes
     * there: `Unavailable`'s Continue would let a refusing backend be
     * bypassed into unmoderated operation, when the honest state is
     * "moderation is offered and refuses this device" — Review with
     * the reason, retry only.
     */
    private var agreeFailures = 0

    /** Fetch directory + manifest for review. Idempotent per surface;
     * safe to call again to retry. */
    suspend fun load() = mutex.withLock {
        agreeFailures = 0
        // A controller is composition-scoped, and the surface can be
        // recreated (rotation) right after a NonCancellable agree()
        // persisted the mandate but before the caller's follow-up
        // ran. A fresh first-consent controller must re-emit
        // Consented for the already-active mandate — reloading to
        // Review here handed the user a second live Agree that minted
        // a SECOND backend enrollment and mandate record. (See
        // [resumeExistingMandate] for why re-consent hosts opt out.)
        if (resumeExistingMandate) {
            runCatching { moderation.activeMandateRecord() }.getOrNull()?.let { record ->
                reviewed = null
                state.value = UiState.Consented(record)
                return
            }
        }
        state.value = UiState.Loading
        val listing = try {
            // The reference deployment designates one authority; a
            // picker becomes worthwhile when the directory grows.
            authoritiesFetcher.fetchLatest().first()
        } catch (e: Exception) {
            state.value = UiState.Unavailable(e.message)
            reviewed = null
            return
        }
        try {
            val signed = manifestFetcher.fetch(listing)
            reviewed = listing to signed
            state.value = UiState.Review(listing, signed.displayJson())
        } catch (e: Exception) {
            state.value = UiState.Unavailable(e.message)
            reviewed = null
        }
    }

    /**
     * Consent with the retained snapshot. On success the caller
     * records the step outcome and pokes the gate (`consentCompleted`).
     *
     * NonCancellable: `agree` runs on the surface's composition scope,
     * and the surface can leave composition mid-transaction (a gate
     * flip re-rendering the root). Cancellation between `enrollDevice`
     * and the mandate save would orphan a backend enrollment and
     * strand the controller in `Consenting` with no way back — the
     * transaction is short, so it completes; only the caller's
     * follow-up (`onConsented`) is lost, and the gate's own next check
     * re-derives from the persisted record.
     */
    suspend fun agree(): MandateRecord? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { agreeInner() }

    private suspend fun agreeInner(): MandateRecord? = mutex.withLock {
        val (listing, signed) = reviewed ?: return@withLock null
        state.value = UiState.Consenting
        try {
            val record = moderation.consent(listing, ReviewedManifest(signed))
            agreeFailures = 0
            state.value = UiState.Consented(record)
            record
        } catch (e: app.onym.android.moderation.ModerationUnsupportedDeviceException) {
            // No-GMS hardware (Huawei-class) refused a token-less
            // enrollment: the rail structurally cannot exist here, so
            // this is deferrable unavailability, not a judged
            // refusal — retrying cannot change the hardware.
            state.value = UiState.Unavailable(e.message)
            null
        } catch (e: Exception) {
            val deterministicRefusal = e is BackendRejectedException
            if (!deterministicRefusal) agreeFailures += 1
            state.value = if (!deterministicRefusal && agreeFailures >= MAX_AGREE_FAILURES) {
                UiState.Unavailable(e.message)
            } else {
                UiState.Review(
                    listing,
                    signed.displayJson(),
                    error = e.message ?: "consent failed",
                )
            }
            null
        }
    }

    private companion object {
        const val MAX_AGREE_FAILURES = 2
    }
}
