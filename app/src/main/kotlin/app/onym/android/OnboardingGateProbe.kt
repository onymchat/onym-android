package app.onym.android

import app.onym.android.chain.RelayerConfiguration
import app.onym.android.onboarding.OnboardingGate
import app.onym.android.onboarding.OnboardingStore
import app.onym.android.transport.blossom.BlossomServersConfiguration
import app.onym.android.transport.nostr.NostrRelaysConfiguration

/**
 * The grandfathering probe [OnymApplication] hands to
 * `OnboardingGate.shouldOnboard`: users who predate onboarding have no
 * completion flag but plenty of configured state, and must not be
 * onboarded as if the app were fresh.
 *
 * Existing-user signals, in the plan's terms ("any hasUserInteracted
 * true or non-empty configs"):
 *  - the relayer configuration was ever touched — auto-populate flips
 *    `hasUserInteracted` on install, so any pre-onboarding user who
 *    ever fetched the published list reads true — or carries
 *    endpoints (belt for a config predating the sticky bit);
 *  - the Nostr-relay or Blossom-server list was user-touched. Their
 *    `endpoints` are deliberately NOT a signal: both seed a default
 *    endpoint on first launch with `hasUserInteracted = false`, so
 *    non-emptiness there is true on a fresh install too;
 *  - [identityInteracted]: identity state that only a real user
 *    produces — a STORED identity with a user-set (non-blank) name,
 *    or more than one identity
 *    (`IdentityRepository.hasNamedOrMultipleIdentities`). Bare
 *    existence is deliberately not the signal: the eager
 *    `IdentityRepository.bootstrap()` auto-generates a blank-named
 *    identity on a fresh install, so "an identity exists" is true
 *    for everyone (and the SUMMARY names are display-mapped to
 *    "Identity N", which is why the raw stored names are read, not
 *    `identities`). This closes the offline pre-onboarding case: a
 *    user whose relayer fetch never succeeded and who never opened
 *    transport settings still reads existing once they named an
 *    identity or added a second one.
 *
 * Pure function of the persisted configurations so the truth table is
 * unit-testable; the caller resolves the reads (after the respective
 * bootstraps) and BEFORE the first relayer fetch — the fetch's
 * auto-populate flips the relayer bit, so racing it would misread a
 * fresh user as existing.
 */
object OnboardingGateProbe {
    fun isExistingUser(
        relayer: RelayerConfiguration,
        nostr: NostrRelaysConfiguration,
        blossom: BlossomServersConfiguration,
        identityInteracted: Boolean = false,
    ): Boolean =
        relayer.hasUserInteracted ||
            relayer.endpoints.isNotEmpty() ||
            nostr.hasUserInteracted ||
            blossom.hasUserInteracted ||
            identityInteracted

    /**
     * The full launch-time decision, fail-open: under the UI-test
     * harness the walk never presents (existing instrumented tests
     * predate onboarding; PR 4 drives it deliberately via registry
     * slots), and ANY error out of the completion-flag read or the
     * grandfathering probe resolves to `false` — a skipped walk is an
     * annoyance, while an unhandled throw here crashed the launch and
     * a swallowed one left the decision null (permanent blank frame)
     * with the relayer fetch never fired.
     */
    suspend fun resolvePending(
        uiTestMode: Boolean,
        store: OnboardingStore,
        isExistingUser: suspend () -> Boolean,
    ): Boolean {
        if (uiTestMode) return false
        return runCatching {
            OnboardingGate.shouldOnboard(store = store, isExistingUser = isExistingUser)
        }.getOrDefault(false)
    }
}
