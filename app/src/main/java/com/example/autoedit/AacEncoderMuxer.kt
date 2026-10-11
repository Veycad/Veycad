package com.veycad.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.content.Context
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max

/**
 * Owned AAC encoder primitives. The export backend feeds PCM from the selected local music
 * asset in graph order; timestamps are sample-clock based rather than decoder callback time.
 * Keeping this separate from Media3 makes the mux contract testable and prevents looping gaps.
 */
internal object AacEncoderMuxer {
    const val AAC_SAMPLES_PER_ACCESS_UNIT = 1024

    data class Config(val sampleRate: Int, val channelCount: Int, val bitrate: Int = 192_000) {
        init { require(sampleRate > 0 && channelCount in 1..2) }
    }

    fun createEncoder(config: Config, codecName: String? = null): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AAC_SAMPLES_PER_ACCESS_UNIT * config.channelCount * 2)
        }
        val encoder = if (codecName == null) MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            else MediaCodec.createByCodecName(codecName)
        try { encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); return encoder }
        catch (failure: Throwable) {
            runCatching { encoder.release() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    /** Queue PCM with a monotonic sample clock. Callers loop/cut PCM using [AudioExportPlan]. */
    fun queuePcm(encoder: MediaCodec, pcm: ByteBuffer, samplesBeforeBuffer: Long, config: Config): Boolean {
        val index = encoder.dequeueInputBuffer(10_000)
        if (index < 0) return false
        val target = encoder.getInputBuffer(index) ?: error("Missing AAC encoder input")
        target.clear(); target.put(pcm)
        encoder.queueInputBuffer(index, 0, target.position(), AudioExportPlan.presentationTimeUs(samplesBeforeBuffer, config.sampleRate), 0)
        return true
    }

    /** Prepare AAC and pre-music speech before any video/text rendering. All sinks are bounded;
     * a failed composition owns/deletes only its unique temp files. MUSIC keeps the legacy API. */
    fun prepareSourceAudio(composer: SourceAudioComposer, settings: AudioMixSettings, directory: File,
        music: PcmStreamProvider? = null, checkCancelled: () -> Unit = {}): SourceAacPreparation {
        require(settings.mode != AudioMixMode.MUSIC) { "MUSIC uses legacy export" }
        checkCancelled()
        require(directory.isDirectory || directory.mkdirs())
        val timing = establishSourceTiming(directory, checkCancelled)
        val aac = File.createTempFile("source-audio-", ".m4a", directory)
        var speech: File? = null
        try {
            val pcmFile = File.createTempFile("source-speech-", ".pcm", directory)
            speech = pcmFile
            val encoder = StreamingEncoder(aac, checkCancelled, timing)
            encoder.use {
                pcmFile.outputStream().buffered(4096).use { pcm ->
                    val scratch = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
                    var speechFrames = 0L
                    val stats = composer.compose(settings, encoder, PcmSink { format, start, samples, frames ->
                        checkCancelled()
                        check(format == PcmFormat(16_000, 1) && start == speechFrames && frames in 1..1024)
                        scratch.clear()
                        repeat(frames) { scratch.putShort(samples[it]) }
                        pcm.write(scratch.array(), 0, frames * 2)
                        speechFrames += frames
                    }, music, checkCancelled)
                    check(speechFrames == encoder.frames / 3) { "Speech/composition duration mismatch" }
                    encoder.finish()
                    checkCancelled()
                    return SourceAacPreparation(aac, pcmFile, encoder.frames, stats, encoder.peakPacketBytes)
                }
            }
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(aac.toPath()) }.exceptionOrNull()?.let(failure::addSuppressed)
            speech?.let { runCatching { Files.deleteIfExists(it.toPath()) }.exceptionOrNull()?.let(failure::addSuppressed) }
            throw failure
        }
    }

    /** Existing AAC encoder owner, progressively drains each packet straight into a single-track
     * M4A. No PCM timeline or AAC packet list. Scratch is the caller's <=1024-frame buffer. */
    private data class SourceTiming(val codecName: String, val delayFrames: Int)

    /** No guessed codec offset. A bounded probe establishes this selected encoder/config and the
     * actual extractor/decoder/container contract on each preparation, including first/last PCM.
     * Older Android releases have not been proven to support this negative-PTS edit-list policy. */
    private fun establishSourceTiming(directory: File, checkCancelled: () -> Unit): SourceTiming {
        val startedNs = System.nanoTime()
        check(Build.VERSION.SDK_INT >= 35) { "Source AAC trim requires verified Android 35+ container support" }
        val selection = createEncoder(Config(48_000, 2))
        val name = try { selection.name } finally { selection.release() }
        val raw = File.createTempFile("aac-probe-", ".m4a", directory)
        var trimmed: File? = null
        var failure: Throwable? = null
        try {
            val trimmedFile = File.createTempFile("aac-probe-", ".m4a", directory)
            trimmed = trimmedFile
            fun encode(file: File, timing: SourceTiming) {
                StreamingEncoder(file, checkCancelled, timing).use { sink ->
                    val samples = ShortArray(2048)
                    var frame = 0
                    while (frame < 48000) {
                        checkCancelled()
                        val count = minOf(1024, 48000 - frame)
                        repeat(count) { i ->
                            val distance = listOf(1600, 24000, 46400).minOf { kotlin.math.abs(frame + i - it) }
                            val value = (22000 * kotlin.math.exp(-distance.toDouble() * distance / 625.0)).toInt().toShort()
                            samples[i*2] = value; samples[i*2+1] = value
                        }
                        sink.write(PcmFormat(48000, 2), frame.toLong(), samples, count); frame += count
                    }
                    sink.finish()
                }
            }
            encode(raw, SourceTiming(name, 0))
            val measured = probeMarkers(raw, checkCancelled)
            val offsets = measured.first.mapIndexed { index, position -> position - listOf(1600L,24000L,46400L)[index] }
            check(offsets.distinct().size == 1 && offsets[0] in 1..4800) { "Unsupported AAC marker delay: $name $offsets" }
            val timing = SourceTiming(name, offsets[0].toInt())
            encode(trimmedFile, timing)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(trimmedFile.path); extractor.selectTrack(0)
                val format = extractor.getTrackFormat(0)
                android.util.Log.i("SourceAacTiming", "codec=$name delay=${timing.delayFrames} firstPacketPts=${extractor.sampleTime} format=$format")
                check(format.containsKey(MediaFormat.KEY_ENCODER_DELAY) && format.getInteger(MediaFormat.KEY_ENCODER_DELAY) == timing.delayFrames)
                check(format.containsKey(MediaFormat.KEY_ENCODER_PADDING) && format.getInteger(MediaFormat.KEY_ENCODER_PADDING) >= 0)
                check(kotlin.math.abs(extractor.sampleTime + timing.delayFrames * 1_000_000L / 48000) <= 1) { "AAC extractor did not preserve negative priming PTS" }
            } finally { extractor.release() }
            val proof = probeMarkers(trimmedFile, checkCancelled)
            android.util.Log.i("SourceAacTiming", "codec=$name rawMarkers=${measured.first} rawFrames=${measured.second} trimmedMarkers=${proof.first} trimmedFrames=${proof.second}")
            check(proof.first == listOf(1600L,24000L,46400L) && proof.second == 48000L) {
                "Unsupported AAC container trim/double-trim: $name markers=${proof.first}, frames=${proof.second}"
            }
            return timing
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            android.util.Log.i("SourceAacTiming", "calibrationElapsedUs=${(System.nanoTime()-startedNs)/1000}; probePCM=2048 shorts; packet retention=0; codecs sequential")
            var cleanup: Throwable? = null
            for (file in listOfNotNull(raw,trimmed)) try { Files.deleteIfExists(file.toPath()) }
                catch (caught: Throwable) { if (cleanup == null) cleanup=caught else cleanup.addSuppressed(caught) }
            cleanup?.let { if (failure != null) failure.addSuppressed(it) else throw it }
        }
    }

    /** Measures actual sample positions and sample count, without a whole-probe PCM allocation. */
    private fun probeMarkers(file: File, checkCancelled: () -> Unit): Pair<List<Long>, Long> {
        val peaks = DoubleArray(3)
        val positions = LongArray(3) { -1 }
        var frames = 0L
        val metrics=MediaCodecAudioDecoder.StreamMetrics()
        MediaCodecAudioDecoder.sourceProvider(mapOf("probe" to file), metrics, checkCancelled).open("probe",0)!!.use { stream ->
            check(stream.format == PcmFormat(48000,2))
            while (true) {
                val chunk = stream.read() ?: break
                repeat(chunk.frames) { i ->
                    val position = (chunk.startPtsUs * 48000 + 500000) / 1000000 + i
                    val region = when (position) { in 0..10000 -> 0; in 18000..34000 -> 1; in 40000..55000 -> 2; else -> -1 }
                    val value = kotlin.math.abs(chunk.sample(i,0))
                    if (region >= 0 && value > peaks[region]) { peaks[region] = value; positions[region] = position }
                }
                frames += chunk.frames
            }
        }
        check(metrics.openDecoders == 0 && metrics.retainedChunks == 0)
        android.util.Log.i("SourceAacTiming", "probeFrames=$frames peakOpen=${metrics.peakOpenDecoders} peakRetained=${metrics.peakRetainedChunks} maxDecoderBufferBytes=${metrics.peakDecoderBufferBytes}")
        check(peaks.all { it > .25 }) { "AAC boundary marker missing: ${peaks.toList()}" }
        return positions.toList() to frames
    }

    private class StreamingEncoder(private val file: File, val checkCancelled: () -> Unit,
        private val timing: SourceTiming) : PcmSink, AutoCloseable {
        private val config = Config(48_000, 2)
        private val encoder = createEncoder(config, timing.codecName)
        private var packets = 0L
        private var muxer: MediaMuxer? = null
        private var track = -1
        private var muxStarted = false
        private var finished = false
        private var eos = false
        private var progress = System.nanoTime()
        private val info = MediaCodec.BufferInfo()
        var frames = 0L; private set
        var peakPacketBytes = 0; private set

        init {
            try {
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                encoder.start()
            } catch (failure: Throwable) {
                runCatching { encoder.release() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { muxer?.release() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

        private fun checkProgress() {
            checkCancelled()
            check(System.nanoTime() - progress < 20_000_000_000L) { "Streaming AAC encoder stalled" }
        }

        override fun write(format: PcmFormat, startFrame: Long, samples: ShortArray, frames: Int) {
            check(!finished && format == PcmFormat(48_000, 2) && startFrame == this.frames)
            require(frames in 1..1024 && samples.size >= frames * 2)
            var offset = 0
            while (offset < frames) {
                checkProgress()
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val target = requireNotNull(encoder.getInputBuffer(index)).apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                    val count = minOf(frames - offset, target.remaining() / 4)
                    check(count > 0) { "AAC input cannot fit a PCM frame" }
                    repeat(count * 2) { target.putShort(samples[offset * 2 + it]) }
                    encoder.queueInputBuffer(index, 0, count * 4, AudioExportPlan.presentationTimeUs(this.frames, 48_000), 0)
                    this.frames += count; offset += count; progress = System.nanoTime()
                }
                drain()
            }
        }

        private fun drain() {
            while (true) {
                checkProgress()
                when (val index = encoder.dequeueOutputBuffer(info, 0)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxStarted) { "AAC encoder changed output format twice" }
                        android.util.Log.i("SourceAacTiming", "encoder=${encoder.name} actualOutput=${encoder.outputFormat}")
                        track = muxer!!.addTrack(encoder.outputFormat)
                        muxer!!.start(); muxStarted = true; progress = System.nanoTime()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxStarted)
                                // Preserve every delayed tail packet. The measured priming offset is
                                // expressed as negative PTS; exact padding is an edit-list boundary.
                                check(kotlin.math.abs(info.presentationTimeUs - AudioExportPlan.presentationTimeUs(packets * 1024, 48000)) <= 2) {
                                    "Unsupported AAC output timestamp contract: ${encoder.name} packet=$packets pts=${info.presentationTimeUs}"
                                }
                                run {
                                    val packet = requireNotNull(encoder.getOutputBuffer(index))
                                    peakPacketBytes = maxOf(peakPacketBytes, info.size)
                                    packet.position(info.offset); packet.limit(info.offset + info.size)
                                    val sample = MediaCodec.BufferInfo().apply { set(info.offset, info.size, info.presentationTimeUs - timing.delayFrames * 1_000_000L / 48000,
                                        info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()) }
                                    muxer!!.writeSampleData(track, packet, sample)
                                    packets++
                                }
                            }
                            eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { encoder.releaseOutputBuffer(index, false); progress = System.nanoTime() }
                    } else return
                }
            }
        }

        fun finish() {
            check(!finished && frames > 0)
            var queued = false
            while (!eos) {
                checkProgress()
                if (!queued) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        encoder.queueInputBuffer(index, 0, 0, AudioExportPlan.presentationTimeUs(frames, 48_000), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        queued = true; progress = System.nanoTime()
                    }
                }
                drain()
            }
            check(muxStarted)
            // AAC-LC access units have a fixed 1024-frame duration. Let MediaMuxer retain the
            // final coded AU duration, then crop only the presentation edit. A shortened EOS
            // also shortens mdhd/stts, hiding the padding from the extractor's gapless contract.
            muxer!!.stop(); muxStarted = false; finished = true
            if (timing.delayFrames > 0) AacMp4Trim.apply(file, frames, timing.delayFrames)
        }

        override fun close() {
            try { try { encoder.stop() } finally { encoder.release() } }
            finally { try { if (muxStarted) runCatching { muxer?.stop() } } finally { muxer?.release() } }
        }
    }

    /** Borrow prepared audio and video. Caller supplies proven exclusive raw video EOS, not
     * firstPTS + declared KEY_DURATION. Samples/PTS/rotation are copied unchanged. Publish only
     * after successful stop/close and final cancellation check; atomic failure preserves old MP4. */
    fun muxPreparedAudio(prepared: SourceAacPreparation, videoFile: File, outputFile: File,
        videoEndPtsUs: Long, checkCancelled: () -> Unit = {}) {
        prepared.requireOpen()
        require(videoEndPtsUs > 0 && videoFile.isFile)
        require(outputFile.canonicalFile != videoFile.canonicalFile && outputFile.canonicalFile != prepared.aacFile.canonicalFile
            && outputFile.canonicalFile != prepared.speechPcmFile.canonicalFile)
        checkCancelled()
        val partial = File.createTempFile("source-mux-", ".mp4", requireNotNull(outputFile.absoluteFile.parentFile))
        try {
            copyPreparedTracks(prepared, videoFile, partial, videoEndPtsUs, checkCancelled)
            checkCancelled()
            Files.move(partial.toPath(), outputFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(partial.toPath()) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun copyPreparedTracks(prepared: SourceAacPreparation, videoFile: File, partial: File,
        videoEndPtsUs: Long, checkCancelled: () -> Unit) {
        val video = MediaExtractor()
        try {
            video.setDataSource(videoFile.absolutePath)
            val audio = MediaExtractor()
            try {
                audio.setDataSource(prepared.aacFile.absolutePath)
                fun select(extractor: MediaExtractor, prefix: String): MediaFormat {
                    val track = (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
                    } ?: error("Missing prepared $prefix track")
                    extractor.selectTrack(track)
                    return extractor.getTrackFormat(track)
                }
                val videoFormat = select(video, "video/")
                val audioFormat = select(audio, "audio/")
                check(audioFormat.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC)
                val delay = audioFormat.getInteger(MediaFormat.KEY_ENCODER_DELAY)
                check(delay > 0 && kotlin.math.abs(audio.sampleTime + delay * 1_000_000L / 48000) <= 1) {
                    "Prepared AAC lost its verified priming edit"
                }
                val muxer = MediaMuxer(partial.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var started = false
                try {
                    if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
                    val videoTrack = muxer.addTrack(videoFormat)
                    val audioTrack = muxer.addTrack(audioFormat)
                    muxer.start(); started = true
                    // Hard ceiling instead of trusting arbitrary file metadata / allocating to sample size.
                    val capacity = 16 * 1024 * 1024
                    val buffer = ByteBuffer.allocateDirect(capacity)
                    val sample = MediaCodec.BufferInfo()
                    var lastVideo = Long.MIN_VALUE
                    var lastAudio = Long.MIN_VALUE
                    while (video.sampleTrackIndex >= 0 || audio.sampleTrackIndex >= 0) {
                        checkCancelled()
                        val useVideo = video.sampleTrackIndex >= 0 && (audio.sampleTrackIndex < 0 || video.sampleTime <= audio.sampleTime)
                        val extractor = if (useVideo) video else audio
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            check(extractor.sampleSize in 0..capacity.toLong()) { "Mux sample exceeds 16 MiB" }
                        check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted mux input unsupported" }
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        check(size in 0..capacity) { "Invalid or oversized mux sample" }
                        val pts = extractor.sampleTime
                        if (useVideo) check(pts < videoEndPtsUs) { "Video sample beyond proven track endpoint" }
                        var flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                        sample.set(0, size, pts, flags)
                        muxer.writeSampleData(if (useVideo) videoTrack else audioTrack, buffer, sample)
                        if (useVideo) lastVideo = maxOf(lastVideo, pts) else lastAudio = maxOf(lastAudio, pts)
                        extractor.advance()
                    }
                    check(lastVideo != Long.MIN_VALUE && lastAudio != Long.MIN_VALUE) { "Empty prepared mux track" }
                    // Keep every coded AAC packet, including priming and padding. elst supplies
                    // presentation length after stop; an EOS shorter than the last AU loses tail.
                    for ((track, end) in listOf(videoTrack to videoEndPtsUs)) {
                        sample.set(0, 0, end, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        muxer.writeSampleData(track, ByteBuffer.allocate(0), sample)
                    }
                    checkCancelled()
                    muxer.stop(); started = false
                    AacMp4Trim.apply(partial, prepared.sampleFrames, delay)
                } finally { try { if (started) runCatching { muxer.stop() } } finally { muxer.release() } }
            } finally { audio.release() }
        } finally { video.release() }
    }

    /**
     * Decodes the bundled music once, loops/cuts PCM on the sample clock, encodes AAC-LC and
     * muxes it with the VME H.264 stream. No Media3 audio clock participates in this path.
     */
    fun muxMusic(context: Context, resourceId: Int, videoFile: File, outputFile: File, durationUs: Long) {
        val decoded = MediaCodecAudioDecoder.decodeResource(context, resourceId, durationUs,
            preserveFloatHeadroom = true)
        val pcm = prepareMusicPcm(decoded)
        muxPcm(pcm, videoFile, outputFile, durationUs)
    }

    /** Same owned AAC path for user-selected files; the source never has to be packaged in APK. */
    fun muxMusicFile(audioFile: File, videoFile: File, outputFile: File, durationUs: Long,
        checkCancelled: () -> Unit = {}, startUs: Long = 0L) {
        checkCancelled()
        val decoded = MediaCodecAudioDecoder.decode(audioFile, durationUs,
            preserveFloatHeadroom = true, checkCancelled = checkCancelled, startUs = startUs)
        muxPcm(prepareMusicPcm(decoded, checkCancelled), videoFile, outputFile, durationUs, checkCancelled)
    }

    private fun prepareMusicPcm(decoded: MediaCodecAudioDecoder.DecodedAudio,
                                checkCancelled: () -> Unit = {}): Pcm {
        val channels = if (decoded.channelCount <= 2) decoded.channelCount else 1
        val samples = if (channels == decoded.channelCount) decoded.interleavedSamples else decoded.mono()
        val prepared = AudioExportHeadroom.prepare(samples, checkCancelled)
        return Pcm(PcmSampleConverter.encode16(prepared.samples), decoded.sampleRate, channels)
    }

    private fun muxPcm(pcm: Pcm, videoFile: File, outputFile: File, durationUs: Long, checkCancelled: () -> Unit = {}) {
        checkCancelled()
        val config = Config(pcm.sampleRate, pcm.channels)
        val targetSamples = durationUs * config.sampleRate / 1_000_000L
        val looped = loopPcm(pcm.bytes, pcm.bytesPerFrame, targetSamples)
        val encoded = encode(looped, targetSamples, config, checkCancelled)
        val video = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val videoTrack = (0 until video.trackCount).first { video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoFormat = video.getTrackFormat(videoTrack)
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = muxer.addTrack(encoded.format)
            muxer.start()
            video.selectTrack(videoTrack)
            // KEY_MAX_INPUT_SIZE is normally populated for an MP4 track. On API 26–27 we
            // cannot read the current sample size, so retain a conservative fallback for a
            // large H.264 keyframe rather than truncating the video during the final mux.
            val declaredMaxSampleSize = runCatching {
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }.getOrDefault(0)
            var buffer = ByteBuffer.allocateDirect(max(declaredMaxSampleSize, 4 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            while (true) {
                checkCancelled()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val sampleSize = video.sampleSize
                    if (sampleSize > buffer.capacity()) buffer = ByteBuffer.allocateDirect(sampleSize.toInt())
                }
                buffer.clear(); val size = video.readSampleData(buffer, 0)
                if (size < 0) break
                val muxerFlags = if (video.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, video.sampleTime, muxerFlags)
                muxer.writeSampleData(outVideo, buffer, info); video.advance()
            }
            encoded.samples.forEach { sample -> muxer.writeSampleData(outAudio, sample.data.duplicate(), sample.info) }
        } finally {
            video.release(); runCatching { muxer.stop() }; muxer.release()
        }
    }

    private data class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int) {
        val bytesPerFrame get() = channels * 2
    }
    private data class Encoded(val format: MediaFormat, val samples: List<Sample>)
    private data class Sample(val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private fun loopPcm(source: ByteArray, bytesPerFrame: Int, targetSamples: Long): ByteArray {
        require(source.isNotEmpty() && source.size % bytesPerFrame == 0)
        val wanted = (targetSamples * bytesPerFrame).toInt(); val out = ByteArray(wanted); var offset = 0
        while (offset < wanted) { val count = minOf(source.size, wanted - offset); System.arraycopy(source, 0, out, offset, count); offset += count }
        return out
    }

    private fun encode(pcm: ByteArray, targetSamples: Long, config: Config, checkCancelled: () -> Unit): Encoded {
        val encoder = createEncoder(config); val samples = mutableListOf<Sample>(); var format: MediaFormat? = null; var byteOffset = 0; var sampleOffset = 0L; var eosQueued = false; val info = MediaCodec.BufferInfo()
        encoder.start()
        try { while (true) {
            checkCancelled()
            if (!eosQueued) { val index = encoder.dequeueInputBuffer(10_000); if (index >= 0) { val input = encoder.getInputBuffer(index)!!; val bytes = minOf(input.capacity(), pcm.size - byteOffset); if (bytes > 0) { input.put(pcm, byteOffset, bytes); encoder.queueInputBuffer(index, 0, bytes, AudioExportPlan.presentationTimeUs(sampleOffset, config.sampleRate), 0); byteOffset += bytes; sampleOffset += bytes / (config.channelCount * 2) } else { encoder.queueInputBuffer(index, 0, 0, AudioExportPlan.presentationTimeUs(targetSamples, config.sampleRate), MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosQueued = true } } }
            when (val index = encoder.dequeueOutputBuffer(info, 10_000)) { MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = encoder.outputFormat; MediaCodec.INFO_TRY_AGAIN_LATER -> Unit; else -> if (index >= 0) { if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.presentationTimeUs <= AudioExportPlan.presentationTimeUs(targetSamples, config.sampleRate)) { val source = encoder.getOutputBuffer(index)!!; source.position(info.offset); source.limit(info.offset + info.size); val copy = ByteBuffer.allocateDirect(info.size); copy.put(source); copy.flip(); samples += Sample(copy, MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }) }; val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; encoder.releaseOutputBuffer(index, false); if (end) break } }
        } } finally { runCatching { encoder.stop() }; encoder.release() }
        return Encoded(requireNotNull(format), samples)
    }
}
