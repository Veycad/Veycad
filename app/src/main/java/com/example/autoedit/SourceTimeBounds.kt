package com.veycad.app

/**
 * Absolute decoder REQUEST targets are not decoded-frame observations. A request before the
 * first observed frame remains legal and never becomes evidence of a frame at that timestamp.
 * An unknown endpoint retains the previous duration cap, without asserting a zero origin.
 */
class SourceTimeBounds(
    /** Preserved declared/container duration, not proof of the usable content span. */
    val durationUs: Long,
    val firstVideoPtsUs: Long?,
    /** Independently measured exclusive raw endpoint, never origin + declared duration. */
    val videoEndPtsUs: Long? = null
) {
    val measuredRawEndUs: Long?
    val measuredContentSpanUs: Long?
    val requestEndUs: Long

    init {
        require(durationUs > 0 && (firstVideoPtsUs == null || firstVideoPtsUs >= 0))
        require(videoEndPtsUs == null || videoEndPtsUs > 0)
        require(firstVideoPtsUs == null || videoEndPtsUs == null || videoEndPtsUs > firstVideoPtsUs) {
            "Measured video endpoint must follow the first PTS"
        }
        measuredRawEndUs = videoEndPtsUs
        // Ordered nonnegative endpoints make this subtraction safe even at Long.MAX_VALUE.
        measuredContentSpanUs = if (firstVideoPtsUs != null && videoEndPtsUs != null) videoEndPtsUs - firstVideoPtsUs else null
        requestEndUs = measuredRawEndUs ?: durationUs
    }

    fun requireRequest(targetUs: Long, exclusiveBoundary: Boolean = false) {
        require(targetUs >= 0 && if (exclusiveBoundary) targetUs <= requestEndUs else targetUs < requestEndUs) {
            "Requested source time is outside the source"
        }
    }

    fun requireActualPts(actualPtsUs: Long) {
        val first = requireNotNull(firstVideoPtsUs) { "Measured first video PTS is unknown" }
        val end = requireNotNull(measuredRawEndUs) { "Measured video endpoint is unknown" }
        require(actualPtsUs >= first && actualPtsUs < end) { "Decoded video PTS is outside measured bounds" }
    }
}
