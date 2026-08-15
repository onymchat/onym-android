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
 * matches one of the endpoints the USER has configured/consented to —
 * and even then the client is bound to the MATCHED CONFIGURED URL, not
 * the peer's string, so the stamp can't smuggle a path/query onto the
 * user's own server either. Anything else — unknown hosts, malformed
 * stamps, legacy null stamps — downloads through the live client
 * pointed at the user's own active server.
 *
 * ## Two trust levels for http
 *
 * Peer stamps are https-only: a cleartext stamp is never honored, even
 * when it matches a user-configured http endpoint, because the peer
 * string must clear a higher bar than URLs the user typed themselves.
 * The user's own http endpoint (local dev) still works — as the active
 * (first) endpoint it is what live resolution targets, so the
 * "downloads reach your configured server" contract holds through the
 * live client rather than through stamp honoring. This mirrors the
 * onym-ios asymmetry.
 *
 * Mirrors onym-ios `BlossomServerStampPolicy.swift` (round-6 shape).
 */
object BlossomServerStampPolicy {

    /** The client a stamped attachment downloads through: [live] bound
     *  to the MATCHED CONFIGURED endpoint URL when the (https) stamp's
     *  origin is in [allowedServers], plain [live] otherwise. */
    fun client(
        stamp: String?,
        allowedServers: List<String>,
        live: BlossomClient,
    ): BlossomClient {
        if (stamp == null) return live
        // Peer strings must be https — see the class KDoc's two-trust-
        // levels note.
        val scheme = runCatching { URI(stamp).scheme }.getOrNull()?.lowercase()
        if (scheme != "https") return live
        val stampedKey = originKey(stamp) ?: return live
        val matched = allowedServers.firstOrNull { originKey(it) == stampedKey } ?: return live
        // Bind to the CONFIGURED URL, never the raw stamp: the origins
        // are equal, but the peer string may carry a path/query that
        // would otherwise ride into every `GET <base>/<sha>`.
        return live.bound(matched)
    }

    /** Normalized scheme+host+port comparison key, with default ports
     *  folded (`https://host:443` == `https://host`, `http://host:80`
     *  == `http://host`). `null` when the URL has no scheme or host —
     *  such a stamp is never comparable and never honored. */
    fun originKey(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty()) return null
        val defaultPort = when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
        val port = if (uri.port != -1 && uri.port != defaultPort) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }
}
