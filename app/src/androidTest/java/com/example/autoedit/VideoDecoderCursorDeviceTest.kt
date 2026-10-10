package com.veycad.app

import android.graphics.SurfaceTexture
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES20
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real AVC + display GLES tests; source compilation is not device execution evidence. */
@RunWith(AndroidJUnit4::class)
class VideoDecoderCursorDeviceTest {
    @Test fun previous_sync_forward_exact_seek_and_backward_range_at_30_fps() = exactSeek(30)

    @Test fun previous_sync_forward_exact_seek_and_backward_range_at_60_fps() = exactSeek(60)

    private fun exactSeek(fps: Int) {
        val file = fixture(fps)
        val samples = samples(file)
        assertEquals(3 * fps, samples.pts.size)
        assertTrue(samples.syncPts.zipWithNext().all { (a, b) -> b - a <= 2_000_000L })
        DisplayHarness().use { display ->
            VideoDecoderCursor(file, display.inputSurface).use { cursor ->
                assertNull(cursor.currentTexturePtsUs)
                val target = 717_777L // Between samples, within the first GOP.
                val previousSync = samples.syncPts.last { it <= target }
                val expected = samples.pts.first { it >= target }
                assertTrue(previousSync < expected)
                cursor.beginRange(target)
                assertTrue(cursor.advanceTo(target) { display.awaitTexture() })
                assertEquals(expected, cursor.currentTexturePtsUs)
                assertNotEquals(previousSync, cursor.currentTexturePtsUs)
                val first = display.draw(cursor, target)
                val callbacks = display.textureCallbacks
                assertTrue(cursor.advanceTo(target) { display.awaitTexture() })
                assertEquals("Same-frame lookup must keep the current texture", callbacks, display.textureCallbacks)

                val forward = 1_177_777L
                assertTrue(cursor.advanceTo(forward) { display.awaitTexture() })
                assertEquals(samples.pts.first { it >= forward }, cursor.currentTexturePtsUs)
                val later = display.draw(cursor, forward)

                val backward = 217_777L
                cursor.beginRange(backward)
                assertNull(cursor.currentTexturePtsUs)
                assertTrue(cursor.advanceTo(backward) { display.awaitTexture() })
                assertEquals(samples.pts.first { it >= backward }, cursor.currentTexturePtsUs)
                val earlier = display.draw(cursor, backward)
                // Fixture luma changes with frame number. Wrong cached/first-GOP pixels fail here.
                assertTrue("Forward seek must change actual pixels", later > first + 4)
                assertTrue("Backward range must replace later pixels", earlier < first - 4)
            }
        }
    }

    @Test fun final_sample_target_then_beyond_eos_keeps_last_valid_texture_at_30_and_60_fps() {
        for (fps in listOf(30, 60)) {
            val file = fixture(fps)
            val finalSamplePtsUs = samples(file).pts.last()
            DisplayHarness().use { display ->
                VideoDecoderCursor(file, display.inputSurface).use { cursor ->
                    cursor.beginRange(0)
                    // EOS may accompany this nonempty output, rather than a later empty buffer.
                    assertTrue(cursor.advanceTo(finalSamplePtsUs) { display.awaitTexture() })
                    assertEquals(finalSamplePtsUs, cursor.currentTexturePtsUs)
                    val pixels = display.draw(cursor, finalSamplePtsUs)
                    val callbacks = display.textureCallbacks
                    assertTrue(cursor.advanceTo(4_000_000) { display.awaitTexture() })
                    assertEquals(finalSamplePtsUs, cursor.currentTexturePtsUs)
                    assertEquals(callbacks, display.textureCallbacks)
                    assertEquals(pixels, display.draw(cursor, 4_000_000))
                    assertTrue(cursor.advanceTo(5_000_000) { display.awaitTexture() })
                    assertEquals(finalSamplePtsUs, cursor.currentTexturePtsUs)
                    assertEquals(callbacks, display.textureCallbacks)
                    assertEquals(pixels, display.draw(cursor, 5_000_000))
                    cursor.beginRange(217_777)
                    assertTrue(cursor.advanceTo(217_777) { display.awaitTexture() })
                    assertEquals(samples(file).pts.first { it >= 217_777 }, cursor.currentTexturePtsUs)
                }
            }
        }
    }

    @Test fun cancellation_during_gop_unwinds_owner_before_target_and_surface_is_reusable() {
        val file = fixture(60)
        val cancelled = CancellationException("cancel decode after two textures")
        DisplayHarness().use { display ->
            val checkCancelled = { if (display.textureCallbacks >= 2) throw cancelled }
            val cursor = VideoDecoderCursor(file, display.inputSurface, checkCancelled)
            try {
                cursor.beginRange(0)
                assertSame(cancelled, assertThrows(CancellationException::class.java) {
                    cursor.advanceTo(1_900_000) { display.awaitTexture(checkCancelled) }
                })
                assertEquals(2, display.textureCallbacks)
                assertTrue(requireNotNull(cursor.currentTexturePtsUs) < 1_900_000)
            } finally { cursor.close() }
            assertClosed(cursor, display)
            assertSurfaceReusable(file, display)
        }
    }

    @Test fun cancellation_between_bounded_codec_operations_precedes_full_gop_decode() {
        val file = fixture(60)
        DisplayHarness().use { display ->
            var armed = false
            var checks = 0
            val cancelled = CancellationException("cancel bounded operation")
            val checkCancelled = { if (armed && ++checks == 5) throw cancelled }
            val cursor = VideoDecoderCursor(file, display.inputSurface, checkCancelled)
            try {
                cursor.beginRange(0)
                armed = true
                assertSame(cancelled, assertThrows(CancellationException::class.java) {
                    cursor.advanceTo(1_900_000) { display.awaitTexture(checkCancelled) }
                })
                assertTrue(display.textureCallbacks < 2)
            } finally { cursor.close() }
            assertClosed(cursor, display)
            assertSurfaceReusable(file, display)
        }
    }

