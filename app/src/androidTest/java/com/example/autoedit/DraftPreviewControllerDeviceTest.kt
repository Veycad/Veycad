package com.veycad.app

import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
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
    @Test fun pausedNormalMinuteDropsProducerAnalysisAndKeepsOnlyBoundedOwnedPages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "minute-preview-${System.nanoTime()}").apply { mkdirs() }
        val video = SyntheticVideo.create(File(directory, "minute.mp4"), 60_000, 160, 160)
        val originalBytes = video.readBytes()
        val assets = ProjectAssetStore(File(directory, "project"))
        val inspected = MediaInputInspector().inspect("minute", video, "Minute")
        val source = assets.importVideo(inspected, 1f)
        val score = assets.import(SyntheticAudio.writeWave(File(directory, "unused-score.wav"), 1),
            ProjectAsset.Kind.AUDIO, 1_000_000)
        val settings = ProjectExportSettings(720, 720, 30, 3_000_000, 192_000)
        val output = File(directory, "never-rendered.mp4")
        val oldPages = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("preview-floats-") }.toSet()
        val states = LinkedBlockingQueue<DraftPreviewController.State>()
        val pixel = CountDownLatch(1)
        val prepared = CountDownLatch(1)
        val reader = ImageReader.newInstance(360, 360, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ images -> images.acquireLatestImage()?.use { image ->
            if (image.planes[0].buffer.remaining() > 0) pixel.countDown()
        } }, Handler(Looper.getMainLooper()))
        lateinit var controller: DraftPreviewController
        instrumentation.runOnMainSync {
            controller = DraftPreviewController(states::offer)
            controller.attach(reader.surface, OutputSize(360, 360))
        }
        // The helper deliberately returns no project/graph/plane. An accidentally retained UI or
        // executor closure therefore keeps these real 120 MiB producer arrays observable here.
        val released = ReferenceQueue<Any>()
        val producers = prepareNormalMinute(controller, assets, source, score, inspected, settings, output, prepared, released)
        var ownedPages = emptyList<File>()
        try {
            assertTrue("Owned preparation callback", prepared.await(60, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var ready: DraftPreviewController.State? = null
            val observed = mutableListOf<DraftPreviewController.State>()
            while (System.nanoTime() < deadline && ready == null) {
                val state = states.poll(200, TimeUnit.MILLISECONDS) ?: continue
                observed += state
                assertNull(state.error)
                if (state.exactFrameReady) ready = state
            }
            assertNotNull("Actual paused native display must be ready; states=$observed; image=${pixel.count == 0L}", ready)
            assertFalse(ready!!.playing)
            assertTrue("Prepared pages and actual GL share one 32 MiB owner: ${ready.appOwnedBytes}",
                ready.appOwnedBytes in 1..(32L * 1024 * 1024))
            assertTrue("Actual ImageReader image", pixel.await(5, TimeUnit.SECONDS))
            ownedPages = context.cacheDir.listFiles().orEmpty().filter {
                it.name.startsWith("preview-floats-") && it !in oldPages
            }
            assertEquals("Large analysis uses one exact owned spool", 1, ownedPages.size)
            assertTrue(ownedPages.single().length() >= 120L * 1024 * 1024)
            val gcDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var collected = 0
            // Poll notifications, not referents: get() can keep the observed project alive across GC.
            while (collected < producers.size && System.nanoTime() < gcDeadline) {
                Runtime.getRuntime().gc()
                System.runFinalization()
                instrumentation.waitForIdleSync()
                while (released.poll() != null) collected++
                Thread.sleep(50)
            }
            assertEquals("Every producer must be collected", producers.size, collected)
            assertTrue("Paused production controller must not retain the input project or any producer float array",
                producers.all { it.get() == null })
            assertFalse(output.exists())
            assertArrayEquals(originalBytes, video.readBytes())
            assertArrayEquals(originalBytes, assets.resolve(source).readBytes())
        } finally {
            val stopped = CountDownLatch(1)
            instrumentation.runOnMainSync { controller.stopAndRelease { stopped.countDown() } }
            assertTrue("Stop acknowledges paged/GL/audio disposal", stopped.await(30, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { controller.close(); reader.setOnImageAvailableListener(null, null) }
            reader.close()
        }
        assertTrue("Acknowledged stop removes only this preparation's spool", ownedPages.all { !it.exists() })
        assertTrue(File(directory, "preview-audio").listFiles().orEmpty().isEmpty())
        assertArrayEquals(originalBytes, video.readBytes())
        assertArrayEquals(originalBytes, assets.resolve(source).readBytes())
    }

    private fun prepareNormalMinute(controller: DraftPreviewController, assets: ProjectAssetStore,
        source: ProjectAsset, score: ProjectAsset, inspected: MediaSource,
        settings: ProjectExportSettings, output: File, prepared: CountDownLatch,
        released: ReferenceQueue<Any>): List<WeakReference<*>> {
        val frames = List(240) { index -> FrameAttachments(index * 250_000L,
            mask = FrameAttachments.Plane(256, 256, FloatArray(256 * 256) { (index + 1) / 1000f }, .9f),
            depth = FrameAttachments.Plane(256, 256, FloatArray(256 * 256) { .7f }, .8f)) }
        val graph = MontageGraph(60_000, 60_000, clips = listOf(MontageGraph.Clip("minute", 0, 60_000,
            60_000, MontageGraph.ShotRole.ACTION, MontageGraph.Transition.HARD_CUT, MontageGraph.Motion.HOLD, 1f, 0)),
            frameAttachments = FrameAttachmentTimeline(frames), metadata = NleProjectMetadata(generator = "normal-minute-preview"))
        val imported = EditableMontageImporter.create("normal-minute", graph, MontageStyleCatalog.Recipe.DUALITY_LOOP,
            settings, listOf(source), score, null,
            listOf(SelectedVideo.fromInspected(inspected, source, SourceOwnership.IMPORTED))).shared
        val revision = imported.current.copy(visualSettings = ProjectVisualSettings(ProjectAspect.SQUARE_1_1, true),
            audioMix = AudioMixSettings(AudioMixMode.SOURCE))
        val project = imported.copy(original = revision, current = revision)
        val references = listOf(WeakReference(project, released)) + frames.flatMap {
            listOf(WeakReference(it.mask!!.values, released), WeakReference(it.depth!!.values, released))
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.prepareAndOpen(project, settings, assets, output,
                InstrumentationRegistry.getInstrumentation().targetContext) { _, _ -> prepared.countDown() }
        }
        return references
    }

    @Test fun overlapTemporalEffectsAndSavedTextMatchNativeExportAfterReverseSeeks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "expanded-preview-${System.nanoTime()}").apply { mkdirs() }
        val videos = listOf(
            SyntheticVideo.create(File(directory, "moving.mp4"), 1000, 160, 160,
                lumaAt = { _, _, frame -> if (frame < 15) 40 else 100 }),
            SyntheticVideo.create(File(directory, "bright.mp4"), 1000, 160, 160, lumaAt = { _, _, _ -> 200 }))
        val assets = ProjectAssetStore(File(directory, "project"))
        val inspected = videos.mapIndexed { index, file -> MediaInputInspector().inspect("slot-$index", file, "Slot $index") }
        val sources = inspected.map { assets.importVideo(it, 1f) }
        val selections = inspected.mapIndexed { index, value -> SelectedVideo.fromInspected(value, sources[index], SourceOwnership.IMPORTED) }
        val score = SyntheticAudio.writeWave(File(directory, "score.wav"), 4)
        val music = assets.import(score, ProjectAsset.Kind.AUDIO, 4_000_000)
        val graph = MontageGraph(1000, 2000, clips = listOf(0, 1).mapIndexed { index, source ->
            MontageGraph.Clip("clip-$index", 0, 1000, 1000, MontageGraph.ShotRole.ACTION,
                if (index == 0) MontageGraph.Transition.HARD_CUT else MontageGraph.Transition.WHIP,
                MontageGraph.Motion.HOLD, 1f, 0, sourceIndex = source)
        }, overlays = listOf(MontageGraph.Overlay("temporal", 400, 900, "temporal",
            overlayKind = MontageGraph.OverlayKind.DOUBLE_EXPOSURE, opacity = .5f)),
            effectGraph = GpuEffectGraph(listOf(GpuEffectGraph.Node("glow", GpuEffectGraph.Kind.GLOW,
                1_000_000, 1_500_000, .2f))), metadata = NleProjectMetadata(generator = "expanded-preview-fixture"))
        val settings = ProjectExportSettings(720, 720, 30, 3_000_000, 192_000)
        val imported = EditableMontageImporter.create("expanded", graph, MontageStyleCatalog.Recipe.DUALITY_LOOP,
            settings, sources, music, null, selections).shared
        val revision = imported.current.copy(visualSettings = ProjectVisualSettings(ProjectAspect.SQUARE_1_1, true),
            music = imported.current.music.copy(startUs = 1_000_000, gain = .4f, fadeInUs = 100_000, fadeOutUs = 200_000, repeat = false),
            texts = listOf(TextItem("legacy", "L", FrameSpan(0, 60), .25f, .65f, .2f, .1f,
                android.graphics.Color.RED, TextItem.Appearance.ACCENT, 2)),
            textState = HybridTextState(layers = listOf(TextLayer("rich", "Сохранено", 0, 1_000_000)),
                captions = listOf(CaptionCue("cue", "Речь", 1_000_000, 2_000_000))))
        val project = imported.copy(original = revision, current = revision)
        val prepared = HybridPreviewPreparation.prepare(project, settings, assets)
        val output = File(directory, "parity.mp4")
        val request = PreparedHybridExportAdapter.request(prepared, project, output, context = context)
        val bytes = prepared.sources.map { it.file.readBytes() }
        val states = LinkedBlockingQueue<DraftPreviewController.State>()
        val pixels = LinkedBlockingQueue<Int>()
        val reader = ImageReader.newInstance(360, 360, PixelFormat.RGBA_8888, 4)
        reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.use { image ->
            val plane = image.planes[0]
            pixels.offer(plane.buffer.get(180 * plane.rowStride + 180 * plane.pixelStride).toInt() and 255)
        } }, Handler(Looper.getMainLooper()))
        lateinit var controller: DraftPreviewController
        val samples = linkedMapOf<Long, Int>()
        instrumentation.runOnMainSync {
            controller = DraftPreviewController(states::offer)
            controller.open(request); controller.attach(reader.surface, OutputSize(360, 360))
        }
        try {
            for (time in listOf(500_000L, 1_100_000L, 100_000L, 1_500_000L)) {
                states.clear(); pixels.clear()
                instrumentation.runOnMainSync { controller.seekTo(time) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                var accepted: DraftPreviewController.State? = null
                while (System.nanoTime() < deadline && accepted == null) {
                    val state = states.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    assertNull(state.error)
                    if (state.exactFrameReady && state.shownOutputTimeUs == time) accepted = state
                }
                assertNotNull("Expected native direct frame $time", accepted)
                if (time == 500_000L || time == 1_100_000L) {
                    assertNotNull("Actual second decoder role must participate", accepted!!.secondarySourceIndex)
                    assertNotNull(accepted.decodedSecondarySourceTimeUs)
                }
                var latestPixel = requireNotNull(pixels.poll(5, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                while (true) latestPixel = pixels.poll() ?: break
                samples[time] = latestPixel
                assertFalse(output.exists())
                assertFalse(File(directory, "parity.video.mp4").exists())
            }
        } finally {
            val stopped = CountDownLatch(1)
            instrumentation.runOnMainSync { controller.stopAndRelease { stopped.countDown() } }
            assertTrue(stopped.await(20, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { controller.close(); reader.setOnImageAvailableListener(null, null) }
            reader.close()
        }
        assertEquals(prepared.framePlan.frames.size, MediaCodecSpeedRampRenderer.render(request))
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(output.path)
            samples.forEach { (time, preview) ->
                val bitmap = requireNotNull(retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST))
                try {
                    val encoded = android.graphics.Color.red(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                    assertTrue("Preview/export actual center pixel at $time: $preview / $encoded",
                        kotlin.math.abs(preview - encoded) <= 24)
                } finally { bitmap.recycle() }
            }
        }
        prepared.sources.forEachIndexed { index, source -> assertArrayEquals(bytes[index], source.file.readBytes()) }
        prepared.close()
    }

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
        val request = PreparedHybridExportAdapter.request(prepared, project, output, audioFile = prepared.audio.musicFile, checkCancelled = {
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
