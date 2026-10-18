package com.veycad.app

import java.io.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val oldBytes = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
    private val newBytes = listOf(byteArrayOf(6, 7), byteArrayOf(8, 9, 10))
    private fun seed(base: File): SourceDraftManifest.Snapshot {
        val root = File(base, "source-draft").apply { mkdirs() }
        val id = UUID.randomUUID()
        val entries = oldBytes.mapIndexed { index, bytes ->
            val file = File(root, "source-$index-$id.mp4").apply { writeBytes(bytes) }
            SourceDraftManifest.Entry(file.name, "old-$index", bytes.size.toLong())
        }
        return SourceDraftManifest.Snapshot(id, SourceDraftManifest.Origin.IMPORT, entries).also {
            atomicWrite(File(root, "current.json"), SourceDraftManifest.encode(it))
        }
    }
    private fun inputs() = newBytes.mapIndexed { i, bytes -> SourceDraftStore.Input("new-$i") { bytes.inputStream() } }
    private fun assertOld(base: File, old: SourceDraftManifest.Snapshot) {
        assertEquals(old, SourceDraftStore(base).read())
        old.entries.forEachIndexed { i, e -> assertArrayEquals(oldBytes[i], File(base, "source-draft/${e.fileName}").readBytes()) }
    }
    @Test fun oldSelectionSurvivesEveryPreCommitFailure() {
        for (fault in listOf("input0", "input1", "write", "sync", "music", "replace", "atomic")) {
            val base = temporary.newFolder()
            val old = seed(base)
            var copies = 0
            val io = object : SourceDraftStore.Io by SourceDraftStore.DiskIo {
                override fun copyAndSync(input: InputStream, target: File) {
                    copies++
                    if (fault == "write") { target.writeBytes(byteArrayOf(42)); throw IOException("write") }
                    SourceDraftStore.DiskIo.copyAndSync(input, target)
                    if (fault == "sync") throw IOException("sync")
                }
                override fun replaceManifest(target: File, bytes: ByteArray) {
                    if (fault == "atomic") throw AtomicMoveNotSupportedException("a", "b", "test")
                    if (fault == "replace") throw IOException("replace")
                    SourceDraftStore.DiskIo.replaceManifest(target, bytes)
                }
            }
            val selected = inputs().mapIndexed { i, input ->
                if (fault == "input$i") SourceDraftStore.Input(input.displayName) { throw IOException("input$i") } else input
            }
            val result = SourceDraftStore(base, io).replace(selected, 2) { if (fault == "music") throw IOException("music"); "music" }
            assertTrue("$fault: $result", result is SourceDraftStore.ReplaceResult.Rejected)
            assertOld(base, old)
            assertEquals(3, File(base, "source-draft").listFiles()!!.size)
        }
    }
    @Test fun throwAfterReplaceKeepsCommittedSources() {
        val base = temporary.newFolder(); seed(base)
        val io = object : SourceDraftStore.Io by SourceDraftStore.DiskIo {
            override fun replaceManifest(target: File, bytes: ByteArray) {
                atomicWrite(target, bytes); throw IOException("after replace")
            }
        }
        val result = SourceDraftStore(base, io).replace(inputs(), 2) { "ready music" }
        assertTrue(result is SourceDraftStore.ReplaceResult.Published)
        result as SourceDraftStore.ReplaceResult.Published
        assertEquals("ready music", result.prepared)
        assertEquals(result.snapshot, SourceDraftStore(base).read())
        result.snapshot.entries.forEachIndexed { i, e -> assertArrayEquals(newBytes[i], File(base, "source-draft/${e.fileName}").readBytes()) }
    }
    @Test fun unreadableCommitStatusPreservesBothSets() {
        val base = temporary.newFolder(); val old = seed(base)
        var replaced = false
        val io = object : SourceDraftStore.Io by SourceDraftStore.DiskIo {
            override fun replaceManifest(target: File, bytes: ByteArray) { atomicWrite(target, bytes); replaced = true; throw IOException("uncertain") }
            override fun readManifest(target: File): ByteArray {
                if (replaced) throw IOException("read failed")
                return SourceDraftStore.DiskIo.readManifest(target)
            }
        }
        assertTrue(SourceDraftStore(base, io).replace(inputs(), 2) { Unit } is SourceDraftStore.ReplaceResult.Uncertain)
        assertTrue(old.entries.all { File(base, "source-draft/${it.fileName}").exists() })
        assertEquals(5, File(base, "source-draft").listFiles()!!.size)
        assertEquals(listOf("new-0", "new-1"), SourceDraftStore(base).read()!!.entries.map { it.displayName })
    }
    @Test fun invalidCountsEmptySourcesAndOversizeDoNotChangeSelection() {
        val base = temporary.newFolder(); val old = seed(base)
        val store = SourceDraftStore(base)
        for ((sources, count) in listOf(emptyList<SourceDraftStore.Input>() to 1, inputs() to 1,
            inputs() to 3, listOf(SourceDraftStore.Input("empty") { ByteArrayInputStream(byteArrayOf()) }) to 1,
            listOf(SourceDraftStore.Input("я".repeat(65_536)) { byteArrayOf(1).inputStream() }) to 1)) {
            assertTrue(store.replace(sources, count) { Unit } is SourceDraftStore.ReplaceResult.Rejected)
            assertOld(base, old)
        }
    }
    @Test fun streamsCloseAndPreparedFilesAreNeverSelectedBeforeCommit() {
        val base = temporary.newFolder(); val old = seed(base)
        var closed = false
        val input = SourceDraftStore.Input("name") { object : ByteArrayInputStream(byteArrayOf(9)) {
            override fun close() { closed = true; super.close() }
        } }
        val io = object : SourceDraftStore.Io by SourceDraftStore.DiskIo {
            override fun checkpoint(phase: SourceDraftStore.Phase) {
                if (phase == SourceDraftStore.Phase.FILES_PREPARED) {
                    assertTrue(closed); assertOld(base, old); throw IOException("stop")
                }
            }
        }
        assertTrue(SourceDraftStore(base, io).replace(listOf(input), 1) { Unit } is SourceDraftStore.ReplaceResult.Rejected)
        assertOld(base, old)
    }
    @Test fun corruptManifestCanBeReplacedButFailedReplacementPreservesIt() {
        val base = temporary.newFolder(); val old = seed(base)
        val pointer = File(base, "source-draft/current.json").apply { writeText("broken") }
        assertThrows(IOException::class.java) { SourceDraftStore(base).read() }
        assertTrue(SourceDraftStore(base).replace(inputs(), 2) { throw IOException("music") } is SourceDraftStore.ReplaceResult.Rejected)
        assertEquals("broken", pointer.readText())
        assertTrue(old.entries.all { File(pointer.parentFile, it.fileName).exists() })
        assertTrue(SourceDraftStore(base).replace(inputs(), 2) { Unit } is SourceDraftStore.ReplaceResult.Published)
    }
    @Test fun sourceAndRootLinksCannotEscape() {
        val base = temporary.newFolder(); val old = seed(base)
        if (System.getProperty("os.name").startsWith("Windows")) {
            val linkedBase = temporary.newFolder()
            val link = File(linkedBase, "source-draft")
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.path, File(base, "source-draft").path)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
            assertThrows(IOException::class.java) { SourceDraftStore(linkedBase).read() }
            assertOld(base, old)
            return // File symlinks are exercised on Android; Windows supplies a real junction.
        }
        val outside = temporary.newFile().apply { writeBytes(oldBytes[0]) }
        val path = File(base, "source-draft/${old.entries[0].fileName}").toPath()
        Files.delete(path)
        Files.createSymbolicLink(path, outside.toPath())
        assertThrows(IOException::class.java) { SourceDraftStore(base).read() }
        assertArrayEquals(oldBytes[0], outside.readBytes())
        val linkedBase = temporary.newFolder()
        Files.createSymbolicLink(File(linkedBase, "source-draft").toPath(), File(base, "source-draft").toPath())
        assertThrows(IOException::class.java) { SourceDraftStore(linkedBase).read() }
    }
    @Test fun mismatchedLengthAndNonRegularPointerAreRejected() {
        val base = temporary.newFolder(); val old = seed(base)
        File(base, "source-draft/${old.entries[0].fileName}").appendText("changed")
        assertThrows(IOException::class.java) { SourceDraftStore(base).read() }
        File(base, "source-draft/current.json").apply { delete(); mkdir() }
        assertThrows(IOException::class.java) { SourceDraftStore(base).read() }
    }
}
