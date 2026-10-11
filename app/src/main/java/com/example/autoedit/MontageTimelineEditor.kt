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
    data class CoreRestoreMontage(val changedClipIds: Set<String>) : TimelinePreparation {
        val command: ProjectCommand get() = ProjectCommand.RestoreMontage
    }
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
        if (command == TimelineCommand.RestoreBaseline) {
            // Core owns reset provenance, validation and referential no-op. Do not project
            // potentially ambiguous current phase or manufacture an ordinary candidate.
            return TimelinePreparation.CoreRestoreMontage(Collections.unmodifiableSet(
                (project.shared.current.clips.map { it.id } + project.shared.original.clips.map { it.id }).toSet()))
        }
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
                require(project.shared.current.graph.manualMontageState != null ||
                    (clip.visible.start == 0L && clip.visible.count ==
                        project.shared.original.clips.single { it.id == clip.id }.span.length.toLong())) {
                    "Missing original frame coordinates for a previously trimmed clip"
                }
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
            TimelineCommand.RestoreBaseline -> error("Reset routes directly to core")
        }
        val shared = project.shared.current
        val sourceById = shared.clips.associateBy { it.id }
        var cursor = 0
        val preparedClips = clips.map { clip ->
            val source = sourceById.getValue(clip.id)
            val end = Math.addExact(cursor, Math.toIntExact(clip.visible.count))
            val descriptor = clip.origin.copy(
                transform = clip.origin.transform.copy(keyframes = montageSnapshot(clip.origin.transform.keyframes)),
                speedRamp = clip.origin.speedRamp.copy(keyframes = montageSnapshot(clip.origin.speedRamp.keyframes)))
            source.copy(span = FrameSpan(cursor, end).also { cursor = end }, original = descriptor,
                originalFrameOffset = Math.toIntExact(clip.visible.start),
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

    /** Shared contiguous selected ancestry is the only authority for hidden source samples. */
    private fun trimmedMap(project: EditableMontageProject, clip: EditableClip, visible: FrameRange): ClipTimeMapping {
        if (visible.start >= clip.visible.start && visible.endExclusive <= clip.visible.endExclusive) return clip.timeMap
        require(Math.addExact(visible.count, 1) <= HybridProjectCodec.MAX_SOURCE_POINTS) {
            "Dense source map exceeds the project point limit"
        }
        val source = project.shared.current.clips.single { it.id == clip.id }
        val leftCount = Math.toIntExact(maxOf(0L, Math.subtractExact(clip.visible.start, visible.start)))
        val rightCount = Math.toIntExact(maxOf(0L, Math.subtractExact(visible.endExclusive, clip.visible.endExclusive)))
        val budget = HybridSourceWindow.ComparisonBudget()
        // Reserve both edges before any source sampling or allocation.
        budget.reserve(Math.addExact(leftCount.toLong(), rightCount.toLong()))
        val window = HybridSourceWindow(project.shared, source, budget)
        validateRecoveryPhase(project, clip, visible, budget)
        val left = if (leftCount > 0) window.extendLeft(leftCount) else source.sourceMap
        val right = if (rightCount > 0) window.extendRight(rightCount) else source.sourceMap
        return ClipTimeMapping(project.export.fps, clip.timeMap.sourceDurationUs,
            LongArray(Math.toIntExact(Math.addExact(visible.count, 1))) { index ->
                val frame = Math.addExact(visible.start, index.toLong())
                val local = Math.subtractExact(frame, clip.visible.start)
                when {
                    local < 0 -> left.sample(Math.toIntExact(local + leftCount))
                    local > source.span.length -> right.sample(Math.toIntExact(local))
                    else -> clip.timeMap.sourceTimeUs(frame)
                }
            }, visible.start)
    }

    /** Conservative legacy phase guard only; it neither selects maps nor supplies PTS. */
    private fun validateRecoveryPhase(project: EditableMontageProject, clip: EditableClip,
        requested: FrameRange, budget: HybridSourceWindow.ComparisonBudget) {
        val shared = project.shared
        val ancestors = shared.undo.associateBy { it.id }
        var revision = shared.current
        repeat(shared.undo.size + 2) {
            budget.reserve(revision.clips.size.toLong())
            val saved = revision.clips.firstOrNull { it.id == clip.id } ?: return
            val states = revision.graph.manualMontageState?.clips
            budget.reserve(states?.size?.toLong() ?: 0)
            val state = states?.firstOrNull { it.clipId == clip.id }
            require(state != null || (saved.originalFrameOffset == 0 && saved.span.length.toLong() == clip.originFrameCount)) {
                "Saved trim is missing original frame coordinates"
            }
            require(state == null || (state.originFrameCount == clip.originFrameCount && state.phase == clip.phase)) {
                "Saved original motion phase is incompatible with the current clip"
            }
            if (revision.id == shared.original.id || revision.restoresAutomaticSources) return
            val start = saved.originalFrameOffset.toLong()
            val end = Math.addExact(start, saved.span.length.toLong())
            if (start <= requested.start && end >= requested.endExclusive) return
            val parent = revision.parentId ?: return
            revision = if (parent == shared.original.id) shared.original else ancestors[parent] ?: return
        }
    }
}
