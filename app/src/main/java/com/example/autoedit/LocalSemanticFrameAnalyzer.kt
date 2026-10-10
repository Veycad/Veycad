package com.veycad.app

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.Closeable
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Local-only semantic evidence for the director. Bitmaps and landmarks are discarded immediately;
 * only a compact person mask, a coarse pose-depth plane and editorial scalars are retained.
 */
internal class LocalSemanticFrameAnalyzer : Closeable {
    data class Result(
        val mask: FrameAttachments.Plane?,
        val depth: FrameAttachments.Plane?,
        val maskTemporalIou: Float,
        val personCoverage: Float,
        val face: VisualEventMap.Face?,
        val faceRegion: FrameAttachments.FaceRegion?,
        val composition: VisualEventMap.Composition?,
        val gestureConfidence: Float,
        val occlusionConfidence: Float,
        val successfulModels: Int,
        val humanPresenceConfidence: Float,
        val faceInferenceSucceeded: Boolean = false,
        /** A detected current-frame pose, not a zero substituted after failure/no landmarks. */
        val gestureEvidenceAvailable: Boolean = false
    )

    private data class PoseResult(
        val gestureConfidence: Float,
        val subjectDepth: Float,
        val depthConfidence: Float,
        val gestureEvidenceAvailable: Boolean
    )

    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            // These are ordered frames from one video. STREAM_MODE reuses prior masks to
            // suppress the frame-to-frame edge breathing that was visible around hair and hands.
            .setDetectorMode(DETECTOR_MODE)
            .enableRawSizeMask()
            .build()
    )
    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
    )
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )
    private var previousMask: FloatArray? = null
    private var previousCoverage = 0f
    private var previousWrists: Map<Int, Pair<Float, Float>>? = null
    private var consecutivePoseFailures = 0
    private var poseDisabled = false

    fun analyze(
        bitmap: Bitmap,
        externalMask: FrameAttachments.Plane? = null,
        runPose: Boolean = true
    ): Result {
        val input = InputImage.fromBitmap(bitmap, 0)
        var successfulModels = 0
        val mask = externalMask?.let { plane ->
            plane to SemanticMaskMetrics.stats(plane.values)
        } ?: runCatching {
            val raw = Tasks.await(segmenter.process(input), TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val buffer = raw.buffer.apply { rewind() }
            val source = FloatArray(raw.width * raw.height) { buffer.float.coerceIn(0f, 1f) }
            val compact = SemanticMaskMetrics.guidedRefine(
                SemanticMaskMetrics.downsample(source, raw.width, raw.height, MASK_WIDTH, MASK_HEIGHT),
                compactLuma(bitmap, MASK_WIDTH, MASK_HEIGHT),
                MASK_WIDTH,
                MASK_HEIGHT
            )
            val stats = SemanticMaskMetrics.stats(compact)
            FrameAttachments.Plane(MASK_WIDTH, MASK_HEIGHT, compact, stats.confidence) to stats
        }.onSuccess { successfulModels++ }.getOrNull()
        if (externalMask != null) successfulModels++

        val maskValues = mask?.first?.values
        val temporalIou = maskValues?.let { SemanticMaskMetrics.temporalIou(previousMask, it) } ?: 0f
        val coverage = mask?.second?.coverage ?: 0f
        // Pose and face are independent observations of the same immutable InputImage. Starting
        // both tasks before either await preserves the full analysis while letting ML Kit use its
        // worker pools concurrently instead of serialising two native inference calls per frame.
        val poseTask = if (poseDisabled || !runPose) null else poseDetector.process(input)
        val faceTask = faceDetector.process(input)
        val pose = poseTask?.let { task ->
            runCatching {
                val detected = Tasks.await(task, TIMEOUT_MS, TimeUnit.MILLISECONDS)
                poseResult(detected.allPoseLandmarks, bitmap.width, bitmap.height)
            }.onSuccess {
                consecutivePoseFailures = 0
                if (it != null) successfulModels++
            }.onFailure {
                consecutivePoseFailures++
                if (consecutivePoseFailures >= MAXIMUM_CONSECUTIVE_POSE_FAILURES) {
                    poseDisabled = true
                    android.util.Log.w(
                        "LocalSemanticPose",
                        "Disabling pose for this source after repeated inference failures",
                        it
                    )
                }
            }.getOrNull()
        }
        // Do not compare later landmarks with a pre-gap pose as if the frames were adjacent.
        if (pose == null) previousWrists = null
        val faceInference = runCatching {
            Tasks.await(faceTask, TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        }.onSuccess { if (it != null) successfulModels++ }
            .onFailure { android.util.Log.w("LocalSemanticFace", "Face inference failed; not evidence of absent face", it) }
        val faceResult = faceInference.getOrNull()

        val face = faceResult?.let {
            VisualEventMap.Face(
                confidence = .90f,
                yawDegrees = it.headEulerAngleY,
                pitchDegrees = it.headEulerAngleX,
                rollDegrees = it.headEulerAngleZ,
                gazeX = (it.headEulerAngleY / 45f).coerceIn(-1f, 1f),
                gazeY = (it.headEulerAngleX / 35f).coerceIn(-1f, 1f)
            )
        }
        val faceRegion = faceResult?.boundingBox?.let { box ->
            FrameAttachments.FaceRegion(
                centerX = (box.centerX().toFloat() / bitmap.width).coerceIn(0f, 1f),
                centerY = (box.centerY().toFloat() / bitmap.height).coerceIn(0f, 1f),
                width = (box.width().toFloat() / bitmap.width).coerceIn(.01f, 1f),
                height = (box.height().toFloat() / bitmap.height).coerceIn(.01f, 1f),
                confidence = .90f
            )
        }
        val composition = faceResult?.let {
            val box = it.boundingBox
            val faceX = box.centerX().toFloat() / bitmap.width
            val faceTop = box.top.toFloat() / bitmap.height
            val faceScale = sqrt(
                (box.width().toFloat() * box.height() / (bitmap.width * bitmap.height).toFloat())
                    .coerceAtLeast(0f)
            )
            val requiredLookRoom = if (it.headEulerAngleY >= 0f) 1f - faceX else faceX
            VisualEventMap.Composition(
                subjectScale = maxOf(faceScale, sqrt(coverage.coerceAtLeast(0f))).coerceIn(0f, 1f),
                headroomScore = (1f - abs(faceTop - .10f) / .32f).coerceIn(0f, 1f),
                lookRoomScore = (requiredLookRoom * 2.15f).coerceIn(0f, 1f),
                backgroundSimplicity = (1f - coverage * .58f).coerceIn(0f, 1f),
                contrastScore = (.58f + (mask?.second?.separation ?: 0f) * .38f).coerceIn(0f, 1f)
            )
        }
        val depth = when {
            mask?.first != null && pose != null -> SemanticMonocularDepthEstimator.fromPose(
                mask.first, pose.subjectDepth, pose.depthConfidence
            )
            mask?.first != null && composition != null -> SemanticMonocularDepthEstimator.estimate(
                mask.first,
                composition.subjectScale,
                face?.confidence ?: 0f
            )
            else -> null
        }
        val occlusion = when {
            coverage >= .91f -> .88f
            previousCoverage >= .14f && coverage <= .025f -> .82f
            else -> 0f
        }
        if (maskValues != null) previousMask = maskValues
        previousCoverage = coverage
        return Result(
            mask = mask?.first,
            depth = depth,
            maskTemporalIou = temporalIou,
            personCoverage = coverage,
            face = face,
            faceRegion = faceRegion,
            composition = composition,
            gestureConfidence = pose?.gestureConfidence ?: 0f,
            occlusionConfidence = occlusion,
            successfulModels = successfulModels,
            humanPresenceConfidence = maxOf(face?.confidence ?: 0f, pose?.depthConfidence ?: 0f),
            faceInferenceSucceeded = faceInference.isSuccess,
            gestureEvidenceAvailable = pose?.gestureEvidenceAvailable == true
        )
    }

    private fun poseResult(
        landmarks: List<com.google.mlkit.vision.pose.PoseLandmark>,
        width: Int,
        height: Int
    ): PoseResult? {
        fun landmark(type: Int) = landmarks.firstOrNull { it.landmarkType == type && it.inFrameLikelihood >= .45f }
        val shoulders = listOfNotNull(landmark(PoseLandmark.LEFT_SHOULDER), landmark(PoseLandmark.RIGHT_SHOULDER))
        val hips = listOfNotNull(landmark(PoseLandmark.LEFT_HIP), landmark(PoseLandmark.RIGHT_HIP))
        if (shoulders.size < 2 || hips.size < 2) return null
        val wrists = listOfNotNull(landmark(PoseLandmark.LEFT_WRIST), landmark(PoseLandmark.RIGHT_WRIST))
        val normalizedWrists = wrists.associate { it.landmarkType to (it.position.x / width to it.position.y / height) }
        val wristSupport = PoseWristEvidence.assess(setOf(PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST),
            normalizedWrists, previousWrists)
        val velocity = wristSupport.maximumDisplacement
        previousWrists = normalizedWrists
        val shoulderY = shoulders.map { it.position.y / height }.average().toFloat()
        val raisedArm = normalizedWrists.values.any { (_, y) -> y < shoulderY - .035f }
        val extension = wrists.maxOfOrNull { wrist ->
            shoulders.minOf { shoulder ->
                hypot(wrist.position.x - shoulder.position.x, wrist.position.y - shoulder.position.y)
            } / height
        } ?: 0f
        val gesture = maxOf(
            if (raisedArm) .78f else 0f,
            (velocity * 4.5f).coerceIn(0f, 1f),
            ((extension - .18f) * 2.5f).coerceIn(0f, .72f)
        )
        val torso = shoulders + hips
        val meanZ = torso.map { it.position3D.z / width }.average().toFloat()
        val spread = (torso.maxOf { it.position3D.z / width } - torso.minOf { it.position3D.z / width })
        return PoseResult(
            gestureConfidence = gesture,
            subjectDepth = (.5f + meanZ).coerceIn(.12f, .88f),
            depthConfidence = (.50f + spread.coerceIn(0f, .25f)).coerceAtMost(.68f),
            gestureEvidenceAvailable = wristSupport.complete
        )
    }

    override fun close() {
        segmenter.close()
        poseDetector.close()
        faceDetector.close()
    }

    private fun compactLuma(bitmap: Bitmap, width: Int, height: Int): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return try {
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            FloatArray(pixels.size) { index ->
                val colour = pixels[index]
                val red = colour shr 16 and 0xff
                val green = colour shr 8 and 0xff
                val blue = colour and 0xff
                (.299f * red + .587f * green + .114f * blue) / 255f
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    companion object {
        internal const val DETECTOR_MODE = SelfieSegmenterOptions.STREAM_MODE
        // ML Kit's raw matte is normally 256x256. Keeping that native grid avoids the previous
        // 192-wide resample, which visibly stairstepped the silhouette before GLES upscaling.
        internal const val MASK_WIDTH = 256
        internal const val MASK_HEIGHT = 256
        const val TIMEOUT_MS = 1_500L
        private const val MAXIMUM_CONSECUTIVE_POSE_FAILURES = 2
    }
}
