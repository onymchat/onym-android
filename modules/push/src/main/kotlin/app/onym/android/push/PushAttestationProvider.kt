package app.onym.android.push

/**
 * Seam over the platform's device-attestation token source, the
 * `DeviceAttestationProvider` shape from :moderation. The token
 * proves "this Play-recognized app on this physical device, for this
 * exact request" to the push backend, which is the only party holding
 * Google credentials.
 */
interface PushAttestationProvider {
    /**
     * A fresh standard integrity token whose `requestHash` binds this
     * session's signed payload.
     *
     * - [PushAttestationToken.Token] — the token string, as Google
     *   minted it.
     * - [PushAttestationToken.Unsupported] — no usable Play
     *   environment (emulator, de-Googled device, outdated Play
     *   services). The request is sent WITHOUT a token — the backend
     *   decides what a token-less registration means.
     * - [PushAttestationToken.Throttled] — the provider is
     *   rate-limited. The reconciler retries later; a throttle never
     *   flips the enabled preference.
     */
    suspend fun requestToken(requestHash: String): PushAttestationToken
}

sealed interface PushAttestationToken {
    data class Token(val value: String) : PushAttestationToken
    data object Unsupported : PushAttestationToken
    data object Throttled : PushAttestationToken
}
