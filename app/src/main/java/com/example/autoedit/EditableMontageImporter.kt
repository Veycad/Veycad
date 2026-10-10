package com.veycad.app

/** Pure winner import. The shared factory owns persistent files and subsequent revision IDs. */
internal object EditableMontageImporter {
    fun create(id: String, graph: MontageGraph, recipe: MontageStyleCatalog.Recipe,
        export: ProjectExportSettings, sources: List<ProjectAsset>, music: ProjectAsset,
        captureLink: CompletedRenderStore.CaptureLink?, selectedVideos: List<SelectedVideo>? = null): EditableMontageProject {
        require(graph.editableTiming == null && graph.manualMontageState == null) { "Import a legacy winner only" }
        require(sources.isNotEmpty() && sources.all { it.kind == ProjectAsset.Kind.VIDEO })
        require(music.kind == ProjectAsset.Kind.AUDIO)
        // Validate every occurrence before deduplicating the physical asset registry.
        val assets = LinkedHashMap<String, ProjectAsset>()
        for (asset in sources + music) {
            val previous = assets.putIfAbsent(asset.id, asset)
            require(previous == null || previous == asset) { "Conflicting metadata for asset: ${asset.id}" }
        }
        if (selectedVideos == null) {
            require(sources.map { it.id }.distinct().size == sources.size) {
                "Repeated source assets require an explicit inspected selection order"
            }
        } else {
            require(selectedVideos.size == sources.size && selectedVideos.zip(sources).all { (selection, asset) ->
                selection.assetId == asset.id
            }) { "Selected video order differs from resolved sources" }
        }
        val winner = snapshotMontageGraph(graph)
        val plan = HighQualityFramePlan.build(winner, export.fps)
        var cursor = 0
        var legacyStartUs = 0L
        val states = ArrayList<ManualClipState>()
        val clips = winner.clips.mapIndexed { index, clip ->
            require(clip.sourceIndex in sources.indices)
            val frames = plan.frames.filter { it.clipIndex == index }
            require(frames.isNotEmpty()) { "A manual clip needs at least one scheduled frame" }
            val points = frames.mapIndexed { frame, value -> SourceTimeMap.Point(frame, value.sourceTimeUs) } +
                SourceTimeMap.Point(frames.size, clip.sourceEndMs * 1_000L)
            val phase = ClipPhase(cursor.toLong(), legacyStartUs, clip.outputDurationMs * 1_000L)
            states += ManualClipState(clip.id, FrameRange(0, frames.size.toLong()), frames.size.toLong(),
                snapshotTracks(winner.parameterTracks.filter { it.target.startsWith("clip/${clip.id}/") }), phase)
            legacyStartUs += clip.outputDurationMs * 1_000L
            HybridClip(clip.id, sources[clip.sourceIndex].id,
                FrameSpan(cursor, cursor + frames.size).also { cursor = it.endExclusive }, SourceTimeMap(points), clip)
        }
        val payload = ManualMontageState(montageSnapshot(states), montageSnapshot(MontageEffectBindings.bind(winner, export.fps)))
        val revision = HybridRevision(1, null, winner.copy(manualMontageState = payload), clips,
            ProjectMusic(music.id, 0, winner.audioTrack?.gain ?: 1f, 0, 0, true), emptyList(),
            ProjectStyle(MontageStyleCatalog.all.single { it.recipe == recipe }.id, 1, ProjectStyle.Mode.AUTHORED, false), emptySet())
        return EditableMontageProject(HybridProject(id, 1, export.fps, 2, assets.values.toList(),
            revision, revision, emptyList(), emptyList(), emptyList(), selectedVideos), export, captureLink)
    }
}
