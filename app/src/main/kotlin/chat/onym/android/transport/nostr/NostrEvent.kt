package chat.onym.android.transport.nostr

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * NIP-01 Nostr event. Wire-encoded via [toJson] / [fromJson],
 * integrity-checked via [verifyEventId], and constructable through
 * [build] which computes the canonical id and asks a [NostrSigner]
 * to produce the BIP340 Schnorr signature.
 *
 * Mirrors `NostrEvent.swift` from onym-ios PR #12. Canonical JSON
 * format is part of the protocol contract — `[0, pubkey, created_at,
 * kind, tags, content]` serialised by `JSONArray.toString()` (Android's
 * `org.json` keeps the array ordered + emits no whitespace, matching
 * iOS's `JSONSerialization`).
 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** Wire JSON object — the inner part of an `["EVENT", {…}]` frame. */
    fun toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("kind", kind)
            put("tags", JSONArray().also { outer ->
                for (tag in tags) {
                    outer.put(JSONArray().also { inner ->
                        for (s in tag) inner.put(s)
                    })
                }
            })
            put("content", content)
            put("sig", sig)
        }

    /**
     * Recompute the event id from canonical JSON and compare. Catches
     * relay-level tampering of `content`, `pubkey`, or `tags`. Full
     * Schnorr signature verification is a separate concern (handled
     * upstream when the SDK exposes a public-key verifier).
     */
    fun verifyEventId(): Boolean = try {
        val canonical = canonicalSerialization(pubkey, createdAt, kind, tags, content)
        val computed = sha256(canonical.toByteArray(Charsets.UTF_8)).toHex()
        computed == id
    } catch (_: Throwable) {
        false
    }

    /**
     * App-local millisecond timestamp from `["ms", "..."]`. NIP-01's
     * `created_at` is second-resolution; the extra tag is a private
     * convention added by [build] so we can order messages within a
     * second. Other clients ignore it.
     */
    val displayMilliseconds: Long
        get() = tags.firstOrNull { it.size >= 2 && it[0] == "ms" }
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: (createdAt * 1000)

    companion object {
        /**
         * Build a NIP-01 event: append the `["ms", ...]` ordering
         * tag, compute the canonical id, sign it with [signer]. The
         * signer's [NostrSigner.publicKey] becomes the event
         * `pubkey` — pass an ephemeral signer (see
         * [OnymNostrSigner.ephemeral]) for metadata-hiding (kinds
         * 44114 / 34113 in this codebase) so the outer pubkey can't
         * be used to cluster related events.
         */
        fun build(
            kind: Int,
            tags: List<List<String>>,
            content: String,
            signer: NostrSigner,
        ): NostrEvent {
            val pubkeyHex = signer.publicKey().toHex()
            val unixMs = System.currentTimeMillis()
            val createdAt = unixMs / 1000
            val allTags = tags + listOf(listOf("ms", unixMs.toString()))

            val canonical = canonicalSerialization(pubkeyHex, createdAt, kind, allTags, content)
            val eventIdBytes = sha256(canonical.toByteArray(Charsets.UTF_8))
            val eventIdHex = eventIdBytes.toHex()

            val sig = signer.signEventId(eventIdBytes).toHex()

            return NostrEvent(
                id = eventIdHex,
                pubkey = pubkeyHex,
                createdAt = createdAt,
                kind = kind,
                tags = allTags,
                content = content,
                sig = sig,
            )
        }

        /** Parse a wire JSON object into a [NostrEvent]. Returns
         *  `null` if any required field is missing or the wrong
         *  type — let the caller decide whether to log + drop. */
        fun fromJson(json: JSONObject): NostrEvent? = try {
            val tagsArray = json.getJSONArray("tags")
            val tags = (0 until tagsArray.length()).map { i ->
                val inner = tagsArray.getJSONArray(i)
                (0 until inner.length()).map { j -> inner.getString(j) }
            }
            NostrEvent(
                id = json.getString("id"),
                pubkey = json.getString("pubkey"),
                createdAt = json.getLong("created_at"),
                kind = json.getInt("kind"),
                tags = tags,
                content = json.getString("content"),
                sig = json.getString("sig"),
            )
        } catch (_: Throwable) {
            null
        }

        /** Build the canonical-JSON string `[0, pubkey, created_at,
         *  kind, tags, content]`. Used by both [build] and
         *  [verifyEventId]. */
        private fun canonicalSerialization(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
        ): String {
            val arr = JSONArray().apply {
                put(0)
                put(pubkey)
                put(createdAt)
                put(kind)
                put(JSONArray().also { outer ->
                    for (tag in tags) {
                        outer.put(JSONArray().also { inner ->
                            for (s in tag) inner.put(s)
                        })
                    }
                })
                put(content)
            }
            return arr.toString()
        }

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        private fun ByteArray.toHex(): String {
            val sb = StringBuilder(size * 2)
            for (b in this) sb.append("%02x".format(b.toInt() and 0xFF))
            return sb.toString()
        }
    }
}
