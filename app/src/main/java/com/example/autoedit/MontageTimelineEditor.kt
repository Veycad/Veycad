package com.veycad.app

import java.util.Collections

enum class ClipEdge { START, END }

sealed interface TimelineCommand {
    data class Trim(val clipId: String, val edge: ClipEdge, val frame: Long) : TimelineCommand
    data class Move(val clipId: String, val toIndex: Int) : TimelineCommand
    data class SetTransition(val incomingClipId: String, val transition: MontageGraph.Transition) : TimelineCommand
    data class SetFlashEnabled(val effectId: String, val enabled: Boolean) : TimelineCommand
    data class MoveFlashToNext(val effectId: String) : TimelineCommand
    data object RestoreBaseline : TimelineCommand
}

sealed interface TimelinePreparation {
    data class Prepared(val candidate: HybridRevision, val changedClipIds: Set<String>) : TimelinePreparation
    data object Unchanged : TimelinePreparation
    data class Rejected(val reason: String) : TimelinePreparation
}

/** Pure preparation only. The common command/store layer owns IDs, history and application. */
internal object MontageTimelineEditor {
    fun prepare(project: EditableMontageProject, command: TimelineCommand): TimelinePreparation = try {
        prepareChecked(project, command)
    } catch (invalid: IllegalArgumentException) {
        TimelinePreparation.Rejected(invalid.message ?: "Invalid montage edit")
    } catch (overflow: ArithmeticException) {
        TimelinePreparation.Rejected("Montage time exceeds supported bounds")
    }

    private fun prepareChecked(project: EditableMontageProject, command: TimelineCommand): TimelinePreparation {
        val current = project.current
        val clips = current.clips.toMutableList()
        var effects = current.effects
        val changed = linkedSetOf<String>()
        fun clipIndex(id: String): Int = clips.indexOfFirst { it.id == id }.also { require(it >= 0) { "Unknown clip: $id" } }
        fun flashIndex(id: String): Int = effects.indexOfFirst { it.id == id }.also { index ->
            require(index >= 0) { "Unknown effect: $id" }
            require(effects[index].originalOverlay?.overlayKind == MontageGraph.OverlayKind.FLASH) { "Effect is not a flash" }
        }
        fun markOwner(effect: AnchoredMontageEffect) {
            when (val anchor = effect.anchor) {
                is EffectAnchor.Clip -> changed += anchor.clipId
                is EffectAnchor.Boundary -> changed += anchor.incomingClipId
                is EffectAnchor.Music -> Unit
            }
        }
        when (command) {
            is TimelineCommand.Trim -> {
                val index = clipIndex(command.clipId)
                val clip = clips[index]
                val visible = when (command.edge) {
                    ClipEdge.START -> FrameRange(command.frame, clip.visible.endExclusive)
                    ClipEdge.END -> FrameRange(clip.visible.start, command.frame)
                }
                if (visible == clip.visible) return TimelinePreparation.Unchanged
                clips[index] = clip.copy(timeMap = trimmedMap(project, clip, visible), visible = visible)
                changed += clip.id
            }
            is TimelineCommand.Move -> {
                val from = clipIndex(command.clipId)
                require(command.toIndex in clips.indices) { "Move index is outside the timeline" }
                if (from == command.toIndex) return TimelinePreparation.Unchanged
                clips.add(command.toIndex, clips.removeAt(from))
                for (index in minOf(from, command.toIndex)..maxOf(from, command.toIndex)) changed += clips[index].id
            }
            is TimelineCommand.SetTransition -> {
                val index = clipIndex(command.incomingClipId)
                val clip = clips[index]
                if (clip.origin.transitionIn == command.transition) return TimelinePreparation.Unchanged
                clips[index] = clip.copy(origin = clip.origin.copy(transitionIn = command.transition))
                // Only update existing explicitly-owned payloads. Never recreate a deleted/moved cue.
                effects = effects.map { effect ->
                    if ((effect.anchor as? EffectAnchor.Clip)?.clipId != clip.id || effect.originalNode == null) effect
                    else when {
                        effect.originId.startsWith("whip-glow-") || effect.originId.startsWith("whip-glitch-") ->
                            effect.copy(enabled = command.transition == MontageGraph.Transition.WHIP)
                        effect.originId.startsWith("impact-glow-") -> effect.copy(enabled = command.transition == MontageGraph.Transition.BLACKOUT)
                        else -> effect
                    }
                }
                changed += clip.id
            }
            is TimelineCommand.SetFlashEnabled -> {
                val index = flashIndex(command.effectId)
                val flash = effects[index]
                if (flash.enabled == command.enabled) return TimelinePreparation.Unchanged
                effects = effects.mapIndexed { i, effect -> if (i == index) effect.copy(enabled = command.enabled) else effect }
                markOwner(flash)
            }
            is TimelineCommand.MoveFlashToNext -> {
                val index = flashIndex(command.effectId)
                val flash = effects[index]
                val anchor = flash.anchor as? EffectAnchor.Boundary
                require(anchor != null) { "Only a boundary flash can move to the next cut" }
                val owner = clipIndex(anchor.incomingClipId)
                require(owner < clips.lastIndex) { "The last flash has no next cut" }
                val moved = flash.copy(anchor = anchor.copy(incomingClipId = clips[owner + 1].id))
                effects = effects.mapIndexed { i, effect -> if (i == index) moved else effect }
                markOwner(flash); markOwner(moved)
            }
            TimelineCommand.RestoreBaseline -> {
                if (current.clips == project.baseline.clips && effects == project.baseline.effects) return TimelinePreparation.Unchanged
                changed += clips.map { it.id }
                clips.clear(); clips.addAll(project.baseline.clips)
                effects = project.baseline.effects
            }
        }
        val shared = project.shared.current
        val sourceClips = if (command == TimelineCommand.RestoreBaseline) project.shared.original.clips else shared.clips
        val sourceById = sourceClips.associateBy { it.id }
        var cursor = 0
        val preparedClips = clips.map { clip ->
            val source = sourceById.getValue(clip.id)
            val end = Math.addExact(cursor, clip.visible.count.toInt())
            val descriptor = clip.origin.copy(
                transform = clip.origin.transform.copy(keyframes = montageSnapshot(clip.origin.transform.keyframes)),
                speedRamp = clip.origin.speedRamp.copy(keyframes = montageSnapshot(clip.origin.speedRamp.keyframes)))
            source.copy(span = FrameSpan(cursor, end).also { cursor = end }, original = descriptor,
                sourceMap = if (command is TimelineCommand.Trim && command.clipId == clip.id)
                    clip.timeMap.toSourceTimeMap(clip.visible) else source.sourceMap)
        }
        val payload = ManualMontageState(montageSnapshot(clips.map {
            ManualClipState(it.id, it.visible, it.originFrameCount, snapshotTracks(it.localTracks), it.phase)
        }), montageSnapshot(effects))
        // Keep all unrelated revision fields; invalid text/locked-cut constraints reject atomically.
        val candidate = shared.copy(clips = preparedClips, graph = shared.graph.copy(manualMontageState = payload))
        val compiled = EditableMontageCompiler.compile(project, candidate)
        return TimelinePreparation.Prepared(candidate.copy(graph = compiled), Collections.unmodifiableSet(changed))
    }

