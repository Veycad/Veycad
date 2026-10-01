package com.example.autoedit

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Diagnostic only. No director, motion score, production cache write, MP4 or acceptance. */
internal object SemanticMaskEvidenceProbe {
    data class Summary(val width: Int, val height: Int, val minimum: Float, val maximum: Float,
        val mean: Double, val foregroundPixels: Int, val coverage: Float,
        val separation: Float, val confidence: Float)

    internal fun summarize(values: FloatArray, width: Int, height: Int): Summary {
        require(width > 0 && height > 0 && values.size == width * height)
        require(values.all { it.isFinite() })
        val stats = SemanticMaskMetrics.stats(values)
        return Summary(width, height, values.minOrNull()!!, values.maxOrNull()!!,
            values.sumOf { it.toDouble() } / values.size, values.count { it.coerceIn(0f, 1f) >= .5f },
            stats.coverage, stats.separation, stats.confidence)
    }

    fun run(context: Context, source: File): JSONObject {
        val key = MediaFrameAnalysisCache.key(source, 250_000L, SourceAnalysisProfile.EDITORIAL_WITH_CORRESPONDENCE)
        val cacheFile = File(context.cacheDir, "full-video-analysis/${key.fileName}")
        require(cacheFile.isFile) { "Diagnostic requires the completed existing cache, not new analysis" }
        // Unlike load(), direct read neither updates cache mtime nor deletes a malformed cache.
        val cacheSha = sha256(cacheFile)
        val cached = MediaFrameAnalysisCache.read(cacheFile, SourceAnalysisProfile.EDITORIAL_WITH_CORRESPONDENCE)
        val cachedMasks = cached.attachments.frames.associateBy { it.sourceTimeUs }
        val stream = segmenter(SelfieSegmenterOptions.STREAM_MODE)
        val single = segmenter(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
        val pose = PoseDetection.getClient(PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE).build())
        val rows = JSONArray()
        val started = System.nanoTime()
        var streamPrefixValid = true
        try {
            // Existing provider is frame-only for analyze(): no MediaPipe segmentation is run.
            MulticlassMatteClient.analyze(context, source, 250_000L).use { decoded ->
                require(decoded.decodedTimesUs.toList() == cached.observations.map { it.sourceTimeUs }) {
                    "Actual decoded PTS differ from the existing production cache"
                }
                cached.observations.forEachIndexed { index, observation ->
                    val bitmap = requireNotNull(decoded.decodedFrame(observation.sourceTimeUs))
                    val pending = mutableListOf<Task<*>>()
                    try {
                        val input = InputImage.fromBitmap(bitmap, 0)
                        val luma = compactLuma(bitmap, 256, 256)
                        val row = JSONObject().apply {
                            put("pts_us", observation.sourceTimeUs)
                            put("ordered_sequence_index", index)
                            put("input_width", bitmap.width); put("input_height", bitmap.height)
                            put("input_argb_big_endian_sha256", bitmapSha256(bitmap))
                            put("cached_human_confidence_reference_only", observation.humanPresenceConfidence)
                            put("cached_refined_mask_reference_only", cachedMasks[observation.sourceTimeUs]
                                ?.mask?.let { summaryJson(summarize(it.values, it.width, it.height)) } ?: JSONObject.NULL)
                        }
                        val streamResult = if (streamPrefixValid) infer(stream, input, luma, pending)
                            else unknown("preceding_stream_inference_failed", "Complete STREAM prefix is no longer established")
                        streamResult.put("mode", "STREAM_MODE")
                        streamResult.put("prefix_before_frame_valid", streamPrefixValid)
                        if (!streamResult.optBoolean("successful_inference")) streamPrefixValid = false
                        row.put("stream", streamResult)
                        row.put("single_image", infer(single, input, luma, pending).apply {
                            put("mode", "SINGLE_IMAGE_MODE")
                        })
                        val poseStarted = System.nanoTime()
                        val poseResult = runCatching {
                            val task = pose.process(input).also { pending += it }
                            val detected = Tasks.await(task, TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            JSONObject().apply {
                                put("successful_inference", true)
                                put("mode", "SINGLE_IMAGE_MODE")
                                put("landmarks", JSONArray().apply {
                                    detected.allPoseLandmarks.forEach { landmark ->
                                        val p = landmark.position3D
                                        require(listOf(p.x, p.y, p.z, landmark.inFrameLikelihood).all { it.isFinite() })
                                        put(JSONObject().apply {
                                            put("landmark_type", landmark.landmarkType)
                                            put("x_pixels", landmark.position.x); put("y_pixels", landmark.position.y)
                                            put("x_normalized", landmark.position.x / bitmap.width)
                                            put("y_normalized", landmark.position.y / bitmap.height)
                                            put("z_pixels", p.z); put("in_frame_likelihood", landmark.inFrameLikelihood)
                                        })
                                    }
                                })
                                put("landmark_count", detected.allPoseLandmarks.size)
                            }
                        }.getOrElse { unknown(it.javaClass.name, it.message.orEmpty()) }
                        poseResult.put("mode", "SINGLE_IMAGE_MODE")
                        if (!poseResult.optBoolean("successful_inference")) {
                            poseResult.put("landmarks", JSONObject.NULL)
                            poseResult.put("landmark_count", JSONObject.NULL)
                        }
                        poseResult.put("elapsed_us", (System.nanoTime() - poseStarted) / 1_000L)
                        row.put("current_pose_diagnostic_only", poseResult)
                        rows.put(row)
                    } finally {
                        // A timed-out task may still own its immutable InputImage. Do not recycle
                        // its pixels before completion; no carried mask/zero is emitted on failure.
                        if (pending.all { it.isComplete }) bitmap.recycle()
                        else Tasks.whenAllComplete(pending).addOnCompleteListener { bitmap.recycle() }
                    }
                }
            }
        } finally {
            stream.close(); single.close(); pose.close()
        }
        require(sha256(cacheFile) == cacheSha) { "Reference cache changed during diagnostic" }
        return JSONObject().apply {
            put("schema_version", 1); put("probe_only", true); put("not_acceptance", true)
            put("method", "fresh-exact-pts-ordered-stream-vs-independent-single-raw-downsample-guided")
            put("mask_sdk", "com.google.mlkit:segmentation-selfie:16.0.0-beta6")
            put("pose_sdk", "com.google.mlkit:pose-detection:18.0.0-beta5")
            put("fresh_frames_decoded", true); put("fresh_semantic_inference", true)
            put("production_cache_modified", false); put("production_thresholds_modified", false)
            put("observations", rows.length()); put("stream_complete_prefix", streamPrefixValid)
            listOf("stream", "single_image", "current_pose_diagnostic_only").forEach { branch ->
                val successful = (0 until rows.length()).count {
                    rows.getJSONObject(it).getJSONObject(branch).getBoolean("successful_inference")
                }
                put("${branch}_successful_inferences", successful)
                put("${branch}_unknown_inferences", rows.length() - successful)
            }
            put("interval_target_us", 250_000L); put("foreground_threshold", .5f)
            put("elapsed_us", (System.nanoTime() - started) / 1_000L)
            put("cache_file", key.fileName); put("cache_version", MediaFrameAnalysisCache.VERSION)
            put("cache_sha256_reference_only", cacheSha); put("samples", rows)
            put("limitation", "Diagnostic models only. STREAM uses the entire ordered actual decoded sequence, not selected isolated frames. SINGLE has no temporal mask smoothing. Cached refined masks/human scalars are references, not physical ground truth. Single-image pose can switch people and supplies no identity, validated motion, all-people mask ownership or camera compensation. No mode is selected for production by this report; no MP4 or human acceptance is produced.")
        }
    }

    private fun segmenter(mode: Int) = Segmentation.getClient(SelfieSegmenterOptions.Builder()
        .setDetectorMode(mode).enableRawSizeMask().build())

    private fun infer(segmenter: Segmenter, input: InputImage, luma: FloatArray,
        pending: MutableList<Task<*>>): JSONObject {
        val started = System.nanoTime()
        val result = runCatching {
            val task = segmenter.process(input).also { pending += it }
            val raw = Tasks.await(task, TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val buffer = raw.buffer.apply { rewind() }
            val values = FloatArray(raw.width * raw.height) { buffer.float }
            require(values.all { it.isFinite() }) { "Non-finite raw mask" }
            val compact = SemanticMaskMetrics.downsample(values, raw.width, raw.height, 256, 256)
            val refined = SemanticMaskMetrics.guidedRefine(compact, luma, 256, 256)
            JSONObject().apply {
                put("successful_inference", true)
                put("raw", summaryJson(summarize(values, raw.width, raw.height)))
                put("direct_downsample", summaryJson(summarize(compact, 256, 256)))
                put("guided_refine", summaryJson(summarize(refined, 256, 256)))
            }
        }.getOrElse { unknown(it.javaClass.name, it.message.orEmpty()) }
        result.put("elapsed_us", (System.nanoTime() - started) / 1_000L)
        return result
    }

    private fun unknown(type: String, detail: String) = JSONObject().apply {
        put("successful_inference", false); put("unknown", true)
        put("failure_type", type); put("failure_detail", detail)
        put("raw", JSONObject.NULL); put("direct_downsample", JSONObject.NULL)
        put("guided_refine", JSONObject.NULL)
    }

    private fun summaryJson(value: Summary) = JSONObject().apply {
        put("width", value.width); put("height", value.height)
        put("minimum", value.minimum); put("maximum", value.maximum); put("mean", value.mean)
        put("foreground_pixels_at_0_5", value.foregroundPixels); put("coverage_at_0_5", value.coverage)
        put("separation", value.separation); put("confidence", value.confidence)
    }

    private fun compactLuma(bitmap: Bitmap, width: Int, height: Int): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            FloatArray(pixels.size) { index ->
                val colour = pixels[index]
                (.299f * (colour shr 16 and 0xff) + .587f * (colour shr 8 and 0xff) +
                    .114f * (colour and 0xff)) / 255f
            }
        } finally { if (scaled !== bitmap) scaled.recycle() }
    }

    private fun bitmapSha256(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val buffer = ByteBuffer.allocate(8 + pixels.size * 4)
        buffer.putInt(bitmap.width); buffer.putInt(bitmap.height)
        pixels.forEach { buffer.putInt(it) }
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).hex()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(1 shl 20)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private const val TIMEOUT_MS = 10_000L
}
