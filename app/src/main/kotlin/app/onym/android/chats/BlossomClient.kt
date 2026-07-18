package app.onym.android.chats

import android.util.Base64
import app.onym.android.transport.nostr.NostrEphemeralSignerProvider
import app.onym.android.transport.nostr.NostrEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Descriptor returned by a Blossom server for a stored blob (BUD-02). */
data class BlobDescriptor(val sha256: String, val url: String, val size: Int)

/**
 * Uploads/downloads opaque blobs to a Blossom media server
 * (`blossom.onym.app`, the reference `hzrd149/blossom-server`). Chat
 * images are AES-GCM-encrypted before upload, so the bytes crossing
 * this seam are always ciphertext. Android twin of iOS `BlossomClient`.
 *
 * An interface so the UI-test harness can swap in an in-memory store.
 */
interface BlossomClient {
    /** `PUT /upload` the blob (BUD-01 auth). Returns its descriptor. */
    suspend fun upload(blob: ByteArray, mimeType: String): BlobDescriptor
    /** `GET /<sha256>` the blob. Callers verify the hash before use. */
    suspend fun download(sha256: String): ByteArray
}

class BlossomException(message: String) : Exception(message)

/**
 * Production [BlossomClient] over OkHttp. Uploads carry a BUD-01
 * `Authorization: Nostr <base64(kind:24242 event)>` header signed by an
 * ephemeral Nostr key. Downloads are unauthenticated `GET`s by hash.
 */
class OkHttpBlossomClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
    private val signerProvider: NostrEphemeralSignerProvider,
    private val authTtlSeconds: Long = 300,
) : BlossomClient {

    override suspend fun upload(blob: ByteArray, mimeType: String): BlobDescriptor =
        withContext(Dispatchers.IO) {
            val sha = ChatImageCrypto.sha256Hex(blob)
            val auth = authorizationHeader("upload", sha, authTtlSeconds, signerProvider)
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/upload")
                .header("Authorization", auth)
                .put(blob.toRequestBody(mimeType.toMediaTypeOrNull()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw BlossomException("upload HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                decodeDescriptor(body, fallbackSha256 = sha)
            }
        }

    override suspend fun download(sha256: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/" + sha256).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw BlossomException("download HTTP ${response.code}")
            response.body?.bytes() ?: throw BlossomException("empty blob body")
        }
    }

    companion object {
        /** Build the BUD-01 authorization header for [action] over [sha256]. */
        fun authorizationHeader(
            action: String,
            sha256: String,
            ttlSeconds: Long,
            signerProvider: NostrEphemeralSignerProvider,
        ): String {
            val expiration = System.currentTimeMillis() / 1000 + ttlSeconds
            val event = NostrEvent.build(
                kind = 24242,
                tags = listOf(
                    listOf("t", action),
                    listOf("x", sha256),
                    listOf("expiration", expiration.toString()),
                ),
                content = "Upload chat image",
                signer = signerProvider.ephemeral(),
            )
            val json = event.toJson().toString().toByteArray(Charsets.UTF_8)
            return "Nostr " + Base64.encodeToString(json, Base64.NO_WRAP)
        }

        private fun decodeDescriptor(body: String, fallbackSha256: String): BlobDescriptor {
            val obj = runCatching { JSONObject(body) }.getOrNull()
                ?: throw BlossomException("malformed upload response")
            return BlobDescriptor(
                sha256 = obj.optString("sha256", fallbackSha256),
                url = obj.optString("url", ""),
                size = obj.optInt("size", 0),
            )
        }
    }
}
