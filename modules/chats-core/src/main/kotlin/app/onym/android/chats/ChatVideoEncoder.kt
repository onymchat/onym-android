package app.onym.android.chats

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transcodes a picked video to a 720p H.264 MP4 for transmission and
 * extracts a poster frame. Android twin of iOS `ChatVideoEncoder`.
 *
 * The transcode (Media3 [Transformer] + [Presentation.createForShortSide])
 * bounds both the pixel dimensions (720p short side) and, in practice,
 * the byte size — most phone clips land well under the Blossom upload
 * cap after re-encoding. The interactor still guards the final ciphertext
 * size for the pathological long-clip case. The poster (first frame) is
 * run through [ChatImageEncoder] so it ships with a JPEG + blurhash +
 * dimensions like any sent photo.
 */
@OptIn(UnstableApi::class)
object ChatVideoEncoder {
    /** 720p short-side target for the transcode. */
    const val SHORT_SIDE = 720

    data class Encoded(
        val mp4: ByteArray,
        val width: Int,
        val height: Int,
        val durationSeconds: Double,
        val poster: ChatImageEncoder.Encoded,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Encoded) return false
            return mp4.contentEquals(other.mp4) &&
                width == other.width &&
                height == other.height &&
                durationSeconds == other.durationSeconds &&
                poster == other.poster
        }

        override fun hashCode(): Int {
            var h = mp4.contentHashCode()
            h = 31 * h + width
            h = 31 * h + height
            h = 31 * h + durationSeconds.hashCode()
            h = 31 * h + poster.hashCode()
            return h
        }
    }

    /** Transcode + extract poster. Returns `null` on any decode / export
     *  / poster-extraction failure (mapped by the caller to a user-facing
     *  "couldn't process the video"). */
    suspend fun encode(context: Context, uri: Uri): Encoded? {
        // Poster + duration from the source clip.
        val retriever = MediaMetadataRetriever()
        val poster: ChatImageEncoder.Encoded
        val durationSeconds: Double
        try {
            retriever.setDataSource(context, uri)
            val durMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            durationSeconds = durMs / 1000.0
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            poster = ChatImageEncoder.encode(frame) ?: return null
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }

        // Transcode to a 720p MP4 in the cache dir, then read the bytes.
        val outputFile = File(context.cacheDir, "chat_transcode_${System.nanoTime()}.mp4")
        try {
            transcode(context, uri, outputFile.absolutePath)
        } catch (_: Exception) {
            runCatching { outputFile.delete() }
            return null
        }
        val mp4 = runCatching { outputFile.readBytes() }.getOrNull()

        // Output dimensions (rotation-corrected) from the transcoded file,
        // falling back to the poster's aspect if the query fails.
        var width = poster.width
        var height = poster.height
        val outRetriever = MediaMetadataRetriever()
        try {
            outRetriever.setDataSource(outputFile.absolutePath)
            val w = outRetriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()
            val h = outRetriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()
            val rotation = outRetriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            if (w != null && h != null) {
                if (rotation == 90 || rotation == 270) {
                    width = h
                    height = w
                } else {
                    width = w
                    height = h
                }
            }
        } catch (_: Exception) {
            // keep poster fallback
        } finally {
            runCatching { outRetriever.release() }
        }
        runCatching { outputFile.delete() }

        if (mp4 == null) return null
        return Encoded(
            mp4 = mp4,
            width = width,
            height = height,
            durationSeconds = durationSeconds,
            poster = poster,
        )
    }

    /** Bridges Media3's listener-based export to a suspend function.
     *  [Transformer] must be created + started on a thread with a
     *  Looper, so this runs on the main dispatcher. */
    private suspend fun transcode(
        context: Context,
        uri: Uri,
        outputPath: String,
    ): ExportResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (cont.isActive) cont.resume(result)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                })
                .build()
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(
                    Effects(
                        /* audioProcessors = */ emptyList(),
                        /* videoEffects = */ listOf(Presentation.createForHeight(SHORT_SIDE)),
                    ),
                )
                .build()
            transformer.start(edited, outputPath)
        }
    }
}
