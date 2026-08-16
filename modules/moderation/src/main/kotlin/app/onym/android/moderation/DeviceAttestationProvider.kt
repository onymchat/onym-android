package app.onym.android.moderation

/**
 * Seam over the platform's device-attestation token source. The token
 * proves "this Play-recognized app on this physical device, for this
 * exact request" to the enforcement backend, which is the only party
 * holding Google credentials (the service-account key never ships in
 * the app).
 */
interface DeviceAttestationProvider {
    /**
     * A fresh standard integrity token whose `requestHash` binds this
     * session's signed payload.
     *
     * - [AttestationToken.Token] — the token string, as Google minted it.
     * - [AttestationToken.Unsupported] — no usable Play environment
     *   (emulator, de-Googled device, outdated Play services). The
     *   request is sent without a token; the backend (or grace
     *   arithmetic) decides — degradation toward blocking, never
     *   toward unmoderated operation.
     * - [AttestationToken.Throttled] — the provider is rate-limited
     *   (prepare cap, `TOO_MANY_REQUESTS` backoff). NOT sent to the
     *   backend: the gate serves the last reconciled state within
     *   offline grace and schedules the next eligible attempt.
     */
    suspend fun requestToken(requestHash: String): AttestationToken
}

sealed interface AttestationToken {
    data class Token(val value: String) : AttestationToken
    data object Unsupported : AttestationToken
    data object Throttled : AttestationToken
}
