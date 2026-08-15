package app.onym.android.foundation

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * A user's consent to one service manifest, plus everything needed to
 * honor it offline: the consented manifest snapshot (exact bytes —
 * re-verifiable, still renderable if the operator disappears).
 * The generic shape every non-moderation seat (courier, notary, blob
 * host, …) shares.
 *
 * Mirrors `PinnedConsentRecord` in onym-ios OnymFoundation. The
 * primary constructor is `internal` (used by the generated serializer
 * and the store inside this module); outside `:foundation` the only
 * way to build a record is [from] a token the reviewer minted — the
 * no-refetch invariant holds at the API level: the bytes pinned here
 * are the bytes that were verified and shown.
 */
@ConsistentCopyVisibility
@Serializable
data class PinnedConsentRecord internal constructor(
    /** `onym:component:<id>` of the consented service. */
    val componentId: String,
    /** The seat that service fills, from its manifest. */
    val seatType: String,
    /** `sha256:<hex>` over [manifestBytes]. */
    val manifestHash: String,
    /** The exact manifest bytes [manifestHash] was computed over.
     *  Never re-fetched, never re-encoded. Base64 in JSON, matching
     *  iOS `Data` encoding. */
    @Serializable(with = Base64ByteArraySerializer::class)
    val manifestBytes: ByteArray,
    /** Where the manifest came from at consent time (a discovery
     *  source's label, "Manual", …). Display provenance only. */
    val sourceLabel: String? = null,
    /** The offer the user selected, when the consent flow chose one. */
    val offerId: String? = null,
    @Serializable(with = InstantIso8601Serializer::class)
    val acceptedAt: Instant,
    /** Exactly one record is active per componentId. Re-consenting
     *  deactivates the old record without touching anything else —
     *  history is kept, consent artifacts are immutable. */
    val isActive: Boolean = true,
) {
    companion object {
        /** Build a record from a reviewer-minted token. */
        fun from(
            reviewed: ReviewedServiceManifest,
            sourceLabel: String? = null,
            offerId: String? = null,
            acceptedAt: Instant,
            isActive: Boolean = true,
        ): PinnedConsentRecord {
            val manifest = reviewed.signedManifest
            return PinnedConsentRecord(
                componentId = manifest.componentId,
                seatType = manifest.seat,
                manifestHash = manifest.manifestHash,
                manifestBytes = manifest.rawBytes,
                sourceLabel = sourceLabel,
                offerId = offerId,
                acceptedAt = acceptedAt,
                isActive = isActive,
            )
        }
    }

    /** The consented manifest, decoded from the stored snapshot. A
     *  manifest that was accepted at consent time keeps decoding from
     *  the pinned bytes; `null` only for a corrupted store. */
    fun consentedManifest(): SignedServiceManifest? = try {
        SignedServiceManifest.parse(manifestBytes)
    } catch (_: ServiceManifestException) {
        null
    }

    // data-class equals/hashCode are wrong for ByteArray fields
    // (reference equality) — compare content instead.
    override fun equals(other: Any?): Boolean =
        other is PinnedConsentRecord &&
            componentId == other.componentId &&
            seatType == other.seatType &&
            manifestHash == other.manifestHash &&
            manifestBytes.contentEquals(other.manifestBytes) &&
            sourceLabel == other.sourceLabel &&
            offerId == other.offerId &&
            acceptedAt == other.acceptedAt &&
            isActive == other.isActive

    override fun hashCode(): Int {
        var result = componentId.hashCode()
        result = 31 * result + manifestHash.hashCode()
        result = 31 * result + acceptedAt.hashCode()
        result = 31 * result + isActive.hashCode()
        return result
    }
}

/**
 * `java.time.Instant` as an ISO 8601 UTC string (e.g.
 * `"2026-08-13T12:00:00Z"`) — matching the iOS store's
 * `.iso8601` date strategy for the consent records.
 */
internal object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("InstantIso8601", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant =
        Instant.parse(decoder.decodeString())
}
