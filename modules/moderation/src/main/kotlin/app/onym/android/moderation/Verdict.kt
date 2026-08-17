package app.onym.android.moderation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The governing verdict, as the gate serves it inside [BanState].
 *
 * Kept opaque until now — the ban screen rendered only the flat fields
 * beside it — which left the in-app appeal unreachable for the one
 * person who needs it most. `POST /v1/cases/{caseId}/appeal` needs a
 * case id, and [BanState] has none; the verdict does, and the Android
 * profile's gate always attaches it
 * (`android/src/enforcement.rs`, `ban_state`: `verdict: Some(...)`).
 * iOS reads the same two fields for the same reason
 * (`BannedView.caseFlowBuilder`).
 *
 * Decoded leniently and never trusted structurally: this is display
 * plus a case reference, not an authorization. `ModerationJson` ignores
 * unknown keys, so an authority that adds fields does not brick the ban
 * screen — and a verdict that fails to decode entirely degrades to the
 * URL-only screen that shipped before, rather than to a blank one.
 *
 * NOT validated against the consented manifest here: iOS's
 * `VerdictValidator` + `sanitize` remain a deferred seam, so
 * [reasoning] is the authority's own words shown as such, never
 * rendered as app copy.
 */
@Serializable
data class Verdict(
    val caseId: String,
    /** The mandate this verdict was decided under — the standing an
     *  appeal is filed on. */
    val mandateRef: String = "",
    val authority: String = "",
    val classId: String = "",
    val disposition: String = "",
    /** The authority's stated grounds. Display only. */
    val reasoning: String = "",
    /** RFC 3339. Absent means the class declares no appeal window, not
     *  that appealing is refused — only the authority decides that, and
     *  it answers 4xx when it does. */
    val appealDeadline: String? = null,
    val banExpires: String? = null,
    val decidedAt: String = "",
    /**
     * `final` on the wire — Kotlin's `final` is a hard keyword, so the
     * property is renamed rather than backticked at every use site.
     * A final verdict is still appealable where the class allows it;
     * this flags exhaustion of the authority's own review, which the
     * screen does not currently render.
     */
    @SerialName("final")
    val isFinal: Boolean = false,
) {
    companion object {
        /**
         * Decode the gate's attached verdict, or null when it is
         * absent, unparseable, or carries no case id.
         *
         * Null is a supported state, not an error: a deployment whose
         * gate answers from a stored verdict_ref alone still gets the
         * ban screen, just without the in-app route.
         */
        fun from(element: JsonElement?): Verdict? {
            val json = element ?: return null
            return runCatching {
                ModerationJson.json.decodeFromJsonElement(serializer(), json)
            }.getOrNull()?.takeIf { it.caseId.isNotBlank() }
        }
    }
}
