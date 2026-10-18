package com.veycad.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Decode-only native evidence. A collected integer stream never becomes a float witness. */
@RunWith(AndroidJUnit4::class)
class CustomMusicAudioFloatProbeTest {
    @Test fun same_saved_native_mp4_audio_float_probe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName.endsWith(".uitest"))
        val args = InstrumentationRegistry.getArguments()
        val directory = File(context.filesDir, "custom-music-audio-probe").apply { mkdirs() }
        val inputFile = File(directory, "audio-input.json")
        val media = File(directory, "native-editor.mp4")
        val destination = File(directory, "audio-probe-report.json")
        destination.delete()
        val report = JSONObject().put("format", "custom-music-native-audio-probe-report-v1")
            .put("method", "unchanged-production-decode-and-native-mime-buffer-trace-v1")
            .put("diagnostic_only", true).put("execution_environment", "remote-ci-emulator").put("release_acceptance", false)
            .put("complete", false).put("float_witness_complete", false)
            .put("fresh_audio_qa_is_not_full_render_acceptance", true)
        var stage = "input"
        try {
            require(inputFile.isFile && inputFile.length() in 1..524_288L)
            val input = JSONObject(inputFile.readText(Charsets.UTF_8))
            validateInput(input)
            val initialJson = identity(inputFile)
            val initialMedia = identity(media)
            require(initialMedia.getLong("bytes") == OUTPUT_BYTES && initialMedia.getString("sha256") == OUTPUT_SHA)
            val buildHead = requireNotNull(args.getString("diagnostic_checkout_sha"))
            require(buildHead.matches(Regex("[0-9a-f]{40}")))
            val modelHashes = JSONObject(requireNotNull(args.getString("audioProbeModelHashesJson")))
            require(modelHashes.keys().asSequence().toSet() == MODEL_PATHS.toSet())
            MODEL_PATHS.forEach { require(modelHashes.getString(it).matches(Regex("[0-9a-f]{40}"))) }
            val run = requireNotNull(args.getString("diagnostic_run_id")).toLong().also { require(it > 0) }
            val attempt = requireNotNull(args.getString("diagnostic_attempt")).toInt().also { require(it > 0) }
            report.put("diagnostic_provenance", JSONObject().put("workflow_run_id", run)
                .put("run_attempt", attempt).put("checkout_sha", buildHead).put("model_git_blobs", modelHashes))
                .put("device", JSONObject().put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
                    .put("model", Build.MODEL).put("native_byte_order", ByteOrder.nativeOrder().toString()))
                .put("input_identity", initialJson).put("output_identity", initialMedia)
                .put("source_provenance", input.getJSONObject("source_provenance"))
                .put("native_snapshot", input.getJSONObject("native_snapshot"))
                .put("invocation", JSONObject().put("max_decode_us", MAX_DECODE_US).put("start_us", 0L)
                    .put("preserve_float_headroom", true).put("expected_duration_us", EXPECTED_US)
                    .put("duration_tolerance_formula", "33334+1024000000/decodedSampleRate"))
            // The production converter uses little-endian PCM; preserve and report that assumption.
            require(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
            stage = "production-decode"
            val production = MediaCodecAudioDecoder.decode(media, maxDecodeUs = MAX_DECODE_US,
                preserveFloatHeadroom = true, startUs = 0L)
            val productionHash = AudioProbePcmChecks.sampleHash(production.interleavedSamples)
            val productionQa = quality(production.interleavedSamples, production.sampleRate, production.channelCount,
                production.pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT)
            report.put("production_decode", JSONObject().put("pcm_encoding", production.pcmEncoding)
                .put("sample_rate", production.sampleRate).put("channels", production.channelCount)
                .put("sample_count", production.interleavedSamples.size).put("frame_count", production.frameCount)
                .put("converted_samples_f32le_sha256", productionHash)
                .put("converted_hash_is_not_a_float_format_witness", true)
                .put("duration_within_tolerance", AudioProbePcmChecks.durationWithinTolerance(productionQa.getLong("durationUs"), production.sampleRate))
                .put("qa", productionQa))
            stage = "mime-trace"
            val trace = JSONObject().put("complete", false).put("decoder_eos", false)
                .put("float_witness_complete", false)
            report.put("mime_trace", trace)
            decodeMimeTrace(media, production, productionHash, trace, report)
            report.put("output_unchanged_after_decode", identity(media).getString("sha256") == OUTPUT_SHA)
                .put("input_unchanged_after_decode", identity(inputFile).getString("sha256") == initialJson.getString("sha256"))
            require(report.getBoolean("output_unchanged_after_decode") && report.getBoolean("input_unchanged_after_decode"))
            require(report.getJSONObject("production_decode").getBoolean("duration_within_tolerance"))
            report.put("float_witness_complete", trace.getBoolean("float_witness_complete"))
                .put("complete", true).put("stage", "complete")
        } catch (failure: Throwable) {
            report.put("stage", stage).put("error", JSONObject().put("type", failure.javaClass.name)
                .put("message", failure.message ?: "No failure message"))
            throw failure
        } finally {
            destination.writeText(report.toString(2) + "\n", Charsets.UTF_8)
        }
    }

    private fun decodeMimeTrace(media: File, production: MediaCodecAudioDecoder.DecodedAudio,
        productionHash: String, trace: JSONObject, report: JSONObject) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val inputPackets = JSONArray()
        val outputBuffers = JSONArray()
        val changes = JSONArray()
        val raw = ByteArrayOutputStream()
        val convertedDigest = MessageDigest.getInstance("SHA-256")
        val state = AudioProbePcmChecks.WitnessState()
        trace.put("input_packets", inputPackets).put("output_buffers", outputBuffers).put("format_events", changes)
        var inputEnded = false
        var outputEnded = false
        var convertedCount = 0L
        try {
            extractor.setDataSource(media.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it)
                .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            require(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC)
            trace.put("extractor_format", formatDescription(format))
            // Same compressed-audio request and file-boundary handling as unchanged production decode.
            if (format.containsKey(MediaFormat.KEY_DURATION) && MAX_DECODE_US < format.getLong(MediaFormat.KEY_DURATION) &&
                format.containsKey(MediaFormat.KEY_ENCODER_PADDING)) format.setInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            trace.put("requested_input_format", formatDescription(format))
            report.put("codec_inventory", inventory(format))
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = codec
            trace.put("selected_codec", JSONObject().put("name", codec.name)
                .put("canonical_name", if (Build.VERSION.SDK_INT >= 29) codec.canonicalName else codec.name)
                .put("creation", "createDecoderByType"))
            codec.configure(format, null, null, 0)
            trace.put("configured_output_format", formatDescription(codec.outputFormat))
            codec.start()
            val info = MediaCodec.BufferInfo()
            var lastProgressNs = System.nanoTime()
            while (!outputEnded) {
                check(System.nanoTime() - lastProgressNs < 20_000_000_000L) { "Native audio trace stalled" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000L)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
                        val pts = extractor.sampleTime
                        if (pts >= 0 && pts >= MAX_DECODE_US) {
                            codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val bytes = extractor.readSampleData(buffer, 0)
                            if (bytes < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                require(inputPackets.length() < 4096)
                                val packet = ByteArray(bytes)
                                buffer.duplicate().apply { position(0); limit(bytes) }.get(packet)
                                inputPackets.put(JSONObject().put("sequence", inputPackets.length()).put("pts_us", pts)
                                    .put("bytes", bytes).put("flags", extractor.sampleFlags).put("sha256", hash(packet)))
                                codec.queueInputBuffer(index, 0, bytes, pts, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                        trace.put("input_eos_queued", inputEnded)
                        lastProgressNs = System.nanoTime()
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        changes.put(JSONObject().put("sequence", changes.length()).put("format", formatDescription(codec.outputFormat)))
                        lastProgressNs = System.nanoTime()
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        try {
                            require(outputBuffers.length() < 4096)
                            val actualFormat = codec.getOutputFormat(index)
                            val row = JSONObject().put("sequence", outputBuffers.length()).put("pts_us", info.presentationTimeUs)
                                .put("offset", info.offset).put("bytes", info.size).put("flags", info.flags)
                                .put("format", formatDescription(actualFormat)).put("retained_frames", 0)
                                .put("retained_bytes", 0)
                            outputBuffers.put(row)
                            if (info.size > 0) {
                                val rate = actualFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                val channels = actualFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                val reportedEncoding = if (actualFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                    actualFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else null
                                val encoding = reportedEncoding ?: AudioFormat.ENCODING_PCM_16BIT
                                val count = AudioProbePcmChecks.frameCount(info.size, encoding, channels)
                                require(rate > 0 && info.offset >= 0)
                                val buffer = requireNotNull(codec.getOutputBuffer(index)).duplicate()
                                require(info.offset <= buffer.capacity() && info.size <= buffer.capacity() - info.offset)
                                buffer.clear(); buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val payload = ByteArray(info.size).also { buffer.get(it) }
                                val window = AudioPcmWindow.frames(info.presentationTimeUs, count, rate, 0L, MAX_DECODE_US)
                                val frameBytes = AudioProbePcmChecks.bytesPerSample(encoding) * channels
                                val retained = payload.copyOfRange(window.first * frameBytes, (window.last + 1) * frameBytes)
                                AudioProbePcmChecks.checkedTotal(raw.size().toLong(), retained.size)
                                val samples = PcmSampleConverter.decode(retained, encoding, clampFloatSamples = false)
                                AudioProbePcmChecks.updateSampleDigest(convertedDigest, samples)
                                convertedCount += samples.size
                                raw.write(retained)
                                state.observe(encoding, rate, channels, reportedEncoding, window.count())
                                row.put("pcm_encoding_key_present", reportedEncoding != null)
                                    .put("reported_pcm_encoding", reportedEncoding ?: JSONObject.NULL).put("effective_pcm_encoding", encoding)
                                    .put("sample_rate", rate).put("channels", channels).put("payload_frames", count)
                                    .put("trim_first_frame", window.first).put("retained_frames", window.count())
                                    .put("retained_bytes", retained.size).put("raw_payload_sha256", hash(payload))
                                    .put("raw_retained_sha256", hash(retained))
                                    .put("converted_samples_f32le_sha256", AudioProbePcmChecks.sampleHash(samples))
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            trace.put("decoder_eos", outputEnded)
                        } finally { codec.releaseOutputBuffer(index, false) }
                        lastProgressNs = System.nanoTime()
                    }
                }
                trace.put("retained_bytes", raw.size()).put("retained_payload_buffers", state.retainedBuffers)
                    .put("encoding_constant", state.encodingConstant).put("sample_rate_constant", state.rateConstant)
                    .put("channels_constant", state.channelsConstant)
                    .put("all_retained_buffers_explicit_pcm_float", state.allRetainedExplicitFloat)
            }
            val retainedBytes = raw.toByteArray()
            val rawHash = hash(retainedBytes)
            val convertedHash = hex(convertedDigest.digest())
            trace.put("raw_retained_sha256", rawHash).put("converted_samples_f32le_sha256", convertedHash)
                .put("sample_count", convertedCount)
            require(inputEnded && outputEnded && state.retainedBuffers > 0)
            require(state.encodingConstant && state.rateConstant && state.channelsConstant)
            val signature = requireNotNull(state.first)
            val samples = PcmSampleConverter.decode(retainedBytes, signature.encoding, clampFloatSamples = false)
            val rawFloatPreserved = signature.encoding == AudioFormat.ENCODING_PCM_FLOAT && rawHash == convertedHash
            val qa = quality(samples, signature.rate, signature.channels, state.hasFloatFormatEvidence(outputEnded) && rawFloatPreserved)
            val durationUs = qa.getLong("durationUs")
            val durationWithin = AudioProbePcmChecks.durationWithinTolerance(durationUs, signature.rate)
            val floatWitness = state.floatComplete(outputEnded, durationUs) && rawFloatPreserved
            trace
                .put("sample_count", samples.size).put("frame_count", samples.size.toLong() / signature.channels)
                .put("pcm_encoding", signature.encoding).put("sample_rate", signature.rate).put("channels", signature.channels)
                .put("unclamped_conversion", true).put("stream_conversion_consistent", convertedHash == AudioProbePcmChecks.sampleHash(samples))
                .put("raw_float_bits_preserved", rawFloatPreserved)
                .put("production_count_match", samples.size == production.interleavedSamples.size)
                .put("production_format_match", signature.encoding == production.pcmEncoding && signature.rate == production.sampleRate &&
                    signature.channels == production.channelCount)
                .put("production_converted_sha256_match", convertedHash == productionHash)
                .put("duration_within_tolerance", durationWithin).put("qa", qa)
            require(trace.getBoolean("stream_conversion_consistent") &&
                (signature.encoding != AudioFormat.ENCODING_PCM_FLOAT || rawFloatPreserved) && trace.getBoolean("production_count_match") &&
                trace.getBoolean("production_format_match") && trace.getBoolean("production_converted_sha256_match"))
            require(durationWithin) { "Native MIME trace does not cover the expected 16-second audio" }
            trace.put("float_witness_complete", floatWitness).put("complete", true)
        } finally {
            trace.put("input_eos_queued", inputEnded).put("decoder_eos", outputEnded)
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }; extractor.release()
        }
    }

    private fun inventory(format: MediaFormat): JSONArray = JSONArray().apply {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder &&
            it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) } }.forEach { info ->
            val item = JSONObject().put("name", info.name).put("declared_support_is_not_a_float_witness", true)
            if (Build.VERSION.SDK_INT >= 29) item.put("canonical_name", info.canonicalName).put("alias", info.isAlias)
                .put("software_only", info.isSoftwareOnly).put("hardware_accelerated", info.isHardwareAccelerated)
            try {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val audioCaps = requireNotNull(caps.audioCapabilities)
                item.put("requested_format_supported", caps.isFormatSupported(format)).put("default_format", formatDescription(caps.defaultFormat))
                    .put("max_input_channels", audioCaps.maxInputChannelCount)
                    .put("sample_rate_ranges", JSONArray(audioCaps.supportedSampleRateRanges.map { it.toString() }))
            } catch (failure: Exception) { item.put("capability_error", failure.javaClass.name + ":" + failure.message) }
            put(item)
        }
    }

    private fun formatDescription(format: MediaFormat): JSONObject = JSONObject().put("raw", format.toString()).apply {
        put("pcm_encoding_key_present", format.containsKey(MediaFormat.KEY_PCM_ENCODING))
        put("reported_pcm_encoding", if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else JSONObject.NULL)
        listOf(MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT, MediaFormat.KEY_ENCODER_DELAY,
            MediaFormat.KEY_ENCODER_PADDING, MediaFormat.KEY_AAC_PROFILE, MediaFormat.KEY_MAX_INPUT_SIZE).forEach { key ->
            put(key, if (format.containsKey(key)) format.getInteger(key) else JSONObject.NULL)
        }
        put("mime", if (format.containsKey(MediaFormat.KEY_MIME)) format.getString(MediaFormat.KEY_MIME) else JSONObject.NULL)
        put("duration_us", if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else JSONObject.NULL)
        val csd = JSONArray()
        repeat(4) { index ->
            val key = "csd-$index"
            if (format.containsKey(key)) {
                val buffer = requireNotNull(format.getByteBuffer(key)).duplicate()
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                csd.put(JSONObject().put("key", key).put("bytes", bytes.size).put("sha256", hash(bytes)))
            }
        }
        put("csd", csd)
    }

    private fun quality(samples: FloatArray, rate: Int, channels: Int, floatEvidence: Boolean): JSONObject {
        val tolerance = 33_334L + 1_024_000_000L / rate
        val qa = DecodedAudioQuality.evaluate(samples, rate, channels, EXPECTED_US, tolerance, floatEvidence)
        return JSONObject().put("sampleRate", qa.sampleRate).put("channels", qa.channels).put("sampleCount", qa.sampleCount)
            .put("durationUs", qa.durationUs).put("peak", qa.peak.toDouble()).put("rms", qa.rms)
            .put("nonFiniteSamples", qa.nonFiniteSamples).put("overFullScaleSamples", qa.overFullScaleSamples)
            .put("longestFullScaleRun", qa.longestFullScaleRun).put("unclampedFloatEvidence", qa.unclampedFloatEvidence)
            .put("issues", JSONArray(qa.issues)).put("accepted", qa.accepted).put("expectedDurationUs", EXPECTED_US)
            .put("durationToleranceUs", tolerance)
    }

    private fun validateInput(input: JSONObject) {
        require(input.getString("format") == "custom-music-native-audio-probe-input-v1")
        require(input.getLong("expected_duration_us") == EXPECTED_US)
        val source = input.getJSONObject("source_provenance")
        require(source.getString("repository") == "Veycad/Veycad")
        require(source.getLong("run_id") == 38_059_647_484L && source.getInt("run_attempt") == 1 &&
            source.getLong("artifact_id") == 11_672_718_841L)
        require(source.getString("head_sha") == "20d64c5a5aaea2e2f0b4966fc6f01f0ed09fe0e5")
        require(source.getString("checkout_sha") == "33b68e3f0643ce95acb46bdf8f3e075448388492")
        require(source.getString("base_sha") == "a0395e26b93463f91520181a2eeb09dfd05e78a3")
        require(source.getString("zip_sha256") == "196e22b1f23f68b69b40e474cd8c82b768f29ce828fcdf37d25f143431488e52")
        require(source.getString("evidence_sha256") == "02f556e7b7f188ca5f45b6f3400dde846bc4382e0e4f5e885de8b92e003c1054")
        require(source.getString("manifest_sha256") == "8281d080e2627e0e778f6e691fa372a79766779247cfdf4f73b7d16bcb9f5998")
        val output = input.getJSONObject("output_identity")
        require(output.getString("name") == "native-editor.mp4" && output.getLong("bytes") == OUTPUT_BYTES &&
            output.getString("sha256") == OUTPUT_SHA)
        val native = input.getJSONObject("native_snapshot")
        require(!native.getBoolean("quality_gate") && native.getInt("frames") == 480)
        val acceptance = native.getJSONObject("acceptance")
        require(!acceptance.getBoolean("accepted") && !acceptance.getJSONObject("decodedAudio").getBoolean("unclampedFloatEvidence"))
    }

    private fun identity(file: File): JSONObject {
        require(file.isFile)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return JSONObject().put("name", file.name).put("bytes", file.length()).put("sha256", hex(digest.digest()))
    }
    private fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    companion object {
        private const val EXPECTED_US = 16_000_000L
        private const val MAX_DECODE_US = 17_000_000L
        private const val OUTPUT_BYTES = 393_863L
        private const val OUTPUT_SHA = "09bb26563846c418b5452d035beb2a83fb528e4bca68e5d46c37eed6d259f187"
        private val MODEL_PATHS = listOf("MediaCodecAudioDecoder", "AudioPcmWindow", "DecodedAudioQuality")
            .map { "app/src/main/java/com/example/autoedit/$it.kt" }
    }
}

