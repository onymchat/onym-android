package app.onym.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pin-on-accept for the SEEDED default discovery source.
 *
 * TRUST RATIONALE. The recommended services card promises "Services
 * published by Onym, verified on this device" — accepting it IS the
 * user's explicit confirm of the seeded directory, so advancing from
 * the services step with the recommendation selected runs the same
 * fetch → verify → TOFU-pin path the hub Directory seat's "Verify &
 * Confirm" drives, programmatically. Scope is deliberately narrow:
 *
 *  - SEEDED SOURCE ONLY — never a user-added URL. A URL the user
 *    typed gets the interactive fingerprint confirmation (the
 *    out-of-band verification affordance); the shipped default's
 *    trust anchor is the app binary that carries its URL, and the
 *    accept tap is the consent.
 *  - Verification is not weakened: the manifest fetched at accept
 *    time is signature-verified before pinning, and every later
 *    manifest is verified against the pinned key — a rotated or
 *    spoofed key is rejected exactly as on the interactive path.
 *  - IDEMPOTENT — a no-op when the seeded source is already pinned
 *    (an earlier accept, or the user confirmed it in the hub) or
 *    when the user removed it.
 *  - NON-BLOCKING — failure (offline first-run) leaves the source
 *    unpinned; the walk proceeds and the Done summary shows the
 *    honest "Not confirmed" state. Nothing retries here; the next
 *    accept or the hub can.
 *
 * The collaborators are injected as closures so the accept-triggers-
 * pin seam is unit-testable without the discovery stack.
 */
internal class RecommendedDirectoryPinner(
    /**
     * The seeded source's current pin status: null = absent (the
     * user removed the default — respect that, never re-add), true =
     * already pinned, false = present and unpinned.
     */
    private val seededSourcePinned: suspend () -> Boolean?,
    /**
     * The programmatic TOFU of the seeded source: fetch + verify its
     * manifest, check it is still the expected provider, pin the
     * operator key, refresh so catalogs populate. Throws on
     * network/verification failure.
     */
    private val pinSeededSource: suspend () -> Unit,
) {

    enum class Outcome {
        /** The seeded source was unpinned and is now pinned. */
        Pinned,

        /** Already pinned (earlier accept, or hub confirm) — no-op. */
        AlreadyPinned,

        /** The user removed the seeded default — no-op, never re-add. */
        SourceAbsent,

        /** Fetch/verify failed (offline first-run) — the source stays
         *  unpinned; the walk is not blocked. */
        Failed,
    }

    /** Serializes concurrent accepts (advance + a racing re-accept)
     *  so the status check and the pin are one unit. */
    private val mutex = Mutex()

    suspend fun pinIfUnpinned(): Outcome = mutex.withLock {
        when (seededSourcePinned()) {
            null -> Outcome.SourceAbsent
            true -> Outcome.AlreadyPinned
            false -> try {
                pinSeededSource()
                Outcome.Pinned
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                Outcome.Failed
            }
        }
    }
}
