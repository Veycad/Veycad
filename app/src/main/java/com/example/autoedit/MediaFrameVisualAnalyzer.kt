package com.veycad.app

import android.graphics.Bitmap
import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File

/** Samples real source pixels and on-device semantic evidence without retaining decoded bitmaps. */
internal object MediaFrameVisualAnalyzer {
    data class Result(
        val profile: SourceAnalysisProfile,
        val correspondenceAssessmentsCompleted: Int,
        val durationUs: Long,
        val observations: List<VisualEventMap.Observation>,
        val attachments: FrameAttachmentTimeline,
        val semanticFrames: Int,
        val maskFrames: Int,
        val semanticModelSuccesses: Int
    ) {
        init { profile.validate(observations, correspondenceAssessmentsCompleted) }

        fun requireCapabilities(required: SourceAnalysisProfile) {
            require(profile.supplies(required)) {
                "Source analysis ${profile.cacheToken} cannot supply ${required.cacheToken}"
            }
        }
    }

    fun analyze(context: Context, file: File, profile: SourceAnalysisProfile, intervalUs: Long = 250_000L,
        checkCancelled: () -> Unit = {}, jobLease: File? = null): Result {
        checkCancelled()
        require(file.isFile && intervalUs in 100_000L..1_000_000L)
        val startedNs = System.nanoTime()
        val cacheKey = MediaFrameAnalysisCache.key(file, intervalUs, profile)
        MediaFrameAnalysisCache.load(context, cacheKey)?.let { return it }
        // A failed sequential decode must not silently become sync-frame-only observations
        // labelled with new timestamps. Fail closed; the isolated provider already supports
        // frame-only decode when full-video external segmentation is not requested.
        val multiclassAnalysis = MulticlassMatteClient.analyze(context, file, intervalUs, jobLease)
        val multiclassMattes = multiclassAnalysis.masks
        val retriever = MediaMetadataRetriever()
        val semanticAnalyzer = runCatching { LocalSemanticFrameAnalyzer() }.getOrNull()
        var semanticElapsedNs = 0L
        var motionElapsedNs = 0L
        val result = try {
            checkCancelled()
            retriever.setDataSource(file.absolutePath)
            val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("Video duration unavailable")) * 1_000L
            val observations = ArrayList<VisualEventMap.Observation>()
            val attachments = ArrayList<FrameAttachments>()
            var previous: LumaMotionEstimator.Plane? = null
            var previousCorrespondence: LumaMotionEstimator.Plane? = null
            var previousTimeUs: Long? = null
            var semanticFrames = 0
            var maskFrames = 0
            var semanticModelSuccesses = 0
            var correspondenceAssessmentsCompleted = 0
            for (timeUs in multiclassAnalysis.decodedTimesUs) {
                checkCancelled()
                val bitmap = requireNotNull(multiclassAnalysis.decodedFrame(timeUs)) {
                    "Missing decoded analysis frame at $timeUs us"
                }
                run {
                    val plane = motionPlane(bitmap)
                    val correspondence = profile.correspondence { correspondencePlane(bitmap) }
                    val estimate = LumaMotionEstimator.estimate(previous, plane)
                    val denseFlow = DenseOpticalFlowEstimator.estimate(previous, plane)
                    val semanticStartedNs = System.nanoTime()
                    val semantic = if (semanticAnalyzer != null) {
                        semanticFrames++
                        val externalMask = multiclassMattes[timeUs] ?: multiclassMattes.entries
                            .minByOrNull { kotlin.math.abs(it.key - timeUs) }
                            ?.takeIf { kotlin.math.abs(it.key - timeUs) <= intervalUs / 2L }
                            ?.value
                        runCatching {
                            semanticAnalyzer.analyze(
                                bitmap,
                                externalMask,
                                // Fresh full-body evidence is required even when no face is visible.
                                runPose = true
                            )
                        }.getOrNull()
                    } else null
                    semanticElapsedNs += System.nanoTime() - semanticStartedNs
                    semanticModelSuccesses += semantic?.successfulModels ?: 0
                    val mask = semantic?.mask
                    val motionStartedNs = System.nanoTime()
                    val motionAssessment = profile.correspondence {
                        PersonMotionEvidence.assess(previousCorrespondence, requireNotNull(correspondence), previousTimeUs,
                            timeUs, if (semantic != null) timeUs else null, mask,
                            semantic?.humanPresenceConfidence ?: 0f).also {
                            correspondenceAssessmentsCompleted++
                        }
                    }
                    if (profile.requestsCorrespondence) motionElapsedNs += System.nanoTime() - motionStartedNs
                    val subjectOcclusion = maxOf(
                        estimate.occlusionConfidence,
                        semantic?.occlusionConfidence ?: 0f,
                        (semantic?.gestureConfidence?.takeIf { semantic.gestureEvidenceAvailable } ?: 0f) * .82f
                    ).coerceIn(0f, 1f)
                    val subjectQuality = if (mask == null) 0f else {
                        val semanticScore =
                            mask.confidence * .27f +
                            (semantic.maskTemporalIou) * .24f +
                            estimate.visualQuality * .18f +
                            (semantic.face?.confidence ?: 0f) * .18f +
                            (semantic.composition?.quality ?: 0f) * .13f
                        (semanticScore * (1f - subjectOcclusion * .82f)).coerceIn(0f, 1f)
                    }
                    if (mask != null) maskFrames++
                    if (semantic != null && (mask != null || semantic.depth != null || denseFlow != null)) {
                        attachments += FrameAttachments(
                            sourceTimeUs = timeUs,
                            mask = mask,
                            depth = semantic?.depth,
                            flow = denseFlow?.flow,
                            subjectQuality = subjectQuality,
                            subjectOcclusion = subjectOcclusion,
                            maskTemporalIou = semantic?.maskTemporalIou ?: 0f,
                            faceRegion = semantic.faceRegion,
                            detectedFaceCount = semantic.detectedFaceCount,
                            faceInferenceSucceeded = semantic.faceInferenceSucceeded
                        )
                    }
                    observations += observationForFrame(timeUs, estimate, semantic,
                        motionAssessment?.measurement, motionAssessment?.cameraMeasurement)
                    previous = plane
                    previousCorrespondence = correspondence
                    previousTimeUs = timeUs
                    bitmap.recycle()
                }
            }
            require(observations.isNotEmpty()) { "No video frames decoded" }
            Result(
                profile,
                correspondenceAssessmentsCompleted,
                durationUs,
                observations,
                FrameAttachmentTimeline(attachments),
                semanticFrames,
                maskFrames,
                semanticModelSuccesses
            )
        } finally {
            semanticAnalyzer?.close()
            retriever.release()
            multiclassAnalysis.close()
        }
        checkCancelled()
        MediaFrameAnalysisCache.store(context, cacheKey, result)
        LocalDiagnostics.record(context, "video_analysis_complete", mapOf(
            "elapsed_ms" to ((System.nanoTime() - startedNs) / 1_000_000L).toString(),
            "semantic_elapsed_ms" to (semanticElapsedNs / 1_000_000L).toString(),
            "motion_elapsed_ms" to (motionElapsedNs / 1_000_000L).toString(),
            "observations" to result.observations.size.toString(),
            "semantic_frames" to result.semanticFrames.toString(),
            "model_successes" to result.semanticModelSuccesses.toString(),
            "source_analysis_profile" to profile.cacheToken,
            "correspondence_state" to profile.correspondenceState.name,
            "correspondence_assessments_completed" to result.correspondenceAssessmentsCompleted.toString(),
            "motion_measured_samples" to if (profile.requestsCorrespondence)
                result.observations.count { it.motionMeasurement != null }.toString() else "not_requested",
            "motion_unknown_samples" to if (profile.requestsCorrespondence)
                result.observations.count { it.motionMeasurement == null }.toString() else "not_requested",
            "camera_measured_samples" to if (profile.requestsCorrespondence)
                result.observations.count { it.cameraMeasurement != null }.toString() else "not_requested",
            "camera_only_measured_samples" to if (profile.requestsCorrespondence) result.observations.count {
                it.cameraMeasurement != null && it.motionMeasurement == null }.toString() else "not_requested"
        ))
        return result
    }

    internal fun motionPlane(bitmap: Bitmap): LumaMotionEstimator.Plane {
        val scaled = Bitmap.createScaledBitmap(bitmap, 48, 72, true)
        return try { scaled.toLumaPlane() } finally { if (scaled !== bitmap) scaled.recycle() }
    }

    /** No previous semantic result is accepted here: a failed frame stays unknown.
     * Pixel quality/legacy flow still refer to the current frame, but cannot invent a face/person.
     */
    internal fun observationForFrame(timeUs: Long, estimate: LumaMotionEstimator.Estimate,
        semantic: LocalSemanticFrameAnalyzer.Result?,
        motionMeasurement: VisualEventMap.MotionMeasurement? = null,
        cameraMeasurement: VisualEventMap.CameraMeasurement? = null): VisualEventMap.Observation =
        VisualEventMap.Observation(
            sourceTimeUs = timeUs,
            cameraMotion = estimate.cameraMotion,
            subjectMotion = estimate.subjectMotion,
            face = semantic?.face,
            gestureConfidence = semantic?.gestureConfidence?.takeIf { semantic.gestureEvidenceAvailable } ?: 0f,
            occlusionConfidence = maxOf(estimate.occlusionConfidence, semantic?.occlusionConfidence ?: 0f),
            personMaskConfidence = semantic?.mask?.confidence ?: 0f,
            personMaskTemporalIou = semantic?.maskTemporalIou ?: 0f,
            visualQuality = estimate.visualQuality,
            meanLuma = estimate.meanLuma,
            // Keep the existing current-pixel body ranking when semantics exist, never a carried scale.
            composition = semantic?.let { it.composition ?: estimate.composition },
            sceneChangeConfidence = estimate.sceneChangeConfidence,
            humanPresenceConfidence = semantic?.humanPresenceConfidence ?: 0f,
            motionMeasurement = motionMeasurement.takeIf { semantic != null },
            faceInferenceSucceeded = semantic?.faceInferenceSucceeded ?: false,
            gestureEvidenceAvailable = semantic?.gestureEvidenceAvailable ?: false,
            cameraMeasurement = cameraMeasurement.takeIf { semantic != null },
            detectedFaceCount = semantic?.detectedFaceCount
        )

    /** Aspect-preserving full-frame area integration is only for explicit correspondence.
     * Keep legacy luma moments/blur-flow unchanged; geometry/units are explicit in that matcher.
     */
    internal fun correspondencePlane(bitmap: Bitmap): LumaMotionEstimator.Plane {
        val size = MotionAnalysisGeometry.size(bitmap.width, bitmap.height)
        return LumaAreaResampler.resize(bitmap.toLumaPlane(), size.width, size.height)
    }

    private fun Bitmap.toLumaPlane(): LumaMotionEstimator.Plane {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = FloatArray(pixels.size) { index ->
            val colour = pixels[index]
            val red = colour shr 16 and 0xff
            val green = colour shr 8 and 0xff
            val blue = colour and 0xff
            (.299f * red + .587f * green + .114f * blue) / 255f
        }
        return LumaMotionEstimator.Plane(width, height, luma)
    }

    // Motion samples target 250 ms intervals, aligned to real video PTS. Semantic models run on
    // each observation: a carried mask/human label cannot validate a new pixel displacement.
    // FrameAttachmentTimeline still blends masks for preview/export, not motion evidence.
}
