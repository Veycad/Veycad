package com.veycad.app

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal fun storageProject(assets: List<ProjectAsset> = listOf(
    ProjectAsset("video", "video.mp4", ProjectAsset.Kind.VIDEO, 2_000_000, "video-hash", "Holiday"),
    ProjectAsset("music", "music.wav", ProjectAsset.Kind.AUDIO, 2_000_000, "music-hash", "My song"))): HybridProject {
    val curve = MontageGraph.SpeedRamp.CubicBezier(.1f, .2f, .8f, .9f)
    val clip = MontageGraph.Clip("c", 100, 1100, 1000, MontageGraph.ShotRole.ACTION,
        MontageGraph.Transition.WHIP, MontageGraph.Motion.WHIP_LEFT, .7f, 123,
        MontageGraph.ClipTransform(listOf(MontageGraph.ClipTransform.Keyframe(0f, 1.2f, .1f, .2f, 3f),
            MontageGraph.ClipTransform.Keyframe(1f, 1.4f, .3f, .4f, 5f))),
        MontageGraph.SpeedRamp(listOf(MontageGraph.SpeedRamp.Keyframe(0f, .6f, curve),
            MontageGraph.SpeedRamp.Keyframe(1f, 1.8f))), .1f, .2f, .3f, .4f, .5f, 150, 2)
    val plane = FrameAttachments.Plane(2, 1, floatArrayOf(.25f, .75f), .8f)
    val frame = FrameAttachments(100_000, plane, plane.copy(confidence = .6f),
        FrameAttachments.FlowPlane(1, 1, listOf(.2f, -.3f), .7f), .6f, .2f, .8f,
        FrameAttachments.FaceRegion(.4f, .5f, .2f, .3f, .8f), plane, .3f, true)
    val graph = MontageGraph(2000, 1000, clips = listOf(clip), audioTrack = MontageGraph.AudioTrack("music", .7f),
        overlays = listOf(MontageGraph.Overlay("o", 12, 900, "authored", MontageGraph.BlendMode.OVERLAY,
            MontageGraph.OverlayKind.DOUBLE_EXPOSURE, .4f, .1f, .2f, .3f, 45, 67)),
        metadata = NleProjectMetadata(2, 5, 3, "custom"),
        parameterTracks = listOf(ParameterTrack("p", "clip/c/scale", ParameterTrack.ValueType.VEC2,
            listOf(ParameterTrack.Keyframe(120, listOf(.3f, .7f), ParameterTrack.Interpolation.LINEAR, curve)))),
        frameAttachments = FrameAttachmentTimeline(listOf(frame), listOf(frame)),
        effectGraph = GpuEffectGraph(listOf(GpuEffectGraph.Node("fx", GpuEffectGraph.Kind.GLITCH, 10, 900_000, .7f, -.6f))))
    val revision = HybridRevision(0, null, graph, listOf(HybridClip("c", assets.first().id, FrameSpan(0, 30),
        SourceTimeMap(listOf(SourceTimeMap.Point(0, 100_000), SourceTimeMap.Point(15, 400_000),
            SourceTimeMap.Point(30, 1_100_000))), clip)),
        ProjectMusic(assets.last().id, 123, .7f, 12, 34, true),
        listOf(TextItem("t", "Привет", FrameSpan(2, 28), .2f, .3f, .8f, .1f, -123,
            TextItem.Appearance.BACKGROUND, 4)), ProjectStyle("recipe", 3, ProjectStyle.Mode.ADAPTIVE, false), emptySet())
    return HybridProject("project", 1, 30, 1, assets, revision, revision, emptyList(), emptyList(), emptyList())
}

class HybridProjectCodecTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun codecPreservesEveryGraphField() {
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val original = storageProject()
        val bytes = codec.encode(original)
        val loaded = codec.decode(bytes)
        assertEquals(original, loaded)
        assertEquals(HighQualityFramePlan.build(original.current.graph), HighQualityFramePlan.build(loaded.current.graph))
        assertSame(loaded.original, loaded.current)
        original.current.graph.frameAttachments.frames.first().mask!!.values[0] = .99f
        assertEquals(.25f, loaded.current.graph.frameAttachments.frames.first().mask!!.values[0], 0f)
        assertEquals("My song", loaded.assets.last().displayName)
    }
    @Test fun pathTraversalAndUnknownSchemaRejected() {
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val bytes = codec.encode(storageProject())
        ByteBuffer.wrap(bytes).putInt(4, 99)
        assertThrows(IllegalArgumentException::class.java) { codec.decode(bytes) }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(ByteArray(8)) }
        assertThrows(IllegalArgumentException::class.java) { storageProject().assets.first().copy(fileName = "../source.mp4") }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(codec.encode(storageProject()) + byteArrayOf(0)) }
    }
    @Test fun corruptAnalysisOpensWithRegenerationRequirement() {
        val directory = temporary.newFolder()
        val codec = HybridProjectCodec(AnalysisSidecarStore(directory))
        val bytes = codec.encode(storageProject())
        directory.listFiles()!!.filter { it.isFile }.forEach { it.writeBytes(byteArrayOf(9)) }
        val loaded = codec.decodeWithAnalysisStatus(bytes)
        assertTrue(loaded.analysisRegenerationRequired)
        assertTrue(loaded.missingAnalysisHashes.isNotEmpty())
        assertEquals(storageProject().assets, loaded.project.assets)
        assertTrue(loaded.project.current.graph.frameAttachments.frames.isEmpty())
    }
    @Test fun malformedLengthsAndEncodedTraversalAreRejectedBeforeAllocation() {
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val bytes = codec.encode(storageProject())
        val excessive = bytes.copyOf()
        ByteBuffer.wrap(excessive).putInt(8, Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { codec.decode(excessive) }
        val name = "video.mp4".toByteArray()
        val offset = (0..bytes.size - name.size).first { index -> name.indices.all { bytes[index + it] == name[it] } }
        "../xx.mp4".toByteArray().copyInto(bytes, offset)
        assertThrows(IllegalArgumentException::class.java) { codec.decode(bytes) }
    }
    @Test fun distinctRevisionsShareDecodedSidecarArraysButEachEncodeFreezesFreshValues() {
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val base = storageProject()
        val later = base.copy(current = base.current.copy(id = 1, parentId = 0), nextRevisionId = 2, undo = listOf(base.original))
        val loaded = codec.decode(codec.encode(later))
        assertSame(loaded.original, loaded.undo.single())
        assertSame(loaded.original.graph.frameAttachments.frames.single().mask!!.values,
            loaded.current.graph.frameAttachments.frames.single().mask!!.values)
        base.original.graph.frameAttachments.frames.single().mask!!.values[0] = .9f
        val fresh = codec.decode(codec.encode(later))
        assertEquals(.9f, fresh.current.graph.frameAttachments.frames.single().mask!!.values[0], 0f)
        assertEquals(.25f, loaded.current.graph.frameAttachments.frames.single().mask!!.values[0], 0f)
    }
}
