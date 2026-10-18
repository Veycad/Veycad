package com.veycad.app

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in evidence collection for an existing MP4; never renders or changes production QA. */
@RunWith(AndroidJUnit4::class)
class CustomMusicTargetedDecodeTest {
    @Test fun requested_pts_match_only_the_exact_frame_or_an_explicit_one_us_rounding_offset() {
        assertTrue(TargetedDecodeMath.matchesRequestedPts(1_533_333L, 1_533_333L))
        assertTrue(TargetedDecodeMath.matchesRequestedPts(1_533_333L, 1_533_332L))
        assertTrue(TargetedDecodeMath.matchesRequestedPts(1_533_333L, 1_533_334L))
        assertFalse(TargetedDecodeMath.matchesRequestedPts(1_533_333L, 1_533_331L))
        assertFalse(TargetedDecodeMath.matchesRequestedPts(1_533_333L, 1_533_335L))
        assertFalse(TargetedDecodeMath.matchesRequestedPts(-1L, 0L))
        assertFalse(TargetedDecodeMath.matchesRequestedPts(0L, -1L))
        assertTrue(TargetedDecodeMath.matchesRequestedPts(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun diagnostic_difference_preserves_the_native_pixel_formula_and_rejects_invalid_grids() {
        assertEquals(.33f, TargetedDecodeMath.frameDifference(floatArrayOf(.1f, .2f), floatArrayOf(.2f, .4f)), .000001f)
        assertEquals(0f, TargetedDecodeMath.frameDifference(floatArrayOf(.1f), floatArrayOf(.1f)), 0f)
        assertEquals(1f, TargetedDecodeMath.frameDifference(floatArrayOf(0f), floatArrayOf(1f)), 0f)
        assertThrows(IllegalArgumentException::class.java) {
            TargetedDecodeMath.frameDifference(floatArrayOf(), floatArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetedDecodeMath.frameDifference(floatArrayOf(.1f), floatArrayOf(.1f, .2f))
        }
    }

    @Test fun plane_grid_reads_strided_bytes_from_nonzero_buffer_position() {
        val bytes = ByteArray(24) { 7 }
        bytes[5] = 16
        bytes[17] = 219.toByte()
        val buffer = ByteBuffer.wrap(bytes).apply { position(5); limit(18) }
        assertEquals(16, TargetedDecodeMath.unsignedPlaneSample(buffer, 8, 2, 0, 0))
        assertEquals(219, TargetedDecodeMath.unsignedPlaneSample(buffer, 8, 2, 2, 1))
        assertEquals(5, buffer.position())
    }

    @Test fun plane_grid_rejects_indices_outside_buffer_limit() {
        val buffer = ByteBuffer.wrap(ByteArray(24)).apply { position(5); limit(18) }
        assertThrows(IllegalArgumentException::class.java) {
            TargetedDecodeMath.unsignedPlaneSample(buffer, 8, 2, 3, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetedDecodeMath.unsignedPlaneSample(buffer, 8, 2, -1, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetedDecodeMath.unsignedPlaneSample(buffer, Int.MAX_VALUE, 2, 0, 2)
        }
    }

    @Test fun saved_mp4_provides_every_requested_blackout_frame_and_neighbour() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "custom-music-targeted")
            .apply { mkdirs() }
        val inputFile = File(directory, "targeted-input.json")
        val video = File(directory, "native-editor.mp4")
        val destination = File(directory, "targeted-report.json")
        destination.delete() // A failed rerun must not expose an old successful report.
        val arguments = InstrumentationRegistry.getArguments()
        val report = JSONObject()
            .put("format", "custom-music-targeted-report-v1")
            .put("method", "android-sequential-mediacodec-requested-pts-v1")
            .put("local_only", true)
            .put("release_acceptance", false)
            .put("native_quality_gate", JSONObject.NULL)
            .put("complete", false)
            .put("decoder_eos", false)
            .put("decoded_payload_frames", 0)
            .put("queued_input_payloads", 0)
            .put("missing_requested_us", JSONArray())
            .put("duplicate_requested_us", JSONArray())
            .put("duplicate_decoded_us", JSONArray())
            .put("non_increasing_decoded_us", JSONArray())
            .put("selected_frames", JSONArray())
            .put("target_pair_differences", JSONArray())
            .put("decoder_output_formats", JSONArray())
            .put("diagnostic_provenance", JSONObject().apply {
                listOf("diagnostic_run_id", "diagnostic_attempt", "diagnostic_checkout_sha")
                    .forEach { put(it, arguments.getString(it) ?: JSONObject.NULL) }
            })
            .put("device", JSONObject()
                .put("api", Build.VERSION.SDK_INT)
                .put("build_fingerprint", Build.FINGERPRINT)
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL))
            .put("grid_method", JSONObject()
                .put("width", GRID_WIDTH).put("height", GRID_HEIGHT)
                .put("luma", "clamp((unsigned_Y-16f)/219f,0f,1f)")
                .put("positions", "crop.left+x*crop.width/48, crop.top+y*crop.height/72")
                .put("difference", "clamp(2.2f*mean(abs(previous_Y-current_Y)),0f,1f)")
                .put("production_formula_copy", true))
            .put("limitations", JSONArray(listOf(
                "Only requested decoded images are retained; all payload frames are counted.",
                "Diagnostic adjacent-frame differences are not a replacement transitionPeak.",
                "The original negative native quality gate and acceptance remain unchanged.",
                "This synthetic saved-MP4 diagnostic is not artistic or release acceptance."
            )))
        var failure: Throwable? = null
        try {
            report.put("input_identity", identity(inputFile))
            val input = JSONObject(inputFile.readText(Charsets.UTF_8))
            report.put("source_provenance", input.getJSONObject("source_provenance"))
                .put("native_snapshot", input.getJSONObject("native_snapshot"))
                .put("native_quality_gate", input.getJSONObject("native_snapshot").getBoolean("quality_gate"))
                .put("native_visual_samples", input.getJSONArray("native_visual_samples"))
                .put("graph_sha256_declared_by_input", input.getString("graph_sha256"))
                .put("requests", input.getJSONArray("requests"))
                .put("targets", input.getJSONArray("targets"))
            report.put("missing_requested_us", JSONArray((0 until input.getJSONArray("requests").length()).map {
                input.getJSONArray("requests").getJSONObject(it).getLong("output_us")
            })).put("output_identity", identity(video))
            validate(input, report.getJSONObject("output_identity"))
            listOf("diagnostic_run_id", "diagnostic_attempt", "diagnostic_checkout_sha")
                .forEach { require(!arguments.getString(it).isNullOrBlank()) { "Missing instrumentation argument $it" } }
            decode(video, input, report)
            require(report.getBoolean("decoder_eos")) { "Decoder did not reach EOS" }
            require(report.getInt("decoded_payload_frames") == input.getJSONObject("native_snapshot").getInt("frames")) {
                "Decoded frame count differs from the saved native snapshot"
            }
            require(report.getJSONArray("missing_requested_us").length() == 0) { "Requested PTS were not decoded" }
            require(report.getJSONArray("duplicate_requested_us").length() == 0) { "Requested PTS have duplicate images" }
            require(report.getJSONArray("duplicate_decoded_us").length() == 0) { "Decoder emitted duplicate PTS" }
            require(report.getJSONArray("non_increasing_decoded_us").length() == 0) { "Decoder emitted non-increasing PTS" }
            require(report.getJSONArray("selected_frames").length() == 16) { "Incomplete selected-frame coverage" }
            require(report.getJSONArray("target_pair_differences").length() == 8) { "Incomplete target-neighbour coverage" }
            report.put("complete", true)
        } catch (error: Throwable) {
            failure = error
            report.put("error", JSONObject().put("type", error.javaClass.name)
                .put("message", error.message ?: JSONObject.NULL))
        } finally {
            // The complete/partial diagnostic is persisted before the final JUnit assertion.
            destination.writeText(report.toString(2) + "\n", Charsets.UTF_8)
        }
        assertNull("Targeted decode failed; inspect ${destination.absolutePath}: ${failure?.message}", failure)
    }

    private fun validate(input: JSONObject, outputIdentity: JSONObject) {
        require(input.getString("format") == "custom-music-targeted-input-v1") { "Unsupported input format" }
        val native = input.getJSONObject("native_snapshot")
        require(!native.getBoolean("quality_gate") && !native.getJSONObject("acceptance").getBoolean("accepted")) {
            "The input must preserve the original failed native quality gate"
        }
        require(native.getInt("frames") == 480) { "Expected the saved 480-frame MP4" }
        require(input.getJSONArray("native_visual_samples").length() == 160) { "Incomplete original VisualSamples" }
        val expectedOutput = input.getJSONObject("output")
        require(expectedOutput.getString("name") == "native-editor.mp4") { "Unexpected source MP4 name" }
        require(outputIdentity.getLong("bytes") == expectedOutput.getLong("bytes") &&
            outputIdentity.getString("sha256") == expectedOutput.getString("sha256")) { "Saved MP4 identity mismatch" }
        val targets = input.getJSONArray("targets")
        val requests = input.getJSONArray("requests")
        require(targets.length() == 8 && requests.length() == 16) { "Expected eight targets and 16 unique requested images" }
        val expectedRoles = linkedMapOf<Long, MutableSet<Pair<Long, String>>>()
        val targetTimes = linkedSetOf<Long>()
        for (index in 0 until targets.length()) {
            val target = targets.getJSONObject(index)
            val time = target.getLong("output_us")
            val previous = target.getLong("previous_us")
            val next = target.getLong("next_us")
            require(previous >= 0 && previous < time && time < next && target.getDouble("blackout") > 0) {
                "Invalid target or neighbour order at $time"
            }
            require(targetTimes.add(time)) { "Duplicate target $time" }
            listOf(previous to "previous", time to "target", next to "next").forEach { (requested, role) ->
                expectedRoles.getOrPut(requested) { linkedSetOf() }.add(time to role)
            }
        }
        require(targetTimes == setOf(1_533_333L, 1_566_667L, 5_533_333L, 5_566_667L,
            9_533_333L, 9_566_667L, 13_533_333L, 13_566_667L)) { "Unexpected saved BLACKOUT targets" }
        val requestTimes = arrayListOf<Long>()
        for (index in 0 until requests.length()) {
            val request = requests.getJSONObject(index)
            val time = request.getLong("output_us")
            require(time >= 0 && (requestTimes.isEmpty() || time - requestTimes.last() > 2L)) {
                "Requests must be sorted, unique and unambiguous within the one-us allowance"
            }
            requestTimes += time
            val execution = request.getJSONObject("execution")
            require(execution.getInt("clip") >= 0 && execution.getLong("source_us") >= 0 &&
                execution.getLong("decoded_source_us") >= 0 && execution.getDouble("blackout") >= 0) {
                "Incomplete saved execution evidence at $time"
            }
            val roles = request.getJSONArray("roles")
            val actualRoles = (0 until roles.length()).map { roleIndex ->
                val role = roles.getJSONObject(roleIndex)
                role.getLong("target_us") to role.getString("role")
            }
            require(actualRoles.size == actualRoles.toSet().size && actualRoles.toSet() == expectedRoles[time]) {
                "Request roles do not cover exactly the saved target and adjacent execution PTS at $time"
            }
        }
        require(requestTimes.toSet() == expectedRoles.keys) { "Missing target or neighbour requests" }
    }

    private data class Captured(val actualUs: Long, val grid: FloatArray)

    private fun decode(video: File, input: JSONObject, report: JSONObject) {
        val requests = input.getJSONArray("requests")
        val requested = (0 until requests.length()).associate { index ->
            requests.getJSONObject(index).getLong("output_us") to requests.getJSONObject(index)
        }
        val captured = linkedMapOf<Long, Captured>()
        val selected = JSONArray()
        val duplicates = JSONArray()
        val duplicateDecoded = JSONArray()
        val nonIncreasing = JSONArray()
        val outputFormats = JSONArray()
        report.put("selected_frames", selected).put("duplicate_requested_us", duplicates)
            .put("duplicate_decoded_us", duplicateDecoded).put("non_increasing_decoded_us", nonIncreasing)
            .put("decoder_output_formats", outputFormats).put("decoder_eos", false)
            .put("decoded_payload_frames", 0).put("queued_input_payloads", 0)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var payloads = 0
        var inputPayloads = 0
        var inputEnded = false
        var outputEnded = false
        var lastPts: Long? = null
        val seenPts = hashSetOf<Long>()
        try {
            extractor.setDataSource(video.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("Saved MP4 has no video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            report.put("extractor_video_format", formatDescription(format))
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            report.put("decoder", JSONObject().put("name", codec.name).put("mime", mime)
                .put("requested_color_format", MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible))
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 60_000L
            var lastProgress = SystemClock.elapsedRealtime()
            while (!outputEnded) {
                require(SystemClock.elapsedRealtime() <= deadline) { "Targeted sequential decode exceeded 60 seconds" }
                require(SystemClock.elapsedRealtime() - lastProgress < 10_000L) { "Targeted decoder stalled" }
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(inputIndex)).apply { clear() }
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                            inputPayloads++
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormats.put(formatDescription(codec.outputFormat))
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                    else -> if (outputIndex >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val actualUs = info.presentationTimeUs // Never round, seek or substitute decoder PTS.
                                payloads++
                                if (!seenPts.add(actualUs)) duplicateDecoded.put(actualUs)
                                lastPts?.let { if (actualUs <= it) nonIncreasing.put(actualUs) }
                                lastPts = actualUs
                                if (!report.has("first_decoded_us")) report.put("first_decoded_us", actualUs)
                                report.put("last_decoded_us", actualUs)
                                val matches = requested.keys.filter { TargetedDecodeMath.matchesRequestedPts(it, actualUs) }
                                require(matches.size <= 1) { "Actual decoder PTS matches multiple requests: $actualUs" }
                                matches.singleOrNull()?.let { requestedUs ->
                                    if (captured.containsKey(requestedUs)) {
                                        duplicates.put(requestedUs)
                                    } else {
                                        val image = codec.getOutputImage(outputIndex)
                                            ?: error("Decoder exposed no image for requested PTS $requestedUs / actual $actualUs")
                                        image.use {
                                            val frame = JSONObject().put("requested_us", requestedUs)
                                                .put("actual_us", actualUs).put("rounding_offset_us", actualUs - requestedUs)
                                                .put("request", requested.getValue(requestedUs))
                                            val grid = grid(image, frame)
                                            captured[requestedUs] = Captured(actualUs, grid)
                                            selected.put(frame)
                                        }
                                    }
                                }
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                        lastProgress = SystemClock.elapsedRealtime()
                    }
                }
                report.put("decoded_payload_frames", payloads).put("queued_input_payloads", inputPayloads)
            }
            report.put("decoder_eos", true)
        } finally {
            report.put("decoded_payload_frames", payloads).put("queued_input_payloads", inputPayloads)
                .put("missing_requested_us", JSONArray(requested.keys.filterNot(captured::containsKey)))
            val pairs = JSONArray()
            val targets = input.getJSONArray("targets")
            for (index in 0 until targets.length()) {
                val target = targets.getJSONObject(index)
                val time = target.getLong("output_us")
                val previous = target.getLong("previous_us")
                val next = target.getLong("next_us")
                if (listOf(previous, time, next).all(captured::containsKey)) {
                    pairs.put(JSONObject().put("target_us", time).put("saved_execution_target", target)
                        .put("previous_to_target", pair(previous, time, captured))
                        .put("target_to_next", pair(time, next, captured)))
                }
            }
            report.put("target_pair_differences", pairs)
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    private fun grid(image: Image, frame: JSONObject): FloatArray {
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) { "Expected three-plane YUV_420_888" }
        val crop = image.cropRect
        require(crop.width() > 0 && crop.height() > 0) { "Empty decoder crop" }
        frame.put("image_format", image.format).put("image_width", image.width).put("image_height", image.height)
            .put("crop", JSONObject().put("left", crop.left).put("top", crop.top)
                .put("right", crop.right).put("bottom", crop.bottom))
            .put("plane_strides", JSONArray(image.planes.map { plane ->
                JSONObject().put("row_stride", plane.rowStride).put("pixel_stride", plane.pixelStride)
                    .put("buffer_position", plane.buffer.position())
                    .put("buffer_limit", plane.buffer.limit())
            }))
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val values = FloatArray(GRID_WIDTH * GRID_HEIGHT)
        var sum = 0f
        var index = 0
        repeat(GRID_HEIGHT) { sampleY ->
            val y = crop.top + sampleY * crop.height() / GRID_HEIGHT
            repeat(GRID_WIDTH) { sampleX ->
                val x = crop.left + sampleX * crop.width() / GRID_WIDTH
                val unsigned = TargetedDecodeMath.unsignedPlaneSample(buffer, plane.rowStride, plane.pixelStride, x, y)
                val luma = ((unsigned - 16f) / 219f).coerceIn(0f, 1f)
                values[index++] = luma
                sum += luma
            }
        }
        frame.put("mean_luma", sum / values.size).put("limited_y_grid", JSONArray(values.toList()))
        return values
    }

    private fun pair(leftUs: Long, rightUs: Long, captured: Map<Long, Captured>): JSONObject {
        val left = captured.getValue(leftUs)
        val right = captured.getValue(rightUs)
        return JSONObject().put("left_requested_us", leftUs).put("right_requested_us", rightUs)
            .put("left_actual_us", left.actualUs).put("right_actual_us", right.actualUs)
            .put("diagnostic_adjacent_frame_difference", TargetedDecodeMath.frameDifference(left.grid, right.grid))
    }

    private fun formatDescription(format: MediaFormat): JSONObject = JSONObject().put("raw", format.toString()).apply {
        listOf(MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, MediaFormat.KEY_COLOR_FORMAT,
            "stride", "slice-height", "crop-left", "crop-top", "crop-right", "crop-bottom",
            MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER)
            .filter(format::containsKey).forEach { key -> put(key, format.getInteger(key)) }
        if (format.containsKey(MediaFormat.KEY_MIME)) put("mime", format.getString(MediaFormat.KEY_MIME))
        if (format.containsKey(MediaFormat.KEY_DURATION)) put("duration_us", format.getLong(MediaFormat.KEY_DURATION))
    }

    private fun identity(file: File): JSONObject {
        require(file.isFile) { "Missing diagnostic input ${file.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return JSONObject().put("name", file.name).put("bytes", file.length()).put("sha256",
            digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
    }

    private companion object {
        const val GRID_WIDTH = 48
        const val GRID_HEIGHT = 72
    }
}

/** Pure test-only matching/math; host tests exercise this exact extracted object without Android. */
internal object TargetedDecodeMath {
    fun unsignedPlaneSample(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, x: Int, y: Int): Int {
        require(x >= 0 && y >= 0 && rowStride > 0 && pixelStride > 0) { "Invalid plane coordinates or strides" }
        val origin = buffer.position()
        val index = origin.toLong() + y.toLong() * rowStride + x.toLong() * pixelStride
        require(index >= origin && index < buffer.limit()) { "Plane sample is outside the readable buffer" }
        return buffer.get(index.toInt()).toInt() and 0xff
    }

    fun matchesRequestedPts(requestedUs: Long, actualUs: Long): Boolean =
        requestedUs >= 0L && actualUs >= 0L && abs(actualUs - requestedUs) <= 1L

    /** Copy of RenderedVisualSampler.frameDifference; production's private implementation stays untouched. */
    fun frameDifference(previous: FloatArray, current: FloatArray): Float {
        require(previous.isNotEmpty() && previous.size == current.size) { "Different or empty luma grids" }
        return previous.indices.sumOf { index -> abs(previous[index] - current[index]).toDouble() }
            .toFloat().div(previous.size).times(2.2f).coerceIn(0f, 1f)
    }
}
