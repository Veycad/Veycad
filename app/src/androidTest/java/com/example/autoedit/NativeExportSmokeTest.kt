package com.veycad.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class NativeExportSmokeTest {
    // Catches bypassed GLES/encoding, frozen or black video, missing AAC and silent/wrong music.
    @Test(timeout = 120_000) fun nativeMontageExportsDecodableAvcAndAac() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "native-export-smoke-${System.nanoTime()}")
        check(folder.mkdirs())
        try {
            val source = SyntheticVideo.create(File(folder, "source.mp4"), 2_000)
            val music = File(folder, "music.mp4")
            InstrumentationRegistry.getInstrumentation().context.assets.open("media/two-audio.mp4").use { input ->
                music.outputStream().use { input.copyTo(it) }
            }
            val graph = MontageGraph(2_000, 1_000, clips = listOf(MontageGraph.Clip(
                id = "smoke", sourceStartMs = 0, sourceEndMs = 2_000, outputDurationMs = 1_000,
                role = MontageGraph.ShotRole.ACTION, transitionIn = MontageGraph.Transition.HARD_CUT,
                motion = MontageGraph.Motion.HOLD, confidence = 1f, beatAnchorMs = 0,
                redBias = 1f
            )), audioTrack = MontageGraph.AudioTrack("smoke-music"))
            val output = File(folder, "export.mp4")
            val deadline = SystemClock.elapsedRealtime() + 90_000
            val frames = MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
                masterFile = source, graph = graph, outputFile = output,
                width = 96, height = 144, fps = 30, bitrate = 300_000, audioFile = music,
                checkCancelled = { check(SystemClock.elapsedRealtime() < deadline) { "Native export timed out" } }
            ))
            assertEquals(30, frames)
            assertTrue("Export must contain encoded media", output.length() > 1_000)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.path)
                assertEquals("Exactly one video and one audio track", 2, extractor.trackCount)
                val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                val videoTrack = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME) == "video/avc" }
                assertTrue("Owned video encoder produces AVC", videoTrack >= 0)
                val video = formats[videoTrack]
                assertEquals(96, video.getInteger(MediaFormat.KEY_WIDTH))
                assertEquals(144, video.getInteger(MediaFormat.KEY_HEIGHT))
                assertTrue("Video has the authored one-second clock",
                    video.getLong(MediaFormat.KEY_DURATION) in 950_000L..1_050_000L)
                val audio = formats.single { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
                assertTrue("AAC covers the authored duration",
                    audio.getLong(MediaFormat.KEY_DURATION) in 900_000L..1_150_000L)
                extractor.selectTrack(videoTrack)
                var count = 0
                var previous = -1L
                while (extractor.sampleTime >= 0) {
                    val pts = extractor.sampleTime
                    if (count == 0) assertEquals(0L, pts)
                    else assertTrue("Encoded frame timestamps follow 30 fps", pts - previous in 33_332L..33_334L)
                    previous = pts
                    count++
                    extractor.advance()
                }
                assertEquals("Count actual AVC samples, not the renderer's return value", 30, count)
            } finally { extractor.release() }

            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(output.path)
                val early = requireNotNull(retriever.getFrameAtTime(200_000, MediaMetadataRetriever.OPTION_CLOSEST))
                val late = requireNotNull(retriever.getFrameAtTime(800_000, MediaMetadataRetriever.OPTION_CLOSEST))
                try {
                    assertEquals(96, early.width)
                    assertEquals(144, early.height)
                    val first = centreColour(early)
                    val last = centreColour(late)
                    assertTrue("GLES applies the authored red grade", Color.red(first) > Color.green(first) + 20)
                    assertTrue("Source decoder advances rather than exporting a frozen/black texture",
                        Color.green(last) > Color.green(first) + 15)
                } finally { early.recycle(); late.recycle() }
            }

            val decoded = MediaCodecAudioDecoder.decode(output, 2_000_000)
            val durationUs = decoded.frameCount * 1_000_000 / decoded.sampleRate
            assertTrue("AAC really decodes to roughly one second of PCM", durationUs in 900_000L..1_150_000L)
            val mono = decoded.mono()
            assertTrue("AAC contains a signal, not silence", mono.any { it > .02f } && mono.any { it < -.02f })
            // The fixture's first track is 330 Hz MP3; its second is 880 Hz AAC.
            assertTrue("Music selection survives native AAC re-encoding",
                tonePower(mono, decoded.sampleRate, 330) > tonePower(mono, decoded.sampleRate, 880) * 10)
        } finally {
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
    }

    private fun centreColour(bitmap: Bitmap): Int {
        var red = 0
        var green = 0
        var blue = 0
        for (y in 48 until 96) for (x in 24 until 72) {
            val pixel = bitmap.getPixel(x, y)
            red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel)
        }
        return Color.rgb(red / 2_304, green / 2_304, blue / 2_304)
    }

    private fun tonePower(samples: FloatArray, sampleRate: Int, frequency: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        for (index in sampleRate / 5 until sampleRate * 4 / 5) {
            val phase = 2 * Math.PI * frequency * index / sampleRate
            real += samples[index] * cos(phase)
            imaginary += samples[index] * sin(phase)
        }
        return real * real + imaginary * imaginary
    }
}
