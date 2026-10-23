package com.veycad.app

import android.content.Context
import java.io.File
import kotlin.math.roundToInt

/** Existing decoder/semantic models, indexed in independently inspected absolute source PTS. */
internal object SmartFramingSourceAnalysis {
    fun analyze(context: Context, file: File, key: SmartFramingKey,
        checkCancelled: () -> Unit): MediaFrameVisualAnalyzer.Result {
        val span = key.endPtsUs - key.firstPtsUs
        SourceAnalysisBudget.preflight(span, key.intervalUs, context.cacheDir.usableSpace)
        val targets = targets(file, key, checkCancelled)
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
            SequentialBitmapDecoder.decodeActual(file, targets, width, height, checkCancelled) { target, pts, bitmap ->
                checkCancelled()
                SourceAnalysisTimeline.requireDecodedPts(target, pts)
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

    internal fun targets(file: File, key: SmartFramingKey, checkCancelled: () -> Unit = {}): LongArray {
        val planned = SequentialBitmapDecoder.intervalTargets(key.endPtsUs - key.firstPtsUs, key.intervalUs)
            .map { Math.addExact(key.firstPtsUs, it) }.toLongArray()
        val indexed = MulticlassMatteClient.decodedTargets(file, planned, checkCancelled, analysisClock = true)
        require(indexed.isNotEmpty() && indexed.size <= 240 &&
            indexed.all { it >= key.firstPtsUs && it < key.endPtsUs }) { "SMART frame index differs from inspected PTS bounds" }
        return indexed
    }
}
