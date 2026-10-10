package com.veycad.app

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Debug-only controlled experiment: replace masks, not editorial decisions or source pixels. */
internal object ExternalMatteRenderProbe {
    fun render(context: Context, source: File, music: File, output: File,
               graph: MontageGraph, directory: File) {
        val marker = File(output.path + ".result")
        require(!output.exists() && !marker.exists()) { "Probe output already exists" }
        val manifest = JSONObject(File(directory, "manifest.json").readText())
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash == manifest.getString("source_sha256")) { "Matte belongs to another source" }
        val width = manifest.getInt("width")
        val height = manifest.getInt("height")
        require(width in 1..1024 && height in 1..1024)
        val times = manifest.getJSONArray("timestamps_us")
        require(times.length() in 2..120) { "Probe expects a short local sequence" }
        val originals = graph.frameAttachments
        val replacements = (0 until times.length()).map { index ->
            val time = times.getLong(index)
            require(time >= 0 && (index == 0 || time > times.getLong(index - 1)))
            val bitmap = requireNotNull(BitmapFactory.decodeFile(
                File(directory, "alpha-${time.toString().padStart(12, '0')}.png").path))
            val plane = try {
                require(bitmap.width == width && bitmap.height == height)
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val values = FloatArray(pixels.size) { Color.red(pixels[it]) / 255f }
                FrameAttachments.Plane(width, height, values, SemanticMaskMetrics.stats(values).confidence)
            } finally { bitmap.recycle() }
            val original = requireNotNull(originals.interpolated(time)) {
                "No baseline attachment near external matte PTS $time"
            }
            original.copy(sourceTimeUs = time, mask = plane,
                maskBlendTarget = null, maskBlendProgress = 0f, maskIsOpacity = true)
        }
        val range = replacements.first().sourceTimeUs..replacements.last().sourceTimeUs
        val timeline = mergeTimelines(originals, replacements)
        val candidateGraph = graph.copy(frameAttachments = timeline)
        val frames = MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
            masterFile = source, graph = candidateGraph, outputFile = output,
            width = 720, height = 1280, bitrate = 5_000_000, audioFile = music, context = context,
            renderPlan = RenderPassPlanner.plan(candidateGraph,
                RenderPassPlanner.DeviceCapabilities(3, 4_096, 512, false, true))))
        val container = VeykadRenderInspector.inspectContainer(output)
        VeykadRenderInspector.latest(context)?.let { artifacts ->
            artifacts.report.copyTo(File(output.parentFile, "${output.nameWithoutExtension}-inspector.json"))
            artifacts.openingSheet?.copyTo(File(output.parentFile, "${output.nameWithoutExtension}-opening.jpg"))
        }
        marker.writeText("status=ok\nscope=external-matte-diagnostic-not-production\n" +
            "frames=$frames\nsource_sha256=$hash\nmask_frames=${replacements.size}\n" +
            "first_mask_us=${range.first}\nlast_mask_us=${range.last}\n" +
            "video_last_pts_us=${container.videoLastPtsUs}\naudio_last_pts_us=${container.audioLastPtsUs}\n")
    }

    internal fun mergeTimelines(originals: FrameAttachmentTimeline,
                                replacements: List<FrameAttachments>): FrameAttachmentTimeline {
        require(replacements.isNotEmpty())
        require(replacements.all { it.mask != null })
        require(replacements.zipWithNext().all { (a, b) -> b.sourceTimeUs > a.sourceTimeUs })
        val range = replacements.first().sourceTimeUs..replacements.last().sourceTimeUs
        return FrameAttachmentTimeline((originals.frames.filterNot {
            it.sourceTimeUs in range
        } + replacements).sortedBy { it.sourceTimeUs })
    }
}
