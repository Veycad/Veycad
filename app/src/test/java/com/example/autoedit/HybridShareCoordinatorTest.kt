package com.veycad.app

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.*
import org.junit.Test

class HybridShareCoordinatorTest {
    private val entry = CompletedRenderStore.Entry(File("published.mp4"), "result", false)

    @Test fun successfulForegroundExportOpensChooserOnce() {
        val consumed = mutableSetOf<String>()
        val request = HybridShareCoordinator.AutomaticRequest { consumed.add(it) }
        request.request("current")
        val action = requireNotNull(request.complete("current", entry, foreground = true))
        assertEquals("current", action.operationId)
        assertEquals(entry, action.entry)
        assertTrue(action.automaticEligible)
        assertTrue("Consumed before the caller can launch a chooser", "current" in consumed)
        assertNull(request.complete("current", entry, foreground = true))
    }

    @Test fun restoreAndCancelledChooserDoNotRepeat() {
        val consumed = mutableSetOf<String>()
        val first = HybridShareCoordinator.AutomaticRequest { consumed.add(it) }
        first.request("one")
        assertNotNull(first.complete("one", entry, foreground = true))
        // A cancelled/failed chooser cannot undo consumption; a fresh Activity has no request.
        val restored = HybridShareCoordinator.AutomaticRequest { consumed.add(it) }
        assertNull(restored.complete("one", entry, foreground = true))
        restored.request("one")
        assertNull(restored.complete("one", entry, foreground = true))
    }

    @Test fun backgroundCompletionKeepsManualShareAction() {
        val consumed = mutableSetOf<String>()
        val request = HybridShareCoordinator.AutomaticRequest { consumed.add(it) }
        request.request("background")
        assertNull(request.complete("background", entry, foreground = false))
        assertTrue("background" in consumed)
        assertNull(request.complete("background", entry, foreground = true))
        assertEquals(File("published.mp4"), entry.file)
    }

    @Test fun leavingOriginatingScreenRevokesAutomaticEligibility() {
        val consumed = mutableSetOf<String>()
        val request = HybridShareCoordinator.AutomaticRequest { consumed.add(it) }
        request.request("left")
        request.abandon()
        assertTrue("left" in consumed)
        assertNull(request.complete("left", entry, foreground = true))
    }

    @Test fun differentOrFailedExportNeverSharesPreviousResult() {
        val consumed = mutableSetOf<String>()
        val request = HybridShareCoordinator.AutomaticRequest { consumed.add(it) }
        request.request("wanted")
        assertNull(request.complete("older", entry, foreground = true))
        request.request("retry")
        assertNull(request.complete(null, entry, foreground = true))
        request.request("missing")
        assertNull(request.complete("missing", null, foreground = true))
        assertTrue(consumed.isEmpty())
    }

    @Test fun onlyNonemptyPublishedMp4InsideAuthorizedDurableDirectoriesIsShareable() {
        val root = createTempDirectory("share-validation").toFile()
        try {
            val published = File(root, "completed-renders/current.mp4").apply {
                parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2))
            }
            val saved = File(root, "my-edits/saved.mp4").apply {
                parentFile!!.mkdirs(); writeBytes(byteArrayOf(3))
            }
            assertEquals(published.canonicalFile, HybridShareCoordinator.validateFile(root, published))
            assertEquals(saved.canonicalFile, HybridShareCoordinator.validateFile(root, saved))
            val invalid = listOf(
                File(root, "pending-renders/scratch.mp4").apply { parentFile!!.mkdirs(); writeText("scratch") },
                File(root, "completed-renders/../outside.mp4").apply { writeText("outside") },
                File(root, "completed-renders/bad.txt").apply { writeText("text") },
                File(root, "completed-renders/empty.mp4").apply { createNewFile() },
                File(root, "completed-renders/directory.mp4").apply { mkdirs() },
                File(root, "completed-renders/missing.mp4")
            )
            invalid.forEach { file ->
                assertThrows(IllegalArgumentException::class.java) { HybridShareCoordinator.validateFile(root, file) }
            }
        } finally { root.deleteRecursively() }
    }
}
