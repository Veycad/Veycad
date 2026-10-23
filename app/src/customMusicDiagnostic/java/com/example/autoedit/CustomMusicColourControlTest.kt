package com.veycad.app

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Optional test source, injected identically into base/current isolated UI-test APKs. */
@RunWith(AndroidJUnit4::class)
class CustomMusicColourControlTest {
    @Test fun same_frozen_visual_graph_and_source_control() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName.endsWith(".uitest")) { "Diagnostic must use the isolated test app" }
        val args = InstrumentationRegistry.getArguments()
        val directory = args.getString("controlInputDirectory")?.let(::File)
            ?: File(context.filesDir, "custom-music-colour-control")
        val reportFile = File(directory, "colour-control-report.json")
        val output = File(directory, "control-output.mp4")
        require(directory.isDirectory)
        reportFile.delete()
        val report = JSONObject().put("schema_version", 1)
            .put("method", "same-frozen-visual-graph-colour-control-v1")
            .put("diagnostic_only", true).put("release_acceptance", false)
            .put("projection", "audioTrack=null; every other saved graph field retained")
            .put("success", false)
        var stage = "input"
        try {
            val buildRef = requireNotNull(args.getString("controlBuildRef"))
            val buildHead = requireNotNull(args.getString("controlBuildHeadSha"))
            require(buildRef.isNotBlank() && buildHead.matches(Regex("[0-9a-f]{40}")))
            val modelHashes = JSONObject(requireNotNull(args.getString("controlModelHashesJson")))
            require(modelHashes.length() > 0)
            modelHashes.keys().forEach { key ->
                require(modelHashes.getString(key).matches(Regex("[0-9a-f]{40}")))
            }
            report.put("build_ref", buildRef).put("build_head_sha", buildHead)
                .put("model_git_blobs_declared_by_build", modelHashes)
                .put("device", JSONObject().put("sdk", Build.VERSION.SDK_INT)
                    .put("fingerprint", Build.FINGERPRINT).put("model", Build.MODEL))
            val loaded = FrozenVisualGraphLoader.load(directory)
            report.put("origin", loaded.origin)
                .put("original_native_qa", JSONObject().put("quality_gate", loaded.origin.getBoolean("native_quality_gate"))
                    .put("issues", loaded.origin.getJSONArray("native_issues"))
                    .put("scope", "Original production snapshot; this control never overrides it"))
                .put("input_sha256", loaded.inputSha256).put("source_sha256", loaded.sourceSha256)
                .put("frozen_json_sha256", loaded.frozenJsonSha256).put("planes_sha256", loaded.planesSha256)
                .put("visual_graph_sha256_declared", loaded.origin.getString("visual_graph_sha256"))
                .put("plane_count", loaded.planeCount)
                .put("render_inputs", JSONObject().put("width", 160).put("height", 240).put("fps", 30)
                    .put("bitrate", 200_000).put("secondary_source", JSONObject.NULL)
                    .put("audio_file", JSONObject.NULL).put("audio_resource_id", 0)
                    .put("output_window", JSONObject.NULL).put("debug_flags", false)
                    .put("capabilities", "conservative"))
            stage = "plan"
            val fingerprints = FrameFingerprints(loaded.planeHashes)
            val plan = HighQualityFramePlan.build(loaded.graph, 30)
            require(plan.frames.size == 480 && plan.durationUs == 16_000_000L)
            val planned = JSONArray()
            val planDigest = MessageDigest.getInstance("SHA-256")
            plan.frames.forEach { frame ->
                val item = fingerprints.frame(frame)
                planned.put(item)
                planDigest.update((item.getString("sha256") + "\n").toByteArray(Charsets.UTF_8))
            }
            report.put("planned_frame_fingerprint_method", "typed-fields-big-endian-raw-f32-v1")
                .put("planned_frames_sha256", FrozenVisualGraphLoader.hex(planDigest.digest()))
                .put("planned_frames", planned)
            stage = "render"
            val rendered = MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
                masterFile = loaded.source, graph = loaded.graph, outputFile = output,
                width = 160, height = 240, fps = 30, bitrate = 200_000,
                audioFile = null, audioResourceId = 0, context = context,
                renderPlan = RenderPassPlanner.plan(loaded.graph, RenderPassPlanner.DeviceCapabilities.conservative())
            ))
            require(rendered == 480)
            report.put("rendered_frames", rendered).put("output_sha256", FrozenVisualGraphLoader.sha256(output))
            stage = "execution-clock"
            val artifacts = requireNotNull(VeykadRenderInspector.latest(context, output))
            val execution = JSONObject(artifacts.report.readText(Charsets.UTF_8))
            val executionFrames = execution.getJSONArray("frames")
            require(executionFrames.length() == 480)
            val actual = JSONArray()
            val sourceClock = linkedMapOf<Long, Long>()
            repeat(executionFrames.length()) { index ->
                val frame = executionFrames.getJSONObject(index)
                require(!frame.isNull("decoded_source_us"))
                val outputUs = frame.getLong("output_us")
                val sourceUs = frame.getLong("decoded_source_us")
                require(outputUs == plan.frames[index].outputTimeUs && outputUs >= 0 && sourceUs >= 0)
                require(sourceClock.put(outputUs, sourceUs) == null)
                actual.put(JSONObject().put("output_us", outputUs).put("source_us", frame.getLong("source_us"))
                    .put("decoded_source_us", sourceUs).put("source_sampling_error_us", frame.getLong("source_sampling_error_us"))
                    .put("mask_source_us", if (frame.isNull("mask_source_us")) JSONObject.NULL else frame.getLong("mask_source_us"))
                    .put("decoded_secondary_source_us", if (frame.isNull("decoded_secondary_source_us")) JSONObject.NULL
                        else frame.getLong("decoded_secondary_source_us"))
                    .put("clip", frame.getInt("clip")).put("transition", frame.getString("transition"))
                    .put("dual_decoder", frame.getBoolean("dual_decoder")))
            }
            report.put("actual_video_source_clock", actual)
                .put("execution_summary", execution.getJSONObject("summary"))
                .put("video_only_container", execution.getJSONObject("container"))
                .put("video_only_container_is_not_release_acceptance", true)
            stage = "decoded-pixels"
            val pixels = decodedPixels(output)
            require(pixels.getJSONArray("frames").length() == 480)
            report.put("decoded_pixels", pixels)
            stage = "colour-samples"
            val samples = RenderedVisualSampler.sample(output, loaded.graph,
                sourceFile = loaded.source, intervalUs = 100_000L,
                decodedSourceTimes = sourceClock, renderFps = 30)
            require(samples.size == 160)
            val colours = JSONArray()
            samples.forEach { sample ->
                colours.put(JSONObject().put("output_us", sample.outputTimeUs)
                    .put("luma", sample.luma.toDouble()).put("chroma_u", sample.chromaU.toDouble())
                    .put("chroma_v", sample.chromaV.toDouble()).put("transition_strength", sample.transitionStrength.toDouble())
                    .put("raw_f32_bits", JSONArray(listOf(sample.luma, sample.chromaU, sample.chromaV,
                        sample.transitionStrength).map { java.lang.Float.floatToRawIntBits(it) })))
            }
            val before = samples.single { it.outputTimeUs == 11_900_000L }
            val after = samples.single { it.outputTimeUs == 12_000_000L }
            val deltaLuma = abs(after.luma - before.luma)
            val deltaChroma = sqrt((after.chromaU - before.chromaU) * (after.chromaU - before.chromaU) +
                (after.chromaV - before.chromaV) * (after.chromaV - before.chromaV))
            report.put("decoded_colour_samples", colours)
                .put("colour_sample_method", "unchanged-RenderedVisualSampler-100000us; no new source analysis")
                .put("twelve_second_pair", JSONObject().put("left_output_us", before.outputTimeUs)
                    .put("right_output_us", after.outputTimeUs).put("luma_delta", deltaLuma.toDouble())
                    .put("chroma_delta", deltaChroma.toDouble())
                    .put("weighted_colour_delta", (deltaLuma * .65f + deltaChroma * .35f).toDouble())
                    .put("threshold_applied", false))
                .put("source_unchanged_after_render", FrozenVisualGraphLoader.sha256(loaded.source) == loaded.sourceSha256)
                .put("frozen_json_unchanged_after_render",
                    FrozenVisualGraphLoader.sha256(File(directory, "frozen-graph.json")) == loaded.frozenJsonSha256)
                .put("planes_unchanged_after_render",
                    FrozenVisualGraphLoader.sha256(File(directory, "frozen-planes.f32")) == loaded.planesSha256)
            require(report.getBoolean("source_unchanged_after_render") && report.getBoolean("frozen_json_unchanged_after_render") &&
                report.getBoolean("planes_unchanged_after_render"))
            report.put("success", true).put("stage", "complete")
        } catch (failure: Throwable) {
            report.put("stage", stage).put("failure_type", failure.javaClass.name)
                .put("failure", failure.message ?: "No failure message")
            throw failure
        } finally {
            reportFile.writeText(report.toString(2) + "\n", Charsets.UTF_8)
        }
    }

    /** Digest typed frame inputs without reflection, rounded values or copied plane arrays. */
    private class FrameFingerprints(val planeHashes: IdentityHashMap<FloatArray, String>) {
        fun frame(f: HighQualityFramePlan.Frame): JSONObject {
            val floats = listOf(f.clipProgress, f.transform.at, f.transform.scale, f.transform.translateX,
                f.transform.translateY, f.transform.rotationDegrees, f.exposureBias, f.redBias, f.greenBias,
                f.blueBias, f.flowStrength, f.effects.glow, f.effects.glitch, f.effects.lensBlur,
                f.effects.direction, f.effects.defocus, f.layer.opacity, f.layer.red, f.layer.green,
                f.layer.blue, f.layer.progress, f.layer.finalFadeOpacity)
            val attachment = f.attachments?.let(::attachment)
            val hash = digest { out ->
                out.writeLong(f.outputTimeUs); out.writeLong(f.sourceTimeUs); out.writeInt(f.clipIndex)
                out.writeInt(f.sourceIndex); out.writeUTF(f.transitionIn.name)
                floats.forEach { out.f(it) }; out.nf(f.transitionProgress)
                out.nl(f.secondarySourceTimeUs); out.writeUTF(f.layer.blendMode.name); out.writeUTF(f.layer.kind.name)
                out.writeLong(f.layer.secondarySourceOffsetMs); out.nl(f.layer.secondaryTimelineStartMs)
                out.writeLong(f.layer.durationMs); out.writeBoolean(f.layer.heartbeatEcho)
                out.writeBoolean(attachment != null); if (attachment != null) out.writeUTF(attachment)
            }
            return JSONObject().put("output_us", f.outputTimeUs).put("source_us", f.sourceTimeUs)
                .put("clip", f.clipIndex).put("source_index", f.sourceIndex).put("transition", f.transitionIn.name)
                .put("float_bits", JSONArray(floats.map { java.lang.Float.floatToRawIntBits(it) }))
                .put("transition_progress_bits", f.transitionProgress?.let { java.lang.Float.floatToRawIntBits(it) } ?: JSONObject.NULL)
                .put("secondary_source_us", f.secondarySourceTimeUs ?: JSONObject.NULL)
                .put("attachment_sha256", attachment ?: JSONObject.NULL).put("sha256", hash)
        }

        private fun attachment(a: FrameAttachments): String = digest { out ->
            out.writeLong(a.sourceTimeUs); out.plane(a.mask); out.plane(a.depth)
            out.writeBoolean(a.flow != null)
            a.flow?.let { flow ->
                out.writeInt(flow.width); out.writeInt(flow.height); out.f(flow.confidence)
                out.writeInt(flow.vectors.size); flow.vectors.forEach { out.f(it) }
            }
            out.f(a.subjectQuality); out.f(a.subjectOcclusion); out.f(a.maskTemporalIou)
            out.writeBoolean(a.faceRegion != null)
            a.faceRegion?.let { face ->
                listOf(face.centerX, face.centerY, face.width, face.height, face.confidence).forEach { out.f(it) }
            }
            out.plane(a.maskBlendTarget); out.f(a.maskBlendProgress); out.writeBoolean(a.maskIsOpacity)
        }

        private fun DataOutputStream.plane(p: FrameAttachments.Plane?) {
            writeBoolean(p != null)
            if (p == null) return
            writeInt(p.width); writeInt(p.height); f(p.confidence); writeInt(p.values.size)
            writeUTF(requireNotNull(planeHashes[p.values]) { "Frame references a plane absent from frozen binary" })
        }

        private fun digest(write: (DataOutputStream) -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val sink = object : OutputStream() { override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit }
            DataOutputStream(DigestOutputStream(sink, digest)).use(write)
            return FrozenVisualGraphLoader.hex(digest.digest())
        }

        private fun DataOutputStream.f(value: Float) { writeInt(java.lang.Float.floatToRawIntBits(value)) }
        private fun DataOutputStream.nf(value: Float?) { writeBoolean(value != null); if (value != null) f(value) }
        private fun DataOutputStream.nl(value: Long?) { writeBoolean(value != null); if (value != null) writeLong(value) }
    }

    /** Sequential actual output decode; each hash covers visible Y/U/V pixels, not encoded packets. */
    private fun decodedPixels(file: File): JSONObject {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val frames = JSONArray()
        val aggregate = MessageDigest.getInstance("SHA-256")
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it)
                .getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = codec
            codec.configure(format, null, null, 0); codec.start()
            val result = JSONObject().put("method", "android-yuv420-visible-planes-v1")
                .put("decoder", codec.name)
                .put("hash_layout", "big-endian int32 visible width,height then row-major unsigned-byte Y,U,V; chroma half-resolution")
            val info = MediaCodec.BufferInfo()
            var inputEnded = false; var outputEnded = false; var lastProgressNs = System.nanoTime()
            var lastPts = -1L
            while (!outputEnded) {
                check(System.nanoTime() - lastProgressNs < 20_000_000_000L) { "Diagnostic video decoder stalled" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)); buffer.clear()
                        val bytes = extractor.readSampleData(buffer, 0)
                        if (bytes < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { codec.queueInputBuffer(index, 0, bytes, extractor.sampleTime, extractor.sampleFlags); extractor.advance() }
                        lastProgressNs = System.nanoTime()
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> lastProgressNs = System.nanoTime()
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        if (info.size > 0) {
                            val image = requireNotNull(codec.getOutputImage(index)) { "No actual decoded Image pixels" }
                            val hash = try { pixelHash(image) } finally { image.close() }
                            require(info.presentationTimeUs > lastPts)
                            lastPts = info.presentationTimeUs
                            frames.put(JSONObject().put("pts_us", info.presentationTimeUs).put("sha256", hash))
                            aggregate.update(("${info.presentationTimeUs}:$hash\n").toByteArray(Charsets.UTF_8))
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false); lastProgressNs = System.nanoTime()
                    }
                }
            }
            return result.put("frames", frames).put("sha256", FrozenVisualGraphLoader.hex(aggregate.digest()))
        } finally {
            runCatching { decoder?.stop() }; decoder?.release(); extractor.release()
        }
    }

    private fun pixelHash(image: Image): String {
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3)
        val crop = image.cropRect
        require(crop.width() == 160 && crop.height() == 240 && crop.left % 2 == 0 && crop.top % 2 == 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() { override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit }
        DataOutputStream(DigestOutputStream(sink, digest)).use { out ->
            out.writeInt(crop.width()); out.writeInt(crop.height())
            image.planes.forEachIndexed { index, plane ->
                val divisor = if (index == 0) 1 else 2
                val width = crop.width() / divisor; val height = crop.height() / divisor
                val left = crop.left / divisor; val top = crop.top / divisor
                val buffer = plane.buffer.duplicate()
                val origin = buffer.position()
                val row = ByteArray(width)
                repeat(height) { y ->
                    repeat(width) { x -> row[x] = buffer.get(origin + (top + y) * plane.rowStride + (left + x) * plane.pixelStride) }
                    out.write(row)
                }
            }
        }
        return FrozenVisualGraphLoader.hex(digest.digest())
    }
}
