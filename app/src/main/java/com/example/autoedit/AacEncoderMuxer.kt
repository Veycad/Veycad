package com.veycad.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.content.Context
import android.os.Build
import java.io.BufferedWriter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
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
     * M4A. No PCM timeline or AAC packet list; one 1024-frame input block owns the final tail. */
    private data class SourceTiming(val codecName: String, val delayFrames: Int)

    /** Own one copied stereo block; pad its final tail and queue empty EOS at the padded end.
     * Accepted frames describe authored PCM; queued frames include only final zero padding. */
    internal class SourceInputBlocks(private val queue: (ShortArray, Int, Long, Boolean) -> Unit) {
        private val pending=ShortArray(2048)
        var retainedFrames=0; private set
        var acceptedFrames=0L; private set
        var queuedFrames=0L; private set
        private var ended=false
        fun append(samples: ShortArray, count: Int) {
            check(!ended)
            require(count in 1..1024 && samples.size>=count*2)
            var offset=0
            while (offset<count) {
                if (retainedFrames==1024) flush()
                val copied=minOf(count-offset,1024-retainedFrames)
                samples.copyInto(pending,retainedFrames*2,offset*2,(offset+copied)*2)
                retainedFrames+=copied; acceptedFrames+=copied; offset+=copied
            }
        }
        fun finish() {
            check(!ended && acceptedFrames>0)
            ended=true
            pending.fill(0,retainedFrames*2)
            retainedFrames=1024
            flush()
            queue(pending,0,queuedFrames,true)
            check(queuedFrames>=acceptedFrames && queuedFrames-acceptedFrames<1024)
        }
        private fun flush() {
            check(retainedFrames==1024)
            queue(pending,retainedFrames,queuedFrames,false)
            queuedFrames+=retainedFrames; retainedFrames=0
        }
        fun close() { ended=true; retainedFrames=0 }
    }

    /** A raw synthetic probe may defer this failure to observe the complete tail, never waive it. */
    internal class SourcePacketTimestampAudit(private val codecName: String,
        private val deferForProbe: Boolean = false) {
        var failure: IllegalStateException? = null; private set
        var violations = 0L; private set
        fun record(packet: Long, ptsUs: Long) {
            val expected = AudioExportPlan.presentationTimeUs(packet * 1024, 48000)
            if (ptsUs !in expected - 2..expected + 2) {
                violations++
                if (failure == null) failure = IllegalStateException(
                    "Unsupported AAC output timestamp contract: $codecName packet=$packet pts=$ptsUs")
                if (!deferForProbe) requireValid()
            }
        }
        fun requireValid() { failure?.let { throw it } }
    }

    /** One diagnostic decode deadline, including provider priming and reads that return EOF. */
    internal class SourceProbeDecodeBudget(private val checkCancelled: () -> Unit,
        private val nanoTime: () -> Long = System::nanoTime) : () -> Unit {
        private val startedNs = nanoTime()
        override fun invoke() {
            checkCancelled()
            check(nanoTime() - startedNs < 60_000_000_000L) { "AAC diagnostic decode time bound exceeded" }
        }
        fun read(stream: PcmStream): PcmChunk? {
            invoke()
            val chunk = stream.read()
            invoke()
            return chunk
        }
    }

    /** Observes two axes of the SAME PCM. It neither inserts gap samples nor rewrites raw PTS. */
    internal class SourceProbeSamples {
        val sequentialMarkers = LongArray(3) { -1 }
        val ptsMarkers = LongArray(3) { -1 }
        val peaks = DoubleArray(3)
        var frames = 0L; private set
        var chunks = 0L; private set
        var discontinuities = 0L; private set
        var maximumGapFrames = 0L; private set
        private var previousEnd: Long? = null
        fun append(chunk: PcmChunk) {
            check(chunk.format == PcmFormat(48000,2))
            check(chunks < 256 && frames + chunk.frames <= 256L * 1024) { "AAC diagnostic decode bound exceeded" }
            val start = (chunk.startPtsUs * 48000 + 500000) / 1000000
            previousEnd?.let { end ->
                val gap = start - end
                if (gap != 0L) discontinuities++
                maximumGapFrames = maxOf(maximumGapFrames,kotlin.math.abs(gap))
            }
            repeat(chunk.frames) { i ->
                val sequential = frames + i
                val region = when (sequential) { in 0..10000 -> 0; in 18000..34000 -> 1; in 40000..60000 -> 2; else -> -1 }
                val value = kotlin.math.abs(chunk.sample(i,0))
                if (region >= 0 && value > peaks[region]) {
                    peaks[region] = value; sequentialMarkers[region] = sequential; ptsMarkers[region] = start + i
                }
            }
            previousEnd = start + chunk.frames; frames += chunk.frames; chunks++
        }
    }

    /** Only instrumentation's existing evidence directory can retain these synthetic diagnostics.
     * Names/sidecars deliberately cannot impersonate the five native acceptance artifacts. */
    private class ProbeEvidence(private val directory: File, private val raw: File) : AutoCloseable {
        private val retainedDirectory = directory.takeIf {
            BuildConfig.DEBUG && it.parentFile?.name == "source-audio-native-evidence"
        }
        private val writer: BufferedWriter? = retainedDirectory?.let {
            File(it,"diagnostic-${raw.name}.txt").bufferedWriter()
        }
        fun log(message: String) {
            android.util.Log.i("SourceAacTiming",message)
            writer?.appendLine(message)
        }
        fun retain(inputFrames: Int, checkCancelled: () -> Unit) {
            val destination = retainedDirectory ?: return
            check(raw.length() in 1..2_097_152L) { "AAC diagnostic container byte bound exceeded" }
            val copy = File(destination,"diagnostic-$inputFrames-${raw.name}")
            val sidecar = File(destination,"${copy.name}.diagnostic-sha256.txt")
            try {
                checkCancelled(); raw.copyTo(copy)
                val digest = MessageDigest.getInstance("SHA-256")
                copy.inputStream().use { input ->
                    val bytes = ByteArray(8192)
                    while (true) {
                        checkCancelled()
                        val count = input.read(bytes); if (count < 0) break
                        digest.update(bytes,0,count)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                sidecar.writeText("DIAGNOSTIC_ONLY_NOT_ACCEPTED\nfile=${copy.name}\ninputFrames=$inputFrames\nbytes=${copy.length()}\nsha256=$hash\n")
                log("ledger=${raw.name} diagnosticOnly=true retained=${copy.name} sha256=$hash bytes=${copy.length()}")
            } catch (failure: Throwable) {
                for (file in listOf(copy,sidecar)) runCatching { Files.deleteIfExists(file.toPath()) }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
        override fun close() { writer?.close() }
    }

    private data class ProbeEncoding(val packets: Long, val failure: IllegalStateException?)

    /** Exact bounded bytes only; this diagnostic does not interpret or normalize the AAC config. */
    private fun probeCsdHex(format: MediaFormat): String {
        val bytes = format.getByteBuffer("csd-0")?.duplicate() ?: return "missing"
        check(bytes.remaining() in 1..256) { "AAC diagnostic CSD byte bound exceeded" }
        return buildString { while (bytes.hasRemaining()) append("%02x".format(bytes.get().toInt() and 255)) }
    }

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
            fun encode(file: File, timing: SourceTiming, inputFrames: Int = 48000,
                deferFailureForRawProbe: Boolean = false, evidence: ProbeEvidence? = null): ProbeEncoding {
                val sink = StreamingEncoder(file, checkCancelled, timing, diagnostics = true,
                    rawProbeFrames = inputFrames.takeIf { deferFailureForRawProbe }, probeLog = evidence?.let { it::log })
                try {
                    sink.use {
                        val samples = ShortArray(2048)
                        var frame = 0
                        while (frame < inputFrames) {
                            checkCancelled()
                            val count = minOf(1024, inputFrames - frame)
                            repeat(count) { i ->
                                val distance = listOf(1600, 24000, inputFrames-1600).minOf { kotlin.math.abs(frame + i - it) }
                                val value = (22000 * kotlin.math.exp(-distance.toDouble() * distance / 625.0)).toInt().toShort()
                                samples[i*2] = value; samples[i*2+1] = value
                            }
                            sink.write(PcmFormat(48000, 2), frame.toLong(), samples, count); frame += count
                        }
                        sink.finish()
                        return ProbeEncoding(sink.encodedPackets,sink.timestampFailure)
                    }
                } catch (caught: Throwable) {
                    val violation = sink.timestampFailure
                    if (violation == null || caught is CancellationException) throw caught
                    if (caught !== violation) violation.addSuppressed(caught)
                    throw violation
                }
            }
            ProbeEvidence(directory,raw).use { evidence ->
                evidence.log("ledger=${raw.name} diagnosticOnly=true inputFrames=48000 authoredMarkers=[1600,24000,46400] rawPtsPreserved=true")
                val result = encode(raw,SourceTiming(name,0),deferFailureForRawProbe=true,evidence=evidence)
                result.failure?.let { violation ->
                    // Complete raw observation cannot authorize this codec. Preserve the original
                    // error even when decoding/retention fails, and never enter trimmed/real work.
                    fun observe(action: () -> Unit) {
                        try { action() } catch (cancelled: CancellationException) { throw cancelled }
                        catch (caught: Throwable) { violation.addSuppressed(caught) }
                    }
                    observe { inspectRawProbe(raw,48000,result.packets,evidence,checkCancelled) }
                    observe {
                        val full = File.createTempFile("aac-probe-", ".m4a", directory)
                        var fullFailure: Throwable? = null
                        try {
                            ProbeEvidence(directory,full).use { fullEvidence ->
                                fullEvidence.log("ledger=${full.name} diagnosticOnly=true inputFrames=51200 authoredMarkers=[1600,24000,49600] rawPtsPreserved=true")
                                val fullResult = encode(full,SourceTiming(name,0),51200,true,fullEvidence)
                                fullResult.failure?.let { violation.addSuppressed(it) }
                                inspectRawProbe(full,51200,fullResult.packets,fullEvidence,checkCancelled)
                            }
                        } catch (caught: Throwable) { fullFailure = caught; throw caught }
                        finally {
                            try { Files.deleteIfExists(full.toPath()) }
                            catch (caught: Throwable) { (fullFailure ?: violation).addSuppressed(caught) }
                        }
                    }
                    observe { evidence.log("ledger=${raw.name} diagnosticAttemptFinished=true productionAccepted=false failure=${violation.message} secondaryFailures=${violation.suppressed.size}") }
                    throw violation
                }
            }
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
            android.util.Log.i("SourceAacTiming", "calibrationElapsedUs=${(System.nanoTime()-startedNs)/1000}; probeScratchPCM=4096 bytes; encoderPendingPCM=4096 bytes; packet retention=0; codecs sequential")
            var cleanup: Throwable? = null
            for (file in listOfNotNull(raw,trimmed)) try { Files.deleteIfExists(file.toPath()) }
                catch (caught: Throwable) { if (cleanup == null) cleanup=caught else cleanup.addSuppressed(caught) }
            cleanup?.let { if (failure != null) failure.addSuppressed(it) else throw it }
        }
    }

    /** Failure-only observation: sequential PCM positions are evidence, not replacement PTS. */
    private fun inspectRawProbe(file: File, inputFrames: Int, codedPackets: Long,
        evidence: ProbeEvidence, checkCancelled: () -> Unit) {
        checkCancelled()
        evidence.retain(inputFrames,checkCancelled)
        val containerPackets = logProbeContainer(file,"diagnostic-before-decode",checkCancelled,evidence::log,true)
        val observed = SourceProbeSamples()
        val metrics = MediaCodecAudioDecoder.StreamMetrics()
        val budget = SourceProbeDecodeBudget(checkCancelled)
        var failure: Throwable? = null
        try {
            budget()
            MediaCodecAudioDecoder.sourceProvider(mapOf("probe" to file),metrics,budget).open("probe",0)!!.use { stream ->
                budget()
                check(stream.format == PcmFormat(48000,2))
                while (true) {
                    val chunk = budget.read(stream) ?: break
                    evidence.log("ledger=${file.name} diagnosticChunk=${observed.chunks} sequentialStart=${observed.frames} pts=${chunk.startPtsUs} frames=${chunk.frames}")
                    observed.append(chunk)
                    budget()
                }
            }
            budget()
            evidence.log("ledger=${file.name} diagnosticDecodeEos=true inputFrames=$inputFrames codedPackets=$codedPackets containerPackets=$containerPackets decodedFrames=${observed.frames} chunks=${observed.chunks} sequentialMarkers=${observed.sequentialMarkers.toList()} ptsMarkers=${observed.ptsMarkers.toList()} peaks=${observed.peaks.toList()} discontinuities=${observed.discontinuities} maximumGapFrames=${observed.maximumGapFrames} productionAccepted=false")
            check(containerPackets == codedPackets) { "AAC diagnostic mux lost packets" }
            check(observed.frames == codedPackets * 1024) { "AAC diagnostic decoded count differs from complete coded access units" }
            check(observed.peaks.all { it > .25 }) { "AAC diagnostic boundary marker missing" }
            check(metrics.openDecoders == 0 && metrics.retainedChunks == 0) { "AAC diagnostic decoder ownership not released" }
            budget()
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            try { evidence.log("ledger=${file.name} diagnosticClosed=true openDecoders=${metrics.openDecoders} retainedChunks=${metrics.retainedChunks} decodedFrames=${observed.frames} chunks=${observed.chunks}") }
            catch (caught: Throwable) { if (failure != null) failure.addSuppressed(caught) else throw caught }
        }
    }

    /** Measures actual sample positions and sample count, without a whole-probe PCM allocation. */
    private fun probeMarkers(file: File, checkCancelled: () -> Unit): Pair<List<Long>, Long> {
        logProbeContainer(file, "before-decode", checkCancelled)
        val peaks = DoubleArray(3)
        val positions = LongArray(3) { -1 }
        var frames = 0L
        val metrics=MediaCodecAudioDecoder.StreamMetrics()
        var chunks=0L
        MediaCodecAudioDecoder.sourceProvider(mapOf("probe" to file), metrics, checkCancelled).open("probe",0)!!.use { stream ->
            check(stream.format == PcmFormat(48000,2))
            while (true) {
                val chunk = stream.read() ?: break
                if (chunks < 256) android.util.Log.i("SourceAacTiming", "ledger=${file.name} decodedChunk=$chunks pts=${chunk.startPtsUs} frames=${chunk.frames} format=${stream.format}")
                chunks++
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
        android.util.Log.i("SourceAacTiming", "ledger=${file.name} probeFrames=$frames chunks=$chunks ledgerTruncated=${chunks>256} peakOpen=${metrics.peakOpenDecoders} peakRetained=${metrics.peakRetainedChunks} maxDecoderBufferBytes=${metrics.peakDecoderBufferBytes}")
        check(peaks.all { it > .25 }) { "AAC boundary marker missing: ${peaks.toList()}" }
        return positions.toList() to frames
    }

    /** Short synthetic probes only. Stream metadata/packet facts without retaining a packet list. */
    private fun logProbeContainer(file: File, stage: String, checkCancelled: () -> Unit,
        log: (String) -> Unit = { android.util.Log.i("SourceAacTiming",it); Unit }, bounded: Boolean = false): Long {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.path); extractor.selectTrack(0)
            log("ledger=${file.name} container=$stage bytes=${file.length()} format=${extractor.getTrackFormat(0)}")
            if (bounded) log("ledger=${file.name} container=$stage csd0Hex=${probeCsdHex(extractor.getTrackFormat(0))}")
            var packets=0L
            while (extractor.sampleTrackIndex>=0) {
                checkCancelled()
                if (bounded) check(packets < 256 && file.length() <= 2_097_152L) { "AAC diagnostic container bound exceeded" }
                val size=if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.P) extractor.sampleSize else -1L
                if (packets<256) log("ledger=${file.name} containerPacket=$packets pts=${extractor.sampleTime} bytes=$size flags=${extractor.sampleFlags}")
                packets++; extractor.advance()
            }
            log("ledger=${file.name} containerPackets=$packets ledgerTruncated=${packets>256}")
            return packets
        } finally { extractor.release() }
    }

    private class StreamingEncoder(private val file: File, val checkCancelled: () -> Unit,
        private val timing: SourceTiming, private val diagnostics: Boolean = false,
        private val rawProbeFrames: Int? = null, private val probeLog: ((String) -> Unit)? = null) : PcmSink, AutoCloseable {
        private val config = Config(48_000, 2)
        private val logPackets = diagnostics || BuildConfig.DEBUG
        private val timestampAudit = SourcePacketTimestampAudit(timing.codecName,rawProbeFrames != null)
        private val probeStarted = System.nanoTime()
        private var encodedBytes = 0L
        private val encoder = createEncoder(config, timing.codecName)
        private var packets = 0L
        private var inputWorks = 0L
        private var outputEvents = 0L
        private var submittedFrames = 0L
        private var inputEosQueued = false
        private var muxer: MediaMuxer? = null
        private var track = -1
        private var muxStarted = false
        private var finished = false
        private var eos = false
        private var progress = System.nanoTime()
        private val info = MediaCodec.BufferInfo()
        private val input = SourceInputBlocks(::queueBlock)
        val frames get() = input.acceptedFrames
        val encodedPackets get() = packets
        val timestampFailure get() = timestampAudit.failure
        var peakPacketBytes = 0; private set

        init {
            try {
                check(rawProbeFrames == null || rawProbeFrames in listOf(48000,51200))
                check(rawProbeFrames == null || timing.delayFrames == 0)
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
            if (rawProbeFrames != null) check(System.nanoTime() - probeStarted < 60_000_000_000L) { "AAC diagnostic encoder time bound exceeded" }
        }

        private fun log(message: String) {
            if (probeLog == null) android.util.Log.i("SourceAacTiming",message) else probeLog.invoke(message)
        }

        override fun write(format: PcmFormat, startFrame: Long, samples: ShortArray, frames: Int) {
            check(!finished && format == PcmFormat(48_000, 2) && startFrame == this.frames)
            checkCancelled()
            if (rawProbeFrames != null) check(this.frames + frames <= rawProbeFrames) { "AAC diagnostic input bound exceeded" }
            input.append(samples,frames)
        }

        private fun queueBlock(samples: ShortArray, count: Int, start: Long, last: Boolean) {
            check(!inputEosQueued && start==submittedFrames)
            check(if(last) count==0 else count==1024)
            if (rawProbeFrames != null) check(inputWorks < 51) { "AAC diagnostic input work bound exceeded" }
            while (true) {
                checkProgress()
                val index = encoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val target = requireNotNull(encoder.getInputBuffer(index)).apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                    check(target.remaining()>=count*4) { "AAC input cannot fit the bounded PCM block" }
                    repeat(count * 2) { target.putShort(samples[it]) }
                    val pts=AudioExportPlan.presentationTimeUs(start, 48_000)
                    val flags=if(last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    if (logPackets && inputWorks<256) log("ledger=${file.name} input=$inputWorks start=$start frames=$count pts=$pts flags=$flags acceptedFrames=$frames queuedBefore=$submittedFrames")
                    encoder.queueInputBuffer(index, 0, count * 4, pts, flags)
                    inputWorks++
                    submittedFrames+=count; inputEosQueued=last; progress = System.nanoTime()
                    drain()
                    return
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
                        log("ledger=${file.name} encoder=${encoder.name} actualOutput=${encoder.outputFormat}")
                        if (rawProbeFrames != null) log("ledger=${file.name} encoderOutputCsd0Hex=${probeCsdHex(encoder.outputFormat)}")
                        track = muxer!!.addTrack(encoder.outputFormat)
                        muxer!!.start(); muxStarted = true; progress = System.nanoTime()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    else -> if (index >= 0) {
                        try {
                            if (rawProbeFrames != null) check(outputEvents < 256) { "AAC diagnostic output event bound exceeded" }
                            if (logPackets && outputEvents<256) log("ledger=${file.name} output=$outputEvents packet=$packets pts=${info.presentationTimeUs} bytes=${info.size} flags=${info.flags} acceptedFrames=$frames queuedFrames=$submittedFrames inputEos=$inputEosQueued")
                            outputEvents++
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxStarted)
                                if (rawProbeFrames != null) check(packets < 256 && info.size <= 65_536 && encodedBytes + info.size <= 2_097_152L) {
                                    "AAC diagnostic encoded data bound exceeded"
                                }
                                // Preserve every delayed tail packet. The measured priming offset is
                                // expressed as negative PTS; exact padding is an edit-list boundary.
                                timestampAudit.record(packets,info.presentationTimeUs)
                                if (timestampAudit.failure != null) {
                                    log("ledger=${file.name} diagnosticOnly=true timestampViolations=${timestampAudit.violations} retainedFailure=${timestampAudit.failure!!.message}")
                                }
                                run {
                                    val packet = requireNotNull(encoder.getOutputBuffer(index))
                                    peakPacketBytes = maxOf(peakPacketBytes, info.size)
                                    packet.position(info.offset); packet.limit(info.offset + info.size)
                                    val sample = MediaCodec.BufferInfo().apply { set(info.offset, info.size, info.presentationTimeUs - timing.delayFrames * 1_000_000L / 48000,
                                        info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()) }
                                    muxer!!.writeSampleData(track, packet, sample)
                                    encodedBytes += info.size
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
            if (rawProbeFrames != null) check(frames == rawProbeFrames.toLong())
            input.finish()
            check(inputEosQueued && submittedFrames==input.queuedFrames)
            check(submittedFrames>=frames && submittedFrames-frames<1024)
            while (!eos) {
                checkProgress()
                drain()
            }
            check(muxStarted)
            if (rawProbeFrames != null) log("ledger=${file.name} diagnosticEncoderEos=true acceptedFrames=$frames queuedFrames=$submittedFrames packets=$packets outputEvents=$outputEvents timestampViolations=${timestampAudit.violations} rawPtsPreserved=true productionAccepted=false")
            // AAC-LC access units have a fixed 1024-frame duration. Let MediaMuxer retain the
            // final coded AU duration, then crop only the presentation edit. A shortened EOS
            // also shortens mdhd/stts, hiding the padding from the extractor's gapless contract.
            muxer!!.stop(); muxStarted = false; finished = true
            if (diagnostics) logProbeContainer(file,"coded-before-edit",checkCancelled,::log,rawProbeFrames != null)
            if (timing.delayFrames > 0) AacMp4Trim.apply(file, frames, timing.delayFrames)
        }

        override fun close() {
            try {
                log("ledger=${file.name} close inputWorks=$inputWorks acceptedFrames=$frames queuedFrames=$submittedFrames retainedFrames=${input.retainedFrames} pendingCapacityBytes=4096 packets=$packets outputEvents=$outputEvents inputEos=$inputEosQueued eos=$eos finished=$finished ledgerTruncated=${inputWorks>256 || outputEvents>256}")
            } finally {
                input.close()
                try { try { encoder.stop() } finally { encoder.release() } }
                finally { try { if (muxStarted) runCatching { muxer?.stop() } } finally { muxer?.release() } }
            }
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
