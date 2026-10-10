package com.veycad.app

import java.io.File
import java.io.StringWriter
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Run on the host with -Xmx64m. No Gradle, emulator, renderer or increased Android heap. */
class CustomMusicJsonWriterTest {
    @Test fun scalar_precision_nulls_enum_and_escaping_are_lossless() {
        val writer = StringWriter()
        CustomMusicJsonWriter.writeValue(writer, linkedMapOf(
            "long" to 9_007_199_254_740_993L, "double" to .12345678901234567,
            "null" to null, "bool" to false, "enum" to MontageGraph.Transition.WHIP,
            "text" to "Музыка 🎵\n\t\r\b\u000c\u0000\"\\"))
        assertEquals("{\"long\":9007199254740993,\"double\":0.12345678901234566," +
            "\"null\":null,\"bool\":false,\"enum\":\"WHIP\"," +
            "\"text\":\"Музыка \\ud83c\\udfb5\\n\\t\\r\\b\\f\\u0000\\\"\\\\\"}", writer.toString())
    }

    @Test fun utf8_file_preserves_paired_and_unpaired_utf16_surrogates() {
        val file = File.createTempFile("custom-music-string-", ".json")
        try {
            CustomMusicJsonWriter.write(file, "a\uD800b\uDC00c🎵")
            assertEquals("\"a\\ud800b\\udc00c\\ud83c\\udfb5\"", file.readText())
        } finally { assertTrue(file.delete()) }
    }

    @Test fun unsupported_and_nonfinite_values_fail_explicitly() {
        for (value in listOf(Float.NaN, Double.POSITIVE_INFINITY, File("unsupported"))) {
            assertThrows(IllegalArgumentException::class.java) {
                CustomMusicJsonWriter.writeValue(StringWriter(), value)
            }
        }
    }

    @Test fun complete_models_write_a_small_independently_parseable_fixture() {
        val directory = File(requireNotNull(System.getProperty("custom.music.evidence.dir"))).apply { mkdirs() }
        val graph = MontageGraph(1_000, 1_000, clips = listOf(MontageGraph.Clip(
            "clip", 0, 1_000, 1_000, MontageGraph.ShotRole.OPENING,
            MontageGraph.Transition.OPEN, MontageGraph.Motion.HOLD, 1f, 0)),
            audioTrack = MontageGraph.AudioTrack("custom", sourceStartUs = 15_000_000),
            frameAttachments = FrameAttachmentTimeline(listOf(FrameAttachments(123_456,
                mask = FrameAttachments.Plane(2, 1, floatArrayOf(.25f, .75f), .9f)))))
        val audio = DecodedAudioQuality.evaluate(floatArrayOf(.5f), 10, 1, 100_000, 0, false)
        val acceptance = RenderedMp4Acceptance.Report(false,
            RenderedMp4Acceptance.Metrics(1f, 0f, .12f, 0, 0f, .3f, 0f,
                maximumColourJumpTimeUs = 222_222), RenderedMp4Acceptance.ReferenceMontageCard(),
            listOf("audio-unclamped-headroom-unavailable", "colour-jump"), audio)
        CustomMusicJsonWriter.write(File(directory, "fidelity.json"), linkedMapOf(
            "quality_gate" to false, "graph" to graph, "acceptance" to acceptance,
            "visual_samples" to listOf(RenderedMp4Acceptance.VisualSample(
                222_222, .12f, .3f, .5f, .5f, 0f, false, 0f)),
            "audio_map" to AudioBeatMap(16_000, 16_000, 120f, listOf(AudioBeatMap.Beat(
                0, 1f, AudioBeatMap.FrequencyBand.LOW, true)),
                listOf(AudioBeatMap.Onset(500, .6f, AudioBeatMap.FrequencyBand.HIGH)),
                listOf(AudioBeatMap.Drop(1_000, .8f))),
            "visual_map" to VisualEventMap(1_000_000, listOf(VisualEventMap.Event(
                VisualEventMap.EventType.CAMERA_MOVE, 123_456, .6f, .8f,
                VisualEventMap.Direction.RIGHT, VisualEventMap.UsableWindow(100_000, 200_000))),
                listOf(VisualEventMap.Observation(0))),
            "decoded_source_clock" to mapOf(222_222L to 123_456L, 9_007_199_254_740_993L to -1L),
            "scalars" to mapOf("long" to 9_007_199_254_740_993L,
                "double" to .12345678901234567, "text" to "Музыка 🎵\n\u0000\"\\",
                "surrogates" to "\uD800x\uDFFF")))
    }

    @Test fun plane_larger_than_heap_streams_with_exact_complete_payload() {
        assertTrue("Use -Xmx64m for the memory regression", Runtime.getRuntime().maxMemory() <= 64L * 1024 * 1024)
        val count = 8_000_000
        val values = FloatArray(count) { .12345678f }
        values[0] = -0.0f
        values[count - 1] = Float.MIN_VALUE
        val file = File.createTempFile("custom-music-stream-", ".json")
        try {
            CustomMusicJsonWriter.write(file, FrameAttachments.Plane(count, 1, values, .9f))
            assertTrue("Payload must exceed the entire heap", file.length() > Runtime.getRuntime().maxMemory())
            // Independent expected wire payload, built only into an incremental digest.
            val expected = MessageDigest.getInstance("SHA-256")
            expected.update("{\"confidence\":0.9,\"height\":1,\"values\":[-0.0".toByteArray())
            val repeated = ",0.12345678".toByteArray()
            repeat(count - 2) { expected.update(repeated) }
            expected.update(",1.4E-45],\"width\":8000000}".toByteArray())
            val actual = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    actual.update(buffer, 0, read)
                }
            }
            assertArrayEquals(expected.digest(), actual.digest())
            println("memory_probe: heap=${Runtime.getRuntime().maxMemory()}, payload=${file.length()}, values=$count")
        } finally { assertTrue(file.delete()) }
    }
}
