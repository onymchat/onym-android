package app.onym.android.backup

import app.onym.android.foundation.ServiceManifestCanonical
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Minimal ISO-8601 duration parser/formatter for `BackupTerms`'
 * declared-duration fields: `PnYnMnWnDTnHnMnS`. Years and months are
 * approximated (365d / 30d respectively) — these fields are compared
 * for *regression*, not scheduled against, so the approximation only
 * has to be consistent between two parses of the same unit.
 */
object BackupDuration {
    /** `"until-erased"` — no upper bound. */
    const val UNTIL_ERASED = Long.MAX_VALUE

    private val PATTERN = Regex(
        """^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""",
    )

    /** Parses a declared-duration string to whole seconds.
     *  `"until-erased"` → [UNTIL_ERASED]. `"none"` → `0`. Anything
     *  unparseable → `null` (callers must treat `null` as the
     *  weakest/most-regressive value — an undeclared duration proves
     *  nothing about strength). */
    fun seconds(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed == "until-erased") return UNTIL_ERASED
        if (trimmed == "none") return 0L
        if (trimmed.isEmpty() || trimmed == "P" || trimmed == "PT") return null
        val match = PATTERN.matchEntire(trimmed) ?: return null
        val (y, mo, w, d, h, mi, s) = match.destructured
        fun Long(part: String) = if (part.isEmpty()) 0L else part.toLong()
        return Long(y) * 365 * 86400 +
            Long(mo) * 30 * 86400 +
            Long(w) * 7 * 86400 +
            Long(d) * 86400 +
            Long(h) * 3600 +
            Long(mi) * 60 +
            Long(s)
    }
}

