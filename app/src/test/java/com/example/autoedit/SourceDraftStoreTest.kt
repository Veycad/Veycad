package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root
    private fun source(name: String, bytes: String = name) =
        File(RenderWorkspace.sourceDraftDirectory(root), name).apply { writeText(bytes) }

    @Test fun interruptedReplacementRestoresOnlyTheCommittedPairAndLabels() {
        val store = SourceDraftStore(root)
        val first = source("source-0-old.mp4")
        val second = source("source-1-old.mp4")
        store.publish(listOf(first, second), listOf("Z first", "A second"))
        source("source-0-new.mp4")
        source("source-1-new.mp4.partial")
        val restored = SourceDraftStore(root).load()!!
        assertEquals(listOf(first, second), restored.files)
        assertEquals(listOf("Z first", "A second"), restored.names)
    }

    @Test fun interruptedFirstImportCannotBecomeALegacySelection() {
        val store = SourceDraftStore(root)
        store.initialize(emptyList())
        source("source-0-incomplete.mp4")
        assertNull(SourceDraftStore(root).load())
    }

    @Test fun failedPublicationRetainsPreviousSelectionAndFiles() {
        val store = SourceDraftStore(root)
        val previous = source("source-0-old.mp4")
        store.publish(listOf(previous), listOf("Previous"))
        val empty = source("source-0-new.mp4", "")
        assertThrows(IllegalArgumentException::class.java) { store.publish(listOf(empty), listOf("New")) }
        assertEquals(listOf(previous), store.load()!!.files)
        assertEquals("Previous", store.load()!!.names.single())
        assertTrue(previous.isFile)
    }

    @Test fun completeLegacyPairMigratesButMixedGenerationsDoNot() {
        val first = source("source-0-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.mp4")
        val second = source("source-1-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.mp4")
        val store = SourceDraftStore(root)
        store.initialize(listOf("First", "Second"))
        source("source-0-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.mp4")
        assertEquals(listOf(first, second), SourceDraftStore(root).load()!!.files)
        assertEquals(listOf("First", "Second"), store.load()!!.names)
    }

    @Test fun ambiguousLegacyFilesArePreservedWithoutSelectingTwoPrimaries() {
        val files = listOf(source("source-0-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.mp4"),
            source("source-1-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.mp4"),
            source("source-0-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb.mp4"))
        val store = SourceDraftStore(root)
        store.initialize(listOf("Old first", "Old second"))
        assertNull(store.load())
        assertTrue(files.all(File::isFile))
    }

    @Test fun changedOrCorruptCommittedSelectionNeverFallsBackToDirectoryScanning() {
        val store = SourceDraftStore(root)
        val file = source("source-0-old.mp4")
        store.publish(listOf(file), listOf("Old"))
        file.appendText("changed")
        assertThrows(IllegalArgumentException::class.java) { store.load() }
        File(root, "source-draft.properties").writeText("version=99\ncount=1\n")
        assertThrows(IllegalArgumentException::class.java) { store.load() }
        assertTrue(file.isFile)
    }

    @Test fun clearCannotRestoreOldMediaAfterRestart() {
        val file = source("source-0-old.mp4")
        SourceDraftStore(root).publish(listOf(file), listOf("Old"))
        RenderWorkspace.clearSourceDraft(root)
        assertFalse(file.exists())
        assertNull(SourceDraftStore(root).load())
    }

    @Test fun failedClearPublicationDoesNotDeleteMedia() {
        val file = source("source-0-old.mp4")
        val store = SourceDraftStore(root)
        store.publish(listOf(file), listOf("Old"))
        val manifest = File(root, "source-draft.properties")
        assertTrue(manifest.delete())
        assertTrue(manifest.mkdir())
        File(manifest, "blocker").writeText("deny replacement")
        assertThrows(java.io.IOException::class.java) { store.clear() }
        assertTrue(file.isFile)
        assertEquals("source-0-old.mp4", file.readText())
    }
}
