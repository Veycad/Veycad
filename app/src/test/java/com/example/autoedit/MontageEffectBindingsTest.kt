package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class MontageEffectBindingsTest {
    @Test fun fractionalTrimRetainsEveryEnvelopeSample() {
        val base = EditableMontageCompilerTest.fractionalGraph()
        val graph = base.copy(overlays = listOf(MontageGraph.Overlay("crossing", 0, 2400, "glow",
            overlayKind = MontageGraph.OverlayKind.GLOW, opacity = .8f)),
            effectGraph = GpuEffectGraph(listOf(GpuEffectGraph.Node("crossing-node", GpuEffectGraph.Kind.GLOW, 0, 2_400_000, .8f))))
        for (fps in listOf(30, 60)) {
            val project = EditableMontageCompilerTest.imported(graph, fps)
            val compiled = EditableMontageCompiler.compile(EditableMontageCompilerTest.revised(project, listOf(1, 0), "B", 1))
            val original = HighQualityFramePlan.build(graph, fps).frames.filter { it.clipIndex == 1 }.drop(1)
            val actual = HighQualityFramePlan.build(compiled, fps).frames.filter { it.clipIndex == 0 }
            original.zip(actual).forEach { (before, after) ->
                assertEquals("GPU at ${before.outputTimeUs}", before.effects, after.effects)
                assertEquals("layer at ${before.outputTimeUs}", before.layer.opacity, after.layer.opacity, 0f)
            }
        }
    }

    @Test fun firstIncomingBoundaryIsDormantAndReactivates() {
        val project = EditableMontageCompilerTest.imported(ManualMontageFixtures.generatedGraph())
        val first = EditableMontageCompilerTest.revised(project, listOf(1, 0, 2))
        assertTrue(EditableMontageCompiler.compile(first).overlays.isEmpty())
        assertTrue(first.current.effects.single().enabled)
        val restored = EditableMontageCompilerTest.revised(first, listOf(1, 0, 2))
        val overlay = EditableMontageCompiler.compile(restored).overlays.single()
        assertEquals(2000L, overlay.startMs)
        assertEquals("flash-B", overlay.originId)
    }

    @Test fun currentSharedDescriptorOwnsTransition() {
        val project = EditableMontageCompilerTest.imported(ManualMontageFixtures.generatedGraph())
        val old = project.shared.current
        val changed = old.copy(id = 2, parentId = 1, clips = old.clips.map { clip ->
            if (clip.id == "B") clip.copy(original = clip.original.copy(transitionIn = MontageGraph.Transition.HARD_CUT)) else clip
        })
        val graph = EditableMontageCompiler.compile(project.copy(shared = project.shared.copy(current = changed, nextRevisionId = 3)))
        assertEquals(MontageGraph.Transition.HARD_CUT, graph.clips[1].transitionIn)
        assertEquals(MontageGraph.Transition.WHIP, project.originalGraph.clips[1].transitionIn)
        assertEquals(1, graph.overlays.size) // Standalone flash is independently controlled.
    }

    // Catches restarting the triangular envelope at the split instead of continuing the authored cue.
    @Test fun splitEffectKeepsEnvelopePhase() {
        val base = EditableMontageCompilerTest.fractionalGraph()
        val graph = base.copy(overlays = listOf(MontageGraph.Overlay("crossing", 500, 2000, "glow",
            overlayKind = MontageGraph.OverlayKind.GLOW, opacity = .8f)),
            effectGraph = GpuEffectGraph(listOf(GpuEffectGraph.Node("crossing-node", GpuEffectGraph.Kind.GLOW, 500_000, 2_000_000, .8f))))
        val imported = EditableMontageCompilerTest.imported(graph)
        val compiled = EditableMontageCompiler.compile(EditableMontageCompilerTest.revised(imported, listOf(1, 0)))
        val before = HighQualityFramePlan.build(graph).frames.single { it.outputTimeUs == 1_500_000L }
        val after = HighQualityFramePlan.build(compiled).frames.single { it.outputTimeUs == 266_667L }
        assertEquals(before.layer.opacity, after.layer.opacity, 0f)
        assertEquals(before.layer.progress, after.layer.progress, 0f)
        assertEquals(before.effects, after.effects)
        assertEquals(setOf("crossing"), compiled.overlays.map { it.originId }.toSet())
        assertEquals(2, compiled.overlays.map { it.id }.distinct().size)
    }

    // Explicit recipe identities distinguish music impulses from scene effects even next to a beat.
    @Test fun currentRecipeCueKindsHaveExplicitOwnership() {
        val base = ManualMontageFixtures.generatedGraph()
        val musicIds = listOf("heartbeat-pulse-0", "heartbeat-pulse-30", "fear-step-0", "onset-flash-0", "reference-first-phrase-impact")
        val localIds = listOf("heartbeat-echo-0", "heartbeat-echo-7", "heartbeat-finale-echo-stutter", "heartbeat-tail",
            "reference-final-subject-stage", "reference-final-black-release", "reference-double-exposure-build",
            "reference-hallway-echo", "reference-office-echo-build", "reference-office-echo-peak",
            "reference-mirror-slice-peak", "reference-double-exposure-release")
        val nodes = listOf("heartbeat-defocus-0", "fear-defocus-0", "fear-opener-split-0", "fear-opener-lens-0",
            "fear-split-0", "fear-lens-0", "duality-motion-soft-0", "duality-motion-lens-0", "duality-motion-light-0", "lens-0", "reference-slice-glitch")
        val graph = base.copy(overlays = (musicIds + localIds).map { id -> MontageGraph.Overlay(id, 2000, 2100,
            when { id.startsWith("heartbeat-pulse") || id == "heartbeat-tail" -> "heartbeat-measured-step"
                id.startsWith("fear-step") -> "authored-measured-step"
                id.startsWith("heartbeat-echo") -> "heartbeat-echo-experimental"
                else -> "fixture" }) } + MontageGraph.Overlay("boundary-flash", 2000, 2100, "flash"),
            effectGraph = GpuEffectGraph((nodes + listOf("whip-glow-1", "whip-glitch-1", "impact-glow-1")).map {
                GpuEffectGraph.Node(it, when {
                    it.contains("defocus") || it.contains("soft") -> GpuEffectGraph.Kind.DEFOCUS_BLUR
                    it.contains("split") || it.contains("glitch") -> GpuEffectGraph.Kind.GLITCH
                    it.contains("lens") -> GpuEffectGraph.Kind.LENS_BLUR
                    else -> GpuEffectGraph.Kind.GLOW
                }, 2_000_000, 2_100_000, .5f) }))
        val effects = MontageEffectBindings.bind(graph, 30)
        musicIds.forEach { id -> assertTrue(id, effects.single { it.originId == id }.anchor is EffectAnchor.Music) }
        (localIds + nodes + listOf("whip-glow-1", "whip-glitch-1", "impact-glow-1")).forEach { id ->
            assertEquals(id, "B", (effects.single { it.originId == id }.anchor as EffectAnchor.Clip).clipId)
        }
        assertEquals("B", (effects.single { it.originId == "boundary-flash" }.anchor as EffectAnchor.Boundary).incomingClipId)
        graph.effectGraph.nodes.forEach { node -> assertEquals(node, effects.single { it.originId == node.id }.originalNode) }
    }

    // Catches parsing segmented IDs as authored heartbeat indices and losing Sigma finale special cases.
    @Test fun segmentedSpecialCuesUseOriginIdAndOriginalPhase() {
        for (index in 0..10) {
            val echo = MontageGraph.Overlay("heartbeat-echo-$index", 0, 2000, "heartbeat-echo-experimental",
                overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE, opacity = .62f)
            val segment = echo.copy(id = "segment-$index", startMs = 1000, originId = echo.id, phaseStart = .5f)
            assertEquals(LayerCompositorModel.sample(listOf(echo), 1500).opacity,
                LayerCompositorModel.sample(listOf(segment), 1500).opacity, 0f)
        }
        val sigma = MontageGraph.Overlay("reference-final-subject-stage", 0, 2000, "reference-subject-stage",
            overlayKind = MontageGraph.OverlayKind.SUBJECT_STAGE, opacity = 1f)
        assertEquals(1f, LayerCompositorModel.sample(listOf(sigma.copy(id = "segment", originId = sigma.id)), 0).opacity, 0f)
        val node = GpuEffectGraph.Node("reference-slice-glitch", GpuEffectGraph.Kind.GLITCH, 0, 2000, .7f)
        assertEquals(.7f, GpuEffectGraph(listOf(node.copy(id = "segment", originId = node.id))).sample(0).glitch, 0f)
    }

    @Test fun sigmaLegacyFadeKeepsExactMillisecondEnvelope() {
        val fade = ReferenceMontageProfile.authoredOverlays().single { it.id == "reference-final-black-release" }
        for (elapsed in 0L..350L) {
            val p = elapsed / 350f
            val expected = p * p * (3f - 2f * p)
            assertEquals("fade millisecond $elapsed", expected,
                LayerCompositorModel.sample(listOf(fade), fade.startMs + elapsed).finalFadeOpacity, 0f)
        }
    }
}
