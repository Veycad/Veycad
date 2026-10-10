package com.veycad.app

/**
 * Local single-frame semantic depth for editorial parallax. This is deliberately not labelled a
 * learned metric-depth model: it estimates near subject versus far background from a person matte
 * and composition scale, which is sufficient to drive restrained depth-aware transitions.
 */
object SemanticMonocularDepthEstimator {
    fun fromPose(mask: FrameAttachments.Plane, subjectDepth: Float, confidence: Float): FrameAttachments.Plane {
        require(subjectDepth in 0f..1f && confidence in 0f..1f)
        return mask.copy(values = FloatArray(mask.values.size) { index ->
            1f + (subjectDepth - 1f) * mask.values[index]
        }, confidence = confidence)
    }

    fun estimate(
        mask: FrameAttachments.Plane,
        subjectScale: Float,
        faceConfidence: Float
    ): FrameAttachments.Plane {
        require(subjectScale in 0f..1f && faceConfidence in 0f..1f)
        val subjectDepth = (.72f - subjectScale * .55f).coerceIn(.16f, .68f)
        val values = FloatArray(mask.values.size) { index ->
            val person = mask.values[index]
            val y = index / mask.width.toFloat() / mask.height
            val backgroundDepth = (.84f + y * .10f).coerceAtMost(.94f)
            (backgroundDepth + (subjectDepth - backgroundDepth) * person.coerceIn(0f, 1f))
                .coerceIn(0f, 1f)
        }
        val confidence = (mask.confidence * (.58f + faceConfidence * .28f)).coerceIn(0f, .84f)
        return FrameAttachments.Plane(mask.width, mask.height, values, confidence)
    }
}
