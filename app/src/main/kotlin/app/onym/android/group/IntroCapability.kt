package app.onym.android.group

import app.onym.android.identity.Base64ByteArraySerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Base64

/**
 * The deeplink-shareable capability for the Level-2 sender-approval
 * invite flow. Intentionally minimal — carries **no `groupSecret`**,
 * **no member roster**. A bearer of this capability can only do one
 * thing: send a "request to join" to the inviter's intro inbox.
 * The actual `GroupInvitationPayload` (with `groupSecret` + members)
 * is sealed by the inviter only after they tap Approve in their app.
 *
 * ## Wire shape
 *
 * Encoded as `Base64(JSON)` and ferried via either:
 *
 *  - `https://onym.app/join?c=<base64>` — the App Link (autoVerify=true).
 *    Universal channel; works from any messenger / browser.
 *  - `onym://join?c=<base64>` — custom-scheme fallback when App Link
 *    auto-verification fails (e.g., on devices that haven't fetched
 *    the assetlinks.json yet).
 *
 * The query parameter is `c` (capability), kept short to keep the
 * URL pasteable through SMS-character-counted channels.
 *
 * ## Per-invite ephemeral key
 *
 * [introPublicKey] is a **fresh X25519 pubkey minted per invite** —
 * never the inviter's identity inbox key. The matching private key
 * stays on the inviter's device (persisted in PR-2). This gives the
 * inviter per-link revocation: stop listening on a given intro tag
 * → that invite link goes silent. It also means link interception
 * doesn't leak the inviter's long-term inbox key.
 *
 * ## What's safe to ship in [groupName]
 *
 * Optional, plaintext, **public**. The link transits cleartext
 * channels (Telegram, SMS, etc.) — anyone observing the link can
 * read this string. Useful for the joiner's "join this group?"
 * preview before they tap Approve. Inviter chooses whether to
 * include it; for groups with sensitive names, leave null and let
 * the inviter convey context out-of-band ("hey, join my chat:
 * <link>").
 */
@Serializable
data class IntroCapability(
    /** X25519 32-byte pubkey, freshly minted per invite. Encrypts
     *  the joiner's request envelope; the inviter's app holds the
     *  matching private key (PR-2 persistence). */
    @SerialName("intro_pub")
    @Serializable(with = Base64ByteArraySerializer::class)
    val introPublicKey: ByteArray,

    /** 32-byte canonical bls12-381 Fr (BE). The on-chain `group_id`
     *  the joiner is asking to join. Lets the joiner verify the
     *  group exists on chain (`get_commitment`) before sending a
     *  request — protects against a forged link pointing at a
     *  non-existent group. */
    @SerialName("group_id")
    @Serializable(with = Base64ByteArraySerializer::class)
    val groupId: ByteArray,

    /** Optional display name. Public — see class doc. */
    @SerialName("group_name")
    val groupName: String? = null,
) {
    init {
        require(introPublicKey.size == 32) {
            "introPublicKey: expected 32 bytes, got ${introPublicKey.size}"
        }
        require(groupId.size == 32) {
            "groupId: expected 32 bytes, got ${groupId.size}"
        }
    }

    /** Encode to the base64-of-JSON payload that lands in the URL
     *  query string. URL-safe Base64 (no `=` padding, no `+`/`/`)
     *  so the result drops straight into a URL query without
     *  percent-encoding. */
    fun encode(): String {
        val raw = jsonFormat.encodeToString(serializer(), this)
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    /** Build the canonical App Link form. Drop into
     *  [android.content.Intent.EXTRA_TEXT] for an
     *  [android.content.Intent.ACTION_SEND] share intent. */
    fun toAppLink(): String = "$APP_LINK_BASE${encode()}"

    /** Build the custom-scheme fallback. Same payload, different
     *  scheme — for testing in environments where the App Link
     *  auto-verification hasn't completed yet. */
    fun toCustomSchemeLink(): String = "$CUSTOM_SCHEME_BASE${encode()}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IntroCapability) return false
        return introPublicKey.contentEquals(other.introPublicKey) &&
            groupId.contentEquals(other.groupId) &&
            groupName == other.groupName
    }

    override fun hashCode(): Int {
        var h = introPublicKey.contentHashCode()
        h = 31 * h + groupId.contentHashCode()
        h = 31 * h + (groupName?.hashCode() ?: 0)
        return h
    }

    companion object {
        const val APP_LINK_BASE = "https://onym.app/join?c="
        const val CUSTOM_SCHEME_BASE = "onym://join?c="

        private val jsonFormat = Json {
            encodeDefaults = false  // omit groupName=null from the wire
            ignoreUnknownKeys = true
        }

        /** Inverse of [encode]. Throws [InvalidIntroCapability] on any
         *  malformed input (bad base64, bad JSON, wrong key sizes). */
        fun decode(payload: String): IntroCapability {
            val raw = try {
                Base64.getUrlDecoder().decode(payload)
            } catch (e: IllegalArgumentException) {
                throw InvalidIntroCapability("base64 decode failed: ${e.message}", e)
            }
            return try {
                jsonFormat.decodeFromString(serializer(), raw.toString(Charsets.UTF_8))
            } catch (e: SerializationException) {
                throw InvalidIntroCapability("JSON decode failed: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                // size requires() on the constructor
                throw InvalidIntroCapability(e.message ?: "shape check failed", e)
            }
        }

        /** Pull the `c=…` query parameter out of any link form
         *  ([APP_LINK_BASE] or [CUSTOM_SCHEME_BASE]) + decode it.
         *  Returns null if the URL doesn't carry a capability —
         *  caller decides whether that's an error. */
        fun fromLink(link: String): IntroCapability? {
            val uri = try { URI(link) } catch (_: Throwable) { return null }
            val query = uri.rawQuery ?: return null
            for (part in query.split('&')) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                val key = part.substring(0, eq)
                if (key != "c") continue
                val value = part.substring(eq + 1)
                return decode(value)
            }
            return null
        }

        /** Build a `mailto:`-style share-text payload bundling the
         *  link with a human-readable nudge. Inviter pastes this
         *  into their share-sheet target. */
        fun shareText(link: String, groupName: String?): String =
            if (groupName.isNullOrBlank()) {
                "Join my chat on Onym: $link"
            } else {
                "Join \"$groupName\" on Onym: $link"
            }
    }
}

/** Decode-side failures from [IntroCapability.decode] /
 *  [IntroCapability.fromLink]. Caller maps to user-facing copy. */
class InvalidIntroCapability(message: String, cause: Throwable? = null) :
    Exception(message, cause)
