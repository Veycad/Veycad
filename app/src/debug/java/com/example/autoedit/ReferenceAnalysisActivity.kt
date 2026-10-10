package com.veycad.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil

/** Local debug-only evidence extractor for user-owned montage references. */
class ReferenceAnalysisActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            setBackgroundColor(Color.rgb(9, 9, 11))
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            text = "Локальный анализ авторского референса…"
        }
        setContentView(status)
        Thread({ analyze(status) }, "veycad-reference-analysis").start()
    }

    private fun analyze(status: TextView) {
        val source = File(requireNotNull(intent.getStringExtra("source")))
        val outputDirectory = File(requireNotNull(intent.getStringExtra("output"))).apply { mkdirs() }
        val marker = File(outputDirectory, "complete.result").apply { delete() }
        runCatching {
            require(source.isFile) { "Reference does not exist: ${source.absolutePath}" }
            val retriever = MediaMetadataRetriever().apply { setDataSource(source.absolutePath) }
            val durationUs = requireNotNull(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ) * 1_000L
            if (intent.hasExtra("matte_cache_probe_us")) {
                try {
                    val timeUs = intent.getLongExtra("matte_cache_probe_us", 0L)
                    val planes = MulticlassMatteClient.read(File(cacheDir, "multiclass-matte.bin"))
                    val timeline = FrameAttachmentTimeline(planes.map { (pts, plane) ->
                        FrameAttachments(pts, mask = plane)
                    })
                    val frame = requireNotNull(timeline.interpolated(timeUs))
                    val mask = requireNotNull(frame.mask)
                    val bitmap = requireNotNull(scaledFrame(retriever, timeUs, mask.width, mask.height))
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val alpha = IntArray(pixels.size)
                    val cutout = IntArray(pixels.size)
                    pixels.indices.forEach { i ->
                        val m = mask.values[i] + ((frame.maskBlendTarget?.values?.get(i)
                            ?: mask.values[i]) - mask.values[i]) * frame.maskBlendProgress
                        val a = (m.coerceIn(0f, 1f) * 255f).toInt()
                        alpha[i] = Color.rgb(a, a, a)
                        cutout[i] = Color.rgb((Color.red(pixels[i]) * m).toInt(),
                            (Color.green(pixels[i]) * m).toInt(), (Color.blue(pixels[i]) * m).toInt())
                    }
                    fun save(name: String, image: Bitmap) {
                        File(outputDirectory, name).outputStream().use {
                            image.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        image.recycle()
                    }
                    val width = bitmap.width
                    val height = bitmap.height
                    save("source.png", bitmap)
                    save("alpha.png", Bitmap.createBitmap(alpha, width, height, Bitmap.Config.ARGB_8888))
                    save("cutout.png", Bitmap.createBitmap(cutout, width, height, Bitmap.Config.ARGB_8888))
                    marker.writeText("status=ok\nsource_us=$timeUs\nmask_blend=${frame.maskBlendProgress}\n")
                } finally { retriever.release() }
                return
            }
            if (intent.hasExtra("still_us")) {
                try {
                    val timeUs = intent.getLongExtra("still_us", 0L).coerceIn(0L, durationUs - 1L)
                    val bitmap = requireNotNull(retriever.getFrameAtTime(
                        timeUs, MediaMetadataRetriever.OPTION_CLOSEST
                    )) { "No decoded frame at $timeUs us" }
                    File(outputDirectory, "still.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val face = if (intent.getBooleanExtra("measure_face", false)) {
                        LocalSemanticFrameAnalyzer().use { it.analyze(bitmap).faceRegion }
                    } else null
                    bitmap.recycle()
                    marker.writeText("status=ok\nmode=decoded-still\ntime_us=$timeUs\n" +
                        "face=${face?.let { "${it.centerX},${it.centerY},${it.width},${it.height},${it.confidence}" } ?: "none"}\n")
                } finally { retriever.release() }
                return
            }
            // Inspect already encoded pixels without rerunning inference or extracting audio.
            // This keeps visual diagnosis independent of the model being evaluated.
            if (intent.getBooleanExtra("contact_only", false)) {
                try {
                    val startUs = intent.getLongExtra("contact_start_us", 0L)
                        .coerceIn(0L, durationUs - 1L)
                    val windowUs = intent.getLongExtra("contact_duration_us", 3_000_000L)
                        .coerceIn(1L, durationUs - startUs)
                    val stepUs = intent.getLongExtra("contact_interval_us", 100_000L)
                        .coerceIn(MIN_CONTACT_INTERVAL_US, MAX_CONTACT_INTERVAL_US)
                    writeContactSheet(
                        retriever,
                        startUs,
                        windowUs,
                        stepUs,
                        File(outputDirectory, "contact.jpg")
                    )
                    marker.writeText(
                        "status=ok\nmode=decoded-contact-only\nstart_us=$startUs\n" +
                            "window_us=$windowUs\nstep_us=$stepUs\n"
                    )
                } finally {
                    retriever.release()
                }
                runOnUiThread { status.text = "Контрольные кадры готовы" }
                return
            }
            val audio = MediaCodecAudioDecoder.decode(source)
            val beatMap = AudioBeatMapAnalyzer.analyze(audio.mono(), audio.sampleRate)
            val visual = MediaFrameVisualAnalyzer.analyze(this, source,
                SourceAnalysisProfile.EDITORIAL_WITH_CORRESPONDENCE, 250_000L)
            val changes = detectVisualChanges(retriever, durationUs)
            val cuts = changes.filter { it.difference >= HARD_CUT_THRESHOLD }
            val contact = File(outputDirectory, "contact.jpg")
            val requestedContactDurationUs = intent.getLongExtra("contact_duration_us", durationUs)
                .coerceIn(1L, durationUs)
            val requestedContactIntervalUs = intent.getLongExtra(
                "contact_interval_us",
                CONTACT_INTERVAL_US
            ).coerceIn(MIN_CONTACT_INTERVAL_US, MAX_CONTACT_INTERVAL_US)
            writeContactSheet(
                retriever,
                0L,
                requestedContactDurationUs,
                requestedContactIntervalUs,
                contact
            )
            val track = File(outputDirectory, "author-track.m4a")
            extractAudioTrack(source, track)
            retriever.release()

            val report = JSONObject().apply {
                put("format", "veycad-reference-analysis-v1")
                put("local_only", true)
                put("source", source.name)
                put("duration_us", durationUs)
                put("width", visualWidth(source))
                put("height", visualHeight(source))
                put("sample_rate", audio.sampleRate)
                put("channels", audio.channelCount)
                put("tempo_bpm", beatMap.estimatedTempoBpm ?: JSONObject.NULL)
                put("beats_us", JSONArray(beatMap.beats.map { beatMap.timestampUs(it.sampleIndex) }))
                put("accent_beats_us", JSONArray(beatMap.beats.filter { it.isDownbeat }.map { beatMap.timestampUs(it.sampleIndex) }))
                put("onsets_us", JSONArray(beatMap.onsets.map { beatMap.timestampUs(it.sampleIndex) }))
                put("visual_cuts", JSONArray(cuts.map { cut ->
                    JSONObject().put("time_us", cut.timeUs).put("difference", cut.difference.toDouble())
                }))
                put("visual_changes", JSONArray(changes.map { change ->
                    JSONObject().put("time_us", change.timeUs).put("difference", change.difference.toDouble())
                }))
                put("visual_events", JSONArray(VisualEventMapAnalyzer.analyze(
                    visual.durationUs,
                    visual.observations
                ).events.map { event ->
                    JSONObject()
                        .put("type", event.type.name)
                        .put("peak_us", event.peakTimeUs)
                        .put("strength", event.strength.toDouble())
                        .put("confidence", event.confidence.toDouble())
                }))
                put("visual_observations", JSONArray(visual.observations.map { observation ->
                    val faceRegion = visual.attachments.nearest(observation.sourceTimeUs)?.faceRegion
                    JSONObject()
                        .put("time_us", observation.sourceTimeUs)
                        .put("mean_luma", observation.meanLuma.toDouble())
                        .put("visual_quality", observation.visualQuality.toDouble())
                        .put("camera_motion_x", observation.cameraMotion.x.toDouble())
                        .put("camera_motion_y", observation.cameraMotion.y.toDouble())
                        .put("camera_motion_magnitude", observation.cameraMotion.magnitude.toDouble())
                        .put("subject_motion_x", observation.subjectMotion.x.toDouble())
                        .put("subject_motion_y", observation.subjectMotion.y.toDouble())
                        .put("subject_motion_magnitude", observation.subjectMotion.magnitude.toDouble())
                        .put("gesture_confidence", observation.gestureConfidence.toDouble())
                        .put("occlusion_confidence", observation.occlusionConfidence.toDouble())
                        .put("face_confidence", observation.face?.confidence?.toDouble()
                            ?: JSONObject.NULL)
                        .put("face_yaw_degrees", observation.face?.yawDegrees?.toDouble()
                            ?: JSONObject.NULL)
                        .put("face_region_width", faceRegion?.width?.toDouble() ?: JSONObject.NULL)
                        .put("face_region_height", faceRegion?.height?.toDouble() ?: JSONObject.NULL)
                        .put("face_region_center_x", faceRegion?.centerX?.toDouble() ?: JSONObject.NULL)
                        .put("face_region_center_y", faceRegion?.centerY?.toDouble() ?: JSONObject.NULL)
                        .put("subject_scale", observation.composition?.subjectScale?.toDouble()
                            ?: JSONObject.NULL)
                        .put("composition_quality", observation.composition?.quality?.toDouble()
                            ?: JSONObject.NULL)
                }))
                put("mask_frames", visual.maskFrames)
                put("semantic_frames", visual.semanticFrames)
                put("contact_duration_us", requestedContactDurationUs)
                put("contact_interval_us", requestedContactIntervalUs)
                put("audio_file", track.name)
                put("contact_sheet", contact.name)
            }
            File(outputDirectory, "reference-analysis.json").writeText(report.toString(2))
            marker.writeText(
                "status=ok\nbeats=${beatMap.beats.size}\nonsets=${beatMap.onsets.size}\n" +
                    "cuts=${cuts.size}\nvisual_changes=${changes.size}\n"
            )
            runOnUiThread { status.text = "Анализ завершён\n${changes.size} акцентов · ${beatMap.beats.size} битов" }
        }.onFailure { error ->
            marker.writeText("status=failed\ntype=${error.javaClass.name}\ndetail=${error.message.orEmpty()}\n")
            runOnUiThread { status.text = "Ошибка анализа\n${error.message.orEmpty()}" }
        }
    }

    private data class Cut(val timeUs: Long, val difference: Float)

    private fun detectVisualChanges(retriever: MediaMetadataRetriever, durationUs: Long): List<Cut> {
        val measurements = ArrayList<Cut>()
        var previous: FloatArray? = null
        var timeUs = 0L
        while (timeUs < durationUs) {
            val bitmap = scaledFrame(retriever, timeUs, 48, 72)
            if (bitmap != null) {
                val luma = luma(bitmap)
                val difference = previous?.indices?.sumOf { index ->
                    abs(previous!![index] - luma[index]).toDouble()
                }?.div(luma.size)?.toFloat() ?: 0f
                measurements += Cut(timeUs, difference)
                previous = luma
                bitmap.recycle()
            }
            timeUs += CUT_SAMPLE_US
        }
        val localPeaks = measurements.filterIndexed { index, sample ->
            val previousDifference = measurements.getOrNull(index - 1)?.difference ?: 0f
            val nextDifference = measurements.getOrNull(index + 1)?.difference ?: 0f
            sample.difference >= VISUAL_CHANGE_THRESHOLD &&
                sample.difference >= previousDifference && sample.difference > nextDifference
        }
        return localPeaks.fold(ArrayList()) { accepted, candidate ->
            val prior = accepted.lastOrNull()
            if (prior == null || candidate.timeUs - prior.timeUs >= CUT_MERGE_US) {
                accepted.apply { add(candidate) }
            } else {
                if (candidate.difference > prior.difference) accepted[accepted.lastIndex] = candidate
                accepted
            }
        }
    }

    private fun luma(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return FloatArray(pixels.size) { index ->
            val pixel = pixels[index]
            val red = pixel shr 16 and 0xff
            val green = pixel shr 8 and 0xff
            val blue = pixel and 0xff
            (.299f * red + .587f * green + .114f * blue) / 255f
        }
    }

    private fun writeContactSheet(
        retriever: MediaMetadataRetriever,
        startUs: Long,
        durationUs: Long,
        intervalUs: Long,
        output: File
    ) {
        val count = ceil(durationUs.toDouble() / intervalUs).toInt().coerceAtLeast(1)
        val rows = ceil(count.toDouble() / CONTACT_COLUMNS).toInt()
        val sheet = Bitmap.createBitmap(
            CONTACT_COLUMNS * CONTACT_WIDTH,
            rows * (CONTACT_HEIGHT + LABEL_HEIGHT),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(sheet).apply { drawColor(Color.BLACK) }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 21f }
        repeat(count) { index ->
            val timeUs = minOf(startUs + index * intervalUs, startUs + durationUs - 1L)
            scaledFrame(retriever, timeUs, CONTACT_WIDTH, CONTACT_HEIGHT)?.let { frame ->
                val left = index % CONTACT_COLUMNS * CONTACT_WIDTH
                val top = index / CONTACT_COLUMNS * (CONTACT_HEIGHT + LABEL_HEIGHT)
                canvas.drawBitmap(frame, null, Rect(left, top, left + CONTACT_WIDTH, top + CONTACT_HEIGHT), null)
                canvas.drawText("%.2fs".format(timeUs / 1_000_000.0), left + 6f, top + CONTACT_HEIGHT + 24f, label)
                frame.recycle()
            }
        }
        output.outputStream().use { sheet.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        sheet.recycle()
    }

    private fun extractAudioTrack(source: File, output: File) {
        val extractor = MediaExtractor().apply { setDataSource(source.absolutePath) }
        val trackIndex = (0 until extractor.trackCount).first { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        val format = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outputTrack = muxer.addTrack(format)
        val capacity = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
        val buffer = ByteBuffer.allocateDirect(capacity)
        val info = MediaCodec.BufferInfo()
        muxer.start()
        try {
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, extractor.sampleTime, flags)
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            muxer.stop()
            muxer.release()
            extractor.release()
        }
    }

    private fun visualWidth(file: File): Int = metadata(file, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
    private fun visualHeight(file: File): Int = metadata(file, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
    private fun metadata(file: File, key: Int): Int = MediaMetadataRetriever().let { retriever ->
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(key)?.toIntOrNull() ?: 0
        } finally {
            retriever.release()
        }
    }

    private fun scaledFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        width: Int,
        height: Int
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                width,
                height
            )
        }
        val original = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: return null
        return Bitmap.createScaledBitmap(original, width, height, true).also { scaled ->
            if (scaled !== original) original.recycle()
        }
    }

    companion object {
        private const val CUT_SAMPLE_US = 100_000L
        private const val CUT_MERGE_US = 220_000L
        private const val VISUAL_CHANGE_THRESHOLD = .045f
        private const val HARD_CUT_THRESHOLD = .145f
        private const val CONTACT_INTERVAL_US = 500_000L
        private const val MIN_CONTACT_INTERVAL_US = 30_000L
        private const val MAX_CONTACT_INTERVAL_US = 2_000_000L
        private const val CONTACT_COLUMNS = 5
        private const val CONTACT_WIDTH = 180
        private const val CONTACT_HEIGHT = 320
        private const val LABEL_HEIGHT = 32
    }
}
