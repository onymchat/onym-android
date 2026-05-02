package chat.onym.android.identity

/**
 * Identity-layer failures. Wraps storage I/O errors, malformed stored
 * snapshots, invalid mnemonics, and FFI failures from `OnymSDK`.
 *
 * Mirrors the iOS `IdentityError` enum 1:1 — same cases, same semantics
 * — so test expectations and surfaced error messages stay parallel
 * across platforms.
 */
sealed class IdentityError(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class StorageRead(cause: Throwable?) :
        IdentityError("EncryptedSharedPreferences read failed", cause)

    class StorageWrite(cause: Throwable?) :
        IdentityError("EncryptedSharedPreferences write failed", cause)

    class StorageDelete(cause: Throwable?) :
        IdentityError("EncryptedSharedPreferences delete failed", cause)

    class StoredSnapshotInvalid(reason: String) :
        IdentityError("Stored identity is invalid: $reason")

    object InvalidMnemonic :
        IdentityError("Invalid recovery phrase") {
        private fun readResolve(): Any = InvalidMnemonic
    }

    class SdkFailure(message: String, cause: Throwable? = null) :
        IdentityError("OnymSDK call failed: $message", cause)
}
