package app.onym.android.chats

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/**
 * Live microphone capture for voice messages. Records mono AAC into a temp
 * `.m4a`, polls `getMaxAmplitude()` while recording to build a waveform,
 * and hands back the file + duration + downsampled waveform on stop.
 * Android twin of iOS `ChatVoiceRecorder` (+ the encoder's waveform step —
 * on Android the amplitude is sampled live rather than decoded afterward).
 *
 * Owned by the Compose composer: holding the mic button starts recording,
 * releasing stops + sends, sliding to cancel aborts + deletes.
 */
class ChatVoiceRecorder(private val context: Context) {

    /** The result of a completed recording. */
    data class Recording(
        val file: File,
        val durationSeconds: Double,
        val waveform: List<Int>,
    )

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0
    private val amplitudes = mutableListOf<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val sampler = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            amplitudes.add(runCatching { r.maxAmplitude }.getOrDefault(0))
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    val isRecording: Boolean get() = recorder != null

    /** Elapsed recording time in seconds (0 when not recording). */
    val elapsedSeconds: Double
        get() = if (recorder != null) (SystemClock.elapsedRealtime() - startedAt) / 1000.0 else 0.0

    /** Begin recording to a fresh temp file. Returns `false` if capture
     *  couldn't start (device/permission problem). */
    fun start(): Boolean {
        if (recorder != null) return false
        val file = File(context.cacheDir, "voice-${UUID.randomUUID()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(24_000)
            rec.setAudioEncodingBitRate(32_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outputFile = file
            amplitudes.clear()
            startedAt = SystemClock.elapsedRealtime()
            handler.postDelayed(sampler, SAMPLE_INTERVAL_MS)
            true
        } catch (_: Exception) {
            runCatching { rec.release() }
            runCatching { file.delete() }
            recorder = null
            outputFile = null
            false
        }
    }

    /** Stop recording and return the file + duration + waveform, or `null`
     *  if nothing was recording / the encoder failed. */
    fun stop(): Recording? {
        val rec = recorder ?: return null
        val file = outputFile ?: return null
        handler.removeCallbacks(sampler)
        val ok = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        recorder = null
        outputFile = null
        if (!ok) {
            runCatching { file.delete() }
            return null
        }
        return Recording(file, durationSeconds(file), downsample(amplitudes, WAVEFORM_BAR_COUNT))
    }

    /** Abort + delete the in-progress recording (slide-to-cancel). */
    fun cancel() {
        val rec = recorder
        handler.removeCallbacks(sampler)
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        runCatching { outputFile?.delete() }
        recorder = null
        outputFile = null
        amplitudes.clear()
    }

    private fun durationSeconds(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            ms / 1000.0
        } catch (_: Exception) {
            elapsedSeconds
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        const val WAVEFORM_BAR_COUNT = 40
        const val MINIMUM_DURATION_SECONDS = 0.5
        private const val SAMPLE_INTERVAL_MS = 60L

        /**
         * Downsample raw amplitude samples (0…32767) into [barCount] RMS
         * bars normalized 0…255 against the loudest bar. Pure so a unit
         * test can pin the shape without a recorder.
         */
        fun downsample(samples: List<Int>, barCount: Int): List<Int> {
            if (barCount <= 0) return emptyList()
            if (samples.isEmpty()) return List(barCount) { 0 }
            val bucketSize = maxOf(1, samples.size / barCount)
            val buckets = ArrayList<Double>(barCount)
            var idx = 0
            while (idx < samples.size && buckets.size < barCount) {
                val end = minOf(idx + bucketSize, samples.size)
                var sum = 0.0
                for (i in idx until end) {
                    val v = samples[i].toDouble()
                    sum += v * v
                }
                buckets.add(if (end > idx) sqrt(sum / (end - idx)) else 0.0)
                idx = end
            }
            val max = buckets.maxOrNull() ?: 0.0
            val bars = buckets.map { rms ->
                val norm = if (max > 0) rms / max else 0.0
                (norm * 255).toInt().coerceIn(0, 255)
            }.toMutableList()
            while (bars.size < barCount) bars.add(0)
            return bars
        }
    }
}
