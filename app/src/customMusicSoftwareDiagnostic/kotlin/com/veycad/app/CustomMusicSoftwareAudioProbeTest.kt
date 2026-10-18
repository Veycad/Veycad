package com.veycad.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

internal object SoftwareAacNative {
    init { System.loadLibrary("software_aac_probe") }
    external fun create(csd: ByteArray, rate: Int, channels: Int, rawPath: String): Long
    external fun push(handle: Long, packet: ByteArray, ptsUs: Long)
    external fun drain(handle: Long)
    external fun finish(handle: Long): String
    external fun destroy(handle: Long)
}

@RunWith(AndroidJUnit4::class)
class CustomMusicSoftwareAudioProbeTest {
    @Test fun native_mp4() = decode("native_mp4")
    @Test fun overshoot() = decode("overshoot")
    @Test fun integer_cast() {
        val directory = directory()
        val report = JSONObject().put("case", "integer_cast").put("diagnostic_only", true)
            .put("source_case", "overshoot").put("decode_complete", false)
            .put("unclamped_float_witness_complete", false).put("audio_quality_pass", false)
        try {
            check(Build.VERSION.SDK_INT in listOf(26, 36) && Build.SUPPORTED_ABIS.first() == "x86_64")
            val manifestFile = File(directory, "material-input.json")
            check(manifestFile.length() in 1..1048576L)
            val manifest = JSONObject(manifestFile.readText())
            val expected = manifest.getJSONObject("cases").getJSONObject("overshoot")
            val media = File(directory, expected.getString("file"))
            check(media.canonicalFile.parentFile == directory.canonicalFile && media.length() in 1..67108864L)
            val before = hash(media.readBytes())
            check(before == expected.getString("sha256"))
            report.put("material_manifest_sha256", hash(manifestFile.readBytes())).put("input_sha256", before)
                .put("provenance", manifest.getJSONObject("provenance")).put("api", Build.VERSION.SDK_INT)
                .put("requested_preserve_float_headroom", false)
            val args = InstrumentationRegistry.getArguments()
            report.put("checkout_sha", requireNotNull(args.getString("diagnostic_checkout_sha")))
                .put("workflow_run_id", requireNotNull(args.getString("diagnostic_run_id")))
            val expectedDuration = expected.getLong("expected_duration_us")
            check(expectedDuration in 1..60000000L)
            val decoded = MediaCodecAudioDecoder.decode(media, maxDecodeUs = expectedDuration + 1000000L,
                preserveFloatHeadroom = false, startUs = 0L)
            report.put("effective_pcm_encoding", decoded.pcmEncoding)
                .put("encoding_absence_policy", "unchanged-production-default-pcm16")
                .put("sample_rate", decoded.sampleRate).put("channels", decoded.channelCount)
                .put("sample_count", decoded.interleavedSamples.size)
            check(decoded.interleavedSamples.size <= 4194304)
            val converted = ByteBuffer.allocate(decoded.interleavedSamples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            decoded.interleavedSamples.forEach { converted.putInt(java.lang.Float.floatToRawIntBits(it)) }
            val bytes = converted.array()
            File(directory, "integer_cast-converted.f32le").writeBytes(bytes)
            report.put("converted_f32le_sha256", hash(bytes)).put("converted_f32le_bytes", bytes.size)
                .put("input_unchanged", hash(media.readBytes()) == before)
                .put("material_manifest_unchanged", hash(manifestFile.readBytes()) == report.getString("material_manifest_sha256"))
            check(report.getBoolean("input_unchanged"))
            check(report.getBoolean("material_manifest_unchanged"))
            check(decoded.pcmEncoding == android.media.AudioFormat.ENCODING_PCM_16BIT) { "negative control inconclusive: decoder did not deliver PCM16" }
            val tolerance = 33334L + 1024000000L / decoded.sampleRate
            val qa = DecodedAudioQuality.evaluate(decoded.interleavedSamples, decoded.sampleRate, decoded.channelCount,
                expectedDuration, tolerance, false)
            report.put("decode_complete", true).put("unclamped_float_witness_complete", false)
                .put("audio_quality_pass", qa.accepted).put("qa", qaJson(qa))
                .put("qa_expected_duration_us", expectedDuration).put("qa_duration_tolerance_us", tolerance)
            check(!qa.accepted && "audio-unclamped-headroom-unavailable" in qa.issues)
        } catch (error: Throwable) {
            report.put("error", JSONObject().put("type", error.javaClass.name).put("message", error.message))
            throw error
        } finally { File(directory, "integer_cast-report.json").writeText(report.toString(2)) }
    }

    private fun directory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".uitest"))
        return File(context.filesDir, "custom-music-software-audio").apply { mkdirs() }
    }

    private fun decode(case: String) {
        val directory = directory()
        val report = JSONObject().put("case", case).put("diagnostic_only", true)
            .put("release_acceptance", false).put("decode_complete", false)
            .put("unclamped_float_witness_complete", false).put("audio_quality_pass", false)
        val packets = JSONArray()
        report.put("packets", packets)
        val extractor = MediaExtractor()
        var handle = 0L
        var failure: Throwable? = null
        var rollingDigest: MessageDigest? = null
        val raw = File(directory, "$case-raw.f32le")
        try {
            check(Build.VERSION.SDK_INT in listOf(26, 36) && Build.SUPPORTED_ABIS.first() == "x86_64")
            check(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
            val manifestFile = File(directory, "material-input.json")
            check(manifestFile.length() in 1..1048576L)
            val manifest = JSONObject(manifestFile.readText())
            val expected = manifest.getJSONObject("cases").getJSONObject(case)
            report.put("material_manifest_sha256", hash(manifestFile.readBytes()))
                .put("provenance", manifest.getJSONObject("provenance"))
                .put("api", Build.VERSION.SDK_INT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("fingerprint", Build.FINGERPRINT)
            val args = InstrumentationRegistry.getArguments()
            report.put("checkout_sha", requireNotNull(args.getString("diagnostic_checkout_sha")))
                .put("workflow_run_id", requireNotNull(args.getString("diagnostic_run_id")))
            val testContext = InstrumentationRegistry.getInstrumentation().context
            // The test class loader resolves this test APK entry, including direct mmap loading.
            val apk = File(testContext.applicationInfo.sourceDir)
            val libraryBytes = ZipFile(apk).use { zip ->
                val entry = requireNotNull(zip.getEntry("lib/x86_64/libsoftware_aac_probe.so"))
                check(entry.size in 1..33554432L)
                zip.getInputStream(entry).use { it.readBytes() }.also { check(it.size.toLong() == entry.size) }
            }
            report.put("loaded_binary_sha256", hash(libraryBytes)).put("binary_identity_source", "test-apk-lib/x86_64/libsoftware_aac_probe.so")
                .put("binary_bytes", libraryBytes.size).put("test_apk_path", apk.absolutePath)
            check(report.getString("loaded_binary_sha256") == manifest.getString("jni_sha256"))
            val media = File(directory, expected.getString("file"))
            check(media.canonicalFile.parentFile == directory.canonicalFile && media.length() in 1..67108864L)
            check(hash(media.readBytes()) == expected.getString("sha256"))
            if (case == "native_mp4") {
                check(expected.getString("sha256") == "09bb26563846c418b5452d035beb2a83fb528e4bca68e5d46c37eed6d259f187")
                val original = File(directory, "audio-input.json")
                check(original.length() in 1..524288L)
                report.put("original_audio_input_sha256", hash(original.readBytes()))
                    .put("original_audio_input", JSONObject(original.readText()))
            }
            extractor.setDataSource(media.absolutePath)
            val track = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            report.put("extractor_format", format.toString())
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            check(rate == expected.getInt("rate") && channels == expected.getInt("channels"))
            val csdBuffer = requireNotNull(format.getByteBuffer("csd-0")).duplicate()
            check(csdBuffer.remaining() in 1..65536)
            val csd = ByteArray(csdBuffer.remaining()).also { csdBuffer.get(it) }
            report.put("csd_sha256", hash(csd))
            check(hash(csd) == expected.getString("csd_sha256"))
            handle = SoftwareAacNative.create(csd, rate, channels, raw.absolutePath)
            val rolling = MessageDigest.getInstance("SHA-256")
            rollingDigest = rolling
            val buffer = ByteBuffer.allocate(1048576)
            var index = 0
            val started = System.nanoTime()
            while (true) {
                check(!Thread.currentThread().isInterrupted && System.nanoTime() - started < 120000000000L)
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) { report.put("extractor_eof", true); break }
                check(size in 1..buffer.capacity() && index < 65536)
                val pts = extractor.sampleTime
                val packet = ByteArray(size)
                buffer.position(0); buffer.get(packet)
                val observed = JSONObject().put("index", index).put("pts_us", pts).put("size", size).put("sha256", hash(packet))
                packets.put(observed)
                report.put("observed_au_count", index + 1)
                check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0)
                rolling.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array())
                rolling.update(packet)
                val pinnedPacket = expected.getJSONArray("packets").optJSONObject(index)
                observed.put("expected", pinnedPacket ?: JSONObject.NULL)
                requireNotNull(pinnedPacket) { "extra observed AU at index $index" }
                check(pinnedPacket.getInt("index") == index && pinnedPacket.getLong("pts_us") == pts &&
                    pinnedPacket.getInt("size") == size && pinnedPacket.getString("sha256") == hash(packet)) { "pinned AU mismatch at index $index" }
                report.put("matched_au_count", index + 1)
                SoftwareAacNative.push(handle, packet, pts)
                index++
                if (!extractor.advance()) { check(extractor.readSampleData(buffer, 0) < 0); report.put("extractor_eof", true); break }
            }
            val auHash = hex(rolling.digest())
            rollingDigest = null
            report.put("au_count", index).put("au_rolling_sha256", auHash)
            check(index == expected.getInt("au_count") && auHash == expected.getString("au_rolling_sha256"))
            check(index == expected.getJSONArray("packets").length())
            SoftwareAacNative.drain(handle)
            val native = JSONObject(SoftwareAacNative.finish(handle))
            report.put("native", native)
            check(native.getBoolean("drain_eof") && native.getLong("packets") == index.toLong())
            check(raw.length() in 4..16777216L && raw.length() == native.getLong("raw_bytes"))
            val rawBytes = raw.readBytes()
            check(rawBytes.size % (channels * 4) == 0)
            report.put("raw_sha256", hash(rawBytes)).put("raw_sample_count", rawBytes.size / 4)
                .put("reference_raw_sha256", expected.getString("reference_raw_sha256"))
                .put("reference_raw_sample_count", expected.getInt("reference_raw_sample_count"))
                .put("raw_reference_hash_equal", hash(rawBytes) == expected.getString("reference_raw_sha256"))
                .put("raw_reference_sample_count_delta", rawBytes.size / 4 - expected.getInt("reference_raw_sample_count"))
            val start = expected.getInt("crop_start_samples")
            val end = expected.getInt("crop_end_samples")
            val rawFrames = rawBytes.size / (channels * 4)
            // A fixed tail crop is meaningful only in its independently reviewed raw geometry.
            // Preserve differing bytes/counts as incomplete evidence; never adjust them to fit.
            if (rawBytes.size / 4 != expected.getInt("reference_raw_sample_count")) {
                report.put("crop_ambiguous", true).put("crop_ambiguity_reason", "actual-raw-frame-geometry-differs-from-pinned-crop")
                error("pinned crop cannot be applied to differing actual raw frame geometry")
            }
            check(start >= 0 && end >= 0 && start.toLong() + end < rawFrames)
            check(expected.getString("crop_owner") == "pinned_material_manifest")
            check(!expected.getBoolean("crop_ambiguous") && expected.getJSONArray("crop_reasons").length() > 0)
            if (expected.getInt("reference_presentation_sample_count") != (rawFrames - start - end) * channels) {
                report.put("crop_ambiguous", true).put("crop_ambiguity_reason", "pinned-presentation-endpoint-inconsistent-with-crop")
                error("pinned presentation endpoint metadata does not agree with crop geometry")
            }
            report.put("presentation_crop", JSONObject().put("owner", expected.getString("crop_owner"))
                .put("start_frames", start).put("end_frames", end).put("reasons", expected.getJSONArray("crop_reasons"))
                .put("skip_side_data_applied", false).put("forced_duration_trim", false))
                .put("crop_ambiguous", false)
            val presentation = rawBytes.copyOfRange(start * channels * 4, (rawFrames - end) * channels * 4)
            File(directory, "$case-presentation.f32le").writeBytes(presentation)
            val pcm = ByteBuffer.wrap(presentation).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val samples = FloatArray(pcm.remaining()).also { pcm.get(it) }
            report.put("presentation_sha256", hash(presentation)).put("presentation_sample_count", samples.size)
            check(samples.size == (rawFrames - start - end) * channels)
            report.put("reference_presentation_sha256", expected.getString("reference_presentation_sha256"))
                .put("reference_presentation_sample_count", expected.getInt("reference_presentation_sample_count"))
                .put("presentation_reference_hash_equal", hash(presentation) == expected.getString("reference_presentation_sha256"))
                .put("presentation_reference_sample_count_delta", samples.size - expected.getInt("reference_presentation_sample_count"))
            val durationTolerance = 33334L + 1024000000L / rate
            val expectedDuration = if (case == "native_mp4") 16000000L else expected.getLong("expected_duration_us")
            report.put("qa_expected_duration_us", expectedDuration).put("qa_duration_tolerance_us", durationTolerance)
            val qa = DecodedAudioQuality.evaluate(samples, rate, channels, expectedDuration,
                durationTolerance, true)
            report.put("decode_complete", true).put("unclamped_float_witness_complete", true)
                .put("audio_quality_pass", qa.accepted).put("qa", qaJson(qa))
            if (case == "overshoot") check(qa.overFullScaleSamples > 0 && !qa.accepted)
        } catch (error: Throwable) {
            failure = error
            report.put("error", JSONObject().put("type", error.javaClass.name).put("message", error.message))
        } finally {
            rollingDigest?.let { report.put("partial_observed_au_rolling_sha256", hex(it.digest())) }
            if (handle != 0L) {
                try { report.put("partial_native", JSONObject(SoftwareAacNative.finish(handle))) } catch (_: Throwable) { }
                SoftwareAacNative.destroy(handle)
            }
            if (raw.isFile && raw.length() <= 16777216L) {
                report.put("retained_raw_bytes", raw.length()).put("retained_raw_sha256", hash(raw.readBytes()))
            }
            extractor.release()
            File(directory, "$case-report.json").writeText(report.toString(2))
        }
        failure?.let { throw it }
        check(report.getBoolean("decode_complete") && report.getBoolean("unclamped_float_witness_complete"))
    }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun qaJson(q: DecodedAudioQuality.Report) = JSONObject().put("sampleRate", q.sampleRate)
        .put("channels", q.channels).put("sampleCount", q.sampleCount).put("durationUs", q.durationUs)
        .put("peak", q.peak.toDouble()).put("rms", q.rms).put("nonFiniteSamples", q.nonFiniteSamples)
        .put("overFullScaleSamples", q.overFullScaleSamples).put("longestFullScaleRun", q.longestFullScaleRun)
        .put("unclampedFloatEvidence", q.unclampedFloatEvidence).put("issues", JSONArray(q.issues)).put("accepted", q.accepted)
}
