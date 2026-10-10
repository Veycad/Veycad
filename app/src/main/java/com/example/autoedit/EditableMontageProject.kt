package com.veycad.app

data class EditableClip(
    val id: String,
    val sourceIndex: Int,
    val origin: MontageGraph.Clip,
    val timeMap: ClipTimeMapping,
    val visible: FrameRange,
    val localTracks: List<ParameterTrack>,
    val originFrameCount: Long = timeMap.frameCount
) {
    init {
        require(id.isNotBlank() && id == origin.id && sourceIndex == origin.sourceIndex)
        require(localTracks.map { it.id }.distinct().size == localTracks.size)
        require(originFrameCount in 1..Int.MAX_VALUE.toLong())
        timeMap.validateVisible(visible)
    }
}

data class MontageRevision(val number: Long, val clips: List<EditableClip>, val effects: List<AnchoredMontageEffect>) {
    init {
        require(number >= 0L && clips.isNotEmpty())
        val clipIds = clips.map { it.id }.toSet()
        require(clipIds.size == clips.size)
        require(effects.map { it.id }.distinct().size == effects.size)
        require(effects.all { effect -> when (val anchor = effect.anchor) {
            is EffectAnchor.Clip -> anchor.clipId in clipIds
            is EffectAnchor.Boundary -> anchor.incomingClipId in clipIds
            is EffectAnchor.Music -> true
        } })
    }
}

/** Revision-owned montage metadata contains neither assets nor revision allocation/history. */
data class ManualClipState(
    val clipId: String,
    val visible: FrameRange,
    val originFrameCount: Long,
    val localTracks: List<ParameterTrack>
) {
    init {
        require(clipId.isNotBlank() && originFrameCount in 1..Int.MAX_VALUE.toLong())
        require(localTracks.map { it.id }.distinct().size == localTracks.size)
    }
}

data class ManualMontageState(val clips: List<ManualClipState>, val effects: List<AnchoredMontageEffect>) {
    init {
        require(clips.isNotEmpty() && clips.map { it.clipId }.distinct().size == clips.size)
        require(effects.map { it.id }.distinct().size == effects.size)
    }
}

/** A view of shared original/current; all files, IDs and history remain owned by HybridProject. */
internal data class EditableMontageProject(
    val shared: HybridProject,
    val export: ProjectExportSettings,
    val captureLink: CompletedRenderStore.CaptureLink? = null
) {
    val id: String get() = shared.id
    val schemaVersion: Int get() = shared.schemaVersion
    val sources: List<ProjectAsset> = shared.assets.filter { it.kind == ProjectAsset.Kind.VIDEO }
    val music: ProjectAsset get() = shared.assets.single { it.id == shared.current.music.assetId }
    val originalGraph: MontageGraph get() = shared.original.graph
    val recipe: MontageStyleCatalog.Recipe = requireNotNull(MontageStyleCatalog.all
        .firstOrNull { it.id == shared.current.style.recipeId }?.recipe) { "Unknown montage recipe" }
    val baseline: MontageRevision
    val current: MontageRevision

    init {
        require(export.fps == shared.fps)
        require(shared.current.clips.map { it.id }.toSet() == shared.original.clips.map { it.id }.toSet())
        baseline = projectRevision(shared.original)
        current = projectRevision(shared.current)
    }

    private fun projectRevision(revision: HybridRevision): MontageRevision {
        val payload = revision.graph.manualMontageState
        val states = payload?.clips?.associateBy { it.clipId }.orEmpty()
        require(payload == null || states.keys == revision.clips.map { it.id }.toSet()) { "Manual clip IDs differ from shared revision" }
        val origins = shared.original.clips.associateBy { it.id }
        val clips = revision.clips.map { clip ->
            val sourceIndex = sources.indexOfFirst { it.id == clip.assetId }
            require(sourceIndex >= 0 && clip.original.sourceIndex == sourceIndex) { "Shared asset order differs from saved sourceIndex" }
            require(clip.original.id == clip.id)
            val original = requireNotNull(origins[clip.id])
            require(clip.assetId == original.assetId) { "Manual clip source identity changed" }
            val state = states[clip.id]
            val visible = state?.visible ?: FrameRange(0, clip.span.length.toLong())
            require(visible.count == clip.span.length.toLong()) { "Manual visible length differs from shared span" }
            val originFrameCount = original.span.length.toLong()
            require(state == null || state.originFrameCount == originFrameCount) { "Original motion phase count differs" }
            EditableClip(clip.id, sourceIndex, clip.original,
                ClipTimeMapping.fromShared(shared.fps, sources[sourceIndex].durationUs, clip.sourceMap, visible.start),
                visible, state?.localTracks.orEmpty(), originFrameCount)
        }
        return MontageRevision(revision.id, clips, payload?.effects.orEmpty())
    }
}
