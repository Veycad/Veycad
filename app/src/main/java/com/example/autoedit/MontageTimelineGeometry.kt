package com.veycad.app

import java.util.Collections
import kotlin.math.floor

/**
 * Pure output-frame geometry. Extents are visible output counts in current clip order, never
 * source indices, source PTS, milliseconds, or clip-local originalFrameOffset values.
 * Building and hit testing are O(clips), with no source maps or per-frame allocations.
 */
internal object MontageTimelineGeometry {
    data class Extent(val clipId: String, val visibleFrames: Long)
    enum class Edge { START, END }
    enum class Mode { BODY, HANDLES, CUTS }
    sealed interface Target {
        data class Body(val clipId: String) : Target
        data class Handle(val clipId: String, val edge: Edge) : Target
        data class Cut(val incomingClipId: String) : Target
    }
    sealed interface Hit {
        data object None : Hit
        data class Found(val target: Target) : Hit
        data class Ambiguous(val targets: List<Target>) : Hit
    }
    /** Half-open viewport pixel interval. Double retains subpixel zones at very small zoom. */
    data class PixelRange(val start: Double, val endExclusive: Double) {
        init { require(start.isFinite() && endExclusive.isFinite() && start >= 0.0 && endExclusive > start) }
        internal fun contains(x: Double) = x >= start && x < endExclusive
    }
    /** Exact Long bounds survive clipping; absent zones mean the target is not visible. */
    data class Placement(val clipId: String, val startFrame: Long, val endFrameExclusive: Long,
        val body: PixelRange?, val startHandle: PixelRange?, val endHandle: PixelRange?,
        val incomingCut: PixelRange?, val nextBoundaryIncomingClipId: String?)
    class Layout internal constructor(val clips: List<Placement>, val totalFrames: Long,
        val viewportWidthPx: Float, val content: PixelRange?)

    /** floor((x + scroll) / scale), clamped at output frame zero. Overflow is rejected. */
    fun frameAt(xPx: Float, scrollPx: Float, pixelsPerFrame: Float): Long =
        frameAt(xPx, scrollPx, pixelsPerFrame, 0L)

    /**
     * Pixel offsets relative to an exact frame origin, for a viewport far along a large timeline.
     * Float inputs cannot distinguish adjacent frames once their pixel spacing is below the Float
     * ULP. Keep offsets near this Long origin; exact +/-1 actions must operate on Long positions,
     * not round-trip through pixels. This seam does not make tiny screen distances distinguishable.
     */
    fun frameAt(xPx: Float, scrollPx: Float, pixelsPerFrame: Float, windowOriginFrame: Long): Long {
        require(xPx.isFinite() && scrollPx.isFinite())
        require(pixelsPerFrame.isFinite() && pixelsPerFrame > 0f)
        require(windowOriginFrame >= 0L)
        val offset = floor((xPx.toDouble() + scrollPx.toDouble()) / pixelsPerFrame.toDouble())
        require(offset.isFinite())
        // Every possible nonnegative Long origin is before an offset this far to the left.
        if (offset <= Long.MIN_VALUE.toDouble()) return 0L
        if (windowOriginFrame == 0L && offset <= 0.0) return 0L
        // Long.MAX_VALUE.toDouble() rounds to 2^63; conversion at that value would saturate.
        require(offset < Long.MAX_VALUE.toDouble()) { "Pixel coordinate exceeds Long frame bounds" }
        return Math.addExact(windowOriginFrame, offset.toLong()).coerceAtLeast(0L)
    }