/** Pure guards and raw-bit digests checked without Android codecs or an emulator. */
internal object AudioProbePcmChecks {
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        2 -> 2 // AudioFormat.ENCODING_PCM_16BIT
        3 -> 1 // AudioFormat.ENCODING_PCM_8BIT
        4 -> 4 // AudioFormat.ENCODING_PCM_FLOAT
        else -> error("Unsupported native PCM encoding $encoding")
    }
    fun frameCount(bytes: Int, encoding: Int, channels: Int): Int {
        require(bytes >= 0 && channels in 1..8)
        val frameBytes = bytesPerSample(encoding) * channels
        require(bytes % frameBytes == 0) { "Partial native PCM frame" }
        return bytes / frameBytes
    }
    fun checkedTotal(previous: Long, additional: Int): Long {
        val limit = 64L * 1024 * 1024
        require(previous in 0..limit && additional >= 0 && additional.toLong() <= limit - previous)
        return previous + additional
    }
    fun durationWithinTolerance(durationUs: Long, rate: Int): Boolean {
        require(durationUs >= 0 && rate > 0)
        val tolerance = 33_334L + 1_024_000_000L / rate
        val delta = durationUs - 16_000_000L
        return delta >= -tolerance && delta <= tolerance
    }
    fun updateSampleDigest(digest: MessageDigest, samples: FloatArray) {
        val buffer = ByteArray(8192)
        var used = 0
        samples.forEach { sample ->
            val bits = java.lang.Float.floatToRawIntBits(sample)
            repeat(4) { byte -> buffer[used++] = (bits ushr (8 * byte)).toByte() }
            if (used == buffer.size) { digest.update(buffer); used = 0 }
        }
        if (used > 0) digest.update(buffer, 0, used)
    }
    fun sampleHash(samples: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateSampleDigest(digest, samples)
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    data class Signature(val encoding: Int, val rate: Int, val channels: Int)
    class WitnessState {
        var first: Signature? = null
            private set
        var encodingConstant = true
            private set
        var rateConstant = true
            private set
        var channelsConstant = true
            private set
        var retainedBuffers = 0
            private set
        var allRetainedExplicitFloat = true
            private set
        fun observe(encoding: Int, rate: Int, channels: Int, reported: Int?, retainedFrames: Int) {
            bytesPerSample(encoding)
            require(rate > 0 && channels in 1..8 && retainedFrames >= 0)
            require(encoding == (reported ?: 2))
            val previous = first
            if (previous == null) first = Signature(encoding, rate, channels)
            else {
                encodingConstant = encodingConstant && previous.encoding == encoding
                rateConstant = rateConstant && previous.rate == rate
                channelsConstant = channelsConstant && previous.channels == channels
            }
            if (retainedFrames > 0) {
                retainedBuffers++
                allRetainedExplicitFloat = allRetainedExplicitFloat && reported == 4 && encoding == 4
            }
        }
        fun hasFloatFormatEvidence(eos: Boolean): Boolean = eos && retainedBuffers > 0 && allRetainedExplicitFloat &&
            encodingConstant && rateConstant && channelsConstant
        fun floatComplete(eos: Boolean, durationUs: Long): Boolean = hasFloatFormatEvidence(eos) &&
            first?.let { durationWithinTolerance(durationUs, it.rate) } == true
    }
}
