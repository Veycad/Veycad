package com.veycad.app

import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftPreviewControllerDeviceTest {
    // This fails if direct display is replaced by export, source slots are compacted, reverse seeks
    // reuse a later texture, cancellation retains a stale image, or the creator Looper deadlocks.
    @Test fun directSurfaceFirstReverseAndSourceChangePreserveInputsWithoutPreviewFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "direct-preview-${System.nanoTime()}").apply { mkdirs() }
        val other = SyntheticVideo.create(File(directory, "other.mp4"), 1000, 160, 160,
            lumaAt = { _, _, _ -> 200 })
        val moving = SyntheticVideo.create(File(directory, "moving.mp4"), 1000, 160, 160,
            lumaAt = { _, _, frame -> if (frame < 15) 40 else 160 })
        val store = ProjectAssetStore(File(directory, "project"))
        val inspected = listOf(other, moving, moving).mapIndexed { index, file ->
            MediaInputInspector().inspect("slot-$index", file, "Slot $index")
        }
        val assets = inspected.map { store.importVideo(it, 1f) }
        val selections = inspected.mapIndexed { index, value ->
            SelectedVideo.fromInspected(value, assets[index], SourceOwnership.IMPORTED)
        }
        val graph = MontageGraph(1000, 2000, clips = listOf(1, 0).mapIndexed { index, source ->
            MontageGraph.Clip("clip-$index", 0, 1000, 1000, MontageGraph.ShotRole.ACTION,
                if (index == 0) MontageGraph.Transition.OPEN else MontageGraph.Transition.HARD_CUT,
                MontageGraph.Motion.HOLD, 1f, 0, sourceIndex = source)
        }, overlays = listOf(MontageGraph.Overlay("flash-B", 1000, 1100, "flash", opacity = .6f)),
            metadata = NleProjectMetadata(generator = "manual-basic-test"))
        val export = ProjectExportSettings(720, 720, 60, 2_000_000, 128_000)
        val musicFile = File(directory, "original-music.m4a")
        instrumentation.targetContext.resources.openRawResource(R.raw.duality_loop_author).use { source ->
            musicFile.outputStream().use { source.copyTo(it) }
        }
        val musicDuration = MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(musicFile.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1_000
        }
        val music = store.import(musicFile, ProjectAsset.Kind.AUDIO, musicDuration)
        val imported = EditableMontageImporter.create("direct", graph, MontageStyleCatalog.Recipe.DUALITY_LOOP,
            export, assets, music, null, selections).shared
        val revision = imported.current.copy(visualSettings = ProjectVisualSettings(ProjectAspect.SQUARE_1_1, true))
        val project = imported.copy(original = revision, current = revision)
        val prepared = HybridPreviewPreparation.prepare(project, export, store)
        val output = File(directory, "must-not-exist.mp4")
        val cancelArmed = AtomicBoolean(false)
        val enteredCancelledOperation = CountDownLatch(1)
        val continueCancelledOperation = CountDownLatch(1)
        val request = PreparedHybridExportAdapter.request(prepared, project, output, audioFile = musicFile, checkCancelled = {
            if (cancelArmed.compareAndSet(true, false)) {
                assertNull("Direct GL worker must have no Looper", Looper.myLooper())
                enteredCancelledOperation.countDown()
                check(continueCancelledOperation.await(5, TimeUnit.SECONDS))
            }
        })
        val before = prepared.sources.map { it.file.readBytes() }
        val musicBefore = musicFile.readBytes()
        val states = LinkedBlockingQueue<DraftPreviewController.State>()
        val emitted = AtomicReference<DraftPreviewController.State>()
        val pixels = LinkedBlockingQueue<Int>()
        val reader = ImageReader.newInstance(360, 360, PixelFormat.RGBA_8888, 4)
        reader.setOnImageAvailableListener({ source ->
            source.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                pixels.offer(plane.buffer.get(180 * plane.rowStride + 180 * plane.pixelStride).toInt() and 255)
            }
        }, Handler(Looper.getMainLooper()))
        lateinit var controller: DraftPreviewController
        instrumentation.runOnMainSync {
            controller = DraftPreviewController { emitted.set(it); states.offer(it) }
            controller.open(MediaCodecSpeedRampRenderer.Request(graph = request.graph,
                sourceFiles = request.sourceFiles, outputFile = output, width = request.width,
                height = request.height, fps = request.fps, bitrate = request.bitrate))
        }
        var rejection: DraftPreviewController.State? = null
        val admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < admissionDeadline && rejection == null) {
            val next = states.poll(200, TimeUnit.MILLISECONDS)
            if (next?.error != null) rejection = next
        }
        instrumentation.runOnMainSync {
            controller.open(request)
            controller.attach(reader.surface, OutputSize(360, 360))
        }
        fun awaitFrame(timeUs: Long, source: Int, min: Int, max: Int) {
            val generation = emitted.get().generation
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var ready: DraftPreviewController.State? = null
            while (System.nanoTime() < deadline) {
                val state = states.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (state.generation != generation) continue
                assertNull(state.error)
                if (state.exactFrameReady && state.shownOutputTimeUs == timeUs) { ready = state; break }
            }
            assertNotNull("Expected direct frame $timeUs", ready)
            assertEquals(source, ready!!.sourceIndex)
            val expected = PreviewFrameSelection.nearest(prepared.framePlan, timeUs).sourceTimeUs
            assertTrue(ready.decodedSourceTimeUs!! >= expected)
            assertTrue(ready.decodedSourceTimeUs!! <= expected + 34_000)
            var matched = false
            while (System.nanoTime() < deadline) {
                val value = pixels.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (value in min..max) { matched = true; break }
            }
            assertTrue("Expected actual Surface pixel in $min..$max", matched)
            assertFalse(output.exists())
            assertFalse(File(directory, "must-not-exist.video.mp4").exists())
        }
        fun awaitPlaying() {
            val generation = emitted.get().generation
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                val next = states.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (next.generation != generation) continue
                assertNull(next.error)
                if (next.playing) { assertTrue(next.exactFrameReady); return }
            }
            fail("MediaPlayer prepare/seek plus exact frame must authorize real start")
        }
        try {
            assertTrue("Legacy request must reject even before attaching a Surface",
                rejection?.error?.contains("PreparedHybridExportAdapter") == true)
            awaitFrame(0, 1, 0, 90)
            instrumentation.runOnMainSync { controller.play() }
            awaitPlaying()
            instrumentation.runOnMainSync {
                controller.pause(); controller.beginScrub(); controller.seekTo(750_000); controller.endScrub()
            }
            awaitFrame(750_000, 1, 130, 210)
            instrumentation.runOnMainSync { controller.seekTo(100_000) }
            awaitFrame(100_000, 1, 0, 90)
            instrumentation.runOnMainSync { controller.seekTo(1_100_000) }
            awaitFrame(1_100_000, 0, 180, 255)
            states.clear(); pixels.clear()
            instrumentation.runOnMainSync {
                cancelArmed.set(true); controller.seekTo(900_000)
            }
            assertTrue(enteredCancelledOperation.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { controller.seekTo(0) }
            continueCancelledOperation.countDown()
            awaitFrame(0, 1, 0, 90)
            instrumentation.runOnMainSync { controller.play() }
            awaitPlaying()
            instrumentation.runOnMainSync { controller.detach() }
        } finally {
            continueCancelledOperation.countDown()
            val stopped = CountDownLatch(1)
            instrumentation.runOnMainSync { controller.stopAndRelease { stopped.countDown() } }
            assertTrue("Borrowed Surface must not release before asynchronous stop", stopped.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                controller.close()
                reader.setOnImageAvailableListener(null, null)
            }
            reader.close()
        }
        prepared.sources.forEachIndexed { index, source -> assertArrayEquals(before[index], source.file.readBytes()) }
        assertArrayEquals(musicBefore, musicFile.readBytes())
    }
}
