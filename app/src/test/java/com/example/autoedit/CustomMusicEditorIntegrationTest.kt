package com.veycad.app

import org.junit.Assert.*
import org.junit.Test

class CustomMusicEditorIntegrationTest {
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
