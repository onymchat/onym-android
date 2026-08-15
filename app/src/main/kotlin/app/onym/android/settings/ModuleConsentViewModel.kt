package app.onym.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.onym.android.discovery.AttributedCatalogEntry
import app.onym.android.discovery.DiscoverySource
import app.onym.android.foundation.EntitlementProviding
import app.onym.android.foundation.FreeTierEntitlementProvider
import app.onym.android.foundation.PinnedConsentStore
import app.onym.android.foundation.ReviewedServiceManifest
import app.onym.android.foundation.ServiceManifestException
import app.onym.android.foundation.ServiceManifestReviewer
import app.onym.android.foundation.SignedServiceManifest
import app.onym.android.discovery.ModuleSelection
import app.onym.android.discovery.SeatSelectionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.time.Instant

/**
 * Generalized consent flow for non-moderation seats (transport,
 * notary, blob storage): fetch the catalog entry's manifest, verify
 * it against the catalog-pinned digest, put the exact reviewed terms
 * on screen, and on accept pin the consent + record the selection +
 * apply the endpoint to the seat's legacy configuration.
 *
 * The moderation seat must never route through this flow — there is
 * no mandate flow on Android yet, so moderation entries are hidden
 * upstream entirely; the guard here is defense in depth.
 *
 * Mirrors `ModuleConsentFlow.swift` from onym-ios
 * `discovery-settings-ui`, Android-idiomatic types.
 */
