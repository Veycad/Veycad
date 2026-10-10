package com.veycad.app

import java.math.BigInteger

/** Resolves hidden samples in the same output-phase coordinates as the current visible map. */
internal class HybridSourceWindow(project: HybridProject, private val clip: HybridClip,
    private val comparisonBudget: ComparisonBudget) {
    private data class Backing(val clip: HybridClip, val slipUs: Long, val automatic: Boolean)

    /** One reservation pool for both range windows and the ensuing edit, not per map. */
    internal class ComparisonBudget {
        private var remaining = 1_000_000L

        fun reserve(count: Long) {
            require(count in 0..remaining) {
                "Для проверки сохранённых карт требуется слишком много сравнений; правка недоступна"
            }
            remaining -= count
        }
    }

    // Follow only contiguous selected ancestry. Matching the retained overlap against an
    // arbitrary older curve cannot prove which hidden samples were selected. Redo and gaps
    // must not supply backing, and a rewrite ends provenance even if older overlaps match.
    private val backing: List<Backing> = buildList {
        if (project.current.id == project.original.id) {
            add(Backing(clip, 0, true))
        } else {
            val undoById = project.undo.associateBy { it.id }
            var parentId = project.current.parentId
            var selected = clip
            var shift = 0L
            while (parentId != null) {
                val revision = if (parentId == project.original.id) project.original else undoById[parentId] ?: break
                comparisonBudget.reserve(revision.clips.size.toLong())
                val saved = revision.clips.firstOrNull { it.id == selected.id } ?: break
                if (saved.assetId != selected.assetId || !sameSourceDescriptor(selected, saved)) break
                val delta = slipFrom(selected, saved) ?: break
                shift = Math.addExact(shift, delta)
                add(Backing(saved, shift, revision.id == project.original.id))
                selected = saved
                parentId = revision.parentId
            }
        }
    }

    private fun sameSourceDescriptor(selected: HybridClip, saved: HybridClip): Boolean {
        val a = selected.original
        val b = saved.original
        if (a.speedRamp !== b.speedRamp) comparisonBudget.reserve(a.speedRamp.keyframes.size.toLong())
        return a.id == b.id && a.sourceIndex == b.sourceIndex && a.sourceStartMs == b.sourceStartMs &&
            a.sourceEndMs == b.sourceEndMs && a.outputDurationMs == b.outputDurationMs && a.speedRamp == b.speedRamp
    }

    private fun slipFrom(selected: HybridClip, saved: HybridClip): Long? {
        val phase = selected.originalFrameOffset.toLong()
        val savedPhase = saved.originalFrameOffset.toLong()
        if (phase == savedPhase && selected.sourceMap === saved.sourceMap) return 0
        val start = maxOf(phase, savedPhase)
        val end = minOf(phase + selected.span.length, savedPhase + saved.span.length)
        // One coincident visible sample cannot establish a hidden curve's provenance.
        if (end - start < 2) return null
        comparisonBudget.reserve(1)
        val shift = selected.sourceMap.sample((start - phase).toInt()) - saved.sourceMap.sample((start - savedPhase).toInt())
        val currentPoints = selected.sourceMap.points
        val savedPoints = saved.sourceMap.points
        if (phase == savedPhase && currentPoints.size == savedPoints.size) {
            comparisonBudget.reserve(currentPoints.size.toLong())
            if (currentPoints.indices.all {
                currentPoints[it].localFrame == savedPoints[it].localFrame &&
                    currentPoints[it].sourceTimeUs - savedPoints[it].sourceTimeUs == shift
            }) return shift
        }
        // Reserve the entire overlap before enumeration, including sparse Int.MAX spans.
        comparisonBudget.reserve(end - start + 1)
        for (frame in start..end) {
            if (selected.sourceMap.sample((frame - phase).toInt()) - saved.sourceMap.sample((frame - savedPhase).toInt()) != shift) return null
        }
        return shift
    }

    fun canExtendLeft(frames: Int): Boolean = available { sample(-frames.toLong()) }

    fun canExtendRight(frames: Int, durationUs: Long): Boolean = available {
        val end = clip.span.length.toLong() + frames
        require(sample(end) <= durationUs && sample(end - 1) < durationUs)
    }

    fun extendLeft(frames: Int): SourceTimeMap {
        checkPointBudget(frames)
        val added = (0 until frames).map { SourceTimeMap.Point(it, sample(it.toLong() - frames)) }
        return SourceTimeMap(added + clip.sourceMap.points.map { it.copy(localFrame = it.localFrame + frames) })
    }

    fun extendRight(frames: Int): SourceTimeMap {
        checkPointBudget(frames)
        return SourceTimeMap(clip.sourceMap.points + (1..frames).map {
            val frame = clip.span.length + it
            SourceTimeMap.Point(frame, sample(frame.toLong()))
        })
    }

    private fun checkPointBudget(frames: Int) {
        require(frames >= 0 && frames <= Int.MAX_VALUE - clip.span.length)
        require(clip.sourceMap.points.size.toLong() + frames <= HybridProjectCodec.MAX_SOURCE_POINTS) {
            "Для точного расширения требуется карта кадров больше допустимого размера проекта"
        }
    }

    private fun sample(localFrame: Long): Long {
        if (localFrame in 0..clip.span.length.toLong()) return clip.sourceMap.sample(localFrame.toInt())
        val phaseFrame = clip.originalFrameOffset.toLong() + localFrame
        for (saved in backing) {
            val frame = phaseFrame - saved.clip.originalFrameOffset.toLong()
            if (frame in 0..saved.clip.span.length.toLong()) {
                return Math.addExact(saved.clip.sourceMap.sample(frame.toInt()), saved.slipUs).also {
                    require(it >= 0) { "Выбранный момент предшествует началу исходника" }
                }
            }
        }
        // Only a verified chain to the automatic map proves its baseline extent. After
        // a rewrite or pruned ancestry, durable backing belongs to integration A/A2.
        val original = backing.firstOrNull { it.automatic }
        require(original != null) { "Сохранённая карта скрытых кадров недоступна; расширение клипа невозможно" }
        val frame = phaseFrame - original.clip.originalFrameOffset.toLong()
        val points = original.clip.sourceMap.points
        val (a, b) = if (frame < 0) points[0] to points[1] else points[points.lastIndex - 1] to points.last()
        require(b.sourceTimeUs > a.sourceTimeUs) { "Нельзя продолжить стоп-кадр за сохранённое окно" }
        val denominator = BigInteger.valueOf((b.localFrame - a.localFrame).toLong())
        val numerator = (BigInteger.valueOf(a.sourceTimeUs) + BigInteger.valueOf(original.slipUs)) * denominator +
            BigInteger.valueOf(b.sourceTimeUs - a.sourceTimeUs) * BigInteger.valueOf(frame - a.localFrame)
        require(numerator.signum() >= 0) { "Выбранный момент предшествует началу исходника" }
        val rounded = (numerator + denominator / BigInteger.valueOf(2)) / denominator
        require(rounded <= BigInteger.valueOf(Long.MAX_VALUE)) { "Время исходника выходит за допустимый диапазон" }
        return rounded.toLong()
    }

    private inline fun available(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (_: IllegalArgumentException) { false } catch (_: ArithmeticException) { false }
}
