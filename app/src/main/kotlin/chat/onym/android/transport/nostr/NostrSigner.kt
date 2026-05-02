package chat.onym.android.transport.nostr

/**
 * BIP340 secp256k1 Schnorr signer over a Nostr event id. The
 * transport layer never sees the secret key — it only asks for the
 * x-only public key (32 bytes) and a signature (64 bytes) for a
 * given event id.
 *
 * Mirrors `NostrSigner.swift` from onym-ios PR #12.
 *
 * The concrete OnymSDK-backed implementation lives in the identity
 * layer (see `chat.onym.android.identity.OnymNostrSigner`) so the
 * `chat.onym.sdk.Common` FFI is only ever called from inside a
 * repository or a repository-owned signer provider — see the
 * touch-surface rules in `README.md`.
 */
interface NostrSigner {
    fun publicKey(): ByteArray
    fun signEventId(eventId: ByteArray): ByteArray
}

/**
 * Per-event signer factory. Used by transports that need a fresh
 * ephemeral signer per outbound event for metadata-hiding (kinds
 * 44114 / 34113), so the outer event `pubkey` can't be used to
 * cluster co-membership across the relay's view.
 *
 * Constructor-injected into [NostrMessageTransport] /
 * [NostrInboxTransport] from the composition root, so the transport
 * layer never imports `chat.onym.sdk.*` directly.
 */
interface NostrEphemeralSignerProvider {
    fun ephemeral(): NostrSigner
}

/**
 * Failure modes for [NostrSigner] operations. Wraps lower-layer FFI
 * exceptions so callers in the transport layer don't have to depend
 * on `chat.onym.sdk.OnymException`.
 */
sealed class NostrSignerError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class DerivePublicKeyFailed(message: String, cause: Throwable? = null) : NostrSignerError(message, cause)
    class SignEventIdFailed(message: String, cause: Throwable? = null) : NostrSignerError(message, cause)
}
