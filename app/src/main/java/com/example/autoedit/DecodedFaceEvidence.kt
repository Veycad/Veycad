package com.veycad.app

/** Evidence from one actual decoded image, never a confidence carried from an earlier image. */
data class DecodedFaceEvidence(
    val outputTimeUs: Long,
    val sourceIndex: Int,
    val clipIndex: Int,
    /** null means inference is unavailable/failed, not a successful empty detection. */
    val confidence: Float?
) {
    init {
        require(outputTimeUs >= 0L && sourceIndex >= 0 && clipIndex >= 0)
        require(confidence == null || confidence in 0f..1f)
    }

    fun isFreshFor(timeUs: Long, source: Int?, clip: Int?): Boolean =
        confidence != null && outputTimeUs == timeUs && sourceIndex == source && clipIndex == clip

    companion object {
        const val METHOD = "decoded-face-exact-pts-v1"

        /** Every requested QA scale must finish; a failed scale is not an empty face result. */
        internal fun fromAttempts(timeUs: Long, source: Int, clip: Int, attempts: List<Float?>) =
            DecodedFaceEvidence(timeUs, source, clip,
                if (attempts.isEmpty() || attempts.any { it == null || it !in 0f..1f }) null
                else attempts.filterNotNull().maxOrNull())
    }
}