    @Test fun initialization_cancellation_cleans_partial_resources_and_keeps_caller_surface() {
        val file = fixture(30)
        DisplayHarness().use { display ->
            // Single-video-track fixture: extractor selection, codec creation/configuration/start.
            for (cancelAt in listOf(6, 8, 9, 10)) {
                var checks = 0
                val cancelled = CancellationException("cancel initialization at $cancelAt")
                assertSame(cancelled, assertThrows(CancellationException::class.java) {
                    VideoDecoderCursor(file, display.inputSurface) { if (++checks == cancelAt) throw cancelled }
                })
                assertSurfaceReusable(file, display)
            }
        }
    }

    @Test fun texture_callback_failure_unwinds_owner_and_close_is_idempotent() {
        val file = fixture(30)
        DisplayHarness().use { display ->
            val cursor = VideoDecoderCursor(file, display.inputSurface)
            val failure = IllegalStateException("texture callback failed")
            try {
                cursor.beginRange(0)
                assertSame(failure, assertThrows(IllegalStateException::class.java) {
                    cursor.advanceTo(217_777) { display.awaitTexture(); throw failure }
                })
            } finally { cursor.close(); cursor.release(); cursor.close() }
            assertClosed(cursor, display)
            assertSurfaceReusable(file, display)
        }
    }

    @Test fun missing_input_constructor_failure_keeps_caller_surface() {
        DisplayHarness().use { display ->
            val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "cursor-missing-${System.nanoTime()}.mp4")
            assertThrows(Exception::class.java) { VideoDecoderCursor(file, display.inputSurface) }
            assertSurfaceReusable(fixture(30), display)
        }
    }

    private fun assertClosed(cursor: VideoDecoderCursor, display: DisplayHarness) {
        assertThrows(IllegalStateException::class.java) { cursor.beginRange(0) }
        assertThrows(IllegalStateException::class.java) { cursor.advanceTo(0) { fail("Closed cursor rendered") } }
        assertTrue(display.inputSurface.isValid)
    }

    private fun assertSurfaceReusable(file: File, display: DisplayHarness) {
        assertTrue(display.inputSurface.isValid)
        VideoDecoderCursor(file, display.inputSurface).use { replacement ->
            replacement.beginRange(217_777)
            assertTrue(replacement.advanceTo(217_777) { display.awaitTexture() })
            assertEquals(samples(file).pts.first { it >= 217_777 }, replacement.currentTexturePtsUs)
            display.draw(replacement, 217_777)
        }
    }

    private fun fixture(fps: Int): File {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "decoder-cursor-fixtures").apply { mkdirs() }
        return SyntheticVideo.create(File(directory, "avc-$fps.mp4"), 3_000,
            width = 64, height = 96, fps = fps, iFrameIntervalSeconds = 2)
    }

    private data class Samples(val pts: List<Long>, val syncPts: List<Long>)

    /** Independent container sample clock, rather than deriving expectations from the cursor. */
    private fun samples(file: File): Samples {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc"
            }
            extractor.selectTrack(track)
            val pts = mutableListOf<Long>()
            val sync = mutableListOf<Long>()
            while (extractor.sampleTime >= 0) {
                pts += extractor.sampleTime
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) sync += extractor.sampleTime
                extractor.advance()
            }
            return Samples(pts.sorted(), sync.sorted())
        } finally { extractor.release() }
    }

    private class DisplayHarness : AutoCloseable {
        private val size = OutputSize(64, 96)
        private val texture = SurfaceTexture(false).apply { setDefaultBufferSize(size.width, size.height) }
        private val surface = Surface(texture)
        private val target = EglRenderTarget.forDisplay(surface, size) as EglRenderTarget
        private val graph = GlesFrameCompositorDeviceTest.graph()
        private var evidence: GlesFrameCompositor.DrawEvidence? = null
        private var luma = 0
        private val compositor = GlesFrameCompositor(object : RenderTarget by target, ViewportRenderTarget {
            override fun bindOutput() = target.bindOutput()
            override fun present(outputTimeUs: Long) {
                val pixels = GlesFrameCompositorDeviceTest.readPixels(size)
                luma = pixels[(size.height / 2 * size.width + size.width / 2) * 4].toInt() and 255
                // Read display composition before swap; the detached dummy output has no consumer.
            }
        }, RenderPassPlanner.plan(graph, RenderPassPlanner.DeviceCapabilities.conservative()),
            graph.frameAttachments, onDraw = { evidence = it })
        val inputSurface: Surface get() = compositor.decoderSurface(0)
        var textureCallbacks = 0
            private set

        fun awaitTexture(checkCancelled: () -> Unit = {}) {
            compositor.awaitTexture(0, checkCancelled) // Waits cancellably and updates the real OES image.
            textureCallbacks++
        }

        fun draw(cursor: VideoDecoderCursor, targetUs: Long): Int {
            val frame = HighQualityFramePlan.build(graph).frames[3].let {
                it.copy(sourceTimeUs = targetUs, transform = it.transform.copy(scale = 1f))
            }
            compositor.drawScheduled(frame, cursor.rotationDegrees)
            assertEquals(cursor.currentTexturePtsUs, requireNotNull(evidence).decodedSourceTimeUs)
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
            return luma
        }

        override fun close() { compositor.close(); surface.release(); texture.release() }
    }
}
