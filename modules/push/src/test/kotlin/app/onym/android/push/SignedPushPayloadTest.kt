package app.onym.android.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pinned byte-for-byte to the **normative** fixtures the Rust push
 * backend (onym-push `google/`) publishes — independently computed,
 * not read back out of this implementation. If these fail, this
 * client drifted; the fixtures do not move to accommodate it.
 *
 * Inputs: challenge = 32 bytes of 0x42, userKey `onym:key:aabb`,
 * timestamp `2026-08-22T12:00:00Z`, fcmToken
 * `fcm-registration-token-fixture`, and the two-entry subscription
 * set below.
 */
class SignedPushPayloadTest {
    private val challenge = ByteArray(32) { 0x42 }
    private val userKey = "onym:key:aabb"
    private val timestamp = "2026-08-22T12:00:00Z"
    private val fcmToken = "fcm-registration-token-fixture"
    private val subscriptions = listOf(
        PushSubscription(
            tag = "a1b2c3d4e5f60718",
            relays = listOf("wss://nostr.onym.app", "wss://relay.example.com"),
        ),
        PushSubscription(
            tag = "00ff00ff00ff00ff",
            relays = listOf("wss://nostr.onym.app"),
        ),
    )

    @Test
    fun `subscriptions digest matches the backend fixture`() {
        assertEquals(
            "058ea2fe4226d7fb35a804975feb9961e1800113e70fd39a7dd61647cbdd92b5",
            SignedPushPayload.subscriptionsDigest(subscriptions).toHex(),
        )
    }

    @Test
    fun `register payload matches the backend fixture`() {
        val payload = SignedPushPayload.register(
            challenge = challenge,
            userKey = userKey,
            timestamp = timestamp,
            fcmToken = fcmToken,
            subscriptions = subscriptions,
        )
        assertEquals(
            "0000001d6f6e796d2d707573682d616e64726f69642d72656769737465722d76" +
                "3100000020424242424242424242424242424242424242424242424242424242" +
                "42424242420000000d6f6e796d3a6b65793a6161626200000014323032362d30" +
                "382d32325431323a30303a30305a0000001e66636d2d72656769737472617469" +
                "6f6e2d746f6b656e2d6669787475726500000020058ea2fe4226d7fb35a80497" +
                "5feb9961e1800113e70fd39a7dd61647cbdd92b5",
            payload.toHex(),
        )
        assertEquals(
            "yT_lZpAo7F9EkQoUqA1VH1WzNsZghk0Yrt4RkhF9bKo",
            SignedPushPayload.requestHash(payload),
        )
    }

    @Test
    fun `unregister payload matches the backend fixture`() {
        val payload = SignedPushPayload.unregister(
            challenge = challenge,
            userKey = userKey,
            timestamp = timestamp,
            fcmToken = fcmToken,
        )
        assertEquals(
            "0000001f6f6e796d2d707573682d616e64726f69642d756e7265676973746572" +
                "2d76310000002042424242424242424242424242424242424242424242424242" +
                "424242424242420000000d6f6e796d3a6b65793a616162620000001432303236" +
                "2d30382d32325431323a30303a30305a0000001e66636d2d7265676973747261" +
                "74696f6e2d746f6b656e2d6669787475726500000000",
            payload.toHex(),
        )
        assertEquals(
            "j3AjWzsTgKkqD6P7-PUKonDDW0z3ssdMMUyqmHZxWDw",
            SignedPushPayload.requestHash(payload),
        )
    }

    /** The client must never SEND an empty-relays entry (the server
     * refuses them) — but the digest still binds one if present, so a
     * stripped relay list can't ride under an old signature. */
    @Test
    fun `an empty-relays entry contributes to the digest`() {
        assertEquals(
            "03a2f27940d35cb25a941ba710b844a82cf4aabf50aa0c893785cf8726aab8b8",
            SignedPushPayload.subscriptionsDigest(
                listOf(PushSubscription(tag = "a1b2c3d4e5f60718", relays = emptyList())),
            ).toHex(),
        )
    }

    /** The empty SET digests to SHA-256 of nothing — the RFC 6234
     * empty-input value, pinned so both ends agree what "watch
     * nothing" signs as. */
    @Test
    fun `the empty subscription list digests to the empty-input hash`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SignedPushPayload.subscriptionsDigest(emptyList()).toHex(),
        )
    }

    /** Digest binds the GROUPING, not just the concatenation: the
     * same tags and relays split differently must not collide. */
    @Test
    fun `regrouping relays across subscriptions changes the digest`() {
        val together = listOf(
            PushSubscription("aaaaaaaaaaaaaaaa", listOf("wss://a.example", "wss://b.example")),
            PushSubscription("bbbbbbbbbbbbbbbb", emptyList()),
        )
        val split = listOf(
            PushSubscription("aaaaaaaaaaaaaaaa", listOf("wss://a.example")),
            PushSubscription("bbbbbbbbbbbbbbbb", listOf("wss://b.example")),
        )
        assertNotEquals(
            SignedPushPayload.subscriptionsDigest(together).toHex(),
            SignedPushPayload.subscriptionsDigest(split).toHex(),
        )
    }

    /** The property length-prefixing exists for: no two different
     * field splits may produce the same bytes. */
    @Test
    fun `field boundaries are unambiguous`() {
        assertNotEquals(
            SignedPushPayload.register("ab".toByteArray(), "c", "t", "u", emptyList()).toHex(),
            SignedPushPayload.register("a".toByteArray(), "bc", "t", "u", emptyList()).toHex(),
        )
    }

    /** Same story inside the digest: relay boundaries are prefixed. */
    @Test
    fun `relay boundaries inside the digest are unambiguous`() {
        assertNotEquals(
            SignedPushPayload.subscriptionsDigest(
                listOf(PushSubscription("t", listOf("ab", "c"))),
            ).toHex(),
            SignedPushPayload.subscriptionsDigest(
                listOf(PushSubscription("t", listOf("a", "bc"))),
            ).toHex(),
        )
    }

    @Test
    fun `register and unregister payloads are domain separated`() {
        assertNotEquals(
            SignedPushPayload.register(challenge, userKey, timestamp, fcmToken, emptyList())
                .toHex(),
            SignedPushPayload.unregister(challenge, userKey, timestamp, fcmToken).toHex(),
        )
    }

    /** A signature minted for the iOS push profile must be inert
     * here: the contexts are platform-tagged, and the iOS strings
     * are different bytes. */
    @Test
    fun `contexts are distinct from the iOS push contexts`() {
        assertNotEquals("onym-push-register-v1", SignedPushPayload.REGISTER_CONTEXT)
        assertNotEquals("onym-push-unregister-v1", SignedPushPayload.UNREGISTER_CONTEXT)
        assertEquals("onym-push-android-register-v1", SignedPushPayload.REGISTER_CONTEXT)
        assertEquals("onym-push-android-unregister-v1", SignedPushPayload.UNREGISTER_CONTEXT)
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
