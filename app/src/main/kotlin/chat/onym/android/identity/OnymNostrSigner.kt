package chat.onym.android.identity

import chat.onym.android.transport.nostr.NostrEphemeralSignerProvider
import chat.onym.android.transport.nostr.NostrSigner
import chat.onym.android.transport.nostr.NostrSignerError
import chat.onym.sdk.Common
import chat.onym.sdk.OnymException
import java.security.SecureRandom

/**
 * [NostrSigner] backed by a 32-byte secp256k1 secret. The signer
 * uses [chat.onym.sdk.Common] for the underlying BIP340 operations
 * — same crypto stack the identity layer uses; no second
 * implementation.
 *
 * Lives next to [IdentityRepository] (not in the transport layer)
 * so the `chat.onym.sdk.*` FFI is only imported from within the
 * identity package — a structural enforcement of the touch-surface
 * rule that says OnymSDK is called only from repositories or from
 * repository-owned signer providers.
 *
 * Keep instances short-lived: every call to [publicKey] re-derives
 * from the secret rather than caching, so the secret is the only
 * thing pinned in memory.
 */
class OnymNostrSigner(
    /** `internal` (not `private`) so tests can assert ephemeral
     *  signers produce distinct secrets — the metadata-hiding
     *  invariant. Production code should never read this directly;
     *  go through [signEventId] / [publicKey] instead. */
    internal val secretKey: ByteArray,
) : NostrSigner {

    init {
        require(secretKey.size == 32) {
            "secp256k1 secret key must be 32 bytes, got ${secretKey.size}"
        }
    }

    override fun publicKey(): ByteArray = try {
        Common.nostrDerivePublicKey(secretKey)
    } catch (e: OnymException) {
        throw NostrSignerError.DerivePublicKeyFailed(
            "nostrDerivePublicKey failed: ${e.message}", e,
        )
    }

    override fun signEventId(eventId: ByteArray): ByteArray {
        require(eventId.size == 32) {
            "Nostr event id must be 32 bytes (SHA-256 of canonical JSON), got ${eventId.size}"
        }
        return try {
            Common.nostrSignEventId(secretKey, eventId)
        } catch (e: OnymException) {
            throw NostrSignerError.SignEventIdFailed(
                "nostrSignEventId failed: ${e.message}", e,
            )
        }
    }

    companion object {
        /**
         * Per-event ephemeral signer backed by a fresh CSPRNG-derived
         * secret. Used by [OnymNostrSignerProvider]; exposed directly
         * for tests that want a deterministic call site.
         */
        fun ephemeral(): OnymNostrSigner {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return OnymNostrSigner(bytes)
        }
    }
}

/**
 * Production [NostrEphemeralSignerProvider]. Constructed in
 * `OnymApplication` and injected into the Nostr transports via the
 * composition root, so the transport layer never imports
 * `chat.onym.sdk.*` itself.
 */
class OnymNostrSignerProvider : NostrEphemeralSignerProvider {
    override fun ephemeral(): NostrSigner = OnymNostrSigner.ephemeral()
}
