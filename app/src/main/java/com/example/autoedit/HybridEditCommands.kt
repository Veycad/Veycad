package com.veycad.app

class HybridEditRejected(val reason: String) : IllegalArgumentException(reason)

sealed interface ProjectCommand {
    data class MoveCut(val cutId: String, val targetFrame: Int) : ProjectCommand
    data class SlipClip(val clipId: String, val sourceOffsetUs: Long) : ProjectCommand
    data class ReplaceMusic(val music: ProjectMusic, val asset: ProjectAsset? = null) : ProjectCommand
    data class PutText(val item: TextItem) : ProjectCommand
    data class RemoveText(val id: String) : ProjectCommand
    data class SetAuthoredText(val visible: Boolean) : ProjectCommand
    data class CommitRevision(val candidate: HybridRevision) : ProjectCommand
    data object Undo : ProjectCommand
    data object Redo : ProjectCommand
    data object RestoreAutomatic : ProjectCommand
    data object RestoreMontage : ProjectCommand
}

object HybridEditCommands {
    fun apply(project: HybridProject, command: ProjectCommand): HybridProject = when (command) {
        is ProjectCommand.MoveCut -> moveCut(project, command.cutId, command.targetFrame)
        is ProjectCommand.SlipClip -> slipClip(project, command.clipId, command.sourceOffsetUs)
        is ProjectCommand.ReplaceMusic -> replaceMusic(project, command.music, command.asset)
        is ProjectCommand.PutText -> putText(project, command.item)
        is ProjectCommand.RemoveText -> removeText(project, command.id)
        is ProjectCommand.SetAuthoredText -> setAuthoredText(project, command.visible)
        is ProjectCommand.CommitRevision -> commitRevision(project, command.candidate)
        ProjectCommand.Undo -> undo(project)
        ProjectCommand.Redo -> redo(project)
        ProjectCommand.RestoreAutomatic -> restoreAutomatic(project)
        ProjectCommand.RestoreMontage -> restoreMontage(project)
    }