    /** Reads only canonical saved revisions. There is no independent map registry or history. */
    private fun trimmedMap(project: EditableMontageProject, clip: EditableClip, visible: FrameRange): ClipTimeMapping {
        val original = project.shared.original.clips.single { it.id == clip.id }
        val current = project.shared.current
        require(current.graph.manualMontageState != null || clip.visible.count == original.span.length.toLong()) {
            "Missing original frame coordinates for a previously trimmed clip"
        }
        if (visible.start >= clip.visible.start && visible.endExclusive <= clip.visible.endExclusive) return clip.timeMap
        val saved = (project.shared.undo.sortedByDescending { it.id } + project.shared.original).distinctBy { it.id }
        val maps = saved.map { revision -> lazy {
            val candidate = project.revisionView(revision).clips.single { it.id == clip.id }
            val left = maxOf(clip.visible.start, candidate.visible.start)
            val right = minOf(clip.visible.endExclusive, candidate.visible.endExclusive)
            val delta = if (right > left && candidate.phase == clip.phase) {
                val shift = Math.subtractExact(clip.timeMap.sourceTimeUs(left), candidate.timeMap.sourceTimeUs(left))
                if ((left..right).all { frame -> Math.subtractExact(clip.timeMap.sourceTimeUs(frame), candidate.timeMap.sourceTimeUs(frame)) == shift }) shift else null
            } else null
            SavedMap(candidate, revision.clips.single { it.id == clip.id }.sourceMap, delta)
        } }
        val currentMap = current.clips.single { it.id == clip.id }.sourceMap
        fun sample(frame: Long): Long {
            if (frame in clip.visible.start..clip.visible.endExclusive) return clip.timeMap.sourceTimeUs(frame)
            val edgeMaps = mutableListOf(SavedMap(clip, currentMap, 0L))
            var completeWindowKnown = clip.visible.start <= 0 && clip.visible.endExclusive >= clip.originFrameCount
            for ((index, revision) in saved.withIndex()) {
                val source = revision.clips.single { it.id == clip.id }
                if (revision.graph.manualMontageState == null && source.span.length != original.span.length) {
                    // An older coordinate-less trim supplies neither samples nor edge provenance
                    // once a newer compatible full original window is known. Never infer its offset.
                    require(completeWindowKnown) { "Saved trim is missing original frame coordinates" }
                    continue
                }
                val map = maps[index].value
                if (frame in map.clip.visible.start..map.clip.visible.endExclusive) {
                    val shift = requireNotNull(map.delta) { "Saved source mapping is incompatible with the current clip" }
                    return Math.addExact(map.clip.timeMap.sourceTimeUs(frame), shift)
                }
                if (map.delta != null) {
                    edgeMaps += map
                    if (map.clip.visible.start <= 0 && map.clip.visible.endExclusive >= clip.originFrameCount) completeWindowKnown = true
                }
            }
            require(frame < 0 || frame > clip.originFrameCount) { "Original source samples are unavailable in shared history" }
            // Outside all recorded material, continue the widest compatible saved edge, retaining
            // sparse segment provenance instead of inferring a slope from a newly cropped edge.
            val edge = if (frame < clip.visible.start) edgeMaps.minBy { it.clip.visible.start }
                else edgeMaps.maxBy { it.clip.visible.endExclusive }
            val shifted = SourceTimeMap(edge.map.points.map { it.copy(sourceTimeUs = Math.addExact(it.sourceTimeUs, edge.delta!!)) })
            val local = Math.subtractExact(frame, edge.clip.visible.start)
            return if (local < 0) shifted.extendLeft(Math.toIntExact(-local), 0).sample(0)
                else shifted.extendRight(Math.toIntExact(local - edge.clip.visible.count), clip.timeMap.sourceDurationUs).sample(Math.toIntExact(local))
        }
        require(visible.count < Int.MAX_VALUE) { "Dense map needs an end-exclusive point" }
        return ClipTimeMapping(project.export.fps, clip.timeMap.sourceDurationUs,
            LongArray(visible.count.toInt() + 1) { sample(Math.addExact(visible.start, it.toLong())) }, visible.start)
    }

    private data class SavedMap(val clip: EditableClip, val map: SourceTimeMap, val delta: Long?)
}
