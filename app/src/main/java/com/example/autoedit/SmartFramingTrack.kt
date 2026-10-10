package com.veycad.app

import java.util.Collections
import kotlin.math.exp
import kotlin.math.hypot

enum class SmartFramingStatus { TRACKING, HOLDING, NO_PERSON, AMBIGUOUS, UNKNOWN }

/** Prepared in source PTS; seeking never changes state. */
internal class SmartFramingTrack private constructor(
    val points: List<Point>, val sourceWindows: List<VisualEventMap.UsableWindow>
) {
    data class Point(val sourceTimeUs: Long, val centerX: Float, val centerY: Float, val status: SmartFramingStatus) {
        init { require(sourceTimeUs >= 0 && centerX in 0f..1f && centerY in 0f..1f) }
    }

    fun sample(sourceTimeUs: Long): Point {
        require(sourceTimeUs >= 0)
        val window = sourceWindows.firstOrNull { sourceTimeUs >= it.startUs && sourceTimeUs < it.endUs }
            ?: return centered(sourceTimeUs, SmartFramingStatus.UNKNOWN)
        val found = points.binarySearchBy(sourceTimeUs) { it.sourceTimeUs }
        if (found >= 0) return points[found]
        val right = -found - 1
        val leftPoint = points.getOrNull(right - 1)?.takeIf { it.sourceTimeUs >= window.startUs }
            ?: return centered(sourceTimeUs, SmartFramingStatus.UNKNOWN)
        val rightPoint = points.getOrNull(right)?.takeIf { it.sourceTimeUs < window.endUs }
            ?: return leftPoint.copy(sourceTimeUs = sourceTimeUs)
        val fraction = (sourceTimeUs - leftPoint.sourceTimeUs).toDouble() /
            (rightPoint.sourceTimeUs - leftPoint.sourceTimeUs)
        return blend(leftPoint, rightPoint, sourceTimeUs, fraction.toFloat(), leftPoint.status)
    }

    companion object {
        const val GENERATOR_VERSION = 1
        private const val HOLD_US = 500_000L
        private const val BLEND_US = 300_000L

        /** Compact header restore contract. Copies both lists; no masks or decoder state are kept. */
        fun fromPoints(points: List<Point>, sourceWindows: List<VisualEventMap.UsableWindow>): SmartFramingTrack {
            require(points.zipWithNext().all { (a, b) -> a.sourceTimeUs < b.sourceTimeUs })
            require(sourceWindows.zipWithNext().all { (a, b) -> a.endUs < b.startUs })
            require(points.all { point -> sourceWindows.any { point.sourceTimeUs in it.startUs until it.endUs } })
            return SmartFramingTrack(Collections.unmodifiableList(points.toList()),
                Collections.unmodifiableList(sourceWindows.toList()))
        }

        fun build(analysis: MediaFrameVisualAnalyzer.Result, sourceWindows: List<VisualEventMap.UsableWindow>): SmartFramingTrack {
            require(analysis.observations.zipWithNext().all { (a, b) -> a.sourceTimeUs < b.sourceTimeUs })
            require(sourceWindows.all { it.endUs <= analysis.durationUs })
            val windows = ArrayList<VisualEventMap.UsableWindow>()
            for (window in sourceWindows.sortedBy { it.startUs }) {
                val last = windows.lastOrNull()
                if (last != null && window.startUs <= last.endUs) {
                    windows[windows.lastIndex] = VisualEventMap.UsableWindow(last.startUs, maxOf(last.endUs, window.endUs))
                } else windows += window
            }
            val attachments = analysis.attachments.frames.associateBy { it.sourceTimeUs }
            val points = windows.flatMap { window ->
                val entries = analysis.observations.filter { it.sourceTimeUs in window.startUs until window.endUs }
                    .map { observation -> evidence(observation, attachments[observation.sourceTimeUs]) }.toMutableList()
                rejectOutliers(entries)
                smooth(entries)
                prepareWindow(window, entries)
            }
            return fromPoints(points, windows)
        }

        private data class Evidence(val timeUs: Long, val status: SmartFramingStatus, val center: Point?, val forceCenter: Boolean = false)

        private fun evidence(observation: VisualEventMap.Observation, attachment: FrameAttachments?): Evidence {
            val t = observation.sourceTimeUs
            val count = observation.detectedFaceCount
            if (count == null || !observation.faceInferenceSucceeded) return Evidence(t, SmartFramingStatus.UNKNOWN, null, forceCenter = true)
            if (count > 1) return Evidence(t, SmartFramingStatus.AMBIGUOUS, null, forceCenter = true)
            val mask = attachment?.mask
            if (mask != null && ambiguous(mask)) return Evidence(t, SmartFramingStatus.AMBIGUOUS, null, forceCenter = true)
            val center = if (mask != null && mask.confidence >= .6f && attachment.subjectQuality >= .6f &&
                attachment.subjectOcclusion <= .35f) maskCenter(mask, t) else null
            return Evidence(t, if (center != null) SmartFramingStatus.TRACKING else
                if (count == 0) SmartFramingStatus.NO_PERSON else SmartFramingStatus.UNKNOWN, center)
        }

        /** Equal contribution of alpha-weighted foreground centroid and thresholded bounds midpoint. */
        private fun maskCenter(mask: FrameAttachments.Plane, t: Long): Point? {
            val foreground = mask.values.indices.filter { mask.values[it] >= .5f }
            val coverage = foreground.size.toFloat() / mask.values.size
            if (coverage !in .02f.. .85f) return null
            var weight = 0.0; var x = 0.0; var y = 0.0
            var minX = mask.width; var maxX = 0; var minY = mask.height; var maxY = 0
            for (i in foreground) {
                val px = i % mask.width; val py = i / mask.width
                val alpha = mask.values[i].coerceIn(0f, 1f).toDouble()
                weight += alpha; x += (px + .5) / mask.width * alpha; y += (py + .5) / mask.height * alpha
                minX = minOf(minX, px); maxX = maxOf(maxX, px); minY = minOf(minY, py); maxY = maxOf(maxY, py)
            }
            return Point(t, ((x / weight + (minX + maxX + 1.0) / (2 * mask.width)) / 2).toFloat(),
                ((y / weight + (minY + maxY + 1.0) / (2 * mask.height)) / 2).toFloat(), SmartFramingStatus.TRACKING)
        }

        private fun ambiguous(mask: FrameAttachments.Plane): Boolean {
            val foreground = mask.values.count { it >= .5f }
            if (foreground == 0) return false
            val visited = BooleanArray(mask.values.size)
            val queue = IntArray(mask.values.size)
            val centers = ArrayList<Pair<Float, Float>>()
            for (start in mask.values.indices) {
                if (visited[start] || mask.values[start] < .5f) continue
                var head = 0; var tail = 1; var x = 0.0; var y = 0.0
                queue[0] = start; visited[start] = true
                while (head < tail) {
                    val i = queue[head++]; val px = i % mask.width; val py = i / mask.width
                    x += (px + .5) / mask.width; y += (py + .5) / mask.height
                    fun add(n: Int) {
                        if (!visited[n] && mask.values[n] >= .5f) { visited[n] = true; queue[tail++] = n }
                    }
                    if (px > 0) add(i - 1)
                    if (px + 1 < mask.width) add(i + 1)
                    if (py > 0) add(i - mask.width)
                    if (py + 1 < mask.height) add(i + mask.width)
                }
                if (tail.toDouble() / foreground >= .15) centers += (x / tail).toFloat() to (y / tail).toFloat()
            }
            return centers.indices.any { a -> (a + 1 until centers.size).any { b ->
                hypot(centers[a].first - centers[b].first, centers[a].second - centers[b].second) >= .20f
            } }
        }

        private fun distance(a: Point, b: Point) = hypot(a.centerX - b.centerX, a.centerY - b.centerY)

        private fun rejectOutliers(entries: MutableList<Evidence>) {
            val reliable = entries.indices.filter { entries[it].center != null }
            val rejected = reliable.zipWithNext().zipWithNext().mapNotNull { (left, right) ->
                val (a, b) = left; val c = right.second
                val before = entries[a].center!!; val current = entries[b].center!!; val after = entries[c].center!!
                b.takeIf { distance(before, current) > .20f && distance(after, current) > .08f }
            }
            rejected.forEach { entries[it] = entries[it].copy(status = SmartFramingStatus.UNKNOWN, center = null) }
        }

        /** Forward/backward EMA with real source-PTS delta, reset at missing/ambiguous evidence. */
        private fun smooth(entries: MutableList<Evidence>) {
            var start = 0
            while (start < entries.size) {
                if (entries[start].center == null) { start++; continue }
                var end = start + 1
                while (end < entries.size && entries[end].center != null) end++
                for (i in start + 1 until end) {
                    val alpha = (1.0 - exp(-(entries[i].timeUs - entries[i - 1].timeUs).toDouble() / BLEND_US)).toFloat()
                    entries[i] = entries[i].copy(center = blend(entries[i - 1].center!!, entries[i].center!!,
                        entries[i].timeUs, alpha, SmartFramingStatus.TRACKING))
                }
                for (i in end - 2 downTo start) {
                    val alpha = (1.0 - exp(-(entries[i + 1].timeUs - entries[i].timeUs).toDouble() / BLEND_US)).toFloat()
                    entries[i] = entries[i].copy(center = blend(entries[i + 1].center!!, entries[i].center!!,
                        entries[i].timeUs, alpha, SmartFramingStatus.TRACKING))
                }
                start = end
            }
        }

        private fun prepareWindow(window: VisualEventMap.UsableWindow, entries: List<Evidence>): List<Point> {
            if (entries.isEmpty()) return listOf(centered(window.startUs, SmartFramingStatus.UNKNOWN))
            val knots = sortedSetOf(window.startUs, window.endUs - 1)
            val recoveries = HashMap<Int, Pair<Long, Point>>()
            val anchors = HashMap<Int, Point>()
            var lastStable: Point? = null
            var activeRecovery: Pair<Long, Point>? = null
            fun target(i: Int, t: Long): Point {
                val left = entries[i].center!!
                val right = entries.getOrNull(i + 1)?.center ?: return left.copy(sourceTimeUs = t)
                return blend(left, right, t, ((t - left.sourceTimeUs).toDouble() /
                    (right.sourceTimeUs - left.sourceTimeUs)).toFloat().coerceIn(0f, 1f), SmartFramingStatus.TRACKING)
            }
            fun evaluate(i: Int, t: Long): Point {
                if (i < 0) return centered(t, SmartFramingStatus.UNKNOWN)
                val entry = entries[i]
                if (entry.center != null) {
                    val target = target(i, t)
                    val recovery = recoveries[i] ?: return target
                    return blend(recovery.second, target, t, ((t - recovery.first).toFloat() / BLEND_US)
                        .coerceIn(0f, 1f), SmartFramingStatus.TRACKING)
                }
                if (entry.forceCenter) return centered(t, entry.status)
                val anchor = anchors[i] ?: return centered(t, entry.status)
                val elapsed = t - anchor.sourceTimeUs
                return blend(anchor, centered(t, entry.status), t,
                    ((elapsed - HOLD_US).toFloat() / BLEND_US).coerceIn(0f, 1f),
                    if (elapsed < HOLD_US + BLEND_US) SmartFramingStatus.HOLDING else entry.status)
            }
            for ((i, entry) in entries.withIndex()) {
                knots += entry.timeUs
                if (entry.timeUs > window.startUs) knots += entry.timeUs - 1
                if (entry.center != null) {
                    if (i > 0 && entries[i - 1].center == null) {
                        activeRecovery = entry.timeUs to evaluate(i - 1, entry.timeUs)
                        knots += entry.timeUs + BLEND_US
                    }
                    activeRecovery?.let { recoveries[i] = it }
                    lastStable = evaluate(i, entry.timeUs)
                } else {
                    activeRecovery = null
                    // Explicit ambiguity/unknown invalidates the person, including later holds.
                    if (entry.forceCenter) lastStable = null
                    lastStable?.let {
                        anchors[i] = it
                        knots += it.sourceTimeUs + HOLD_US
                        knots += it.sourceTimeUs + HOLD_US + BLEND_US
                    }
                }
            }
            return knots.filter { it in window.startUs until window.endUs }.map { t ->
                evaluate(entries.indexOfLast { it.timeUs <= t }, t)
            }
        }

        private fun centered(t: Long, status: SmartFramingStatus) = Point(t, .5f, .5f, status)

        private fun blend(a: Point, b: Point, t: Long, fraction: Float, status: SmartFramingStatus) = Point(t,
            (a.centerX + (b.centerX - a.centerX) * fraction).coerceIn(0f, 1f),
            (a.centerY + (b.centerY - a.centerY) * fraction).coerceIn(0f, 1f), status)
    }
}