    /**
     * UI supplies minimumTouchWidthPx after converting its >=48dp target width to pixels.
     * Geometry has no density information. Body ranges, viewport, and content are half-open.
     * Handles/cuts are centered on an edge, then clipped to viewport AND content. An edge center
     * outside [0, viewportWidthPx) has no target, even when its enlarged zone would reach in.
     * The first current clip has no incoming cut; the last has no next boundary move target.
     * windowOriginFrame and scrollPx use the same local coordinate system as frameAt's overload.
     */
    fun layout(extents: List<Extent>, viewportWidthPx: Float, scrollPx: Float,
        pixelsPerFrame: Float, minimumTouchWidthPx: Float, windowOriginFrame: Long = 0L): Layout {
        require(viewportWidthPx.isFinite() && viewportWidthPx > 0f)
        require(scrollPx.isFinite() && pixelsPerFrame.isFinite() && pixelsPerFrame > 0f)
        require(minimumTouchWidthPx.isFinite() && minimumTouchWidthPx > 0f)
        require(windowOriginFrame >= 0L)
        val input = ArrayList(extents)
        val ids = HashSet<String>()
        val ends = LongArray(input.size)
        var total = 0L
        input.forEachIndexed { index, extent ->
            require(extent.clipId.isNotBlank() && ids.add(extent.clipId)) { "Clip IDs must be distinct" }
            require(extent.visibleFrames > 0L)
            total = Math.addExact(total, extent.visibleFrames)
            ends[index] = total
        }
        fun x(frame: Long): Double {
            // Subtract exact integers before converting; late adjacent frames remain adjacent.
            val position = (frame - windowOriginFrame).toDouble() * pixelsPerFrame.toDouble() - scrollPx.toDouble()
            require(position.isFinite()) { "Frame coordinate is not representable" }
            return position
        }
        val contentStart = x(0L)
        val contentEnd = x(total)
        val viewportEnd = viewportWidthPx.toDouble()
        fun clipped(left: Double, right: Double): PixelRange? {
            val start = maxOf(0.0, contentStart, left)
            val end = minOf(viewportEnd, contentEnd, right)
            return if (end > start) PixelRange(start, end) else null
        }
        val halfTouch = minimumTouchWidthPx.toDouble() / 2.0
        fun handle(center: Double): PixelRange? =
            if (center >= 0.0 && center < viewportEnd) clipped(center - halfTouch, center + halfTouch) else null
        val placements = ArrayList<Placement>(input.size)
        var start = 0L
        input.forEachIndexed { index, extent ->
            val end = ends[index]
            val startX = x(start)
            val endX = x(end)
            val startHandle = handle(startX)
            placements += Placement(extent.clipId, start, end, clipped(startX, endX),
                startHandle, handle(endX), startHandle.takeIf { index > 0 }, input.getOrNull(index + 1)?.clipId)
            start = end
        }
        return Layout(Collections.unmodifiableList(placements), total, viewportWidthPx, clipped(contentStart, contentEnd))
    }

    /**
     * Body mode selects the half-open clip body, including the incoming body at an exact cut.
     * Handle/cut modes never fall back to reordering. For overlapping targets the UI must select
     * a clip and START/END, or present Ambiguous.targets. Selection filters apply only to HANDLES.
     * Nonfinite pointer coordinates and positions outside content/viewport produce None.
     */
    fun hitTest(layout: Layout, xPx: Float, mode: Mode, selectedClipId: String? = null,
        selectedEdge: Edge? = null): Hit {
        if (!xPx.isFinite()) return Hit.None
        val x = xPx.toDouble()
        if (layout.content?.contains(x) != true) return Hit.None
        val targets = ArrayList<Target>()
        for (clip in layout.clips) {
            when (mode) {
                Mode.BODY -> if (clip.body?.contains(x) == true) return Hit.Found(Target.Body(clip.clipId))
                Mode.CUTS -> if (clip.incomingCut?.contains(x) == true) targets += Target.Cut(clip.clipId)
                Mode.HANDLES -> if (selectedClipId == null || clip.clipId == selectedClipId) {
                    if ((selectedEdge == null || selectedEdge == Edge.START) && clip.startHandle?.contains(x) == true)
                        targets += Target.Handle(clip.clipId, Edge.START)
                    if ((selectedEdge == null || selectedEdge == Edge.END) && clip.endHandle?.contains(x) == true)
                        targets += Target.Handle(clip.clipId, Edge.END)
                }
            }
        }
        return when (targets.size) {
            0 -> Hit.None
            1 -> Hit.Found(targets.single())
            else -> Hit.Ambiguous(Collections.unmodifiableList(targets))
        }
    }
}
