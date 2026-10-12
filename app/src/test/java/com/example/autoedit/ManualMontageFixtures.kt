package com.veycad.app

internal object ManualMontageFixtures {
    fun generatedGraph(): MontageGraph {
        val clips = listOf(
            clip("A1", 0, 1_000, MontageGraph.Transition.OPEN),
            clip("B", 1, 4_000, MontageGraph.Transition.WHIP),
            clip("A2", 0, 6_000, MontageGraph.Transition.WHIP))
        return MontageGraph(10_000, 6_000, clips = clips, audioTrack = MontageGraph.AudioTrack("score"),
            metadata = NleProjectMetadata(generator = "manual-contract-fixture"),
            parameterTracks = listOf(scaleTrack(0), gradeTrack(2_000_000)),
            overlays = listOf(MontageGraph.Overlay("flash-B", 2_000, 2_100, "flash", opacity = .6f)))
    }

    fun linearProject(fps: Int = 30): EditableMontageProject {
        val graph = generatedGraph()
        val clips = graph.clips.mapIndexed { index, origin ->
            val startUs = origin.sourceStartMs * 1_000L
            // Independent fixture clock: never derive oracle samples from production mapping.
            val samples = LongArray(2 * fps + 1) { index ->
                startUs + kotlin.math.round(index * 1_000_000.0 / fps).toLong()
            }
            HybridClip(origin.id, "video-${origin.sourceIndex}", FrameSpan(index * 2 * fps, (index + 1) * 2 * fps),
                SourceTimeMap(samples.mapIndexed { frame, pts -> SourceTimeMap.Point(frame, pts) }), origin)
        }
        val states = clips.map { clip ->
            ManualClipState(clip.id, FrameRange(0, 2L * fps), 2L * fps, when (clip.id) {
                    "A1" -> listOf(scaleTrack(0))
                    "B" -> listOf(gradeTrack(0))
                    else -> emptyList()
                })
        }
        val effects = listOf(AnchoredMontageEffect("flash-B", "flash-B", "flash-B",
            EffectAnchor.Boundary("B", 0, 100_000), true, originalOverlay = graph.overlays.single()))
        val revision = HybridRevision(1, null, graph.copy(manualMontageState = ManualMontageState(states, effects)),
            clips, ProjectMusic("score", 0, 1f, 0, 0, true), emptyList(),
            ProjectStyle("duality_loop", 1, ProjectStyle.Mode.AUTHORED, false), emptySet())
        val shared = HybridProject("manual-fixture", 1, fps, 2,
            listOf(asset("video-0", 10_000_000), asset("video-1", 10_000_000), asset("score", 4_000_000)),
            revision, revision, emptyList(), emptyList(), emptyList())
        return EditableMontageProject(shared, ProjectExportSettings(720, 1280, fps, 5_000_000, 128_000))
    }

    private fun clip(id: String, source: Int, startMs: Long, transition: MontageGraph.Transition) = MontageGraph.Clip(
        id, startMs, startMs + 2_000, 2_000, MontageGraph.ShotRole.ACTION,
        transition, MontageGraph.Motion.HOLD, 1f, 0, sourceIndex = source)

    private fun asset(id: String, duration: Long) = ProjectAsset(id, "$id.bin",
        if (id == "score") ProjectAsset.Kind.AUDIO else ProjectAsset.Kind.VIDEO, duration, "0".repeat(64), id)
    private fun scaleTrack(time: Long) = ParameterTrack("A1-scale", ParameterTargets.clip("A1", "transform.scale"),
        ParameterTrack.ValueType.SCALAR, listOf(ParameterTrack.Keyframe(time, listOf(1.25f), ParameterTrack.Interpolation.HOLD)))
    private fun gradeTrack(time: Long) = ParameterTrack("B-grade", ParameterTargets.clip("B", "grade.rgbaBias"),
        ParameterTrack.ValueType.VEC4, listOf(ParameterTrack.Keyframe(time, listOf(.1f, 0f, 0f, 0f), ParameterTrack.Interpolation.HOLD)))
}
