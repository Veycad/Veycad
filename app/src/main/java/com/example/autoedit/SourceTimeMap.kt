package com.veycad.app

import java.math.BigInteger
import java.util.Collections

/**
 * Source PTS at local frame boundaries, including 0 and the end-exclusive clip boundary.
 * Holds are allowed. Slicing preserves saved interior points and samples both boundaries;
 * extensions preserve all existing points and continue only the nearest edge segment.
 */
class SourceTimeMap(points: List<Point>) {
    val points: List<Point> = Collections.unmodifiableList(ArrayList(points))
    data class Point(val localFrame: Int, val sourceTimeUs: Long)

    init {
        require(this.points.size >= 2 && this.points.first().localFrame == 0)
        require(this.points.all { it.localFrame >= 0 && it.sourceTimeUs >= 0 })
        require(this.points.zipWithNext().all { (a, b) ->
            b.localFrame > a.localFrame && b.sourceTimeUs >= a.sourceTimeUs
        })
    }

    fun sample(localFrame: Int): Long {
        require(localFrame in 0..points.last().localFrame)
        val index = points.binarySearchBy(localFrame) { it.localFrame }
        if (index >= 0) return points[index].sourceTimeUs
        val right = -index - 1
        return interpolate(points[right - 1], points[right], localFrame)
    }

    fun slice(from: Int, until: Int): SourceTimeMap {
        require(from >= 0 && until > from && until <= points.last().localFrame)
        return SourceTimeMap(listOf(Point(0, sample(from))) +
            points.filter { it.localFrame > from && it.localFrame < until }
                .map { it.copy(localFrame = it.localFrame - from) } +
            Point(until - from, sample(until)))
    }

    fun extendLeft(frames: Int, minimumUs: Long): SourceTimeMap {
        require(frames >= 0 && minimumUs >= 0 && points.first().sourceTimeUs >= minimumUs)
        require(frames <= Int.MAX_VALUE - points.last().localFrame)
        if (frames == 0) return this
        val source = interpolate(points[0], points[1], -frames)
        require(source >= minimumUs)
        return SourceTimeMap(listOf(Point(0, source)) + points.map { it.copy(localFrame = it.localFrame + frames) })
    }

    fun extendRight(frames: Int, maximumUs: Long): SourceTimeMap {
        require(frames >= 0 && maximumUs >= points.last().sourceTimeUs)
        require(frames <= Int.MAX_VALUE - points.last().localFrame)
        if (frames == 0) return this
        val lastFrame = points.last().localFrame + frames
        val source = interpolate(points[points.lastIndex - 1], points.last(), lastFrame)
        require(source <= maximumUs)
        return SourceTimeMap(points + Point(lastFrame, source))
    }

    private fun interpolate(a: Point, b: Point, frame: Int): Long {
        // Exact rational arithmetic avoids precision loss near Long.MAX_VALUE and overflow
        // in deltaPTS * deltaFrames. Round the absolute result to the nearest microsecond.
        val denominator = BigInteger.valueOf((b.localFrame - a.localFrame).toLong())
        val numerator = BigInteger.valueOf(a.sourceTimeUs).multiply(denominator) +
            BigInteger.valueOf(b.sourceTimeUs - a.sourceTimeUs)
                .multiply(BigInteger.valueOf(frame.toLong() - a.localFrame))
        require(numerator.signum() >= 0) { "Source time precedes the source" }
        val rounded = (numerator + denominator / BigInteger.valueOf(2L)) / denominator
        require(rounded <= BigInteger.valueOf(Long.MAX_VALUE)) { "Source time overflow" }
        return rounded.toLong()
    }

    override fun equals(other: Any?): Boolean = other is SourceTimeMap && points == other.points
    override fun hashCode(): Int = points.hashCode()
    override fun toString(): String = "SourceTimeMap(points=$points)"
}
