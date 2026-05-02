package chat.onym.android.transport.nostr

import chat.onym.sdk.Common
import chat.onym.sdk.OnymException
import java.security.SecureRandom

/**
 * BIP340 secp256k1 Schnorr signer over a Nostr event id. The
 * transport layer never sees the secret key — it only asks for the
 * x-only public key (32 bytes) and a signature (64 bytes) for a
 * given event id.
 *
 * Mirrors `NostrSigner.swift` from onym-ios PR #12.
 */
interface NostrSigner {
    fun publicKey(): ByteArray
    fun signEventId(eventId: ByteArray): ByteArray
}

/**
 * [NostrSigner] backed by a 32-byte secp256k1 secret. The signer
 * uses [chat.onym.sdk.Common] for the underlying BIP340 operations
 * — same crypto stack the identity layer uses; no second
 * implementation.
 *
 * Keep instances short-lived: every call to [publicKey] re-derives
 * from the secret rather than caching, so the secret is the only
 * thing pinned in memory.
 */
class OnymNostrSigner(private val secretKey: ByteArray) : NostrSigner {

    init {
        require(secretKey.size == 32) {
            "secp256k1 secret key must be 32 bytes, got ${secretKey.size}"
        }
    }

    override fun publicKey(): ByteArray = try {
        Common.nostrDerivePublicKey(secretKey)
    } catch (e: OnymException) {
        throw NostrSignerException("nostrDerivePublicKey failed: ${e.message}", e)
    }

    override fun signEventId(eventId: ByteArray): ByteArray {
        require(eventId.size == 32) {
            "Nostr event id must be 32 bytes (SHA-256 of canonical JSON), got ${eventId.size}"
        }
        return try {
            Common.nostrSignEventId(secretKey, eventId)
        } catch (e: OnymException) {
            throw NostrSignerException("nostrSignEventId failed: ${e.message}", e)
        }
    }

    companion object {
        /**
         * Per-event ephemeral signer backed by a fresh CSPRNG-derived
         * secret. Used for metadata-hiding kinds (44114 / 34113) so
         * the outer event `pubkey` can't be used to cluster
         * co-membership across the relay's view.
         */
        fun ephemeral(): OnymNostrSigner {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return OnymNostrSigner(bytes)
        }
    }
}

/** Wraps lower-layer FFI failures so callers don't have to depend
 *  on `chat.onym.sdk.OnymException`. */
class NostrSignerException(message: String, cause: Throwable? = null) : Exception(message, cause)