    fun moveCut(project: HybridProject, cutId: String, targetFrame: Int): HybridProject = editValidation {
        val index = project.current.clips.indexOfFirst { it.id == cutId }
        require(index > 0) { "Склейка не найдена: $cutId" }
        val left = project.current.clips[index - 1]
        val right = project.current.clips[index]
        if (targetFrame == right.span.start) return@editValidation project
        val budget = HybridSourceWindow.ComparisonBudget()
        require(targetFrame in HybridCutConstraints.range(project, cutId, budget)) {
            "Граница вне доступного диапазона исходников или окна перехода"
        }
        val delta = targetFrame - right.span.start
        val leftMap: SourceTimeMap
        val rightMap: SourceTimeMap
        if (delta > 0) {
            leftMap = HybridSourceWindow(project, left, budget).extendRight(delta)
            rightMap = exactSlice(right.sourceMap, delta, right.span.length)
        } else {
            leftMap = exactSlice(left.sourceMap, 0, left.span.length + delta)
            rightMap = HybridSourceWindow(project, right, budget).extendLeft(-delta)
        }
        val phase = right.originalFrameOffset.toLong() + delta
        require(phase in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Смещение фазы клипа слишком велико" }
        val clips = project.current.clips.toMutableList()
        clips[index - 1] = left.copy(span = FrameSpan(left.span.start, targetFrame), sourceMap = leftMap)
        clips[index] = right.copy(span = FrameSpan(targetFrame, right.span.endExclusive), sourceMap = rightMap,
            originalFrameOffset = phase.toInt())
        commitRevision(project, project.current.copy(clips = clips, lockedCutIds = project.current.lockedCutIds + cutId))
    }

    fun slipClip(project: HybridProject, clipId: String, sourceOffsetUs: Long): HybridProject = editValidation {
        val index = project.current.clips.indexOfFirst { it.id == clipId }
        require(index >= 0) { "Клип не найден: $clipId" }
        if (sourceOffsetUs == 0L) return@editValidation project
        val clip = project.current.clips[index]
        val map = SourceTimeMap(clip.sourceMap.points.map {
            val time = Math.addExact(it.sourceTimeUs, sourceOffsetUs)
            require(time >= 0) { "Выбранный момент предшествует началу исходника" }
            it.copy(sourceTimeUs = time)
        })
        val clips = project.current.clips.toMutableList()
        clips[index] = clip.copy(sourceMap = map)
        val locks = if (index > 0) project.current.lockedCutIds + clipId else project.current.lockedCutIds
        commitRevision(project, project.current.copy(clips = clips, lockedCutIds = locks))
    }

    fun replaceMusic(project: HybridProject, music: ProjectMusic, asset: ProjectAsset? = null): HybridProject = editValidation {
        var attached = project
        if (asset != null) {
            require(asset.id == music.assetId && asset.kind == ProjectAsset.Kind.AUDIO) { "Выбранный файл не соответствует музыке" }
            val published = project.assets.firstOrNull { it.id == asset.id }
            require(published == null || published == asset) { "Сохранённый исходник нельзя заменить другим файлом" }
            if (published == null) attached = project.copy(assets = project.assets + asset)
        }
        commitRevision(attached, attached.current.copy(music = music))
    }

    fun putText(project: HybridProject, item: TextItem): HybridProject = editValidation {
        require(item.span.endExclusive <= project.current.clips.last().span.endExclusive) { "Надпись выходит за конец ролика" }
        val texts = project.current.texts.toMutableList()
        val index = texts.indexOfFirst { it.id == item.id }
        if (index < 0) texts += item else texts[index] = item
        commitRevision(project, project.current.copy(texts = texts))
    }

    fun removeText(project: HybridProject, id: String): HybridProject = editValidation {
        require(project.current.texts.any { it.id == id }) { "Надпись не найдена: $id" }
        commitRevision(project, project.current.copy(texts = project.current.texts.filterNot { it.id == id }))
    }

    fun setAuthoredText(project: HybridProject, visible: Boolean): HybridProject =
        commitRevision(project, project.current.copy(style = project.current.style.copy(showAuthoredText = visible)))

    /** External adapters supply payload at the current ID; allocation and parenting belong here. */
    fun commitRevision(project: HybridProject, candidate: HybridRevision): HybridProject =
        commitRevision(project, candidate, restoresAutomaticSources = false)

    private fun commitRevision(project: HybridProject, candidate: HybridRevision,
        restoresAutomaticSources: Boolean): HybridProject = editValidation {
        require(candidate.id == project.current.id) { "Проект изменился после начала правки" }
        // Compare content without interpreting an inherited event as a new reset. An
        // explicit reset can change hidden source selection even when visible content matches.
        val payload = candidate.copy(parentId = project.current.parentId,
            restoresAutomaticSources = project.current.restoresAutomaticSources)
        if (payload == project.current && (!restoresAutomaticSources ||
                project.current.id == project.original.id || project.current.restoresAutomaticSources)) return@editValidation project
        require(project.nextRevisionId < Long.MAX_VALUE) { "Исчерпан диапазон номеров ревизий" }
        val assets = project.assets.associateBy { it.id }
        payload.clips.forEach { clip ->
            val asset = assets[clip.assetId]
            require(asset != null && asset.kind == ProjectAsset.Kind.VIDEO) { "Видеоисходник клипа не найден: ${clip.id}" }
            require(clip.sourceMap.points.last().sourceTimeUs <= asset.durationUs &&
                clip.sourceMap.sample(clip.span.length - 1) < asset.durationUs) { "Здесь заканчивается исходник клипа: ${clip.id}" }
        }
        val music = assets[payload.music.assetId]
        require(music != null && music.kind == ProjectAsset.Kind.AUDIO) { "Аудиоисходник музыки не найден" }
        require(payload.music.startUs < music.durationUs) { "Выбранный отрывок начинается после конца музыки" }
        // HybridProject validates the entire payload, source bounds and historical ownership.
        project.copy(current = payload.copy(id = project.nextRevisionId, parentId = project.current.id,
            restoresAutomaticSources = restoresAutomaticSources),
            nextRevisionId = project.nextRevisionId + 1,
            undo = (project.undo + project.current).takeLast(50), redo = emptyList())
    }

    fun undo(project: HybridProject): HybridProject {
        val previous = project.undo.lastOrNull() ?: return project
        return project.copy(current = previous, undo = project.undo.dropLast(1),
            redo = (project.redo + project.current).takeLast(50))
    }

    fun redo(project: HybridProject): HybridProject {
        val next = project.redo.lastOrNull() ?: return project
        return project.copy(current = next, undo = (project.undo + project.current).takeLast(50),
            redo = project.redo.dropLast(1))
    }

    fun restoreAutomatic(project: HybridProject): HybridProject = commitRevision(project,
        project.original.copy(id = project.current.id, parentId = project.current.parentId), restoresAutomaticSources = true)

    /** Explicit montage reset retains author content verbatim or rejects the entire edit. */
    fun restoreMontage(project: HybridProject): HybridProject = editValidation {
        val original = project.original
        val current = project.current
        val incomingIds = original.clips.drop(1).map { it.id }.toSet()
        current.lockedCutIds.forEach { id ->
            require(id in incomingIds) {
                "Нельзя сбросить монтаж: закреплённая склейка $id отсутствует в исходном монтаже или становится первым клипом. Снимите её закрепление"
            }
        }
        val frames = original.clips.last().span.endExclusive
        val durationUs = ProjectClock(project.fps).timeUs(frames)
        current.texts.forEach { item ->
            require(item.span.endExclusive <= frames) {
                "Нельзя сбросить монтаж: надпись ${item.id} выходит за конец исходного ролика. Сократите её интервал"
            }
        }
        current.textState.layers.forEach { layer ->
            require(layer.endUs <= durationUs) {
                "Нельзя сбросить монтаж: текстовый слой ${layer.id} выходит за конец исходного ролика. Сократите его интервал"
            }
        }
        current.textState.captions.forEach { cue ->
            require(cue.endUs <= durationUs) {
                "Нельзя сбросить монтаж: субтитр ${cue.id} выходит за конец исходного ролика. Сократите его интервал"
            }
        }
        if (current.graph == original.graph && originalMontageSelected(project)) return@editValidation project
        commitRevision(project, current.copy(graph = original.graph, clips = original.clips), restoresAutomaticSources = true)
    }

    // Author-only revisions clear the event, but do not change its selected source window.
    // Prove selection only through retained, contiguous ancestry of complete original clips.
    // A changed map/phase/identity or a gap ends proof, even if visible samples later match.
    private fun originalMontageSelected(project: HybridProject): Boolean {
        val budget = HybridSourceWindow.ComparisonBudget()
        val byId = project.undo.associateBy { it.id }
        var revision = project.current
        repeat(project.undo.size + 1) {
            // The model already validates every reset event's complete original clips.
            if (revision.id == project.original.id || revision.restoresAutomaticSources) return true
            val clips = revision.clips
            val original = project.original.clips
            if (clips.size != original.size) return false
            budget.reserve(clips.size.toLong())
            for (index in clips.indices) {
                val selected = clips[index]
                val expected = original[index]
                if (selected === expected) continue
                if (selected.sourceMap !== expected.sourceMap) budget.reserve(selected.sourceMap.points.size.toLong())
                if (selected.original.speedRamp !== expected.original.speedRamp) {
                    budget.reserve(selected.original.speedRamp.keyframes.size.toLong())
                }
                if (selected.original.transform !== expected.original.transform) {
                    budget.reserve(selected.original.transform.keyframes.size.toLong())
                }
                if (selected != expected) return false
            }
            if (revision.parentId == project.original.id) return true
            revision = byId[revision.parentId] ?: return false
        }
        return false
    }

    private fun exactSlice(map: SourceTimeMap, from: Int, until: Int): SourceTimeMap {
        // Re-interpolating rounded endpoints changes unsaved fractional samples by 1 us.
        // Keep whole segments and integral slopes sparse; only partial fractional segments
        // need samples in the old coordinate system, including the exclusive boundary.
        var count = 1L
        for (index in 1..map.points.lastIndex) {
            val a = map.points[index - 1]
            val b = map.points[index]
            val start = maxOf(from, a.localFrame)
            val end = minOf(until, b.localFrame)
            if (start >= end) continue
            val integral = (b.sourceTimeUs - a.sourceTimeUs) % (b.localFrame - a.localFrame) == 0L
            count += if (integral || (start == a.localFrame && end == b.localFrame)) 1 else (end - start).toLong()
            require(count <= HybridProjectCodec.MAX_SOURCE_POINTS) {
                "Для точной правки требуется карта кадров больше допустимого размера проекта"
            }
        }
        val points = ArrayList<SourceTimeMap.Point>(count.toInt())
        points += SourceTimeMap.Point(0, map.sample(from))
        for (index in 1..map.points.lastIndex) {
            val a = map.points[index - 1]
            val b = map.points[index]
            val start = maxOf(from, a.localFrame)
            val end = minOf(until, b.localFrame)
            if (start >= end) continue
            val integral = (b.sourceTimeUs - a.sourceTimeUs) % (b.localFrame - a.localFrame) == 0L
            if (integral || (start == a.localFrame && end == b.localFrame)) {
                points += SourceTimeMap.Point(end - from, map.sample(end))
            } else {
                for (frame in start + 1..end) points += SourceTimeMap.Point(frame - from, map.sample(frame))
            }
        }
        return SourceTimeMap(points)
    }
}

internal inline fun <T> editValidation(block: () -> T): T = try {
    block()
} catch (error: HybridEditRejected) {
    throw error
} catch (error: IllegalArgumentException) {
    throw HybridEditRejected(error.message?.takeIf { it.isNotBlank() } ?: "Правка нарушает ограничения проекта")
} catch (_: ArithmeticException) {
    throw HybridEditRejected("Смещение выходит за допустимый диапазон времени")
}
