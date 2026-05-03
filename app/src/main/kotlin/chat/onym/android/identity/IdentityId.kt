package chat.onym.android.identity

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Stable per-identity handle. Keys every per-identity record in the
 * app — secrets in [IdentitySecretStore], owner stamps on
 * [chat.onym.android.group.ChatGroup], inbox tags in the transport
 * layer.
 *
 * Wraps a UUID — opaque, never user-visible. Display names live on
 * [IdentitySummary] alongside this id.
 *
 * The string form `value` is what lands on disk + on the wire — safe
 * to use as a SharedPreferences key suffix or Room column.
 */
@JvmInline
@Serializable
value class IdentityId(val value: String) {
    init {
        require(value.isNotBlank()) { "IdentityId must not be blank" }
    }

    companion object {
        /** Generate a fresh, never-before-used handle. */
        fun new(): IdentityId = IdentityId(UUID.randomUUID().toString())
    }
}
