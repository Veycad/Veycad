package com.veycad.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectAudioCompositionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun selectedMusicTailStopsAtRealEofAndAppliesOutputFadesWithoutReopening() {
        val format = PcmFormat(8000, 1)
        var opens = 0; var closes = 0
        val raw = PcmStreamProvider { _, start ->
            assertEquals(1000L, start); opens++
            object : PcmStream {
                override val format = format
                private var read = false
                override fun read(): PcmChunk? = if (read) null else {
                    read = true; PcmChunk(format, 0, ShortArray(24) { 16000 })
                }
                override fun close() { closes++ }
            }
        }
        val music = ProjectMusic("music", 1000, 1f, 1000, 1000, false)
        ProjectMusicPcmStream(raw, music, 2000).use { stream ->
            val chunk = stream.read()!!
            assertEquals(16, chunk.frames)
            assertEquals(0L, chunk.startPtsUs)
            assertEquals(0, (chunk.sample(0, 0) * 32768).toInt())
            assertEquals(16000, (chunk.sample(8, 0) * 32768).toInt())
            assertEquals(2000, (chunk.sample(15, 0) * 32768).toInt())
            assertNull(stream.read()); assertNull(stream.read())
        }
        assertEquals(1, opens); assertEquals(opens, closes)
    }

    @Test fun partialFinalVideoCellTruncatesMusicPcmAndSpeechOnTheSameSampleClock() {
        val format = PcmFormat(48000, 2)
        val music = PcmStreamProvider { _, _ -> object : PcmStream {
            override val format = format
            var cursor = 0
            override fun read(): PcmChunk? {
                if (cursor == 1920) return null
                val start = cursor; cursor += 480
                return PcmChunk(format, start * 1000000L / 48000, ShortArray(960) { 8000 })
            }
            override fun close() {}
        } }
        val clip = SourceAudioClip("voice", FrameSpan(0, 1), SourceTimeMap(listOf(
            SourceTimeMap.Point(0, 0), SourceTimeMap.Point(1, 33333))))
        var mixed = 0L; var speech = 0L
        SourceAudioComposer(ProjectClock(30), listOf(clip), PcmStreamProvider { _, _ -> error("MUSIC must not decode speech") })
            .composeProject(AudioMixSettings(AudioMixMode.MUSIC, 0f, 1.5f), PcmSink { _, start, samples, frames ->
                assertEquals(mixed, start); assertEquals(12000, samples[0].toInt()); mixed += frames
            }, PcmSink { _, start, samples, frames ->
                assertEquals(speech, start); assertTrue(samples.take(frames).all { it == 0.toShort() }); speech += frames
            }, music, durationUs = 30001)
        assertEquals(1440L, mixed); assertEquals(480L, speech)
    }

    @Test fun projectSnapshotUsesSavedSourceMapAndTrackGainInHeadroomAndRejectsChangedBytes() {
        val fixture = PreviewTestFixtures.core(MontageStyleCatalog.Recipe.HEARTBEAT)
        val revision = fixture.current.copy(style = fixture.current.style.copy(recipeId = MontageStyleCatalog.heartbeat.id))
        val project = fixture.copy(original = revision, current = revision)
        val root = temporary.newFolder()
        val sources = File(root, "sources").apply { mkdirs() }
        project.assets.forEach { asset -> File(sources, asset.fileName).writeText(
            if (asset.kind == ProjectAsset.Kind.AUDIO) "preview-music" else "preview-${asset.id}") }
        val edited = HybridEditCommands.apply(project, ProjectCommand.ReplaceMusic(project.current.music.copy(gain = 2f)))
        val mixed = HybridEditCommands.apply(edited, ProjectCommand.SetAudioMix(AudioMixSettings(AudioMixMode.SPEECH_AND_MUSIC, 1f, 1f)))
        val snapshot = PreparedProjectAudio.from(mixed, ProjectAssetStore(root))
        assertEquals(mixed.current.id, snapshot.revisionId)
        assertEquals(mixed.current.clips.map { it.sourceMap }, snapshot.clips.map { it.sourceMap })
        assertEquals(.89125094f, snapshot.compositionSettings().let { it.sourceGain + it.musicGain }, .000001f)
        assertEquals(2f, snapshot.compositionSettings().let { it.musicGain / it.sourceGain }, .000001f)
        val loudMusic = HybridEditCommands.apply(edited, ProjectCommand.SetAudioMix(AudioMixSettings(AudioMixMode.MUSIC, musicGain = 2f)))
        assertEquals(2f, PreparedProjectAudio.from(loudMusic, ProjectAssetStore(root)).compositionSettings().musicGain, 0f)
        val video = File(sources, mixed.assets.first { it.kind == ProjectAsset.Kind.VIDEO }.fileName)
        video.writeText("x".repeat(video.length().toInt()))
        assertThrows(IllegalArgumentException::class.java) { snapshot.requireFiles() }
        assertEquals(mixed.current.audioMix, snapshot.settings)
    }

    @Test fun twoPersistedMusicGainsAreBothAppliedWithoutRejectingAValidRevision() {
        val format = PcmFormat(48000, 2)
        val provider = PcmStreamProvider { _, _ -> object : PcmStream {
            override val format = format
            var read = false
            override fun read(): PcmChunk? = if (read) null else {
                read = true; PcmChunk(format, 0, ShortArray(3200) { 2000 })
            }
            override fun close() {}
        } }
        val clip = SourceAudioClip("voice", FrameSpan(0, 1), SourceTimeMap(listOf(
            SourceTimeMap.Point(0, 0), SourceTimeMap.Point(1, 33333))))
        var frames = 0L
        SourceAudioComposer(ProjectClock(30), listOf(clip), PcmStreamProvider { _, _ -> error("music only") })
            .composeProject(AudioMixSettings(AudioMixMode.MUSIC, musicGain = 2f), PcmSink { _, _, samples, count ->
                assertEquals(8000, samples[0].toInt()); frames += count
            }, music = provider, musicMultiplier = 2f)
        assertEquals(1600L, frames)
    }
}
