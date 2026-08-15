package app.onym.android.chats

import app.onym.android.transport.blossom.BlossomClient
import java.net.URI

/**
 * Client selection for a stamped attachment download — the trust
 * boundary between the peer's metadata and the user's network.
 *
 * The `server` stamp is an OPTIMIZATION hint for multi-server
 * consistency — "this blob lives on that one of YOUR servers" — never
 * an instruction from the peer. It decodes straight off the wire and
 * media auto-loads on render, so honoring an arbitrary stamp would let
 * a hostile sender choose the download host: an unauthenticated GET of
 * a per-message-unique path (a read receipt, plus IP/UA) to a third
 * party the moment the recipient scrolls the thread. The stamp is
 * therefore honored ONLY when its normalized origin (scheme+host+port)
 * matches one of the endpoints the USER has configured/consented to;
 * anything else — unknown hosts, malformed stamps, legacy null stamps
 * — downloads through the live client pointed at the user's own
 * active server.
 *
 * Mirrors onym-ios `BlossomServerStampPolicy.swift`.
 */
object BlossomServerStampPolicy {

    /** The client a stamped attachment downloads through: [live] bound
     *  to the stamp when the stamp's origin is in [allowedServers],
     *  plain [live] otherwise. */
    fun client(
        stamp: String?,
        allowedServers: List<String>,
        live: BlossomClient,
    ): BlossomClient {
        val stampedKey = stamp?.let(::originKey) ?: return live
        val allowed = allowedServers.any { originKey(it) == stampedKey }
        return if (allowed) live.bound(stamp) else live
    }

    /** Normalized scheme+host+port comparison key. `null` when the URL
     *  has no scheme or host — such a stamp is never comparable and
     *  never honored. */
    fun originKey(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty()) return null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }
}
