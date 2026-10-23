package com.veycad.app

import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridTextLegacyCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun genuineA531V4TextHistoryExportsAndDefaultsSurviveV7Save() = checkCapture(4)

    @Test fun genuinePrecedingV6ManualTimingAndTextSurviveV7Save() = checkCapture(6)

    private fun checkCapture(format: Int) {
        val resource = "/hybrid-text-v$format/"
        String(read(resource + "SHA256SUMS.tsv"), Charsets.UTF_8).lineSequence().drop(1)
            .filter { it.isNotBlank() }.forEach { line ->
                val (hash, size, path) = line.split('\t')
                val value = read(resource + path)
                assertEquals(size.toInt(), value.size)
                assertEquals(hash, sha256(value))
            }
        val files = unpack(resource + "store-final.zip")
        val historyFiles = unpack(resource + "store-history.zip")
        val store = HybridProjectStore(files)
        val dir = store.directory("project")
        val before = inventory(files)
        val loaded = store.load("project")
        assertEquals(0L, loaded.original.id)
        assertEquals(3L, loaded.current.id)
        assertEquals(listOf(0L), loaded.undo.map { it.id })
        assertTrue(loaded.redo.isEmpty())
        assertEquals(4L, loaded.nextRevisionId)
        assertEquals(1L, loaded.exports.single().revisionId)
        val codec = HybridProjectCodec(AnalysisSidecarStore(File(dir, "analysis")), AuthoredSourceMapStore(File(dir, "authored-maps")))
        assertEquals(loaded, codec.decode(read(resource + "project.bin")))
        assertArrayEquals(read(resource + "project.bin"), manifestProject(dir))

        val old = (0L..3L).associateWith { id ->
            val payload = read(resource + "revision-$id.bin")
            assertEquals(format, ByteBuffer.wrap(payload).getInt(4))
            assertEquals(payload.size.toLong(), File(dir, "revisions/${sha256(payload)}.bin").length())
            assertArrayEquals(payload, File(dir, "revisions/${sha256(payload)}.bin").readBytes())
            val revision = store.loadRevision("project", id)
            assertEquals(revision, codec.decodeRevision(payload))
            assertEquals(legacyText(id.toInt()), revision.textState)
            assertEquals(listOf(0, -2, 3, -1)[id.toInt()], revision.clips.single().originalFrameOffset)
            assertEquals(100_000L, revision.clips.single().sourceMap.sample(0))
            assertEquals(400_000L, revision.clips.single().sourceMap.sample(15))
            assertEquals(1_100_000L, revision.clips.single().sourceMap.sample(30))
            if (format == 6) {
                assertEquals(1, revision.sourceBackings.size)
                assertNotNull(revision.graph.manualOverrides)
                assertEquals(3, revision.graph.manualMontageState!!.effects.size)
                assertEquals(listOf(100_000L, 100_000L, 100_100L),
                    revision.graph.editableTiming!!.clips.single().timeMap.sourceUsByFrame.take(3))
                assertEquals(80_000L, loaded.assets.first().videoMetadata!!.firstVideoPtsUs)
                assertEquals(1_800_000L, loaded.assets.first().videoMetadata!!.videoEndPtsUs)
            } else {
                assertTrue(revision.sourceBackings.isEmpty())
                assertNull(revision.graph.manualMontageState)
                assertNull(revision.graph.editableTiming)
            }
            revision
        }
        val historyBefore = inventory(historyFiles)
        val history = HybridProjectStore(historyFiles).load("project")
        assertEquals(1L, history.current.id)
        assertEquals(listOf(0L), history.undo.map { it.id })
        assertEquals(listOf(2L), history.redo.map { it.id })
        assertEquals(3L, history.nextRevisionId)
        assertEquals(old.getValue(1), history.current)
        assertEquals(old.getValue(2), history.redo.single())
        assertArrayEquals(read(resource + "project-history.bin"), manifestProject(HybridProjectStore(historyFiles).directory("project")))
        assertInventory(historyBefore, historyFiles)
        assertInventory(before, files)

        val rich = HybridTextState(
            layers = listOf(TextLayer("new-title", "Новый 😀", 0, 900_000,
                TextStyle(font = TextFont.MONO, animation = TextAnimation.SCALE, darkPlate = false, plate = TextPlate.ACCENT), TextLayerKind.TITLE)),
            captions = listOf(CaptionCue("new-cue", "Reviewed", 0, 1_000_000, .74, true, false)),
            captionStyle = TextStyle(darkPlate = true, plate = TextPlate.NONE), captionsEdited = true, language = "ru")
        val changed = HybridEditCommands.commitRevision(loaded, loaded.current.copy(textState = rich))
        store.save(changed, loaded.current.id)
        val reopened = HybridProjectStore(files).load("project")
        assertEquals(changed, reopened)
        assertEquals(8, ByteBuffer.wrap(manifestProject(dir)).getInt(4))
        assertEquals(rich, reopened.current.textState)
        assertEquals(loaded.current.graph, reopened.current.graph)
        assertEquals(loaded.current.clips, reopened.current.clips)
        val pruned = HybridEditCommands.commitRevision(reopened,
            reopened.current.copy(textState = rich.copy(language = "en")))
            .let { it.copy(undo = listOf(it.original), redo = emptyList()) }
        store.save(pruned, reopened.current.id)
        assertEquals(pruned, HybridProjectStore(files).load("project"))
        old.forEach { (id, revision) -> assertEquals(revision, HybridProjectStore(files).loadRevision("project", id)) }
        before.filterKeys { it != "projects/project/CURRENT" }.forEach { (path, hash) ->
            assertEquals("Retained captured bytes: $path", hash, sha256(File(files, path).readBytes()))
        }
        val after = inventory(files)
        HybridProjectStore(files).load("project")
        assertInventory(after, files)
    }

    private fun legacyText(id: Int) = HybridTextState(
        layers = listOf(
            TextLayer("mono-r$id", "R$id Привет 🐈 MONO/SCALE", 0, 900_000,
                TextStyle(TextPosition.TOP, TextFont.MONO, .025f, 0xff2b7c91.toInt(), false, TextAnimation.SCALE)),
            TextLayer("control-r$id", "R$id BOLD/SLIDE control", 100_000, 1_000_000,
                TextStyle(TextPosition.CENTER, TextFont.BOLD, .12f, 0xffd83456.toInt(), true, TextAnimation.SLIDE))),
        captions = listOf(CaptionCue("cue-a-r$id", "R$id Начало", 0, 500_000), CaptionCue("cue-b-r$id", "R$id End", 500_000, 1_000_000)),
        captionStyle = TextStyle(TextPosition.BOTTOM, TextFont.MONO, if (id % 2 == 0) .12f else .025f,
            0xffeab321.toInt(), id % 2 == 0, TextAnimation.SCALE), captionsEdited = id % 2 != 0,
        language = if (id % 2 == 0) "ru" else "en")

    private fun unpack(resource: String): File = temporary.newFolder().also { root ->
        val names = hashSetOf<String>()
        ZipInputStream(openResource(resource)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(names.add(entry.name) && !entry.isDirectory)
                val destination = File(root, entry.name).canonicalFile
                require(destination.toPath().startsWith(root.canonicalFile.toPath()))
                destination.parentFile.mkdirs()
                destination.writeBytes(zip.readBytesBounded(65_536))
                zip.closeEntry()
            }
        }
        assertTrue("Authoritative ZIP includes hidden lock", "projects/.store.lock" in names)
    }

    private fun manifestProject(dir: File): ByteArray {
        val pointer = File(dir, "CURRENT").readText(Charsets.US_ASCII)
        return DataInputStream(File(dir, "manifests/$pointer").inputStream()).use { input ->
            require(input.readInt() == 0x56485354 && input.readInt() == 1)
            ByteArray(input.readInt()).also { input.readFully(it) }
        }
    }

    private fun openResource(path: String) = checkNotNull(javaClass.getResourceAsStream(path)) {
        "Missing packaged fixture resource: $path"
    }
    private fun read(path: String) = openResource(path).use { it.readBytes() }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun inventory(root: File) = root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to sha256(it.readBytes())
    }
    private fun assertInventory(expected: Map<String, String>, root: File) = assertEquals(expected, inventory(root))
}
