package app.onym.android.moderation.ui

import app.onym.android.moderation.AuthorityListing
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

    /** Fetch directory + manifest for review. Idempotent per surface;
     * safe to call again to retry. */
    suspend fun load() = mutex.withLock {
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

    /** Consent with the retained snapshot. On success the caller
     * records the step outcome and pokes the gate (`consentCompleted`). */
    suspend fun agree(): MandateRecord? = mutex.withLock {
        val (listing, signed) = reviewed ?: return null
        state.value = UiState.Consenting
        try {
            val record = moderation.consent(listing, ReviewedManifest(signed))
            state.value = UiState.Consented(record)
            record
        } catch (e: ModerationConsentException) {
            state.value = UiState.Review(listing, signed.displayJson(), error = e.message)
            null
        } catch (e: Exception) {
            state.value = UiState.Review(
                listing,
                signed.displayJson(),
                error = e.message ?: "consent failed",
            )
            null
        }
    }
}
