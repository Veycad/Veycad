package com.veycad.app

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest

/** Examines the copied container and probes a real decoder; filename suffixes are irrelevant. */
class MediaInputInspector(private val checkCancelled: () -> Unit = {}) {
    fun inspect(id: String, file: File, name: String): MediaSource =
        inspect(id, file, name, fingerprint = null, checkCancelled = checkCancelled)

    /** Import can supply the SHA-256 accumulated while copying, avoiding a second file read. */
    internal fun inspect(id: String, file: File, name: String, fingerprint: String?,
        checkCancelled: () -> Unit): MediaSource {
        var cancellation: Throwable? = null
        val check = {
            try { checkCancelled() } catch (failure: Throwable) {
                cancellation = failure
                throw failure
            }
        }
        check()
        require(file.isFile && file.canRead() && file.length() > 0) { "$name: файл пуст или недоступен" }
        val initialSize = file.length()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            check()
            var videoTrack = -1
            var hasAudio = false
            for (index in 0 until extractor.trackCount) {
                check()
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = index
                if (mime.startsWith("audio/")) hasAudio = true
            }
            require(videoTrack >= 0) { "Видеодорожка отсутствует" }
            val format = extractor.getTrackFormat(videoTrack)
            val transfer = format.optionalInt(MediaFormat.KEY_COLOR_TRANSFER)
            require(transfer != MediaFormat.COLOR_TRANSFER_ST2084 && transfer != MediaFormat.COLOR_TRANSFER_HLG) {
                "HDR пока не поддерживается: преобразование в SDR не выполняется"
            }
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            require(width > 0 && height > 0) { "Некорректные размеры видеодорожки" }
            val rotation = format.optionalInt(MediaFormat.KEY_ROTATION) ?: 0
            require(rotation in setOf(0, 90, 180, 270)) { "Некорректный поворот видеодорожки" }
            extractor.selectTrack(videoTrack)
            val samples = scanSamples(extractor, check)
            val durationUs = samples.durationUs(format)
            probeDecoder(extractor, format, samples.firstPtsUs, check)
            val digest = fingerprint ?: hash(file, check)
            check()
            require(file.length() == initialSize) { "Файл изменился во время проверки" }
            return MediaSource(id, file, name, durationUs, initialSize, rotation, width, height,
                requireNotNull(format.getString(MediaFormat.KEY_MIME)), transfer, hasAudio, digest,
                firstVideoPtsUs = samples.firstPtsUs)
        } catch (failure: Exception) {
            cancellation?.let { throw it }
            throw IllegalArgumentException("$name: ${failure.message ?: "не удалось проверить видео"}", failure)
        } finally {
            extractor.release()
        }
    }

    private data class Samples(val firstPtsUs: Long, val lastPtsUs: Long, val penultimatePtsUs: Long?) {
        fun durationUs(format: MediaFormat): Long {
            val declared = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
            return mediaInputDurationUs(firstPtsUs, lastPtsUs, penultimatePtsUs, declared,
                format.optionalInt(MediaFormat.KEY_FRAME_RATE))
        }
    }

    private fun scanSamples(extractor: MediaExtractor, check: () -> Unit): Samples {
        var first = Long.MAX_VALUE
        var last = -1L
        var penultimate: Long? = null
        while (extractor.sampleTrackIndex >= 0) {
            check()
            val pts = extractor.sampleTime
            require(pts >= 0) { "Некорректные PTS видео" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                require(extractor.sampleSize > 0) { "Пустой видеосэмпл" }
            }
            require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Защищённое видео не поддерживается" }
            first = minOf(first, pts)
            // B-frame decode order need not be presentation order. Track extrema without
            // retaining an array of every timestamp or treating that reordering as corruption.
            if (pts > last) {
                penultimate = last.takeIf { it >= 0 }
                last = pts
            } else if (pts < last && (penultimate == null || pts > penultimate)) {
                penultimate = pts
            }
            if (!extractor.advance()) break
        }
        require(last >= 0) { "Видеодорожка не содержит кадров" }
        return Samples(first, last, penultimate)
    }

    private fun probeDecoder(extractor: MediaExtractor, format: MediaFormat, firstPtsUs: Long,
        check: () -> Unit) {
        check()
        val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        require(decoderName != null) { "На устройстве нет декодера для ${format.getString(MediaFormat.KEY_MIME)}" }
        val decoder = MediaCodec.createByCodecName(decoderName)
        var started = false
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            started = true
            extractor.seekTo(firstPtsUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var inputEnded = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                check()
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000L)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index))
                        buffer.clear()
                        if (extractor.sampleTrackIndex < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                require(extractor.sampleSize in 1L..buffer.capacity().toLong()) { "Видеосэмпл превышает буфер декодера" }
                            }
                            val size = extractor.readSampleData(buffer, 0)
                            require(size > 0) { "Не удалось прочитать видеосэмпл" }
                            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0)
                                MediaCodec.BUFFER_FLAG_PARTIAL_FRAME else 0
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, flags)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10_000L)
                if (output >= 0) {
                    val decoded = info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    decoder.releaseOutputBuffer(output, false)
                    if (decoded) return
                    require(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) { "Декодер не получил ни одного кадра" }
                }
            }
            throw IllegalArgumentException("Декодер не выдал кадр за 5 секунд")
        } finally {
            if (started) runCatching { decoder.stop() }
            decoder.release()
        }
    }

    private fun hash(file: File, check: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1_024)
        file.inputStream().use { input ->
            while (true) {
                check()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private fun MediaFormat.optionalInt(key: String): Int? = if (containsKey(key)) getInteger(key) else null
