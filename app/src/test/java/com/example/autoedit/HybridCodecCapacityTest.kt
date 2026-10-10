package com.veycad.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridCodecCapacityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fullRichHistoryRoundTripsAllFiftyUndoCommands() {
        val project = richHistory(2_000)
        val codec = codec()
        val bytes = codec.encode(project)
        assertTrue(bytes.size < 16 * 1024 * 1024)
        println("rich history: current=${project.current.id}, undo=${project.undo.size}, cues=2000, bytes=${bytes.size}")
        val reopened = codec.decode(bytes)
        assertEquals(50L, reopened.current.id)
        assertEquals(50, reopened.undo.size)
        assertEquals("cue-1999", reopened.current.textState.captions.last().id)
        assertEquals(2_000_000L, reopened.current.textState.captions.last().endUs)
        assertEquals(project, reopened)
        assertSame(reopened.original, reopened.undo.first())
        // Establish the independent outer writer is valid before using it for the
        // over-capacity reader case below.
        assertEquals(project, codec.decode(externalManifest(project, codec)))
    }

    @Test fun fullRichHistoryCreatesSavesReopensAndRetainsRevisionSnapshots() {
        val files = temporary.newFolder()
        val store = HybridProjectStore(files)
        val initial = richHistory(2_000)
        materialize(store, initial)
        store.create(initial)
        val edited = HybridEditCommands.commitRevision(initial,
            initial.current.copy(textState = initial.current.textState.copy(language = "ru")))
        val exported = edited.copy(exports = listOf(ProjectExportRef(50, "captions.mp4",
            ProjectExportSettings(1080, 1920, 30, 8_000_000, 192_000))))
        store.save(exported, 50)
        val reopenedStore = HybridProjectStore(files)
        val reopened = reopenedStore.load(initial.id)
        assertEquals(exported, reopened)
        assertEquals("ru", reopened.current.textState.language)
        assertEquals(50, reopened.undo.size)
        assertEquals(2_000, reopened.original.textState.captions.size)
        assertEquals(initial.current, reopenedStore.loadRevision(initial.id, reopened.exports.single().revisionId))
        assertEquals(initial.original, reopenedStore.loadRevision(initial.id, 0))
        val undone = HybridEditCommands.undo(reopened)
        reopenedStore.save(undone, 51)
        val undoneLoad = reopenedStore.load(initial.id)
        assertEquals("auto", undoneLoad.current.textState.language)
        assertEquals("ru", undoneLoad.redo.single().textState.language)
        assertEquals(exported.current, HybridEditCommands.redo(undoneLoad).current)
    }

    @Test fun twoRichListsPerRevisionFitTheSameBoundedHistoryCapacity() {
        val project = richHistory(2_000, withLayers = true)
        val codec = codec()
        val bytes = codec.encode(project)
        assertTrue(bytes.size < 16 * 1024 * 1024)
        println("two rich lists: revisions=51, layers=2000, cues=2000, bytes=${bytes.size}")
        val reopened = codec.decode(bytes)
        assertEquals(project, reopened)
        assertEquals(2_000, reopened.current.textState.layers.size)
        assertEquals(2_000, reopened.original.textState.captions.size)
        assertEquals("cue-1999", reopened.undo[25].textState.layers.last().id)
    }

    @Test fun unsupportedAggregateIsRejectedByBothCodecsWithoutMovingCurrent() {
        val oversized = richHistory(6_000)
        val initial = richOriginal(6_000)
        val codec = codec()
        val store = HybridProjectStore(temporary.newFolder())
        materialize(store, initial)
        store.create(initial)
        val pointer = File(store.directory(initial.id), "CURRENT")
        val beforePointer = pointer.readBytes()
        val beforeFiles = store.directory(initial.id).walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(store.directory(initial.id)).path to it.readBytes() }

        val rejected = assertThrows(IllegalArgumentException::class.java) { codec.encode(oversized) }
        // Every individual revision is within capacity. Assemble an external v4 manifest
        // to exercise the reader's aggregate guard independently of its paired writer.
        val external = externalManifest(oversized, codec)
        assertTrue(external.size < 16 * 1024 * 1024)
        println("unsupported aggregate: cues=306000, bytes=${external.size}, reason=${rejected.message}")
        assertThrows(IllegalArgumentException::class.java) { codec.decode(external) }
        assertThrows(IllegalArgumentException::class.java) { store.save(oversized, 0) }
        assertArrayEquals(beforePointer, pointer.readBytes())
        assertEquals(beforeFiles.keys, store.directory(initial.id).walkTopDown().filter { it.isFile }
            .map { it.relativeTo(store.directory(initial.id)).path }.toSet())
        beforeFiles.forEach { (path, bytes) -> assertArrayEquals(bytes, File(store.directory(initial.id), path).readBytes()) }
        assertEquals(initial, store.load(initial.id))
    }

    @Test fun perListAndMalformedByteLimitsRemainEnforced() {
        val codec = codec()
        val perListOverflow = richOriginal(10_001)
        assertThrows(IllegalArgumentException::class.java) { codec.encode(perListOverflow) }
        val bytes = codec.encode(richOriginal(2_000))
        val unknown = bytes.copyOf()
        java.nio.ByteBuffer.wrap(unknown).putInt(4, 99)
        assertThrows(IllegalArgumentException::class.java) { codec.decode(unknown) }
        assertThrows(java.io.EOFException::class.java) { codec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(ByteArray(16 * 1024 * 1024 + 1)) }
        // After magic/version, the first field is a bounded project-ID string length.
        val malformed = bytes.copyOf()
        java.nio.ByteBuffer.wrap(malformed).putInt(8, Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { codec.decode(malformed) }
        val tooManyAssets = bytes.copyOf()
        // v4 header, project-ID string, fps, nextRevisionId, then asset count.
        val assetCountOffset = 8 + 4 + "project".toByteArray(Charsets.UTF_8).size + 4 + 8
        java.nio.ByteBuffer.wrap(tooManyAssets).putInt(assetCountOffset, 10_001)
        assertThrows(IllegalArgumentException::class.java) { codec.decode(tooManyAssets) }
    }

    private fun richOriginal(count: Int, withLayers: Boolean = false): HybridProject {
        val base = editProject()
        val stepUs = if (count <= 2_000) 1_000L else 200L
        val cues = List(count) { CaptionCue("cue-$it", "Caption $it", it * stepUs, (it + 1) * stepUs) }
        val layers = if (withLayers) cues.map { TextLayer(it.id, it.text, it.startUs, it.endUs) } else emptyList()
        val revision = base.current.copy(textState = HybridTextState(layers = layers, captions = cues))
        return base.copy(original = revision, current = revision)
    }

    private fun richHistory(count: Int, withLayers: Boolean = false): HybridProject {
        var project = richOriginal(count, withLayers)
        repeat(50) {
            project = HybridEditCommands.commitRevision(project, project.current.copy(
                textState = project.current.textState.copy(captionsEdited = !project.current.textState.captionsEdited)))
        }
        return project
    }

    private fun codec() = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))

    private fun materialize(store: HybridProjectStore, project: HybridProject) {
        val sources = File(store.directory(project.id), "sources").apply { mkdirs() }
        project.assets.forEach { File(sources, it.fileName).writeText("synthetic source") }
    }

    /** Test-only independent outer writer; no allocation or decoding limit is bypassed in production. */
    private fun externalManifest(project: HybridProject, codec: HybridProjectCodec): ByteArray {
        val revisions = (listOf(project.original, project.current) + project.undo + project.redo).distinctBy { it.id }
        val blobs = codec.encodeRevisions(revisions)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun string(value: String) { val data = value.toByteArray(Charsets.UTF_8); out.writeInt(data.size); out.write(data) }
            out.writeInt(0x56485942); out.writeInt(4)
            string(project.id); out.writeInt(project.fps); out.writeLong(project.nextRevisionId)
            out.writeInt(project.assets.size)
            project.assets.forEach {
                require(it.videoMetadata == null)
                string(it.id); string(it.fileName); string(it.kind.name); out.writeLong(it.durationUs)
                string(it.contentHash); string(it.displayName); out.writeBoolean(false)
            }
            require(project.selectedVideos == null)
            out.writeBoolean(false)
            out.writeInt(revisions.size)
            revisions.forEach { val blob = blobs.getValue(it.id); out.write(blob, 8, blob.size - 8) }
            out.writeLong(project.original.id); out.writeLong(project.current.id)
            out.writeInt(project.undo.size); project.undo.forEach { out.writeLong(it.id) }
            out.writeInt(project.redo.size); project.redo.forEach { out.writeLong(it.id) }
            require(project.exports.isEmpty()); out.writeInt(0)
        }
        return bytes.toByteArray()
    }
}
