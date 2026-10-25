package com.veycad.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** The same harness is installed over both APKs; timings exclude fixture creation and validation. */
@RunWith(AndroidJUnit4::class)
class MediaPerformanceDeviceTest {
    @Test(timeout = 600_000) fun decodedFramesExactMattesAndCompleteExports() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("perfRun")
        // Keep routine software-GPU CI bounded; retained benchmark runs use the delivery resolution.
        val size = if (run == null) OutputSize(96, 144) else OutputSize(720, 1280)
        val repetitions = args.getString("perfRepetitions")?.toInt() ?: 1
        require(repetitions in 1..10)
        require(run == null || run.matches(Regex("[a-zA-Z0-9_-]+")))
        val root = File(context.filesDir, "media-performance").apply { mkdirs() }
        val folder = File(root, run ?: "test-${System.nanoTime()}")
        check(folder.mkdir()) { "Refusing to overwrite performance evidence" }
        try {
            val source = args.getString("perfSource")?.let(::File)
                ?: SyntheticVideo.create(File(root, "source.mp4"), 8_000, 320, 480)
            val music = File(root, "music.mp4")
            if (!music.exists()) InstrumentationRegistry.getInstrumentation().context.assets
                .open("media/two-audio.mp4").use { input -> music.outputStream().use { input.copyTo(it) } }
            val durationUs = SequentialBitmapDecoder.metadata(source).durationUs
            require(durationUs >= 8_000_000L)
            val report = JSONObject().put("run", run ?: "test").put("device", android.os.Build.MODEL)
                .put("source_sha256", source.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    hex(digest.digest())
                })
            val samples = JSONArray()
            report.put("samples", samples)
            // First iteration warms codec initialization and is explicitly excluded by the host.
            repeat(repetitions + 1) { iteration ->
                val sample = JSONObject().put("iteration", iteration).put("warmup", iteration == 0)
                val start = SystemClock.elapsedRealtimeNanos()
                val analysis = MulticlassMatteClient.analyze(context, source, 250_000L)
                sample.put("frame_decode_ns", SystemClock.elapsedRealtimeNanos() - start)
                analysis.use {
                    val expected = MulticlassMatteClient.decodedTargets(source,
                        SequentialBitmapDecoder.intervalTargets(durationUs, 250_000L), analysisClock = true)
                    assertArrayEquals(expected, it.decodedTimesUs)
                    assertTrue("Frame-only decode has no external masks", it.masks.isEmpty())
                    val digest = MessageDigest.getInstance("SHA-256")
                    for (pts in it.decodedTimesUs) {
                        val bitmap = requireNotNull(it.decodedFrame(pts))
                        try {
                            digest.update(ByteBuffer.allocate(16).putLong(pts).putInt(bitmap.width).putInt(bitmap.height).array())
                            val bytes = ByteBuffer.allocate(bitmap.byteCount)
                            bitmap.copyPixelsToBuffer(bytes)
                            digest.update(bytes.array())
                        } finally { bitmap.recycle() }
                    }
                    sample.put("frame_sha256", hex(digest.digest())).put("analysis_frames", expected.size)
                }
                for (fps in listOf(30, 60)) {
                    val graph = graph(durationUs / 1_000L)
                    val output = File(folder, "export-$iteration-$fps.mp4")
                    val renderStart = SystemClock.elapsedRealtimeNanos()
                    val count = MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
                        masterFile = source, graph = graph, outputFile = output, width = size.width, height = size.height,
                        fps = fps, bitrate = 5_000_000, audioFile = music))
                    sample.put("render_${fps}_ns", SystemClock.elapsedRealtimeNanos() - renderStart)
                    assertEquals(4 * fps, count)
                    verifyVideoClock(output, fps, size)
                    val pcm = MediaCodecAudioDecoder.decode(output, 5_000_000L, preserveFloatHeadroom = true)
                    assertTrue(pcm.frameCount * 1_000_000L / pcm.sampleRate in 3_950_000L..4_100_000L)
                    assertTrue("Export must contain real music", pcm.interleavedSamples.any { kotlin.math.abs(it) > .02f })
                    val audioBytes = ByteBuffer.allocate(pcm.interleavedSamples.size * 4)
                    pcm.interleavedSamples.forEach(audioBytes::putFloat)
                    sample.put("audio_${fps}_sha256", hex(MessageDigest.getInstance("SHA-256").digest(audioBytes.array())))
                }
                samples.put(sample)
                File(folder, "report.json").writeText(report.toString(2))
            }
            // Real exact-matte inference still owns its bitmap copy; run after timed samples.
            val targets = MulticlassMatteClient.decodedTargets(source, longArrayOf(250_000, 500_000, 750_000))
            val mattes = MulticlassMatteClient.analyzeTargets(context, source, targets)
            assertEquals(targets.toSet(), mattes.keys)
            val digest = MessageDigest.getInstance("SHA-256")
            for ((pts, plane) in mattes.toSortedMap()) {
                assertTrue(plane.values.all { it.isFinite() && it in 0f..1f })
                val buffer = ByteBuffer.allocate(16 + plane.values.size * 4)
                    .putLong(pts).putInt(plane.width).putInt(plane.height)
                plane.values.forEach(buffer::putFloat)
                digest.update(buffer.array())
            }
            report.put("exact_matte_sha256", hex(digest.digest())).put("status", "ok")
            File(folder, "report.json").writeText(report.toString(2))
        } finally {
            if (run == null) folder.deleteRecursively()
        }
    }

    private fun graph(durationMs: Long) = MontageGraph(durationMs, 4_000, clips = listOf(
        MontageGraph.Clip("first", 0, 3_000, 2_000, MontageGraph.ShotRole.ACTION,
            MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD, 1f, 0, redBias = .25f),
        MontageGraph.Clip("second", 4_000, 7_000, 2_000, MontageGraph.ShotRole.FINALE,
            MontageGraph.Transition.WHIP, MontageGraph.Motion.PUSH_IN, 1f, 2_000,
            speedRamp = MontageGraph.SpeedRamp(listOf(MontageGraph.SpeedRamp.Keyframe(0f, .5f),
                MontageGraph.SpeedRamp.Keyframe(1f, 2f))))
    ), audioTrack = MontageGraph.AudioTrack("performance-music"))

    private fun verifyVideoClock(output: File, fps: Int, size: OutputSize) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            assertEquals(2, formats.size)
            assertEquals(1, formats.count { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
            val index = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME) == "video/avc" }
            assertTrue(index >= 0)
            assertEquals(size.width, formats[index].getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(size.height, formats[index].getInteger(MediaFormat.KEY_HEIGHT))
            extractor.selectTrack(index)
            var count = 0
            while (extractor.sampleTime >= 0L) {
                assertTrue("Actual packet PTS must cover the complete frame grid",
                    kotlin.math.abs(extractor.sampleTime - count * 1_000_000L / fps) <= 1L)
                count++
                extractor.advance()
            }
            assertEquals("EOS drain must retain the last encoded frame", 4 * fps, count)
        } finally { extractor.release() }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
