package com.veycad.app

import kotlin.math.abs

/** Numeric, local-only per-frame ML products. No face template or original bitmap is retained. */
data class FrameAttachments(
    val sourceTimeUs: Long,
    val mask: Plane? = null,
    val depth: Plane? = null,
    val flow: FlowPlane? = null,
    /** Editorial suitability of this exact frame for a retained person cutout. */
    val subjectQuality: Float = .5f,
    /** Explicit rejection signal: hands/lens occlusion must never become a retained portrait. */
    val subjectOcclusion: Float = 0f,
    /** Stability of the mask against the preceding semantic sample. */
    val maskTemporalIou: Float = 0f,
    /** Normalized bitmap-space face region used to keep authored layers readable over a face. */
    val faceRegion: FaceRegion? = null,
    /** Second sparse matte and PTS fraction; blended only while uploading the GL texture. */
    val maskBlendTarget: Plane? = null,
    val maskBlendProgress: Float = 0f,
    /** True only for inferred physical opacity, not a class-confidence segmentation mask. */
    val maskIsOpacity: Boolean = false
) {
    init {
        require(sourceTimeUs >= 0L)
        require(mask != null || depth != null || flow != null)
        require(subjectQuality in 0f..1f && subjectOcclusion in 0f..1f && maskTemporalIou in 0f..1f)
        require(maskBlendProgress in 0f..1f)
        require(!maskIsOpacity || mask != null) { "Opacity semantics require a mask plane" }
    }

    data class Plane(val width: Int, val height: Int, val values: FloatArray, val confidence: Float) {
        constructor(width: Int, height: Int, values: List<Float>, confidence: Float) :
            this(width, height, values.toFloatArray(), confidence)

        init {
            require(width > 0 && height > 0 && values.size == width * height)
            require(values.all { it.isFinite() })
            require(confidence in 0f..1f)
        }

        // Kotlin arrays use identity equality by default. Planes are value objects in graph tests
        // and attachment interpolation, so retain the former List<Float> value semantics.
        override fun equals(other: Any?): Boolean = other is Plane &&
            width == other.width && height == other.height && confidence == other.confidence &&
            values.contentEquals(other.values)

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + values.contentHashCode()
            return 31 * result + confidence.hashCode()
        }
    }

    data class FlowPlane(val width: Int, val height: Int, val vectors: List<Float>, val confidence: Float) {
        init {
            require(width > 0 && height > 0 && vectors.size == width * height * 2)
            require(vectors.all { it.isFinite() })
            require(confidence in 0f..1f)
        }
    }

    data class FaceRegion(
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
        val confidence: Float
    ) {
        init {
            require(listOf(centerX, centerY, width, height, confidence).all { it in 0f..1f })
            require(width > 0f && height > 0f)
        }
    }
}

