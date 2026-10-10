package com.veycad.app

/** A single rounding of an absolute output-frame index; negative indices support extension. */
fun frameTimeUs(frame: Long, fps: Int): Long {
    require(fps == 30 || fps == 60) { "Editable exports require 30 or 60 FPS" }
    val absolute = if (frame < 0) Math.negateExact(frame) else frame
    require(absolute <= Int.MAX_VALUE) { "Frame index exceeds shared clock bounds" }
    val time = ProjectClock(fps).timeUs(absolute.toInt())
    return if (frame < 0) -time else time
}

data class FrameRange(val start: Long, val endExclusive: Long) {
    val count: Long = Math.subtractExact(endExclusive, start)
    init { require(count in 1..Int.MAX_VALUE.toLong()) { "A clip must contain at least one output frame" } }
}

data class TimelineRange(val startUs: Long, val endUs: Long) {
    init { require(startUs >= 0L && endUs > startUs) }
}

/** N output frames have N+1 original source timestamps, including the exclusive end. */
class ClipTimeMapping(val fps: Int, val sourceDurationUs: Long, sourceUsByFrame: LongArray,
    val frameOrigin: Long = 0L) {
    private val samples = sourceUsByFrame.copyOf()
    val sourceUsByFrame: LongArray get() = samples.copyOf()
    val frameCount: Long get() = samples.lastIndex.toLong()

    init {
        require(fps == 30 || fps == 60)
        require(sourceDurationUs > 0L && samples.size >= 2)
        require(samples.all { it in 0..sourceDurationUs })
        require((1 until samples.size).all { samples[it] >= samples[it - 1] })
    }

    /** Extrapolation stays unclamped so invalid extension can be rejected by the editor. */
    fun sourceTimeUs(frame: Long): Long {
        val local = Math.subtractExact(frame, frameOrigin)
        return when {
            local < 0L -> Math.addExact(samples.first(), Math.multiplyExact(local, samples[1] - samples[0]))
            local <= frameCount -> samples[local.toInt()]
            else -> Math.addExact(samples.last(), Math.multiplyExact(local - frameCount,
                samples.last() - samples[samples.lastIndex - 1]))
        }
    }

    internal fun validateVisible(visible: FrameRange) {
        require(sourceTimeUs(visible.start) in 0..sourceDurationUs &&
            sourceTimeUs(visible.endExclusive) in 0..sourceDurationUs &&
            sourceTimeUs(visible.endExclusive - 1) < sourceDurationUs) { "Visible clip exceeds its source" }
    }

    /** Materialize before slicing: sparse endpoint interpolation can change retained PTS by 1 µs. */
    fun toSourceTimeMap(visible: FrameRange): SourceTimeMap {
        validateVisible(visible)
        require(visible.count < Int.MAX_VALUE) { "Dense map needs an end-exclusive point" }
        return SourceTimeMap((0..visible.count.toInt()).map { frame ->
            SourceTimeMap.Point(frame, sourceTimeUs(Math.addExact(visible.start, frame.toLong())))
        })
    }

    companion object {
        fun fromShared(fps: Int, sourceDurationUs: Long, map: SourceTimeMap, frameOrigin: Long = 0L): ClipTimeMapping {
            val count = map.points.last().localFrame
            require(count < Int.MAX_VALUE) { "Dense map needs an end-exclusive point" }
            return ClipTimeMapping(fps, sourceDurationUs, LongArray(count + 1) { map.sample(it) }, frameOrigin)
        }
    }

    override fun equals(other: Any?): Boolean = other is ClipTimeMapping && fps == other.fps &&
        sourceDurationUs == other.sourceDurationUs && frameOrigin == other.frameOrigin && samples.contentEquals(other.samples)
    override fun hashCode(): Int = 31 * (31 * (31 * fps + sourceDurationUs.hashCode()) + frameOrigin.hashCode()) + samples.contentHashCode()
}

/** Output bounds are cumulative frame counts, independent of legacy millisecond rounding. */
data class EditableClipTiming(
    val clipId: String,
    val timeMap: ClipTimeMapping,
    val visible: FrameRange,
    val startFrame: Long,
    val endFrameExclusive: Long,
    val originFrameCount: Long = timeMap.frameCount
) {
    init {
        require(clipId.isNotBlank() && startFrame >= 0L)
        require(Math.subtractExact(endFrameExclusive, startFrame) == visible.count)
        require(originFrameCount in 1..Int.MAX_VALUE.toLong())
        timeMap.validateVisible(visible)
    }
}

data class EditableFrameTiming(val fps: Int, val clips: List<EditableClipTiming>) {
    val frameCount: Long get() = clips.last().endFrameExclusive
    init {
        require(fps == 30 || fps == 60)
        require(clips.isNotEmpty() && clips.first().startFrame == 0L)
        require(clips.map { it.clipId }.distinct().size == clips.size)
        require(clips.all { it.timeMap.fps == fps })
        require(clips.zipWithNext().all { (left, right) -> left.endFrameExclusive == right.startFrame })
        require(frameCount in 1..Int.MAX_VALUE.toLong())
    }
}
