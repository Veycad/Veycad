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
    @Test fun version1ProjectLoadsWithZeroPhase() {
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val bytes = javaClass.getResourceAsStream("/hybrid-v1/project.bin")!!.use { it.readBytes() }
        val untouched = bytes.copyOf()
        assertEquals(1, ByteBuffer.wrap(bytes).getInt(4))
        val loaded = codec.decode(bytes)
        val base = storageProject()
        val revision = base.current.copy(graph = base.current.graph.copy(frameAttachments = FrameAttachmentTimeline()))
        val expected = base.copy(original = revision, current = revision, exports = listOf(
            ProjectExportRef(0, "automatic.mp4", ProjectExportSettings(1080, 1920, 30, 8_000_000, 192_000))))
        assertEquals(expected, loaded)
        assertEquals(0, loaded.current.clips.single().originalFrameOffset)
        assertSame(loaded.original, loaded.current)
        assertEquals(0L, loaded.exports.single().revisionId)
        assertArrayEquals(untouched, bytes)
        val revisionBytes = javaClass.getResourceAsStream("/hybrid-v1/revision.bin")!!.use { it.readBytes() }
        assertEquals(revision, codec.decodeRevision(revisionBytes))
    }

    @Test fun version2RoundTripsSignedCameraPhaseInHistoryAndRevisionBlobs() {
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val base = storageProject()
        val revision = base.current.copy(id = 1, parentId = 0,
            clips = base.current.clips.map { it.copy(originalFrameOffset = -2) })
        val project = base.copy(current = revision, nextRevisionId = 2, undo = listOf(base.original))
        val encoded = codec.encode(project)
        assertEquals(2, ByteBuffer.wrap(encoded).getInt(4))
        val loaded = codec.decode(encoded)
        assertEquals(project, loaded)
        assertEquals(-2, loaded.current.clips.single().originalFrameOffset)
        assertSame(loaded.original, loaded.undo.single())
        assertEquals(revision, codec.decodeRevision(codec.encodeRevisions(listOf(revision)).getValue(1)))
    }

    @Test fun openingVersion1StoreDoesNotRewriteAndNextEditRetainsOldExportBlob() {
        val store = HybridProjectStore(temporary.newFolder())
        val dir = store.directory("project").apply { mkdirs() }
        val projectBytes = javaClass.getResourceAsStream("/hybrid-v1/project.bin")!!.use { it.readBytes() }
        val revisionBytes = javaClass.getResourceAsStream("/hybrid-v1/revision.bin")!!.use { it.readBytes() }
        val hash = contentHash(revisionBytes)
        val revisionFile = File(File(dir, "revisions").apply { mkdirs() }, "$hash.bin").apply { writeBytes(revisionBytes) }
        val manifestName = "00000000-0000-0000-0000-000000000001.bin"
        val manifest = File(File(dir, "manifests").apply { mkdirs() }, manifestName)
        java.io.DataOutputStream(manifest.outputStream()).use {
            it.writeInt(0x56485354); it.writeInt(1)
            it.writeInt(projectBytes.size); it.write(projectBytes)
            it.writeInt(1); it.writeLong(0); it.write(hash.toByteArray(Charsets.US_ASCII)); it.writeInt(0)
        }
        val before = manifest.readBytes()
        val pointer = File(dir, "CURRENT").apply { writeText(manifestName) }
        val loaded = store.load("project")
        assertArrayEquals(before, manifest.readBytes())
        assertEquals(manifestName, pointer.readText())
        assertEquals(loaded.original, store.loadRevision("project", loaded.exports.single().revisionId))
        val sources = File(dir, "sources").apply { mkdirs() }
        loaded.assets.forEach { File(sources, it.fileName).writeText("fixture source") }
        val edited = HybridEditCommands.setAuthoredText(loaded, true)
        store.save(edited, loaded.current.id)
        val reopened = store.load("project")
        assertEquals(edited, reopened)
        assertSame(reopened.original, reopened.undo.single())
        assertEquals(loaded.original, store.loadRevision("project", 0))
        assertArrayEquals(revisionBytes, revisionFile.readBytes())
        assertArrayEquals(before, manifest.readBytes())
    }
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

    @Test fun pointCountsAreCheckedAgainstAvailableBytesBeforeAllocation() {
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val bytes = codec.encode(editProject())
        val map = ByteBuffer.allocate(28).putInt(2).putInt(0).putLong(500_000)
            .putInt(30).putLong(1_500_000).array()
        val offset = (0..bytes.size - map.size).first { index -> map.indices.all { bytes[index + it] == map[it] } }
        for (count in listOf(Int.MAX_VALUE, bytes.size / 12 + 1)) {
            val corrupt = bytes.copyOf()
            ByteBuffer.wrap(corrupt).putInt(offset, count)
            assertThrows(IllegalArgumentException::class.java) { codec.decode(corrupt) }
        }
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
