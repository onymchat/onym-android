package app.onym.android

import app.onym.android.moderation.AttestationToken
import app.onym.android.moderation.DeviceAttestationProvider
import app.onym.android.push.PushAttestationProvider
import app.onym.android.push.PushAttestationToken

/**
 * Adapts :moderation's [DeviceAttestationProvider] (in production
 * `PlayIntegrityAttestationProvider`, with its single-flight mutex,
 * prepare rate cap, and TOO_MANY_REQUESTS backoff) behind the :push
 * attestation seam. Reuse rather than fork: the provider's API takes
 * exactly the requestHash the push payload computes, and the two
 * seams' token vocabularies map one-to-one.
 */
class PlayIntegrityPushAttestation(
    private val provider: DeviceAttestationProvider,
) : PushAttestationProvider {
    override suspend fun requestToken(requestHash: String): PushAttestationToken =
        when (val token = provider.requestToken(requestHash)) {
            is AttestationToken.Token -> PushAttestationToken.Token(token.value)
            AttestationToken.Unsupported -> PushAttestationToken.Unsupported
            AttestationToken.Throttled -> PushAttestationToken.Throttled
        }
}
