package com.veycad.app

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real MediaExtractor/MediaCodec/GLES/AAC evidence, independent of the controlled UI renderer. */
@RunWith(AndroidJUnit4::class)
class CustomAudioDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory get() = requireNotNull(context.getExternalFilesDir("custom-audio-evidence"))

    @Test fun diagnostic_snapshot_preserves_planes_nulls_and_failed_audio_evidence() {
        val plane = FrameAttachments.Plane(2, 1, floatArrayOf(.25f, .75f), .9f)
        val encoded = CustomMusicEvidence.json(FrameAttachments(123_456, mask = plane)) as JSONObject
        val decoded = JSONObject(encoded.toString())
        assertEquals(123_456L, decoded.getLong("sourceTimeUs"))
        assertTrue(decoded.has("depth") && decoded.isNull("depth"))
        val values = decoded.getJSONObject("mask").getJSONArray("values")
        assertEquals(2, values.length())
        assertEquals(.75, values.getDouble(1), .000001)
        val audio = DecodedAudioQuality.evaluate(floatArrayOf(.5f), 10, 1,
            expectedDurationUs = 100_000, durationToleranceUs = 0, unclampedFloatEvidence = false)
        val evidence = CustomMusicEvidence.json(audio) as JSONObject
        assertFalse(evidence.getBoolean("unclampedFloatEvidence"))
        assertEquals("audio-unclamped-headroom-unavailable", evidence.getJSONArray("issues").getString(0))
        val transitions = CustomMusicEvidence.json(listOf(MontageGraph.Transition.WHIP, null)) as JSONArray
        assertEquals("WHIP", transitions.getString(0))
        assertTrue(transitions.isNull(1))
    }

    @Test fun wav_seek_discards_the_intro_at_exact_pcm_frame_boundaries() {
        val file = SyntheticAudio.writeWave(File(directory, "seek.wav"))
        val original = MediaCodecAudioDecoder.decode(file, 5_000_000)
        val shifted = MediaCodecAudioDecoder.decode(file, 1_000_000, startUs = 2_137_511)
        val first = 34_201 // ceiling(2.137511 * 16000), not a codec packet boundary
        assertEquals(16_000L, shifted.frameCount)
        assertArrayEquals(original.mono().copyOfRange(first, first + 16_000), shifted.mono(), .0001f)
        assertTrue(original.mono().take(16_000).all { it == 0f })
        val exportPcm = MediaCodecAudioDecoder.decode(file, 1_000_000,
            preserveFloatHeadroom = true, startUs = 2_137_511)
        assertTrue(exportPcm.interleavedSamples.all { it.isFinite() })
        assertArrayEquals(shifted.mono(), exportPcm.mono(), .0001f)
    }

    @Test fun selected_tail_drives_real_gpu_cuts_and_repeats_in_export_without_the_intro() {
        val audio = SyntheticAudio.writeWave(File(directory, "render.wav"))
        val video = SyntheticVideo.create(File(directory, "source.mp4"))
        val observations = (0L until 3_000_000 step 100_000).map { VisualEventMap.Observation(it) }
        val directed = VeycadEventPipeline.fromAudioFile("source", audio, 3_000_000,
            observations, requestedOutputDurationMs = 3_000,
            requestedStyle = EventMatchingDirector.Style.DYNAMIC,
            audioStartUs = 15_000_000, useAuthoredProfile = false)
        val graph = directed.alternatives.single().graph
        assertEquals(15_000_000L, graph.audioTrack!!.sourceStartUs)
        assertEquals(120f, requireNotNull(directed.audioMap.estimatedTempoBpm), 4f)
        assertTrue(graph.clips.size >= 2)
        var cutMs = 0L
        graph.clips.dropLast(1).forEach { clip ->
            cutMs += clip.outputDurationMs
            assertTrue("cut=$cutMs is not on the repeated tail's rhythm",
                directed.audioMap.beats.any { abs(directed.audioMap.timestampUs(it.sampleIndex) / 1_000L - cutMs) <= 100 })
        }
        val output = File(directory, "selected-tail.mp4")
        val frames = MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
            masterFile = video, graph = graph, outputFile = output, width = 160, height = 240,
            bitrate = 200_000, audioFile = audio))
        assertTrue(frames >= 90)
        val decoded = MediaCodecAudioDecoder.decode(output)
        val pcm = decoded.mono()
        // The selected one-second tail contains a kick at its start; the original intro is silent.
        val early = pcm.take(decoded.sampleRate / 4)
        val rms = sqrt(early.map { it * it }.average())
        assertTrue("Intro leaked into exported audio: rms=$rms", rms > .05)
        assertTrue(abs(decoded.frameCount * 1_000_000L / decoded.sampleRate - 3_000_000) < 100_000)
        val exportedMap = AudioBeatMapAnalyzer.analyze(pcm, decoded.sampleRate)
        assertEquals(120f, requireNotNull(exportedMap.estimatedTempoBpm), 4f)
        assertTrue(exportedMap.onsets.count { it.sampleIndex >= decoded.sampleRate } >= 3)
        MediaMetadataRetriever().use { metadata ->
            metadata.setDataSource(output.path)
            assertTrue(abs(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() - 3_000L) < 100)
        }
        File(directory, "report.txt").writeText("frames=$frames\naudio_duration_us=${decoded.frameCount * 1_000_000L / decoded.sampleRate}\n" +
            "tempo_bpm=${exportedMap.estimatedTempoBpm}\nearly_rms=$rms\nstart_us=15000000\n")
    }

    @Test fun mp3_is_decoded_and_analyzed_from_the_selected_fragment_without_a_predefined_map() {
        val file = File(directory, "imported.mp3")
        InstrumentationRegistry.getInstrumentation().context.assets.open("synthetic-120-bpm.mp3")
            .use { input -> file.outputStream().use { input.copyTo(it) } }
        val decoded = MediaCodecAudioDecoder.decode(file, 6_000_000, startUs = 2_000_000,
            preserveFloatHeadroom = true)
        assertTrue(decoded.interleavedSamples.all { it.isFinite() })
        assertTrue("frames=${decoded.frameCount}, rate=${decoded.sampleRate}",
            abs(decoded.frameCount - decoded.sampleRate * 6L) <= 1)
        val map = AudioBeatMapAnalyzer.analyze(decoded.mono(), decoded.sampleRate)
        assertEquals(120f, requireNotNull(map.estimatedTempoBpm), 4f)
        assertTrue(map.beats.count { it.isDownbeat } >= 2)
        assertTrue(map.onsets.size >= 10)
        assertTrue(map.timestampUs(map.onsets.first().sampleIndex) < 600_000L)
        val analysisPcm = MediaCodecAudioDecoder.decode(file, 6_000_000, startUs = 2_137_511)
        val exportPcm = MediaCodecAudioDecoder.decode(file, 6_000_000, startUs = 2_137_511,
            preserveFloatHeadroom = true)
        assertEquals(96_000L, analysisPcm.frameCount)
        assertEquals(analysisPcm.frameCount, exportPcm.frameCount)
        assertArrayEquals(analysisPcm.mono(), exportPcm.mono(), .002f)
    }

    @Test fun native_editor_routes_custom_music_and_accepts_a_source_without_people() {
        val audio = SyntheticAudio.writeWave(File(directory, "editor.wav"))
        val video = SyntheticVideo.create(File(directory, "editor-source.mp4"), durationMs = 16_000)
        val output = File(directory, "native-editor.mp4")
        val evidenceFile = File(directory, "native-editor-evidence.json")
        evidenceFile.delete() // A failed rerun must not leave a previous report beside a new MP4.
        val result = VeycadAutomaticEditor.render(VeycadAutomaticEditor.Request(context, video,
            musicFile = audio, outputFile = output, style = EventMatchingDirector.Style.DYNAMIC,
            recipe = MontageStyleCatalog.Recipe.CUSTOM_MUSIC, width = 160, height = 240,
            bitrate = 200_000, musicStartUs = 15_000_000))
        // Save before assertions, and before subsequent UI fixtures clear private inspector files.
        val evidence = CustomMusicEvidence.save(result, video, audio, output, evidenceFile)
        assertEquals(result.passedQualityGate, evidence.getBoolean("quality_gate"))
        assertEquals(result.winner.samples.size, evidence.getJSONArray("visual_samples").length())
        assertEquals(15_000_000L, evidence.getJSONObject("graph")
            .getJSONObject("audioTrack").getLong("sourceStartUs"))
        assertTrue("Missing decoded source clock; inspect saved native-editor-evidence.json",
            evidence.getBoolean("decoded_source_clock_available"))
        assertEquals(result.winner.frames, evidence.getInt("decoded_source_clock_entries"))
        assertTrue("Incomplete/negative decoded PTS; inspect saved native-editor-evidence.json",
            evidence.getBoolean("decoded_source_clock_complete"))
        assertTrue(output.isFile && output.length() > 0)
        assertTrue(result.winner.frames > 0)
        assertEquals(15_000_000L, result.winner.alternative.graph.audioTrack!!.sourceStartUs)
        assertTrue(result.pipeline.audioMap.beats.isNotEmpty())
        assertTrue(result.winner.alternative.graph.clips.size >= 2)
        val exported = MediaCodecAudioDecoder.decode(output)
        assertEquals(120f, requireNotNull(AudioBeatMapAnalyzer.analyze(exported.mono(),
            exported.sampleRate).estimatedTempoBpm), 4f)
        File(directory, "native-editor-report.txt").writeText(
            "frames=${result.winner.frames}\nclips=${result.winner.alternative.graph.clips.size}\n" +
                "quality_gate=${result.passedQualityGate}\nissues=${result.winner.acceptance.issues}\n")
    }
}
