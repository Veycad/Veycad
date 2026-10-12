package com.veycad.app

import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridManualPayloadCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oversizedDenseHistoryIsRejectedBeforeLaterPayloadAndKeepsCurrent() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val sources = ProjectAssetStore(store.directory("project"))
        val video = sources.import(temporary.newFile().apply { writeText("video") }, ProjectAsset.Kind.VIDEO, 2_000_000)
        val audio = sources.import(temporary.newFile().apply { writeText("music") }, ProjectAsset.Kind.AUDIO, 2_000_000)
        val base = storageProject(listOf(video, audio))
        store.create(base)
        // Each permitted map occupies 4,800,008 bytes. Four copies exceed 16 MiB,
        // even though all history entries share the same immutable in-memory mapping.
        val map = ClipTimeMapping(30, 2_000_000, LongArray(600_001) { it.toLong() })
        val timing = EditableFrameTiming(30, listOf(EditableClipTiming("c", map, FrameRange(0, 30), 0, 30)))
        var rejectLaterAccess = false
        val laterPayload = object : AbstractList<SourceAttachments>() {
            override val size: Int get() {
                if (rejectLaterAccess) throw AssertionError("Oversized dense history reached later payload")
                return 0
            }
            override fun get(index: Int): SourceAttachments = throw IndexOutOfBoundsException()
        }
        val graph = base.current.graph.copy(version = 3,
            metadata = base.current.graph.metadata.copy(schemaVersion = 3), editableTiming = timing)
        val revisions = (1L..4L).map { id -> base.current.copy(id = id, parentId = id - 1,
            graph = if (id == 3L) graph.copy(sourceAttachments = laterPayload) else graph) }
        // Codec order is original, current r4, then undo r1/r2/r3; r3 must reject
        // its dense block before touching the subsequent attachment list.
        val candidate = base.copy(current = revisions.last(), nextRevisionId = 5,
            undo = listOf(base.original) + revisions.dropLast(1))
        rejectLaterAccess = true
        val pointer = File(store.directory("project"), "CURRENT")
        val before = pointer.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.save(candidate, 0) }
        assertArrayEquals(before, pointer.readBytes())
        assertEquals(base, HybridProjectStore(files).load("project"))
    }

    @Test fun codecPreservesAuthoredNodeAnchorsPhasesAndDenseTimingAndRejectsInvalidSampleCounts() {
        val base = storageProject()
        val window = EffectSampleWindow(10, 900_000, -3, 7, 899_997, -2, 30)
        val node = base.current.graph.effectGraph.nodes.single().copy(originId = "authored-node",
            phaseStart = .2f, phaseEnd = .8f, sampleWindow = window)
        val effects = listOf(
            AnchoredMontageEffect("clip-node", "logical-node", "authored-node",
                EffectAnchor.Clip("c", -3, 899_997), true, originalNode = node, phaseStart = .2f, phaseEnd = .8f),
            AnchoredMontageEffect("boundary-node", "logical-node", "authored-node",
                EffectAnchor.Boundary("c", -3, 900_000), false, originalNode = node),
            AnchoredMontageEffect("music-node", "logical-node", "authored-node",
                EffectAnchor.Music(7, 899_997), true, originalNode = node))
        val samples = LongArray(31) { 100_000L + (it / 2L) * (it / 2L) * 100 }
        val range = FrameRange(-2, 28)
        val phase = ClipPhase(0, 0, 1_001_000)
        val tracks = base.current.graph.parameterTracks
        val manual = ManualMontageState(listOf(ManualClipState("c", range, 30, tracks, phase)), effects)
        val timing = EditableFrameTiming(30, listOf(EditableClipTiming("c",
            ClipTimeMapping(30, 2_000_000, samples, -2), range, 0, 30, 30, phase, tracks, 150_000)))
        val graph = base.current.graph.copy(version = 3, metadata = base.current.graph.metadata.copy(schemaVersion = 3),
            effectGraph = GpuEffectGraph(listOf(node)), manualMontageState = manual, editableTiming = timing,
            manualOverrides = ManualRenderOverrides(setOf("c"), setOf("boundary-node")))
        val revision = base.current.copy(graph = graph)
        val project = base.copy(original = revision, current = revision)
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val bytes = codec.encode(project)
        assertEquals(project, codec.decode(bytes))
        assertEquals(revision, codec.decodeRevision(codec.encodeRevisions(listOf(revision)).getValue(0)))
        assertArrayEquals(samples, codec.decode(bytes).current.graph.editableTiming!!.clips.single().timeMap.sourceUsByFrame)
        // Corrupt the unique dense mapping header, not a hand-written replacement project.
        val mappingHeader = ByteBuffer.allocate(24).putInt(30).putLong(2_000_000).putLong(-2).putInt(31).array()
        val offset = (0..bytes.size - mappingHeader.size).single { start ->
            mappingHeader.indices.all { bytes[start + it] == mappingHeader[it] }
        }
        ByteBuffer.wrap(bytes).putInt(offset + 20, Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { codec.decode(bytes) }
    }

    @Test fun genuineV5StoreLoadsAndNextSaveKeepsPublishedBytesAndUnknownManualFields() {
        val files = temporary.newFolder()
        javaClass.getResourceAsStream("/hybrid-v5/store.zip")!!.use { resource ->
            ZipInputStream(resource).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val destination = File(files, entry.name)
                    require(destination.canonicalFile.toPath().startsWith(files.canonicalFile.toPath()))
                    if (entry.isDirectory) destination.mkdirs() else {
                        destination.parentFile.mkdirs()
                        destination.writeBytes(zip.readBytesBounded(65_536))
                    }
                    zip.closeEntry()
                }
            }
        }
        val store = HybridProjectStore(files)
        val dir = store.directory("project")
        val originalBytes = dir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(dir).invariantSeparatorsPath to it.readBytes()
        }
        val pointer = File(dir, "CURRENT").readBytes()
        val loaded = store.load("project")
        assertEquals(6L, loaded.current.id)
        assertEquals(7L, loaded.nextRevisionId)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), loaded.undo.map { it.id })
        assertEquals(120_000L, loaded.assets.first().videoMetadata!!.firstVideoPtsUs)
        assertEquals(3_000_000L, loaded.assets.first().videoMetadata!!.videoEndPtsUs)
        assertEquals("capture-selection", loaded.selectedVideos!!.single().id.value)
        assertEquals(12, loaded.current.clips[1].originalFrameOffset)
        assertEquals(813_456L, loaded.current.clips[1].sourceMap.sample(0))
        assertEquals(1_038_456L, loaded.current.clips[1].sourceMap.sample(5))
        assertEquals(1_623_456L, loaded.current.clips[1].sourceMap.sample(18))
        assertEquals(2L, loaded.exports.single().revisionId)
        for (id in 0L..6L) {
            val revision = store.loadRevision("project", id)
            assertNull(revision.graph.manualMontageState)
            assertNull(revision.graph.editableTiming)
            assertNull(revision.graph.manualOverrides)
            assertTrue(revision.graph.sourceAttachments.isEmpty())
        }
        assertArrayEquals(pointer, File(dir, "CURRENT").readBytes())
        val oldExport = store.loadRevision("project", 2)
        assertEquals(-2, oldExport.clips[1].originalFrameOffset)
        assertEquals(556_789L, oldExport.clips[1].sourceMap.sample(0))
        val edited = HybridEditCommands.setAuthoredText(loaded, !loaded.current.style.showAuthoredText)
        store.save(edited, loaded.current.id)
        assertEquals(edited, HybridProjectStore(files).load("project"))
        assertEquals(oldExport, HybridProjectStore(files).loadRevision("project", 2))
        originalBytes.filterKeys { it != "CURRENT" }.forEach { (path, bytes) ->
            assertArrayEquals(path, bytes, File(dir, path).readBytes())
        }
    }

    @Test fun invalidMutableManualPayloadCannotReplaceCurrentPointer() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val sources = ProjectAssetStore(store.directory("project"))
        val video = sources.import(temporary.newFile().apply { writeText("video") }, ProjectAsset.Kind.VIDEO, 2_000_000)
        val audio = sources.import(temporary.newFile().apply { writeText("music") }, ProjectAsset.Kind.AUDIO, 2_000_000)
        val base = storageProject(listOf(video, audio))
        store.create(base)
        val effects = mutableListOf(AnchoredMontageEffect("o", "logical-o", "origin-o",
            EffectAnchor.Music(12_000, 900_000), true, originalOverlay = base.current.graph.overlays.single()))
        val payload = ManualMontageState(listOf(ManualClipState("c", FrameRange(0, 30), 30, emptyList())), effects)
        val graph = base.current.graph.copy(manualMontageState = payload)
        val candidate = base.copy(current = base.current.copy(id = 1, parentId = 0, graph = graph), nextRevisionId = 2)
        // Caller-owned lists can become invalid after construction; the storage boundary must reject them.
        effects += effects.single()
        val pointer = File(store.directory("project"), "CURRENT")
        val before = pointer.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.save(candidate, 0) }
        assertArrayEquals(before, pointer.readBytes())
        assertEquals(base, HybridProjectStore(files).load("project"))
    }
}
