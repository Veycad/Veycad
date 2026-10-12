package com.veycad.app

import java.math.BigInteger

/**
 * The selected authored curve, in original output-phase coordinates. The map is immutable;
 * slip is a separate translation so hidden samples need not be copied or rounded again.
 * Only an original/reset selection permits extrapolation beyond the retained curve.
 */
@ConsistentCopyVisibility
data class HybridSourceBacking internal constructor(val assetId: String, val map: SourceTimeMap,
    val phaseStart: Int, val slipUs: Long, val selectedRevisionId: Long, val automatic: Boolean) {
    init { require(assetId.isNotBlank() && selectedRevisionId >= 0) }

    internal fun sample(phaseFrame: Long): Long {
        val frame = phaseFrame - phaseStart
        val time = if (frame in 0..map.points.last().localFrame.toLong()) {
            Math.addExact(map.sample(frame.toInt()), slipUs)
        } else {
            require(automatic) { "Сохранённая карта скрытых кадров недоступна; расширение клипа невозможно" }
            val points = map.points
            val (a, b) = if (frame < 0) points[0] to points[1] else points[points.lastIndex - 1] to points.last()
            require(b.sourceTimeUs > a.sourceTimeUs) { "Нельзя продолжить стоп-кадр за сохранённое окно" }
            val denominator = BigInteger.valueOf((b.localFrame - a.localFrame).toLong())
            val numerator = (BigInteger.valueOf(a.sourceTimeUs) + BigInteger.valueOf(slipUs)) * denominator +
                BigInteger.valueOf(b.sourceTimeUs - a.sourceTimeUs) * BigInteger.valueOf(frame - a.localFrame)
            require(numerator.signum() >= 0) { "Выбранный момент предшествует началу исходника" }
            val result = (numerator + denominator / BigInteger.valueOf(2)) / denominator
            require(result <= BigInteger.valueOf(Long.MAX_VALUE)) { "Время исходника выходит за допустимый диапазон" }
            result.toLong()
        }
        require(time >= 0) { "Выбранный момент предшествует началу исходника" }
        return time
    }

    internal fun validate(clip: HybridClip, budget: HybridSourceWindow.ComparisonBudget) {
        require(matches(clip, budget)) { "Visible source map does not match its selected backing/phase" }
    }

    internal fun matches(clip: HybridClip, budget: HybridSourceWindow.ComparisonBudget): Boolean {
        if (assetId != clip.assetId) return false
        if (phaseStart == clip.originalFrameOffset && map === clip.sourceMap && slipUs == 0L) return true
        if (phaseStart == clip.originalFrameOffset && map.points.size == clip.sourceMap.points.size) {
            budget.reserve(map.points.size.toLong())
            if (map.points.indices.all { index ->
                val a = map.points[index]; val b = clip.sourceMap.points[index]
                a.localFrame == b.localFrame && Math.addExact(a.sourceTimeUs, slipUs) == b.sourceTimeUs
            }) return true
        }
        val phase = clip.originalFrameOffset.toLong()
        try {
            if (sample(phase) != clip.sourceMap.sample(0) ||
                sample(phase + clip.span.length) != clip.sourceMap.sample(clip.span.length)) return false
        } catch (_: IllegalArgumentException) { return false } catch (_: ArithmeticException) { return false }
        budget.reserve(clip.span.length.toLong() + 1)
        return (0..clip.span.length).all { frame -> sample(phase + frame) == clip.sourceMap.sample(frame) }
    }

    internal companion object {
        fun select(clip: HybridClip, revisionId: Long, automatic: Boolean = false) =
            HybridSourceBacking(clip.assetId, clip.sourceMap, clip.originalFrameOffset, 0, revisionId, automatic)
    }
}