class ModuleConsentViewModel(
    /** The catalog entry under review, or null when the route's
     *  entry lookup came back empty (the aggregate changed under the
     *  navigation) — the flow then reports a stable error instead of
     *  crashing. */
    val entry: AttributedCatalogEntry?,
    private val fetchManifestBytes: suspend (uri: String) -> ByteArray,
    private val consentStore: PinnedConsentStore,
    private val selectionStore: SeatSelectionStore,
    /** Seat-specific: write the consented manifest's endpoint into
     *  the seat's legacy configuration (the operational source of
     *  truth). */
    private val applyToConfiguration: suspend (SignedServiceManifest) -> Unit,
    entitlements: EntitlementProviding? = null,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    enum class Step { Loading, Reviewing, Applying, Done }

    data class State(
        val step: Step = Step.Loading,
        /** The exact manifest on the consent surface. Being a
         *  [ReviewedServiceManifest], it can only have come from the
         *  reviewer's verified path — what's shown is what gets
         *  pinned. */
        val reviewed: ReviewedServiceManifest? = null,
        val errorMessage: String? = null,
        /** Offers the entitlement layer currently grants (free tier
         *  today). Everything else renders disabled. */
        val entitledOfferIds: Set<String> = emptySet(),
        /** The offer the accept will record, when the manifest
         *  carries any. */
        val selectedOfferId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Free offers gate: whether an offer is free lives in the
     *  manifest under review, so the default provider looks the offer
     *  up there. A billing-backed provider slots in via the
     *  constructor parameter later. */
    private val entitlements: EntitlementProviding =
        entitlements ?: FreeTierEntitlementProvider { offerId, componentId ->
            _state.value.reviewed?.signedManifest
                ?.takeIf { it.componentId == componentId }
                ?.offers
                ?.firstOrNull { it.offerId == offerId }
        }

    private var loading = false

    // ─── read helpers ─────────────────────────────────────────────

    /** Display name: the manifest's `name` when published, else the
     *  component id minus its `onym:component:` prefix. */
    val displayName: String
        get() {
            manifestField("name")?.takeIf { it.isNotEmpty() }?.let { return it }
            return shortComponentId(entry?.entry?.componentId ?: "")
        }

    /** `termsUri` / `terms` https URL out of the manifest's raw
     *  bytes, when the operator linked terms. */
    val termsUrl: String?
        get() = listOf("termsUri", "terms").firstNotNullOfOrNull { key ->
            manifestField(key)?.takeIf { value ->
                try {
                    URI(value).scheme?.lowercase() == "https"
                } catch (_: Exception) {
                    false
                }
            }
        }

    /** Grouped fingerprint of the operator key the manifest is signed
     *  by — same format as the provider TOFU screen. */
    val operatorKeyFingerprint: String?
        get() = _state.value.reviewed
            ?.let { DiscoverySource.fingerprint(it.signedManifest.operatorPublicKeyHex) }

    private fun manifestField(key: String): String? {
        val raw = _state.value.reviewed?.signedManifest?.rawBytes ?: return null
        val obj = try {
            Json.parseToJsonElement(String(raw, Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        }
        return (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    // ─── lifecycle ────────────────────────────────────────────────

    /** Fetch + review the entry's manifest. Idempotent while loading;
     *  [tappedRetry] re-arms after a failure. */
    fun start() {
        if (loading || _state.value.reviewed != null) return
        loading = true
        viewModelScope.launch {
            load()
            loading = false
        }
    }

    fun tappedRetry() {
        _state.value = _state.value.copy(errorMessage = null, step = Step.Loading)
        start()
    }

    fun selectOffer(offerId: String) {
        if (offerId !in _state.value.entitledOfferIds) return
        _state.value = _state.value.copy(selectedOfferId = offerId)
    }

    private suspend fun load() {
        val entry = entry
        if (entry == null) {
            _state.value = _state.value.copy(
                errorMessage = "This catalog entry is no longer available.",
            )
            return
        }
        // Defense in depth: moderation entries are hidden from every
        // picker, but a moderation entry must never reach this
        // surface — its consent is a signed mandate, and Android has
        // no mandate flow yet.
        if (entry.entry.seatType == MODERATION_SEAT_TYPE) {
            _state.value = _state.value.copy(
                errorMessage = "Moderation authorities are not supported in this app yet.",
            )
            return
        }
        try {
            val bytes = fetchManifestBytes(entry.entry.manifest.uri)
            // Hard-enforced review: digest pin from the verified
            // catalog entry, embedded operator signature over the
            // exact bytes, expiry.
            val reviewed = ServiceManifestReviewer().review(
                raw = bytes,
                expectedDigest = entry.entry.manifest.digest,
                now = now(),
            )
            // The catalog is the verified side of this trust chain:
            // a fetched manifest naming a different operator key than
            // the entry is rejected, digest pin notwithstanding.
            if (reviewed.signedManifest.operatorKey != entry.entry.operator) {
                _state.value = _state.value.copy(
                    errorMessage = "The service's manifest names a different operator key " +
                        "than the catalog entry. Not safe to accept.",
                )
                return
            }
            if (reviewed.signedManifest.seat != entry.entry.seatType) {
                _state.value = _state.value.copy(
                    errorMessage = "The service's manifest declares a different seat " +
                        "than the catalog entry. Not safe to accept.",
                )
                return
            }
            // Publish the reviewed manifest first so the default
            // entitlement provider's offer lookup can see it.
            _state.value = _state.value.copy(reviewed = reviewed)
            val manifest = reviewed.signedManifest
            val entitled = mutableSetOf<String>()
            for (offer in manifest.offers) {
                if (entitlements.entitlement(offer.offerId, manifest.componentId) != null) {
                    entitled.add(offer.offerId)
                }
            }
            _state.value = _state.value.copy(
                step = Step.Reviewing,
                entitledOfferIds = entitled,
                // Preselect the first offer the user could actually take.
                selectedOfferId = _state.value.selectedOfferId
                    ?: manifest.offers.firstOrNull { it.offerId in entitled }?.offerId,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(errorMessage = message(e))
        }
    }

    // ─── accept ───────────────────────────────────────────────────

    /**
     * "Accept" on the consent surface. Pins the exact reviewed
     * bytes, records the selection provenance, then applies the
     * endpoint to the seat's legacy configuration.
     */
    fun tappedAccept() {
        val current = _state.value
        val reviewed = current.reviewed ?: return
        val entry = entry ?: return
        if (current.step != Step.Reviewing) return
        _state.value = current.copy(step = Step.Applying, errorMessage = null)
        viewModelScope.launch {
            try {
                val acceptedAt = now()
                val record = consentStore.accept(
                    reviewed = reviewed,
                    sourceLabel = entry.attribution.sourceLabel,
                    offerId = _state.value.selectedOfferId,
                    acceptedAt = acceptedAt,
                )
                selectionStore.appendSelection(
                    ModuleSelection(
                        seatType = entry.entry.seatType,
                        componentId = entry.entry.componentId,
                        providerId = entry.attribution.providerId,
                        manifestHash = record.manifestHash,
                        offerId = record.offerId,
                        selectedAt = acceptedAt.toString(),
                    ),
                )
                applyToConfiguration(reviewed.signedManifest)
                _state.value = _state.value.copy(step = Step.Done)
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    step = Step.Reviewing,
                    errorMessage = "Couldn't record consent. Try again.",
                )
            }
        }
    }

    companion object {
        /** Seat whose consent is a signed mandate, not a local pin.
         *  Entries with this seat are hidden from every Android
         *  picker until a moderation port exists. */
        const val MODERATION_SEAT_TYPE = "moderation"

        fun shortComponentId(componentId: String): String =
            componentId.removePrefix("onym:component:")

        /** Wire values like `common-owner` read as "common owner".
         *  Unknown future values pass through verbatim (minus
         *  hyphens) — the disclosure must never be dropped for being
         *  unrecognized. */
        fun disclosureText(wireValue: String): String = wireValue.replace('-', ' ')

        private fun message(e: Exception): String = when (e) {
            is ServiceManifestException.DigestMismatch ->
                "The fetched manifest doesn't match the bytes the catalog reviewed. " +
                    "Not safe to accept."
            is ServiceManifestException.SignatureInvalid ->
                "The manifest's signature failed verification (${e.reason})."
            is ServiceManifestException.ManifestInvalid ->
                "The manifest is malformed (${e.reason})."
            is ServiceManifestException.Expired ->
                "The manifest has expired. The operator needs to publish a fresh one."
            else -> "Couldn't load the service's manifest. Try again."
        }
    }
}
