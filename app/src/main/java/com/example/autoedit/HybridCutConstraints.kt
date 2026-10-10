package com.veycad.app

object HybridCutConstraints {
    fun range(project: HybridProject, cutId: String): IntRange =
        range(project, cutId, HybridSourceWindow.ComparisonBudget())

    internal fun range(project: HybridProject, cutId: String, budget: HybridSourceWindow.ComparisonBudget): IntRange =
        rangeWithHistory(project.current, cutId, project.assets, project.fps, project, budget)

    /** Absolute output boundaries. Manual locks constrain automatic rebuilds, not further manual edits. */
    /** Without project history this overload can only continue the current map's edge. */
    fun range(revision: HybridRevision, cutId: String, assets: List<ProjectAsset>, fps: Int): IntRange =
        rangeWithHistory(revision, cutId, assets, fps, null, HybridSourceWindow.ComparisonBudget())

    private fun rangeWithHistory(revision: HybridRevision, cutId: String, assets: List<ProjectAsset>, fps: Int,
        project: HybridProject?, budget: HybridSourceWindow.ComparisonBudget): IntRange = editValidation {
        ProjectClock(fps)
        val index = revision.clips.indexOfFirst { it.id == cutId }
        require(index > 0) { "Склейка не найдена: $cutId" }
        val left = revision.clips[index - 1]
        val right = revision.clips[index]
        fun duration(clip: HybridClip): Long {
            val asset = assets.firstOrNull { it.id == clip.assetId }
            require(asset != null && asset.kind == ProjectAsset.Kind.VIDEO) { "Исходник клипа не найден" }
            require(clip.sourceMap.points.last().sourceTimeUs <= asset.durationUs &&
                clip.sourceMap.sample(clip.span.length - 1) < asset.durationUs) { "Здесь заканчивается исходник" }
            return asset.durationUs
        }
        val leftDuration = duration(left)
        duration(right)
        // Transitions run inside clips. Both sides must retain the transition's full window;
        // the unchanged neighbor's incoming window also constrains the edited right clip.
        fun minimumLength(at: Int): Long = maxOf(1L, transitionFrames(revision.clips[at], fps),
            revision.clips.getOrNull(at + 1)?.let { transitionFrames(it, fps) } ?: 0L)
        val boundary = right.span.start.toLong()
        var first = maxOf(left.span.start.toLong() + minimumLength(index - 1),
            boundary + Int.MIN_VALUE.toLong() - right.originalFrameOffset)
        var last = minOf(right.span.endExclusive.toLong() - minimumLength(index),
            boundary + Int.MAX_VALUE.toLong() - right.originalFrameOffset)
        if (first > last) return@editValidation IntRange.EMPTY
        if (first < boundary) {
            val window = project?.let { HybridSourceWindow(it, right, budget) }
            val frames = maxExtension((boundary - first).toInt()) { count ->
                val points = right.sourceMap.points
                if (window != null) window.canExtendLeft(count)
                else if (points[0].sourceTimeUs == points[1].sourceTimeUs) false
                else canExtend { right.sourceMap.extendLeft(count, 0) }
            }
            first = maxOf(first, boundary - frames)
        }
        if (last > boundary) {
            val window = project?.let { HybridSourceWindow(it, left, budget) }
            val frames = maxExtension((last - boundary).toInt()) { count ->
                val points = left.sourceMap.points
                if (window != null) window.canExtendRight(count, leftDuration)
                else if (points[points.lastIndex - 1].sourceTimeUs == points.last().sourceTimeUs) false
                else canExtend {
                    left.sourceMap.extendRight(count, leftDuration).also {
                        require(it.sample(it.points.last().localFrame - 1) < leftDuration)
                    }
                }
            }
            last = minOf(last, boundary + frames)
        }
        if (first > last) IntRange.EMPTY else first.toInt()..last.toInt()
    }

    private fun transitionFrames(clip: HybridClip, fps: Int): Long {
        val ms = clip.original.transitionDurationMs ?: TransitionTimeline.durationMs(clip.original.transitionIn)
        // Divide first: an external revision may carry a very large authored duration.
        val seconds = ms / 1000
        if (seconds > Int.MAX_VALUE.toLong() / fps) return Int.MAX_VALUE.toLong() + 1
        return seconds * fps + ((ms % 1000) * fps + 999) / 1000
    }

    private fun maxExtension(limit: Int, allowed: (Int) -> Boolean): Int {
        var low = 0
        var high = limit
        while (low < high) {
            val middle = low + ((high.toLong() - low + 1) / 2).toInt()
            if (allowed(middle)) low = middle else high = middle - 1
        }
        return low
    }

    private inline fun canExtend(block: () -> SourceTimeMap): Boolean = try {
        block()
        true
    } catch (_: IllegalArgumentException) { false }
}
