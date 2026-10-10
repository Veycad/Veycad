package com.veycad.app

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Prepared G2 wrapper fixtures; runtime execution is queued, not claimed by JVM validation. */
@RunWith(AndroidJUnit4::class)
class MultiSourceRequestDeviceTest {
    @Test fun twentyOrderedSourcesReachNativeTransitionAndSamplerAfterCancelledExport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "g2-native-sources").apply { mkdirs() }
        val seed = SyntheticVideo.create(File(directory, "seed.mp4"), 2000)
        val files = List(20) { index -> File(directory, "source-$index.mp4").also {
            seed.copyTo(it, overwrite = true)
        } }
        val clips = listOf(19, 7, 0).mapIndexed { index, sourceIndex -> MontageGraph.Clip(
            "clip-$index", 0, 400, 400, MontageGraph.ShotRole.ACTION,
            if (index == 0) MontageGraph.Transition.HARD_CUT else MontageGraph.Transition.WHIP,
            MontageGraph.Motion.HOLD, 1f, index * 400L, sourceIndex = sourceIndex,
            transitionDurationMs = if (index == 0) null else 100)
        }
        val graph = MontageGraph(2000, 1200, clips = clips)
        val interrupted = File(directory, "cancelled.mp4")
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
                sourceFiles = files, graph = graph, outputFile = interrupted,
                width = 160, height = 240, bitrate = 200_000,
                checkCancelled = { if (++checks >= 44) error("cancelled fixture export") }))
        }
        val output = File(directory, "ordered.mp4")
        assertEquals(36, MediaCodecSpeedRampRenderer.render(MediaCodecSpeedRampRenderer.Request(
            sourceFiles = files, graph = graph, outputFile = output,
            width = 160, height = 240, bitrate = 200_000, context = context)))
        val artifacts = requireNotNull(VeykadRenderInspector.latest(context, output))
        val json = org.json.JSONObject(artifacts.report.readText()).getJSONArray("frames")
        val observed = (0 until json.length()).map { json.getJSONObject(it).getInt("source_index") }.toSet()
        assertEquals(setOf(19, 7, 0), observed)
        assertTrue((0 until json.length()).any { json.getJSONObject(it).optInt("secondary_source_index", -1) == 19 })
        val samples = RenderedVisualSampler.sample(output, graph, sourceFiles = files,
            sourceVisualMaps = List(20) { null }, decodedSourceTimes = VeykadRenderInspector.decodedSourceClock(artifacts))
        assertEquals(setOf(19, 7, 0), samples.mapNotNull { it.decodedSourceIndex }.toSet())
    }

    @Test fun legacyWrapperInspectsOnWorkerAndKeepsCaptureLinkArguments() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = SyntheticVideo.create(File(context.cacheDir, "g2-capture.mp4"), 1000)
        val descriptor = MediaInputInspector().inspect("capture", source, "Capture")
        val music = BuiltInMusicCatalog.select(context, MontageStyleCatalog.fearStrobe.id).file
        val capture = CaptureSessionStore(context.filesDir).create(CaptureScript.fear,
            CaptureMode.BEST_TAKE, 3, true, 0)
        val received = CountDownLatch(1)
        lateinit var session: EditSession
        var observed: VeycadAutomaticEditor.Request? = null
        var inspectionThread: Thread? = null
        instrumentation.runOnMainSync {
            session = EditSession(context, EditSession.RenderEngine { request ->
                observed = request
                source.copyTo(request.outputFile, overwrite = true)
                received.countDown()
                EditSession.RenderSummary(1, 1f, true, emptyList())
            }, EditSession.SourceInspector { id, file, name, checkCancelled ->
                checkCancelled()
                inspectionThread = Thread.currentThread()
                assertEquals(source, file)
                descriptor.copy(id = id, displayName = name)
            })
            session.render(source, null, music, MontageStyleCatalog.fearStrobe,
                captureSessionId = capture.id, captureTakeOrdinal = 2,
                captureAutoRecommendation = true)
        }
        try {
            assertTrue(received.await(10, TimeUnit.SECONDS))
            assertNotSame(Looper.getMainLooper().thread, inspectionThread)
            assertEquals(listOf(source), requireNotNull(observed).sources.items.map { it.file })
            assertEquals(descriptor.fingerprint, observed!!.sources.items.single().fingerprint)
            awaitIdle(session)
            assertEquals(CompletedRenderStore.CaptureLink(capture.id, 2, true),
                session.state.value!!.entry!!.captureLink)
        } finally {
            instrumentation.runOnMainSync { session.cancelRender() }
            awaitIdle(session)
            session.close()
        }
    }

    @Test fun immutableSourceWrapperKeepsSelectionOrderWithoutReinspection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val first = SyntheticVideo.create(File(context.cacheDir, "g2-first.mp4"), 1000)
        val second = SyntheticVideo.create(File(context.cacheDir, "g2-second.mp4"), 1000)
        val inspector = MediaInputInspector()
        val sources = MediaSourceSet(listOf(inspector.inspect("chosen-second", second, "Second"),
            inspector.inspect("chosen-first", first, "First")))
        val music = BuiltInMusicCatalog.select(context, MontageStyleCatalog.dualityLoop.id).file
        val received = CountDownLatch(1)
        lateinit var session: EditSession
        var observed: VeycadAutomaticEditor.Request? = null
        instrumentation.runOnMainSync {
            session = EditSession(context, EditSession.RenderEngine { request ->
                observed = request
                received.countDown()
                error("intentional render failure before publication")
            }, EditSession.SourceInspector { _, _, _, _ -> error("must use supplied metadata") })
            session.render(sources, music, MontageStyleCatalog.dualityLoop, width = 1080,
                height = 1920, bitrate = 8_000_000)
        }
        try {
            assertTrue(received.await(10, TimeUnit.SECONDS))
            assertSame(sources, observed!!.sources)
            assertEquals(listOf(second, first), observed!!.sources.items.map { it.file })
            assertEquals(1080, observed!!.width)
            assertEquals(1920, observed!!.height)
            assertEquals(8_000_000, observed!!.bitrate)
        } finally {
            instrumentation.runOnMainSync { session.cancelRender() }
            awaitIdle(session)
            session.close()
        }
    }

    @Test fun defaultLegacyInspectionKeepsRealMetadataOnWorker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = SyntheticVideo.create(File(context.cacheDir, "g2-default.mp4"), 1000)
        val actual = MediaInputInspector().inspect("expected", source, source.name)
        val music = BuiltInMusicCatalog.select(context, MontageStyleCatalog.fearStrobe.id).file
        val received = CountDownLatch(1)
        lateinit var session: EditSession
        var observed: VeycadAutomaticEditor.Request? = null
        var renderThread: Thread? = null
        instrumentation.runOnMainSync {
            session = EditSession(context, EditSession.RenderEngine { request ->
                observed = request
                renderThread = Thread.currentThread()
                received.countDown()
                error("intentional failure")
            })
            session.render(source, null, music, MontageStyleCatalog.fearStrobe)
        }
        try {
            assertTrue(received.await(10, TimeUnit.SECONDS))
            assertNotSame(Looper.getMainLooper().thread, renderThread)
            assertEquals(actual.copy(id = "legacy-0"), observed!!.sources.items.single())
        } finally {
            instrumentation.runOnMainSync { session.cancelRender() }
            awaitIdle(session)
            session.close()
        }
    }

    private fun awaitIdle(session: EditSession) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        do {
            var busy = true
            instrumentation.runOnMainSync { busy = session.state.value?.busy == true }
            if (!busy) return
            android.os.SystemClock.sleep(25)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        fail("Session did not release its worker")
    }
}
