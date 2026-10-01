package com.example.autoedit

import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class CompletedRenderStoreTest {
    @Test fun completed_result_survives_scratch_and_source_cleanup_and_restores_saved_state() {
        val root = createTempDirectory("completed-render").toFile()
        try {
            val cache = root.resolve("cache").apply { mkdirs() }
            val source = RenderWorkspace.pendingRendersDirectory(root).resolve("render.mp4")
            source.writeBytes(byteArrayOf(1, 2, 3))
            val entry = CompletedRenderStore.publish(root, source, "DUALITY · проверено")
            RenderWorkspace.clearTransient(root, cache)
            RenderWorkspace.clearSourceDraft(root)
            val restored = CompletedRenderStore.latest(root)!!
            assertEquals(entry, restored)
            assertArrayEquals(byteArrayOf(1, 2, 3), restored.file.readBytes())
            CompletedRenderStore.markSaved(restored)
            assertTrue(CompletedRenderStore.latest(root)!!.saved)
        } finally { root.deleteRecursively() }
    }

    @Test fun failed_publication_does_not_replace_previous_completed_result() {
        val root = createTempDirectory("completed-render").toFile()
        try {
            val source = root.resolve("good.mp4").apply { writeBytes(byteArrayOf(7)) }
            val previous = CompletedRenderStore.publish(root, source, "готово")
            val empty = root.resolve("empty.mp4").apply { writeBytes(byteArrayOf()) }
            val directory = root.resolve("directory.mp4").apply { mkdirs() }
            for (invalid in listOf(root.resolve("missing.mp4"), empty, directory)) {
                assertThrows(IllegalArgumentException::class.java) {
                    CompletedRenderStore.publish(root, invalid, "bad")
                }
                assertEquals(previous, CompletedRenderStore.latest(root))
                assertArrayEquals(byteArrayOf(7), previous.file.readBytes())
                assertTrue(root.resolve("completed-renders").listFiles()!!.none { it.name.endsWith(".partial") })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun process_death_between_mp4_publication_and_metadata_keeps_result_recoverable() {
        val root = createTempDirectory("completed-render").toFile()
        try {
            val directory = root.resolve("completed-renders").apply { mkdirs() }
            val published = directory.resolve("published.mp4").apply { writeBytes(byteArrayOf(9)) }
            directory.resolve("unfinished.mp4.partial").writeBytes(byteArrayOf(1))
            val restored = CompletedRenderStore.latest(root)!!
            assertEquals(published, restored.file)
            assertFalse(restored.saved)
        } finally { root.deleteRecursively() }
    }

    @Test fun latest_ignores_empty_and_partial_files_and_saved_state_belongs_to_each_result() {
        val root = createTempDirectory("completed-render").toFile()
        try {
            assertNull(CompletedRenderStore.latest(root))
            val old = CompletedRenderStore.publish(root,
                root.resolve("old.mp4").apply { writeBytes(byteArrayOf(1)) }, "Первый монтаж")
            CompletedRenderStore.markSaved(old)
            val newer = CompletedRenderStore.publish(root,
                root.resolve("new.mp4").apply { writeBytes(byteArrayOf(2)) }, "Новый монтаж ♥")
            assertTrue(old.file.setLastModified(1_000L))
            assertTrue(newer.file.setLastModified(2_000L))
            val directory = newer.file.parentFile
            directory.resolve("empty.mp4").writeBytes(byteArrayOf())
            directory.resolve("unfinished.mp4.partial").writeBytes(byteArrayOf(9))
            directory.resolve("other.txt").writeBytes(byteArrayOf(8))
            assertEquals(newer, CompletedRenderStore.latest(root))
            assertFalse(CompletedRenderStore.latest(root)!!.saved)
            assertTrue(newer.file.delete())
            assertEquals(old.copy(saved = true), CompletedRenderStore.latest(root))
        } finally { root.deleteRecursively() }
    }
}
