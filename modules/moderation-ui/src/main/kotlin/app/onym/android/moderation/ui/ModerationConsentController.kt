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

        /**
         * More than one authority in the directory: the user picks
         * WHO may judge them before reviewing WHAT they'd be agreeing
         * to (iOS `ModerationConsentFlow.pickingAuthority`). A
         * single-entry directory skips this state — the pick is
         * forced, so the review is the decision.
         */
        data class Picking(
            val listings: List<AuthorityListing>,
            val error: String? = null,
        ) : UiState

        /**
         * The reviewed snapshot on screen, DECODED for structured
         * rendering (class cards, procedure, hash — iOS parity with
         * `ModerationConsentView`). What the mandate pins stays the
         * retained exact bytes in the controller's snapshot;
         * [manifestHash] is the hash of those bytes, shown so the
         * user can see what their signature binds.
         */
        data class Review(
            val listing: AuthorityListing,
            val manifest: app.onym.android.moderation.AuthorityManifest,
            val manifestHash: String,
            val error: String? = null,
            /** True when the directory offers alternatives — renders
             *  the back-to-picker affordance. */
            val canPickAnother: Boolean = false,
        ) : UiState

        data object Consenting : UiState

        data class Consented(val record: MandateRecord) : UiState
    }

    private val mutex = Mutex()
    private val state = MutableStateFlow<UiState>(UiState.Loading)
    val snapshots: StateFlow<UiState> = state.asStateFlow()

    /** The retained review snapshot `agree` consents with. */
    private var reviewed: Pair<AuthorityListing, SignedManifest>? = null

    /** The last fetched directory — what the picker offers and what
     *  [backToAuthorities] returns to. */
    private var directory: List<AuthorityListing> = emptyList()

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
        val listings = try {
            authoritiesFetcher.fetchLatest()
        } catch (e: Exception) {
            state.value = UiState.Unavailable(e.message)
            reviewed = null
            return
        }
        directory = listings
        if (listings.size == 1) {
            // A single-entry directory forces the pick, so the review
            // IS the decision — no picker interlude, and a manifest
            // failure is the step's unavailability.
            reviewListingLocked(listings.single(), onFailure = { e ->
                state.value = UiState.Unavailable(e.message)
            })
        } else {
            state.value = UiState.Picking(listings)
        }
    }

    /**
     * Row tap in the authority picker: fetch and stage that
     * authority's manifest for review. A fetch failure returns to the
     * picker with the reason — the OTHER authorities are still
     * offerable, so one unreachable manifest must not declare the
     * whole step unavailable.
     */
    suspend fun select(listing: AuthorityListing) = mutex.withLock {
        state.value = UiState.Loading
        reviewListingLocked(listing, onFailure = { e ->
            state.value = UiState.Picking(directory, error = e.message)
        })
    }

    /** Back from the review surface to the picker. */
    suspend fun backToAuthorities() = mutex.withLock {
        if (directory.size <= 1) return@withLock
        reviewed = null
        state.value = UiState.Picking(directory)
    }

    private suspend fun reviewListingLocked(
        listing: AuthorityListing,
        onFailure: (Exception) -> Unit,
    ) {
        try {
            val signed = manifestFetcher.fetch(listing)
            reviewed = listing to signed
            // A persisted permanent-refusal reason (the boot-time
            // registration retry deactivated the mandate with no
            // screen to explain on) renders as the Review error: the
            // user should see WHY their consent needs redoing, not
            // just that it does. Null in the ordinary first-consent
            // case, and quiet again once a consent activates.
            val priorRefusal = runCatching { moderation.registrationRefusal() }.getOrNull()
            state.value = UiState.Review(
                listing,
                signed.manifest,
                signed.manifestHash,
                error = priorRefusal,
                canPickAnother = directory.size > 1,
            )
        } catch (e: Exception) {
            reviewed = null
            onFailure(e)
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
            // The repository only lets DETERMINISTIC authority
            // rejections escape consent (transient registration
            // failures keep the artifact and succeed), so any
            // AuthorityRejectedException here is a judged "no" — and
            // the receipt refusals (wrong mandateRef, accepted:false)
            // are the same permanence under their own type.
            val deterministicRefusal =
                e is BackendRejectedException ||
                    e is app.onym.android.moderation.AuthorityRejectedException ||
                    e is app.onym.android.moderation.AuthorityRegistrationRefusedException
            if (!deterministicRefusal) agreeFailures += 1
            state.value = if (!deterministicRefusal && agreeFailures >= MAX_AGREE_FAILURES) {
                UiState.Unavailable(e.message)
            } else {
                UiState.Review(
                    listing,
                    signed.manifest,
                    signed.manifestHash,
                    error = e.message ?: "consent failed",
                    canPickAnother = directory.size > 1,
                )
            }
            null
        }
    }

    private companion object {
        const val MAX_AGREE_FAILURES = 2
    }
}
