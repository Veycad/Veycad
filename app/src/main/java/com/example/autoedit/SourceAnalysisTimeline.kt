package com.example.autoedit

/** Analysis observations use actual video timestamps, never a container-only tail clock. */
internal object SourceAnalysisTimeline {
    /**
     * An exact target comes from the sample index, not the declared track duration.
     * Reordered video can contain real presentation timestamps at or beyond that duration.
     * The decoder must still prove every requested PTS exactly and fail on missing targets.
     */
    fun validateDecodeTargets(targetsUs: LongArray, declaredDurationUs: Long, requireExactPts: Boolean) {
        require(declaredDurationUs > 0L) { "Declared video duration must be positive" }
        require(targetsUs.isNotEmpty() && targetsUs.first() >= 0L) {
            "Decode targets must be nonempty and nonnegative"
        }
        require(targetsUs.toList().zipWithNext().all { (a, b) -> a < b }) {
            "Decode targets must be strictly increasing"
        }
        require(requireExactPts || targetsUs.last() < declaredDurationUs) {
            "Approximate decode target ${targetsUs.last()} must precede declared video duration $declaredDurationUs"
        }
    }

    fun requireDecodedPts(expectedUs: Long, actualUs: Long) {
        check(expectedUs >= 0L && actualUs == expectedUs) {
            "Decoded frame PTS $actualUs does not match indexed analysis PTS $expectedUs"
        }
    }

    fun align(planned: LongArray, decodedPts: LongArray): LongArray {
        require(planned.isNotEmpty() && planned.first() >= 0L)
        require(planned.toList().zipWithNext().all { (a, b) -> a < b })
        require(decodedPts.isNotEmpty() && decodedPts.first() >= 0L)
        require(decodedPts.toList().zipWithNext().all { (a, b) -> a < b })
        // A container can extend beyond the final video sample (e.g. an audio tail).
        // Select the final *real* sample once; do not relabel it at every late request.
        return planned.map { time ->
            val found = decodedPts.binarySearch(time)
            val index = if (found >= 0) found else (-found - 1).coerceAtMost(decodedPts.lastIndex)
            decodedPts[index]
        }.distinct().toLongArray()
    }
}
