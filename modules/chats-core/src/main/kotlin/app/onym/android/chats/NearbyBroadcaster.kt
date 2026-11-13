package app.onym.android.chats

import app.onym.android.identity.IdentityId

/**
 * Puts a chat message on the Bluetooth mesh too, for recipients in radio
 * range when there's no internet (onym-ios docs/mesh-ble-v1.md). Given the
 * plaintext payload, not the relay's sealed envelope: the mesh seals its
 * own, compressed, in the compact binary form (§4c).
 *
 * Called only for text chat messages: media descriptors point at a Blossom
 * server and are no use offline, and receipts, joins and group state must
 * never be broadcast — a receipt on the air would reveal its sender was
 * in range. The app's implementation queues the envelope on the mesh
 * outbox under the (sender, recipient) pair's rotating tag; it never
 * reports back whether anyone heard it, because nothing can.
 */
fun interface NearbyBroadcaster {
    suspend fun broadcast(payload: ByteArray, sender: IdentityId, recipientInboxKey: ByteArray)

    companion object {
        val None = NearbyBroadcaster { _, _, _ -> }
    }
}
