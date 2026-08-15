package app.onym.android.chats

import app.onym.android.transport.blossom.BlobDescriptor
import app.onym.android.transport.blossom.BlossomClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * Download-side server binding for [ChatVideoLoader] / [ChatVoiceLoader]:
 * the attachment's `server` stamp routes the download ONLY when its
 * origin is in the user's configured endpoint set; anything else uses
 * the live client. ([ChatImageLoader] shares the exact same
 * [BlossomServerStampPolicy] call but needs the Android Bitmap
 * framework to decode, so its policy coverage lives in
 * [BlossomServerStampPolicyTest].)
 *
 * Mirrors onym-ios `ChatMediaLoaderServerBindingTests.swift`.
 */
class ChatMediaLoaderServerBindingTest {

    /** Serves a sealed blob; records whether the download came through
     *  the live client ("live") or a bound one (the pinned URL). */
    private class ServingClient(private val blob: ByteArray) : BlossomClient {
        val downloadsVia = mutableListOf<String>()

        override suspend fun upload(blob: ByteArray, mimeType: String): BlobDescriptor =
            throw IllegalStateException("not used")

        override suspend fun download(sha256: String): ByteArray {
            downloadsVia.add("live")
            return blob
        }

        override fun bound(serverUrl: String): BlossomClient = object : BlossomClient {
            override suspend fun upload(blob: ByteArray, mimeType: String): BlobDescriptor =
                throw IllegalStateException("not used")
            override suspend fun download(sha256: String): ByteArray {
                downloadsVia.add(serverUrl)
                return this@ServingClient.blob
            }
        }
    }

    private val plaintext = ByteArray(128) { it.toByte() }
    private val sealed = ChatImageCrypto.seal(plaintext)

    private fun videoAttachment(server: String?) = ChatVideoAttachment(
        sha256 = sealed.sha256Hex,
        mimeType = "video/mp4",
        byteSize = sealed.blob.size,
        width = 1280,
        height = 720,
        durationSeconds = 4.0,
        encKey = sealed.key,
        poster = ChatImageAttachment(
            sha256 = "00", mimeType = "image/jpeg", byteSize = 1,
            width = 1, height = 1, encKey = ByteArray(32), blurhash = "LEHV6nWB2yk8",
        ),
        server = server,
    )

    private fun voiceAttachment(server: String?) = ChatVoiceAttachment(
        sha256 = sealed.sha256Hex,
        mimeType = "audio/mp4",
        byteSize = sealed.blob.size,
        durationSeconds = 2.0,
        encKey = sealed.key,
        waveform = listOf(1, 2, 3),
        server = server,
    )

    private fun tempDir() = Files.createTempDirectory("loader-binding-test").toFile()

    @Test
    fun videoLoader_honorsStamp_withinConfiguredSet() = runTest {
        val client = ServingClient(sealed.blob)
        val loader = ChatVideoLoader(
            blossomClient = client,
            cacheDir = tempDir(),
            allowedStampServers = { listOf("https://mine.example") },
        )

        val file = loader.file(videoAttachment(server = "https://mine.example"))

        assertNotNull(file)
        assertArrayEquals(plaintext, file!!.readBytes())
        assertEquals(listOf("https://mine.example"), client.downloadsVia)
    }

    @Test
    fun videoLoader_ignoresStamp_outsideConfiguredSet() = runTest {
        val client = ServingClient(sealed.blob)
        val loader = ChatVideoLoader(
            blossomClient = client,
            cacheDir = tempDir(),
            allowedStampServers = { listOf("https://mine.example") },
        )

        val file = loader.file(videoAttachment(server = "https://evil.example"))

        assertNotNull(file)
        assertEquals(listOf("live"), client.downloadsVia)
    }

    @Test
    fun videoLoader_defaultEmptyAllowlist_ignoresEveryStamp() = runTest {
        val client = ServingClient(sealed.blob)
        val loader = ChatVideoLoader(blossomClient = client, cacheDir = tempDir())

        loader.file(videoAttachment(server = "https://mine.example"))

        assertEquals(listOf("live"), client.downloadsVia)
    }

    @Test
    fun videoLoader_nullStamp_usesLiveClient() = runTest {
        val client = ServingClient(sealed.blob)
        val loader = ChatVideoLoader(
            blossomClient = client,
            cacheDir = tempDir(),
            allowedStampServers = { listOf("https://mine.example") },
        )

        loader.file(videoAttachment(server = null))

        assertEquals(listOf("live"), client.downloadsVia)
    }

    @Test
    fun videoLoader_integrityFailure_returnsNull_andWritesNothing() = runTest {
        // Wrong blob for the descriptor's sha — decrypt/integrity fails
        // and no cache file may be left behind.
        val client = ServingClient(ByteArray(64) { 0x42 })
        val dir = tempDir()
        val loader = ChatVideoLoader(
            blossomClient = client,
            cacheDir = dir,
            allowedStampServers = { listOf("https://mine.example") },
        )

        val file = loader.file(videoAttachment(server = "https://mine.example"))

        assertNull(file)
        assertEquals(0, dir.listFiles().orEmpty().count { it.extension == "mp4" })
    }

    @Test
    fun voiceLoader_honorsStamp_withinConfiguredSet() = runTest {
        val client = ServingClient(sealed.blob)
        val loader = ChatVoiceLoader(
            blossomClient = client,
            cacheDir = tempDir(),
            allowedStampServers = { listOf("https://mine.example") },
        )

        val file = loader.file(voiceAttachment(server = "https://mine.example"))

        assertNotNull(file)
        assertArrayEquals(plaintext, file!!.readBytes())
        assertEquals(listOf("https://mine.example"), client.downloadsVia)
    }

    @Test
    fun voiceLoader_ignoresStamp_outsideConfiguredSet() = runTest {
        val client = ServingClient(sealed.blob)
        val loader = ChatVoiceLoader(
            blossomClient = client,
            cacheDir = tempDir(),
            allowedStampServers = { listOf("https://mine.example") },
        )

        loader.file(voiceAttachment(server = "https://evil.example"))

        assertEquals(listOf("live"), client.downloadsVia)
    }

    @Test
    fun voiceLoader_cachedFile_neverTouchesTheNetworkAgain() = runTest {
        val client = ServingClient(sealed.blob)
        val loader = ChatVoiceLoader(
            blossomClient = client,
            cacheDir = tempDir(),
            allowedStampServers = { listOf("https://mine.example") },
        )
        val attachment = voiceAttachment(server = "https://mine.example")

        loader.file(attachment)
        loader.file(attachment)

        assertEquals(1, client.downloadsVia.size)
    }
}
