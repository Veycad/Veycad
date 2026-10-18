package com.veycad.app

import java.util.concurrent.CancellationException

/** Small control policy; no timeline, renderer, player, or Android ownership. */
internal class PreviewPlaybackPolicy {
    val seeks = PreviewSeekQueue()
    private var wanted = false
    private var scrubbing = false
    private var resumeAfterScrub = false
    private var exact: PreviewGeneration? = null
    private var audio: PreviewGeneration? = null

    /** The caller's hook may yield to invalidation. Recheck the SAME captured operation on return.
     * Do not hold the policy monitor across external code: main must remain able to invalidate.
     */
    fun checkCurrentOperation(generation: PreviewGeneration, checkCancelled: () -> Unit) {
        if (!seeks.isCurrent(generation)) throw CancellationException("Obsolete preview operation")
        checkCancelled()
        if (!seeks.isCurrent(generation)) throw CancellationException("Obsolete preview operation")
    }

    @Synchronized fun seek(timeUs: Long): PreviewGeneration {
        exact = null; audio = null
        return seeks.submit(timeUs)
    }
    @Synchronized fun play() { if (scrubbing) resumeAfterScrub = true else wanted = true }
    @Synchronized fun pause() { wanted = false; resumeAfterScrub = false }
    @Synchronized fun reset() {
        wanted = false; scrubbing = false; resumeAfterScrub = false; exact = null; audio = null
    }
    @Synchronized fun pauseIfCurrent(generation: PreviewGeneration): Boolean {
        if (!seeks.isCurrent(generation)) return false
        wanted = false; resumeAfterScrub = false; return true
    }
    @Synchronized fun beginScrub() {
        if (!scrubbing) resumeAfterScrub = wanted
        scrubbing = true; wanted = false
    }
    @Synchronized fun endScrub() {
        if (scrubbing) wanted = resumeAfterScrub
        scrubbing = false; resumeAfterScrub = false
    }
    @Synchronized fun frameReady(generation: PreviewGeneration): Boolean {
        if (!seeks.isCurrent(generation)) return false
        exact = generation; return true
    }
    @Synchronized fun audioReady(generation: PreviewGeneration): Boolean {
        if (!seeks.isCurrent(generation)) return false
        audio = generation; return true
    }
    @Synchronized fun canStart(generation: PreviewGeneration): Boolean =
        seeks.isCurrent(generation) && wanted && !scrubbing && exact == generation && audio == generation
}

internal object PreviewFrameSelection {
    /** Binary search in the SAME saved plan; ties select the earlier frame. */
    fun nearest(plan: HighQualityFramePlan.Plan, rawUs: Long): HighQualityFramePlan.Frame {
        val frames = plan.frames
        val time = rawUs.coerceIn(0, plan.durationUs)
        val match = frames.binarySearchBy(time) { it.outputTimeUs }
        if (match >= 0) return frames[match]
        val right = -match - 1
        if (right == 0) return frames.first()
        if (right == frames.size) return frames.last()
        val left = frames[right - 1]
        return if (time - left.outputTimeUs <= frames[right].outputTimeUs - time) left else frames[right]
    }
}

internal object PreviewMusicPosition {
    fun seekPosition(outputUs: Long, durationUs: Long): Long {
        require(outputUs >= 0 && durationUs > 0)
        return outputUs % durationUs
    }
    /** Unwrap the modulo player position to the cycle nearest the monotonic global prediction. */
    fun globalPosition(positionUs: Long?, durationUs: Long, predictionUs: Long): Long {
        require(durationUs > 0 && predictionUs >= 0)
        if (positionUs == null) return predictionUs
        val position = positionUs.coerceAtLeast(0) % durationUs
        val cycle = predictionUs / durationUs
        return listOf((cycle - 1).coerceAtLeast(0), cycle, cycle + 1)
            .map { it * durationUs + position }
            .minByOrNull { kotlin.math.abs(it - predictionUs) }!!
    }
}
