package com.veycad.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.roundToInt

/** Existing decoder/semantic models, indexed in independently inspected absolute source PTS. */
internal object SmartFramingSourceAnalysis {
    fun analyze(context: Context, file: File, key: SmartFramingKey,
        checkCancelled: () -> Unit): MediaFrameVisualAnalyzer.Result {
        val span = key.endPtsUs - key.firstPtsUs
        SourceAnalysisBudget.preflight(span, key.intervalUs, context.cacheDir.usableSpace)
        val clock = projectClock(file, key, checkCancelled)
        val targets = decoderTargets(file, key, clock, checkCancelled)
        val metadata = SequentialBitmapDecoder.metadata(file)
        val scale = minOf(1.0, SourceAnalysisBudget.MAX_INPUT_SIDE.toDouble() /
            maxOf(metadata.displayWidth, metadata.displayHeight))
        val width = (metadata.displayWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (metadata.displayHeight * scale).roundToInt().coerceAtLeast(1)
        val observations = mutableListOf<VisualEventMap.Observation>()
        val attachments = mutableListOf<FrameAttachments>()
        var previous: LumaMotionEstimator.Plane? = null
        var successes = 0
        val analyzer = runCatching { LocalSemanticFrameAnalyzer() }.getOrNull()
        try {
            SequentialBitmapDecoder.decodeActual(file, targets, width, height, checkCancelled) { target, decodedPts, bitmap ->
                checkCancelled()
                SourceAnalysisTimeline.requireDecodedPts(target, decodedPts)
                val pts = clock?.toProjectUs(decodedPts) ?: decodedPts
                val plane = MediaFrameVisualAnalyzer.motionPlane(bitmap)
                val estimate = LumaMotionEstimator.estimate(previous, plane)
                // Failure is current UNKNOWN evidence; never reuse the preceding face or mask.
                val semantic = analyzer?.let { runCatching { it.analyze(bitmap) }.getOrNull() }
                checkCancelled()
                successes += semantic?.successfulModels ?: 0
                val (quality, occlusion) = MediaFrameVisualAnalyzer.subjectMetrics(estimate, semantic)
                semantic?.mask?.let { mask -> attachments += FrameAttachments(pts, mask = mask,
                    subjectQuality = quality, subjectOcclusion = occlusion,
                    maskTemporalIou = semantic.maskTemporalIou, faceRegion = semantic.faceRegion,
                    detectedFaceCount = semantic.detectedFaceCount,
                    faceInferenceSucceeded = semantic.faceInferenceSucceeded) }
                observations += MediaFrameVisualAnalyzer.observationForFrame(pts, estimate, semantic)
                previous = plane
            }
        } finally { analyzer?.close() }
        checkCancelled()
        return MediaFrameVisualAnalyzer.Result(SourceAnalysisProfile.EDITORIAL_SEMANTICS, 0,
            key.endPtsUs, observations, FrameAttachmentTimeline(attachments), observations.size,
            attachments.size, successes)
    }

    private fun decoderTargets(file: File, key: SmartFramingKey,
        clock: Mp4PresentationBounds.DecoderClock?, checkCancelled: () -> Unit): LongArray {
        val planned = SequentialBitmapDecoder.intervalTargets(key.endPtsUs - key.firstPtsUs, key.intervalUs)
            .map {
                val projectPts = Math.addExact(key.firstPtsUs, it)
                // Pick the first physical frame at or after the saved project time.
                clock?.toDecoderSeekUs(projectPts) ?: projectPts
            }.toLongArray()
        val indexed = MulticlassMatteClient.decodedTargets(file, planned, checkCancelled, analysisClock = true)
        require(indexed.isNotEmpty() && indexed.size <= 240 && indexed.all {
            val projectPts = clock?.toProjectUs(it) ?: it
            projectPts >= key.firstPtsUs && projectPts < key.endPtsUs
        }) { "SMART frame index differs from inspected PTS bounds" }
        return indexed
    }

    private fun projectClock(file: File, key: SmartFramingKey,
        checkCancelled: () -> Unit): Mp4PresentationBounds.DecoderClock? {
        checkCancelled()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val format = extractor.getTrackFormat(track)
            if (!format.containsKey(MediaFormat.KEY_TRACK_ID)) return null
            extractor.selectTrack(track)
            return Mp4PresentationBounds.decoderClock(file, format.getInteger(MediaFormat.KEY_TRACK_ID),
                key.firstPtsUs, key.endPtsUs, extractor.sampleTime, checkCancelled)
        } finally { extractor.release() }
    }
}
