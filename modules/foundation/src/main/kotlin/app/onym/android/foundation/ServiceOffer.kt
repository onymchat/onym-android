package app.onym.android.foundation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One entry of a service manifest's `offers` array — the commercial
 * terms a seat operator publishes alongside its technical manifest.
 *
 * Only the spine every seat shares is decoded (`offerId`, `model`,
 * optional `period`); everything under `service` is seat-specific
 * and kept opaque for the seat adapter (or a future billing layer)
 * to interpret.
 *
 * Mirrors `ServiceOffer` in onym-ios OnymFoundation.
 */
data class ServiceOffer(
    /** Operator-scoped offer identifier. */
    val offerId: String,
    /** Pricing model. "free", "subscription", and "consumable" are the
     *  models this app understands today; unknown values are preserved
     *  verbatim so a manifest can introduce one without breaking the
     *  decode — they simply gate as not-free. */
    val model: String,
    /** Billing period for recurring models (e.g. "monthly"), when
     *  published. */
    val period: String? = null,
    /** The offer's seat-specific `service` object, re-serialized as
     *  compact key-sorted JSON. Opaque at this layer: not
     *  signature-relevant (signatures cover the manifest's exact
     *  `rawBytes`), just a faithful carrier for whoever understands
     *  the seat's schema. */
    val serviceJson: String? = null,
) {
    /** Free offers are the only ones the stub entitlement layer can
     *  grant; a billing provider slots in behind [EntitlementProviding]
     *  later. */
    val isFree: Boolean get() = model == "free"

    companion object {
        /**
         * Lossy decode of a manifest's `offers` value: a malformed
         * entry (not an object, missing `offerId`/`model`) is skipped
         * rather than failing the whole manifest — offers are
         * presentation metadata, not trust material.
         */
        internal fun decodeLossy(value: JsonElement?): List<ServiceOffer> {
            val array = value as? JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val offerId = obj.stringField("offerId")?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val model = obj.stringField("model")?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val serviceJson = (obj["service"] as? JsonObject)?.let { service ->
                    Json.encodeToString(
                        JsonElement.serializer(),
                        ServiceManifestCanonical.sortKeysRecursively(service),
                    )
                }
                ServiceOffer(
                    offerId = offerId,
                    model = model,
                    period = obj.stringField("period"),
                    serviceJson = serviceJson,
                )
            }
        }

        private fun JsonObject.stringField(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