class BackupTerms(
    val termsVersion: Int,
    val operator: String,
    val retention: Retention,
    val erasure: Erasure,
    val jurisdictions: List<String>,
    val subProcessors: List<SubProcessor>,
    val lawfulAccess: LawfulAccess,
    val breachDisclosureHolderNotice: String,
    val export: Export,
    val shutdownNotice: String,
    val endOfPayment: EndOfPayment,
    val metadataRetention: MetadataRetention,
    val signature: String,
    /** The exact bytes as served — `termsId` and every canonical-bytes
     *  operation are over this, never a re-encode. */
    val rawBytes: ByteArray,
) {
    /** Every surfaced property is a pure function of [rawBytes], so
     *  equality is over the exact bytes — same posture as
     *  `SignedServiceManifest`. */
    override fun equals(other: Any?): Boolean =
        other is BackupTerms && rawBytes.contentEquals(other.rawBytes)

    override fun hashCode(): Int = rawBytes.contentHashCode()

    /** `sha256:<hex>` over the canonical bytes with `termsId` and
     *  `signature` omitted structurally. */
    val termsId: String by lazy {
        BackupFormat.digest(ServiceManifestCanonical.signingBytes(rawBytes, omitting = setOf("termsId", "signature")))
    }

    data class Retention(
        val retentionClass: String,
        val maximumRetentionPeriod: String,
        val snapshotsRetained: String,
        val expiryBehavior: String,
    )

    data class Erasure(
        val acknowledgementDeadline: String,
        val completionDeadline: String,
        val scope: String,
        val excluded: String,
    )

    data class SubProcessor(val role: String, val jurisdiction: String)

    data class LawfulAccess(
        val disclosureWhatIsProduced: String,
        val notifyHolderWhenPermitted: Boolean,
        val transparencyReporting: String?,
    )

    data class Export(val format: String, val availableWhileUnpaid: Boolean)

    data class EndOfPayment(
        val notice: String,
        val grace: String,
        val duringGrace: List<String>,
        val afterGrace: String,
    )

    data class MetadataRetention(
        val accessLogs: String,
        val sizeAndTiming: String,
        val holderIdentifiers: String,
        val operationOutcomes: String,
        val erasureReceipts: String,
        val entitlementRecords: String,
    )

    /**
     * The forward-only check: returns the first field name on which
     * `this` is weaker than [pinned] (the terms an existing snapshot
     * was accepted under), or `null` if `this` regresses nothing.
     * Fails closed everywhere — an unparseable or ambiguous field
     * counts as a regression rather than being skipped.
     *
     * Duration fields: which direction is "weaker" depends on what
     * the field promises the holder, not a uniform "longer is worse."
     * A longer *retention* or *metadata-retention* period, or a longer
     * *erasure deadline*, is weaker (holds/keeps things longer than
     * before). A shorter *notice* or *grace* period is weaker (less
     * warning than before). Free-text scope fields regress on any
     * change — an implementation can't safely infer that a changed
     * sentence only widened. `jurisdictions`/`subProcessors` may only
     * shrink.
     */
    fun regresses(pinned: BackupTerms): String? {
        fun weaker(field: String, new: String, old: String, longerIsWeaker: Boolean): String? {
            val newSeconds = BackupDuration.seconds(new)
            val oldSeconds = BackupDuration.seconds(old)
            if (newSeconds == null || oldSeconds == null) return field
            val isWeaker = if (longerIsWeaker) newSeconds > oldSeconds else newSeconds < oldSeconds
            return if (isWeaker) field else null
        }

        weaker("retention.maximumRetentionPeriod", retention.maximumRetentionPeriod, pinned.retention.maximumRetentionPeriod, longerIsWeaker = true)?.let { return it }
        weaker("erasure.acknowledgementDeadline", erasure.acknowledgementDeadline, pinned.erasure.acknowledgementDeadline, longerIsWeaker = true)?.let { return it }
        weaker("erasure.completionDeadline", erasure.completionDeadline, pinned.erasure.completionDeadline, longerIsWeaker = true)?.let { return it }
        weaker("breachDisclosureHolderNotice", breachDisclosureHolderNotice, pinned.breachDisclosureHolderNotice, longerIsWeaker = true)?.let { return it }
        weaker("shutdownNotice", shutdownNotice, pinned.shutdownNotice, longerIsWeaker = false)?.let { return it }
        weaker("endOfPayment.notice", endOfPayment.notice, pinned.endOfPayment.notice, longerIsWeaker = false)?.let { return it }
        weaker("endOfPayment.grace", endOfPayment.grace, pinned.endOfPayment.grace, longerIsWeaker = false)?.let { return it }
        weaker("metadataRetention.accessLogs", metadataRetention.accessLogs, pinned.metadataRetention.accessLogs, longerIsWeaker = true)?.let { return it }
        weaker("metadataRetention.sizeAndTiming", metadataRetention.sizeAndTiming, pinned.metadataRetention.sizeAndTiming, longerIsWeaker = true)?.let { return it }
        weaker("metadataRetention.holderIdentifiers", metadataRetention.holderIdentifiers, pinned.metadataRetention.holderIdentifiers, longerIsWeaker = true)?.let { return it }
        weaker("metadataRetention.operationOutcomes", metadataRetention.operationOutcomes, pinned.metadataRetention.operationOutcomes, longerIsWeaker = true)?.let { return it }
        weaker("metadataRetention.erasureReceipts", metadataRetention.erasureReceipts, pinned.metadataRetention.erasureReceipts, longerIsWeaker = true)?.let { return it }
        weaker("metadataRetention.entitlementRecords", metadataRetention.entitlementRecords, pinned.metadataRetention.entitlementRecords, longerIsWeaker = true)?.let { return it }

        if (retention.retentionClass != pinned.retention.retentionClass) return "retention.retentionClass"
        if (retention.snapshotsRetained != pinned.retention.snapshotsRetained) return "retention.snapshotsRetained"
        if (erasure.scope != pinned.erasure.scope) return "erasure.scope"
        if (erasure.excluded != pinned.erasure.excluded) return "erasure.excluded"
        if (export.format != pinned.export.format) return "export.format"
        if (lawfulAccess.disclosureWhatIsProduced != pinned.lawfulAccess.disclosureWhatIsProduced) return "lawfulAccess.disclosureWhatIsProduced"
        if (endOfPayment.afterGrace != pinned.endOfPayment.afterGrace) return "endOfPayment.afterGrace"

        if (!pinned.jurisdictions.containsAll(jurisdictions)) return "jurisdictions"
        val pinnedProcessors = pinned.subProcessors.toSet()
        if (!subProcessors.all { it in pinnedProcessors }) return "subProcessors"

        if (!export.availableWhileUnpaid && pinned.export.availableWhileUnpaid) return "export.availableWhileUnpaid"
        if (!lawfulAccess.notifyHolderWhenPermitted && pinned.lawfulAccess.notifyHolderWhenPermitted) return "lawfulAccess.notifyHolderWhenPermitted"
        if (!pinned.endOfPayment.duringGrace.containsAll(endOfPayment.duringGrace)) return "endOfPayment.duringGrace"

        return null
    }

    companion object {
        /** Decode a `BackupTerms` document. Fails closed on any
         *  missing or malformed field.
         *  @throws BackupError.LocalFailure(TermsUnavailable-shaped) */
        fun decode(raw: ByteArray): BackupTerms {
            val obj = try {
                Json.parseToJsonElement(String(raw, Charsets.UTF_8)).jsonObject
            } catch (_: Exception) {
                throw BackupError.TermsUnavailable
            }
            try {
                fun JsonObject.str(key: String): String =
                    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw BackupError.TermsUnavailable
                fun JsonObject.optStr(key: String): String? =
                    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                fun JsonObject.obj(key: String): JsonObject = this[key]?.jsonObject ?: throw BackupError.TermsUnavailable
                fun JsonObject.bool(key: String): Boolean =
                    (this[key] as? JsonPrimitive)?.boolean ?: throw BackupError.TermsUnavailable
                fun JsonObject.strArray(key: String): List<String> =
                    (this[key] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: throw BackupError.TermsUnavailable

                val retentionObj = obj.obj("retention")
                val erasureObj = obj.obj("erasure")
                val lawfulAccessObj = obj.obj("lawfulAccess")
                val breachObj = obj.obj("breachDisclosure")
                val exportObj = obj.obj("export")
                val endOfPaymentObj = obj.obj("endOfPayment")
                val metadataObj = obj.obj("metadataRetention")
                val subProcessorsArray = obj["subProcessors"]?.jsonArray ?: throw BackupError.TermsUnavailable

                return BackupTerms(
                    termsVersion = (obj["termsVersion"] as? JsonPrimitive)?.content?.toIntOrNull()
                        ?: throw BackupError.TermsUnavailable,
                    operator = obj.str("operator"),
                    retention = Retention(
                        retentionClass = retentionObj.str("class"),
                        maximumRetentionPeriod = retentionObj.str("maximumRetentionPeriod"),
                        snapshotsRetained = retentionObj.str("snapshotsRetained"),
                        expiryBehavior = retentionObj.str("expiryBehavior"),
                    ),
                    erasure = Erasure(
                        acknowledgementDeadline = erasureObj.str("acknowledgementDeadline"),
                        completionDeadline = erasureObj.str("completionDeadline"),
                        scope = erasureObj.str("scope"),
                        excluded = erasureObj.str("excluded"),
                    ),
                    jurisdictions = obj.strArray("jurisdictions"),
                    subProcessors = subProcessorsArray.map {
                        val p = it.jsonObject
                        SubProcessor(role = p.str("role"), jurisdiction = p.str("jurisdiction"))
                    },
                    lawfulAccess = LawfulAccess(
                        disclosureWhatIsProduced = lawfulAccessObj.str("disclosureWhatIsProduced"),
                        notifyHolderWhenPermitted = lawfulAccessObj.bool("notifyHolderWhenPermitted"),
                        transparencyReporting = lawfulAccessObj.optStr("transparencyReporting"),
                    ),
                    breachDisclosureHolderNotice = breachObj.str("holderNotice"),
                    export = Export(
                        format = exportObj.str("format"),
                        availableWhileUnpaid = exportObj.bool("availableWhileUnpaid"),
                    ),
                    shutdownNotice = obj.str("shutdownNotice"),
                    endOfPayment = EndOfPayment(
                        notice = endOfPaymentObj.str("notice"),
                        grace = endOfPaymentObj.str("grace"),
                        duringGrace = endOfPaymentObj.strArray("duringGrace"),
                        afterGrace = endOfPaymentObj.str("afterGrace"),
                    ),
                    metadataRetention = MetadataRetention(
                        accessLogs = metadataObj.str("accessLogs"),
                        sizeAndTiming = metadataObj.str("sizeAndTiming"),
                        holderIdentifiers = metadataObj.str("holderIdentifiers"),
                        operationOutcomes = metadataObj.str("operationOutcomes"),
                        erasureReceipts = metadataObj.str("erasureReceipts"),
                        entitlementRecords = metadataObj.str("entitlementRecords"),
                    ),
                    signature = obj.str("signature"),
                    rawBytes = raw,
                )
            } catch (e: BackupError) {
                throw e
            } catch (_: Exception) {
                throw BackupError.TermsUnavailable
            }
        }
    }
}