/** PTS-indexed attachment timeline consumed identically by preview and export frame schedules. */
data class FrameAttachmentTimeline(val frames: List<FrameAttachments> = emptyList(),
    val maskRefinements: List<FrameAttachments> = emptyList()) {
    init { require(frames.zipWithNext().all { (left, right) -> right.sourceTimeUs > left.sourceTimeUs }) }
    init {
        require(maskRefinements.size <= 90)
        require(maskRefinements.all { it.mask != null })
        require(maskRefinements.zipWithNext().all { (a, b) -> a.sourceTimeUs < b.sourceTimeUs })
    }

    fun nearest(sourceTimeUs: Long, toleranceUs: Long = DEFAULT_TOLERANCE_US): FrameAttachments? =
        frames.minByOrNull { abs(it.sourceTimeUs - sourceTimeUs) }
            ?.takeIf { abs(it.sourceTimeUs - sourceTimeUs) <= toleranceUs }

    /**
     * PTS-exact attachment used by the renderer. Semantic inference stays sparse, but a moving
     * decoder texture must not carry one mask unchanged for 250 ms. Interpolating adjacent planes
     * keeps the matte clock continuous; outside a bracket we retain the bounded nearest contract.
     */
    fun interpolated(sourceTimeUs: Long): FrameAttachments? {
        val base = interpolatedBase(sourceTimeUs) ?: return null
        if (maskRefinements.isEmpty() || sourceTimeUs < maskRefinements.first().sourceTimeUs ||
            sourceTimeUs > maskRefinements.last().sourceTimeUs) return base
        val refined = FrameAttachmentTimeline(maskRefinements).interpolatedBase(sourceTimeUs) ?: return base
        // Keep depth, flow, pose and editorial quality on their ORIGINAL interpolation
        // clock. Inserting dense masks into frames would silently resample those fields.
        return base.copy(mask = refined.mask, maskBlendTarget = refined.maskBlendTarget,
            maskBlendProgress = refined.maskBlendProgress, maskIsOpacity = refined.maskIsOpacity)
    }

    private fun interpolatedBase(sourceTimeUs: Long): FrameAttachments? {
        if (frames.isEmpty()) return null
        val insertion = frames.binarySearchBy(sourceTimeUs) { it.sourceTimeUs }
        if (insertion >= 0) return frames[insertion]
        val rightIndex = -insertion - 1
        if (rightIndex == 0 || rightIndex >= frames.size) return nearest(sourceTimeUs)
        val left = frames[rightIndex - 1]
        val right = frames[rightIndex]
        val gapUs = right.sourceTimeUs - left.sourceTimeUs
        if (gapUs > MAXIMUM_INTERPOLATION_GAP_US) return nearest(sourceTimeUs)
        val progress = ((sourceTimeUs - left.sourceTimeUs).toFloat() / gapUs)
            .coerceIn(0f, 1f)
        fun scalar(a: Float, b: Float) = a + (b - a) * progress
        val closest = if (progress < .5f) left else right
        val canBlendMask = left.mask != null && right.mask != null &&
            left.maskIsOpacity == right.maskIsOpacity &&
            left.mask.width == right.mask.width && left.mask.height == right.mask.height
        // Reuse the immutable value arrays. Copying them here for every planned frame exhausted the
        // 192 MB Android heap before encoding started.
        // Plane validates every value at construction. Re-copying the same immutable array for
        // every 60 fps render point turned a 52-mask timeline into tens of millions of redundant
        // checks. The shader already blends left -> right with maskBlendProgress, so retain the
        // validated left plane and avoid changing any mask pixels here.
        val mask = if (canBlendMask) left.mask else closest.mask
        fun face(
            a: FrameAttachments.FaceRegion?,
            b: FrameAttachments.FaceRegion?
        ): FrameAttachments.FaceRegion? {
            if (a == null || b == null) return if (progress < .5f) a ?: b else b ?: a
            return FrameAttachments.FaceRegion(
                scalar(a.centerX, b.centerX), scalar(a.centerY, b.centerY),
                scalar(a.width, b.width), scalar(a.height, b.height),
                scalar(a.confidence, b.confidence)
            )
        }
        return FrameAttachments(
            sourceTimeUs = sourceTimeUs,
            mask = mask,
            depth = closest.depth,
            flow = closest.flow,
            subjectQuality = scalar(left.subjectQuality, right.subjectQuality),
            subjectOcclusion = scalar(left.subjectOcclusion, right.subjectOcclusion),
            maskTemporalIou = scalar(left.maskTemporalIou, right.maskTemporalIou),
            faceRegion = face(left.faceRegion, right.faceRegion),
            maskBlendTarget = if (canBlendMask) right.mask else null,
            maskBlendProgress = if (canBlendMask) progress else 0f,
            maskIsOpacity = closest.maskIsOpacity
        )
    }

    fun coverage(): Coverage = Coverage(
        frames.count { it.mask != null }, frames.count { it.depth != null }, frames.count { it.flow != null }, frames.size
    )

    data class Coverage(val masks: Int, val depths: Int, val flows: Int, val totalFrames: Int) {
        val completeFrames: Int get() = minOf(masks, depths, flows)
    }

    // Offline observations are normally 250 ms apart. Half an interval plus decoder timestamp
    // jitter keeps every rendered frame attached to the nearest local ML sample without
    // accidentally carrying a mask across a real edit.
    companion object {
        const val DEFAULT_TOLERANCE_US = 175_000L
        private const val MAXIMUM_INTERPOLATION_GAP_US = 600_000L
    }
}
