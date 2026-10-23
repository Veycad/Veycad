package com.veycad.app

import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridV7CompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun genuineMusicV7ProjectAndImmutableRevisionsMigrateWithoutLosingOffset() = checkCapture("music")

    @Test fun genuineRichTextV7ProjectAndImmutableRevisionsMigrateWithoutLosingText() = checkCapture("rich-text")

    private fun checkCapture(layout: String) {
        resource(layout, "SHA256SUMS.tsv").toString(Charsets.UTF_8).lineSequence().drop(1)
            .filter { it.isNotBlank() }.forEach { line ->
                val (hash, size, path) = line.split('\t')
                val bytes = resource(layout, path)
                assertEquals(size.toInt(), bytes.size)
                assertEquals(hash, contentHash(bytes))
            }
        val root = temporary.newFolder()
        ZipInputStream(resource(layout, "store.zip").inputStream()).use { zip ->
            val names = hashSetOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && names.add(entry.name))
                val target = File(root, entry.name).canonicalFile
                require(target.toPath().startsWith(root.canonicalFile.toPath()))
                target.parentFile.mkdirs()
                target.writeBytes(zip.readBytesBounded(65_536))
            }
        }
        val before = inventory(root)
        val store = HybridProjectStore(root)
        val dir = store.directory("project")
        val codec = HybridProjectCodec(AnalysisSidecarStore(File(dir, "analysis")),
            AuthoredSourceMapStore(File(dir, "authored-maps")))
        val loaded = store.load("project")
        val expectedOffset = if (layout == "music") 1_234_567L else 0L
        val rich = layout == "rich-text"
        val projectBytes = resource(layout, "project.bin")
        assertEquals(7, ByteBuffer.wrap(projectBytes).getInt(4))
        assertEquals(loaded, codec.decode(projectBytes))
        assertEquals(1L, loaded.current.id)
        assertEquals(listOf(0L), loaded.undo.map { it.id })
        assertEquals(0L, loaded.exports.single().revisionId)
        assertEquals(-2, loaded.current.clips.single().originalFrameOffset)
        for (revision in listOf(loaded.original, loaded.current)) {
            val bytes = resource(layout, "revision-${revision.id}.bin")
            assertEquals(7, ByteBuffer.wrap(bytes).getInt(4))
            assertEquals(revision, codec.decodeRevision(bytes))
            assertEquals(revision, store.loadRevision("project", revision.id))
            assertEquals(expectedOffset, revision.graph.audioTrack!!.sourceStartUs)
            val layer = revision.textState.layers.single()
            assertEquals(TextFont.MONO, layer.style.font)
            assertEquals(TextAnimation.SCALE, layer.style.animation)
            assertEquals(if (rich) TextPlate.ACCENT else null, layer.style.plate)
            assertEquals(if (rich) TextLayerKind.TITLE else TextLayerKind.LEGACY, layer.kind)
            assertEquals(if (rich) .74 else null, revision.textState.captions.single().confidence)
            assertEquals(rich, revision.textState.captions.single().manuallyReviewed)
            for (corrupt in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0))) {
                assertThrows(IllegalArgumentException::class.java) { codec.decodeRevision(corrupt) }
            }
        }
        for (corrupt in listOf(projectBytes.copyOf(projectBytes.size - 1), projectBytes + byteArrayOf(0))) {
            assertThrows(IllegalArgumentException::class.java) { codec.decode(corrupt) }
        }
        assertEquals(before, inventory(root))

        val changed = HybridEditCommands.commitRevision(loaded, loaded.current.copy(
            graph = loaded.current.graph.copy(audioTrack = MontageGraph.AudioTrack("music", .7f, 765_432L)),
            textState = loaded.current.textState.copy(layers = listOf(TextLayer("v8", "V8", 0, 900_000,
                TextStyle(plate = TextPlate.DARK), TextLayerKind.PLATE)))))
        store.save(changed, loaded.current.id)
        val reopened = HybridProjectStore(root).load("project")
        assertEquals(changed, reopened)
        assertEquals(9, ByteBuffer.wrap(codec.encode(reopened)).getInt(4))
        assertEquals(765_432L, reopened.current.graph.audioTrack!!.sourceStartUs)
        assertEquals(TextPlate.DARK, reopened.current.textState.layers.single().style.plate)
        assertEquals(changed.current, codec.decodeRevision(codec.encodeRevisions(listOf(changed.current)).getValue(changed.current.id)))
        for (revision in listOf(loaded.original, loaded.current)) {
            assertEquals(revision, store.loadRevision("project", revision.id))
        }
        before.filterKeys { it != "projects/project/CURRENT" }.forEach { (name, hash) ->
            assertEquals("Retained old bytes: $name", hash, contentHash(File(root, name).readBytes()))
        }
    }

    private fun resource(layout: String, name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/hybrid-v7-$layout/$name")).use { it.readBytes() }

    private fun inventory(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to contentHash(it.readBytes())
    }
}
