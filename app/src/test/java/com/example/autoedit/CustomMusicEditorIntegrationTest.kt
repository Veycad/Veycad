package com.veycad.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class CustomMusicEditorIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedResultKeepsItsEditableProjectLinkAfterMarkingSaved() {
        val source = temporary.newFile("music-result.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val files = temporary.newFolder("files")
        val published = CompletedRenderStore.publish(files, source, "Под свою музыку", projectId = "music-project")
        CompletedRenderStore.markSaved(published)
        val restored = CompletedRenderStore.list(files).single()
        assertEquals("music-project", restored.projectId)
        assertTrue(restored.saved)
    }
    @Test fun replacingMusicUpdatesTheGraphAndUndoRestoresBoth() {
        val base = editProject()
        val chosen = base.current.music.copy(startUs = 1_500_000, repeat = true)
        val edited = HybridEditCommands.replaceMusic(base, chosen)
        assertEquals(MontageGraph.AudioTrack(chosen.assetId, chosen.gain, chosen.startUs), edited.current.graph.audioTrack)
        val undone = HybridEditCommands.apply(edited, ProjectCommand.Undo)
        assertEquals(base.current.music, undone.current.music)
        assertEquals(base.current.graph.audioTrack, undone.current.graph.audioTrack)
        val redone = HybridEditCommands.apply(undone, ProjectCommand.Redo)
        assertEquals(edited.current.music, redone.current.music)
        assertEquals(edited.current.graph.audioTrack, redone.current.graph.audioTrack)
    }
}
