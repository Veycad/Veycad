package com.veycad.app

internal object PreviewTestFixtures {
    fun graph(recipe: MontageStyleCatalog.Recipe, sourceCount: Int = 1): MontageGraph {
        require(sourceCount > 0)
        return MontageGraph(2000, 2000, clips = List(2) { i ->
            MontageGraph.Clip("clip-$i", 0, 1000, 1000, MontageGraph.ShotRole.ACTION,
                if (i == 0) MontageGraph.Transition.OPEN else MontageGraph.Transition.WHIP,
                MontageGraph.Motion.HOLD, 1f, i * 1000L, sourceIndex = i % sourceCount)
        }, metadata = NleProjectMetadata(generator = recipe.name),
            parameterTracks = listOf(ParameterTrack("gain", ParameterTargets.MASTER_AUDIO_GAIN,
                ParameterTrack.ValueType.SCALAR, listOf(ParameterTrack.Keyframe(0, listOf(1f)),
                    ParameterTrack.Keyframe(2_000_000, listOf(.5f))))))
    }
    fun core(recipe: MontageStyleCatalog.Recipe = MontageStyleCatalog.Recipe.HEARTBEAT,
        sourceCount: Int = 1): HybridProject {
        val fps = if (recipe == MontageStyleCatalog.Recipe.HEARTBEAT) 60 else 30
        val graph = graph(recipe, sourceCount)
        val clips = graph.clips.mapIndexed { i, clip -> HybridClip(clip.id, "video-${clip.sourceIndex}",
            FrameSpan(i * fps, (i + 1) * fps), SourceTimeMap(listOf(SourceTimeMap.Point(0, 0),
                SourceTimeMap.Point(fps, 1_000_000))), clip) }
        val revision = HybridRevision(0, null, graph, clips,
            ProjectMusic("music", 1234, .7f, 100, 200, true),
            listOf(TextItem("title", "Keep manual text", FrameSpan(0, fps), .4f, .6f, .8f, .1f,
                -1, TextItem.Appearance.ACCENT, 3)),
            ProjectStyle(recipe.name, 1, ProjectStyle.Mode.AUTHORED, true), setOf("clip-1"))
        // Mixed asset order must never define graph sourceIndex.
        val assets = listOf(ProjectAsset("music", "music.wav", ProjectAsset.Kind.AUDIO,
            3_000_000, "music-hash")) + List(sourceCount) { i ->
            ProjectAsset("video-$i", "video-$i.mp4", ProjectAsset.Kind.VIDEO, 2_000_000, "hash-$i") }
        return HybridProject("core-project", 1, fps, 1, assets, revision, revision,
            emptyList(), emptyList(), emptyList())
    }
    fun project(recipe: MontageStyleCatalog.Recipe = MontageStyleCatalog.Recipe.HEARTBEAT,
        sourceCount: Int = 1): DraftProject {
        val core = core(recipe, sourceCount)
        val order = List(sourceCount) { SourceId("video-$it") }
        return DraftProject(core, ProjectVisualSettings(ProjectFormats.defaultFor(recipe), false),
            order, order.associateWith { SourceGeometry(1920, 1080, 0, 1f) },
            order.associateWith { "semantic-${it.value}" })
    }
}
