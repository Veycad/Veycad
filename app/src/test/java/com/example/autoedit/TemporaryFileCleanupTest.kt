package com.veycad.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlin.io.path.createTempDirectory

class TemporaryFileCleanupTest {
    private fun fixture(action: (File, File) -> Unit) {
        val root = createTempDirectory("cleanup-policy").toFile()
        try { action(File(root, "files").apply { mkdirs() }, File(root, "cache").apply { mkdirs() }) }
        finally { root.deleteRecursively() }
    }
    private fun put(root: File, path: String) = File(root, path).apply {
        parentFile!!.mkdirs(); writeBytes(byteArrayOf(3, 9, 27))
    }

    @Test fun clearsOnlyDeclaredTargets() = fixture { files, cache ->
        val remove = listOf(put(files, "source-draft/source.mp4"), put(files, "pending-renders/job/out.partial"),
            put(cache, "imports/a"), put(cache, "full-video-analysis/b"), put(cache, "text-work/c"))
        val preserve = listOf("completed-renders", "my-edits", "text-sources", "text-drafts", "capture-sessions",
            "projects", "built-in-music", "speech-model", "diagnostics").map { put(files, "$it/keep") } +
            listOf("unknown/keep", "multiclass-decoded-frames/frame.argb", "multiclass-matte.bin", "exported-report.json")
                .map { put(cache, it) }
        assertEquals(15L, TemporaryFileCleanup.estimateBytes(files, cache, TemporaryFileCleanup.Scope.ALL_TEMPORARY))
        requireNotNull(MediaWorkCoordinator().tryBeginCleanup()).use { lease ->
            val result = TemporaryFileCleanup.clear(files, cache, TemporaryFileCleanup.Scope.ALL_TEMPORARY, lease)
            assertTrue(result.failures.toString(), result.failures.isEmpty())
            assertEquals(TemporaryFileCleanup.DraftDisposition.REMOVED, result.draft)
        }
        remove.forEach { assertFalse(it.path, it.exists()) }
        preserve.forEach { assertArrayEquals(it.path, byteArrayOf(3, 9, 27), it.readBytes()) }
    }

    @Test fun narrowScopesPreserveOtherWorkspaces() = fixture { files, cache ->
        val source = put(files, "source-draft/source.mp4")
        val text = put(cache, "text-work/pcm.raw")
        val pending = put(files, "pending-renders/out.partial")
        requireNotNull(MediaWorkCoordinator().tryBeginCleanup()).use {
            TemporaryFileCleanup.clear(files, cache, TemporaryFileCleanup.Scope.TRANSIENT_RENDER, it)
        }
        assertTrue(source.exists()); assertTrue(text.exists()); assertFalse(pending.exists())
    }

    @Test fun retiredProjectScratchIsBoundedAndDeniedWhileAConsumerOwnsWork() = fixture { files, cache ->
        val removed = listOf(put(cache, "preview-floats-123.tmp"), put(cache, "manual-renders/preview-audio/project-audio-1.wav"),
            put(cache, "project-speech-work/project-speech-1.pcm"), put(cache, "project-audio-work/project-audio-2.wav"),
            put(files, ".project-restore-12345678-1234-1234-1234-123456789abc/backup.zip"))
        val preserved = listOf(put(files, "projects/durable/CURRENT"), put(files, "completed-renders/output.mp4"),
            put(cache, "preview-floats-report.txt"), put(files, ".project-restore-user-notes/keep"), put(cache, "smart-framing/track"))
        val coordinator = MediaWorkCoordinator()
        val consumer = requireNotNull(coordinator.tryBeginWork())
        assertNull("Live native/job owner prevents any cleanup", coordinator.tryBeginCleanup())
        removed.forEach { assertTrue(it.exists()) }
        consumer.close()
        requireNotNull(coordinator.tryBeginCleanup()).use {
            assertTrue(TemporaryFileCleanup.clear(files, cache, TemporaryFileCleanup.Scope.TRANSIENT_RENDER, it).failures.isEmpty())
        }
        removed.forEach { assertFalse(it.path, it.exists()) }
        preserved.forEach { assertArrayEquals(it.path, byteArrayOf(3, 9, 27), it.readBytes()) }
    }

    @Test fun rejectsClosedOrWorkLease() = fixture { files, cache ->
        val source = put(files, "source-draft/source.mp4")
        val owner = MediaWorkCoordinator()
        val closed = requireNotNull(owner.tryBeginCleanup()).also { it.close() }
        assertThrows(IllegalStateException::class.java) {
            TemporaryFileCleanup.clear(files, cache, TemporaryFileCleanup.Scope.ALL_TEMPORARY, closed)
        }
        requireNotNull(owner.tryBeginWork()).use {
            assertThrows(IllegalStateException::class.java) {
                TemporaryFileCleanup.clear(files, cache, TemporaryFileCleanup.Scope.ALL_TEMPORARY, it)
            }
        }
        assertTrue(source.exists())
    }

    @Test fun reportsPartialDeletion() = fixture { files, cache ->
        val held = put(files, "source-draft/held.mp4")
        requireNotNull(MediaWorkCoordinator().tryBeginCleanup()).use {
            val report = TemporaryFileCleanup.clear(files, cache, TemporaryFileCleanup.Scope.SOURCE_DRAFT, it) { path ->
                if (path == held.toPath()) throw IOException("injected deletion failure") else Files.delete(path)
            }
            assertFalse(report.failures.isEmpty())
            assertEquals(TemporaryFileCleanup.DraftDisposition.INCOMPLETE, report.draft)
            assertTrue(held.exists())
        }
    }

    @Test fun estimateUsesSameTargetsWithoutFollowingLinks() = fixture { files, cache ->
        val protected = put(files, "projects/protected.bin")
        val owned = put(cache, "text-work/pcm.raw")
        val link = File(owned.parentFile, "external")
        try { Files.createSymbolicLink(link.toPath(), protected.parentFile.toPath()) }
        catch (failure: java.nio.file.FileSystemException) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw failure
            // Windows junctions need no symlink privilege and exercise a real directory escape.
            val process = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.path, protected.parent)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
        }
        assertEquals(3L, TemporaryFileCleanup.estimateBytes(files, cache, TemporaryFileCleanup.Scope.ALL_TEMPORARY))
        requireNotNull(MediaWorkCoordinator().tryBeginCleanup()).use {
            val report = TemporaryFileCleanup.clear(files, cache, TemporaryFileCleanup.Scope.ALL_TEMPORARY, it)
            assertFalse(report.failures.isEmpty())
        }
        assertTrue(link.exists())
        assertEquals(protected.parentFile.canonicalFile, link.canonicalFile)
        assertArrayEquals(byteArrayOf(3, 9, 27), protected.readBytes())
    }
}
