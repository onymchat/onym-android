package app.onym.android

import app.onym.android.chain.RelayerConfiguration
import app.onym.android.chain.RelayerEndpoint
import app.onym.android.transport.blossom.BlossomServerEndpoint
import app.onym.android.transport.blossom.BlossomServersConfiguration
import app.onym.android.transport.nostr.NostrRelayEndpoint
import app.onym.android.transport.nostr.NostrRelaysConfiguration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OnboardingGateProbe.isExistingUser] — the app's grandfathering
 * signal for `OnboardingGate.shouldOnboard`. The critical rows: a
 * truly fresh install answers false even though Nostr/Blossom SEED
 * default endpoints (their seeded configs carry
 * `hasUserInteracted = false`), while any user-touched configuration
 * answers true.
 */
class OnboardingGateProbeTest {

    // NOTE: `RelayerConfiguration()`'s bare default is
    // hasUserInteracted = TRUE (a backward-compat decoding default);
    // a cold-fresh store loads `RelayerConfiguration.empty`, which is
    // what the probe sees on a fresh install.
    private val freshRelayer = RelayerConfiguration.empty
    private val seededNostr = NostrRelaysConfiguration(
        endpoints = listOf(NostrRelayEndpoint(url = "wss://relay.onym.app", name = "Onym", isDefault = true)),
        hasUserInteracted = false,
    )
    private val seededBlossom = BlossomServersConfiguration(
        endpoints = listOf(BlossomServerEndpoint(url = "https://blossom.onym.app", name = "Onym", isDefault = true)),
        hasUserInteracted = false,
    )

    @Test
    fun freshInstall_seededTransportEndpointsAreNotAnExistingUserSignal() {
        assertFalse(
            OnboardingGateProbe.isExistingUser(
                relayer = freshRelayer,
                nostr = seededNostr,
                blossom = seededBlossom,
            ),
        )
    }

    @Test
    fun relayerInteraction_isExisting() {
        assertTrue(
            OnboardingGateProbe.isExistingUser(
                relayer = freshRelayer.copy(hasUserInteracted = true),
                nostr = seededNostr,
                blossom = seededBlossom,
            ),
        )
    }

    @Test
    fun relayerEndpoints_evenWithoutInteractionBit_areExisting() {
        // Belt for configs predating the sticky bit: the relayer list
        // is never seeded locally, so non-emptiness means a real user.
        assertTrue(
            OnboardingGateProbe.isExistingUser(
                relayer = RelayerConfiguration(
                    endpoints = listOf(RelayerEndpoint("A", "https://a.example", listOf("testnet"))),
                    hasUserInteracted = false,
                ),
                nostr = seededNostr,
                blossom = seededBlossom,
            ),
        )
    }

    @Test
    fun nostrInteraction_isExisting() {
        assertTrue(
            OnboardingGateProbe.isExistingUser(
                relayer = freshRelayer,
                nostr = seededNostr.copy(hasUserInteracted = true),
                blossom = seededBlossom,
            ),
        )
    }

    @Test
    fun blossomInteraction_isExisting() {
        assertTrue(
            OnboardingGateProbe.isExistingUser(
                relayer = freshRelayer,
                nostr = seededNostr,
                blossom = seededBlossom.copy(hasUserInteracted = true),
            ),
        )
    }

    @Test
    fun emptyEverything_isFresh() {
        assertFalse(
            OnboardingGateProbe.isExistingUser(
                relayer = RelayerConfiguration.empty,
                nostr = NostrRelaysConfiguration(),
                blossom = BlossomServersConfiguration(),
            ),
        )
    }

    @Test
    fun identityInteracted_namedOrMultipleIdentities_isExisting() {
        // The offline pre-onboarding case: relayer fetch never
        // succeeded, transport settings never opened — a named (or
        // second) identity still grandfathers.
        assertTrue(
            OnboardingGateProbe.isExistingUser(
                relayer = freshRelayer,
                nostr = seededNostr,
                blossom = seededBlossom,
                identityInteracted = true,
            ),
        )
    }

    @Test
    fun freshAutoBootstrapIdentity_doesNotCount() {
        // A fresh install auto-generates one blank-named identity —
        // the caller maps that to identityInteracted = false.
        assertFalse(
            OnboardingGateProbe.isExistingUser(
                relayer = freshRelayer,
                nostr = seededNostr,
                blossom = seededBlossom,
                identityInteracted = false,
            ),
        )
    }

    @Test
    fun legacyDecodedRelayerConfig_defaultInteractedTrue_readsExisting() {
        // The bare RelayerConfiguration() default is the PR #20
        // backward-compat decode ("interacted") — such a blob only
        // exists for a real pre-onboarding user, who must be
        // grandfathered.
        assertTrue(
            OnboardingGateProbe.isExistingUser(
                relayer = RelayerConfiguration(),
                nostr = NostrRelaysConfiguration(),
                blossom = BlossomServersConfiguration(),
            ),
        )
    }
}
