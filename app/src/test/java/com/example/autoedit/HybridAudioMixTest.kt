package com.veycad.app

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HybridAudioMixTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun audioAndTextEditsShareRevisionHistoryAndRoundTripTogether() {
        val root = temporary.newFolder()
        val codec = HybridProjectCodec(AnalysisSidecarStore(File(root, "analysis")))
        val base = storageProject()
        val audio = AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC, .75f, .4f)
        val withAudio = HybridEditCommands.apply(base, ProjectCommand.SetAudioMix(audio))
        val text = HybridTextState(captions = listOf(CaptionCue("spoken", "исходная речь", 0, 900000,
            manuallyReviewed = true)), captionsEdited = true, language = "ru")
        val edited = HybridEditCommands.apply(withAudio, ProjectCommand.SetTextState(text))
        assertEquals(base.current.clips, edited.current.clips)
        assertEquals(base.current.graph, edited.current.graph)
        assertEquals(audio, edited.current.audioMix)
        assertEquals(text, edited.current.textState)
        assertEquals(listOf(base.current.id, withAudio.current.id), edited.undo.map { it.id })
        assertEquals(edited, codec.decode(codec.encode(edited)))
        assertEquals(9, ByteBuffer.wrap(codec.encode(edited)).getInt(4))
        assertEquals(edited.current, codec.decodeRevision(codec.encodeRevisions(listOf(edited.current)).getValue(edited.current.id)))
        val undone = HybridEditCommands.undo(HybridEditCommands.undo(edited))
        assertEquals(base.current, undone.current)
        assertEquals(edited.current, HybridEditCommands.redo(HybridEditCommands.redo(undone)).current)
        assertSame(withAudio, HybridEditCommands.apply(withAudio, ProjectCommand.SetAudioMix(audio)))
    }

    @Test fun genuineV8ProjectAndStandaloneRevisionsDefaultToMusicWithoutChangingAnyOtherValue() {
        val root = temporary.newFolder()
        val inventory = resource("SHA256SUMS.tsv").toString(Charsets.UTF_8).lineSequence().drop(1).filter { it.isNotBlank() }.toList()
        inventory.forEach { row ->
            val (hash, size, path) = row.split('\t')
            val bytes = resource(path)
            assertEquals(size.toLong(), bytes.size.toLong()); assertEquals(hash, contentHash(bytes))
            File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
        }
        val codec = HybridProjectCodec(AnalysisSidecarStore(File(root, "analysis")), AuthoredSourceMapStore(File(root, "authored-maps")))
        val project = codec.decode(resource("project.bin"))
        assertEquals(8, ByteBuffer.wrap(resource("project.bin")).getInt(4))
        for (revision in listOf(project.original, project.current)) {
            assertEquals(AudioMixSettings(AudioMixMode.MUSIC), revision.audioMix)
            assertEquals(revision, codec.decodeRevision(resource("revision-${revision.id}.bin")))
            assertEquals(TextLayerKind.TITLE, revision.textState.layers.single().kind)
            assertEquals(TextPlate.ACCENT, revision.textState.layers.single().style.plate)
            assertTrue(revision.textState.captions.single().manuallyReviewed)
        }
        assertEquals(project, codec.decode(codec.encode(project)))
        inventory.forEach { row ->
            val (hash, _, path) = row.split('\t'); assertEquals(hash, contentHash(File(root, path).readBytes()))
        }
    }

    @Test fun invalidPersistedAudioGainFailsBeforeInstallingAnyRevision() {
        val codec = HybridProjectCodec(AnalysisSidecarStore(temporary.newFolder()))
        val edited = HybridEditCommands.apply(storageProject(), ProjectCommand.SetAudioMix(AudioMixSettings(AudioMixMode.SOURCE, .6f)))
        val revision = codec.encodeRevisions(listOf(edited.current)).getValue(edited.current.id)
        val mode = "SOURCE".toByteArray()
        val at = (0..revision.size - mode.size).single { index -> mode.indices.all { revision[index + it] == mode[it] } }
        ByteBuffer.wrap(revision).putInt(at + mode.size, Float.NaN.toRawBits())
        assertThrows(IllegalArgumentException::class.java) { codec.decodeRevision(revision) }
    }

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/hybrid-v8/$name")).use { it.readBytes() }
}
