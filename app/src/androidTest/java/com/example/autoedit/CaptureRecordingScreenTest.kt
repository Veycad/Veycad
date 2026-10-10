package com.veycad.app

import android.Manifest
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.camera.view.PreviewView
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.VideoRecordEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual CameraX, encoder, local music and MP4. No montage renderer or human-quality claim. */
@RunWith(AndroidJUnit4::class)
class CaptureRecordingScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val owner get() = CaptureRecordingSession.get(context)

    @Before fun permission() {
        check(context.packageName.endsWith(".uitest"))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
    }

    @Test fun countdownCanCancelAndShortRecordingProducesReadableSilentVideo() {
        open().use { screen ->
            ready(screen)
            screenshot("camera-preparation.png")
            val before = owner.store.list().map { it.id }.toSet()
            tap(R.id.capturePrimary)
            tap(R.id.capturePrimary)
            assertEquals(before, owner.store.list().map { it.id }.toSet())
            start(screen)
            onView(withId(R.id.capturePrimary)).check(matches(isDisplayed()))
            onView(withId(R.id.captureRecordingIndicator)).check(matches(isDisplayed()))
            await("three seconds of encoded video") { owner.state.value!!.elapsedMs >= 3_500 }
            screenshot("camera-recording.png")
            tap(R.id.capturePrimary)
            val saved = awaitSaved()
            assertTrue(saved.durationMs in 2_500..14_999)
            checkVideo(saved)
            screenshot("camera-short-review.png")
            screen.recreate()
            var restored = false
            await("restored saved recording") {
                screen.onActivity { restored = it.findViewById<TextView>(R.id.captureTitle).text == context.getString(R.string.capture_saved) }
                restored
            }
            assertEquals(saved, CaptureSessionStore(context.filesDir).load(saved.id))
        }
    }

    @Test fun backgroundingStopsRecordingButRetainsFinalizedSource() {
        open().use { screen ->
            ready(screen)
            start(screen)
            await("source frames before background") { owner.state.value!!.elapsedMs >= 2_500 }
            screen.moveToState(Lifecycle.State.CREATED)
            val saved = awaitSaved()
            checkVideo(saved)
            screen.moveToState(Lifecycle.State.RESUMED)
            assertEquals(CaptureSessionStore.Status.READY, owner.store.load(saved.id)?.status)
        }
    }

    @Test fun cameraLossDuringCountdownCreatesNoDraftAndRetryRecordsWithPreview() {
        open().use { screen ->
            ready(screen)
            val before = owner.store.list().map { it.id }.toSet()
            tap(R.id.capturePrimary)
            screen.onActivity { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            var retryShown = false
            await("camera loss cancels countdown", 10_000) {
                screen.onActivity { retryShown = it.findViewById<TextView>(R.id.capturePrimary).text == context.getString(R.string.capture_retry) }
                retryShown
            }
            assertEquals("Camera loss must not create a recording draft", before, owner.store.list().map { it.id }.toSet())
            assertFalse(owner.state.value!!.busy)
            screen.onActivity {
                assertEquals(context.getString(R.string.capture_failed_title), it.findViewById<TextView>(R.id.captureTitle).text.toString())
                assertEquals(View.GONE, it.findViewById<View>(R.id.capturePreviewFrame).visibility)
            }
            screenshot("camera-countdown-loss.png")
            tap(R.id.capturePrimary)
            ready(screen)
            start(screen)
            await("frames after retry") { owner.state.value!!.elapsedMs >= 3_500 }
            tap(R.id.capturePrimary)
            checkVideo(awaitSaved())
        }
    }

    @Test fun unexpectedCameraLossPreservesSourceAndReasonAfterRecreation() {
        open(CaptureMode.BEST_TAKE).use { screen ->
            ready(screen)
            start(screen)
            await("full first take before camera loss", 60_000) { owner.state.value!!.elapsedMs >= 27_300 }
            screen.onActivity {
                assertEquals(CaptureRecordingSession.Phase.RECORDING, owner.state.value!!.phase)
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
            val saved = awaitSaved()
            checkVideo(saved)
            assertTrue(CaptureTakeTimeline.windows(CaptureScript.fear, 3, saved.durationMs).any { it.complete })
            val reason = context.getString(R.string.capture_source_lost)
            assertEquals(reason, saved.stopReason)
            assertEquals(reason, CaptureSessionStore(context.filesDir).load(saved.id)?.stopReason)
            val report = CaptureDiagnostics.report(context)
            val events = JSONObject(report).getJSONArray("events")
            val finalized = (0 until events.length()).map(events::getJSONObject)
                .last { it.getString("event") == "capture_record_finalized" }
            assertEquals(VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE.toString(), finalized.getString("recorder_error"))
            assertEquals("false", finalized.getString("requested_stop"))
            File(context.getExternalFilesDir("camera-evidence"), "camera-unexpected-loss-report.json").writeText(report)
            fun reasonShown(): Boolean {
                var shown = false
                screen.onActivity { shown = it.findViewById<TextView>(R.id.captureDetail).text.contains(reason) }
                return shown
            }
            await("camera loss explanation") { reasonShown() }
            screenshot("camera-unexpected-loss.png")
            screen.recreate()
            await("camera loss reason after recreation") { reasonShown() }
            assertTrue(owner.store.recording(saved.id).isFile)
        }
    }

    @Test fun cameraLossWhilePreparingTerminatesWithReasonAndRetryRecords() {
        open().use { screen ->
            ready(screen)
            var disconnected = false
            val disconnect = Observer<CaptureRecordingSession.State> { state ->
                if (!disconnected && state.phase == CaptureRecordingSession.Phase.PREPARING) {
                    disconnected = true
                    ProcessCameraProvider.getInstance(context).get().unbindAll()
                }
            }
            screen.onActivity { owner.state.observe(it, disconnect) }
            try {
                tap(R.id.capturePrimary)
                await("disconnect after countdown", 15_000) { disconnected }
                await("preparing capture terminates after camera loss", 15_000) { owner.state.value?.busy != true }
                var reasonVisible = false
                await("camera interruption remains visible") {
                    screen.onActivity {
                        reasonVisible = it.findViewById<TextView>(R.id.captureDetail).text.contains("Камера отключилась")
                    }
                    reasonVisible
                }
                val interrupted = owner.state.value?.session
                interrupted?.let {
                    assertFalse("Interrupted session must leave PREPARED", it.status == CaptureSessionStore.Status.PREPARED)
                    if (it.status == CaptureSessionStore.Status.READY) checkVideo(it)
                }
                var retry = false
                screen.onActivity { retry = it.findViewById<TextView>(R.id.capturePrimary).text == context.getString(R.string.capture_retry) }
                tap(if (retry) R.id.capturePrimary else R.id.captureSecondary)
                ready(screen)
                start(screen)
                await("encoded frames after preparing-stage retry") { owner.state.value!!.elapsedMs >= 3_500 }
                tap(R.id.capturePrimary)
                checkVideo(awaitSaved())
            } finally {
                instrumentation.runOnMainSync { owner.state.removeObserver(disconnect) }
            }
        }
    }

    @Test fun continuousThreeTakeSessionHasRecoveriesAndFinalizesAutomatically() {
        open(CaptureMode.BEST_TAKE).use { screen ->
            ready(screen)
            start(screen)
            await("second take", 75_000) { owner.state.value!!.take >= 2 }
            screenshot("camera-second-take.png")
            await("automatic finalization", 150_000) { owner.state.value!!.phase == CaptureRecordingSession.Phase.READY }
            val saved = requireNotNull(owner.state.value!!.session)
            val expected = CaptureTakeTimeline.totalDurationMs(CaptureScript.fear, 3)
            // CameraX may flush queued frames after stop; they stay in the raw source, outside take windows.
            assertTrue("duration=${saved.durationMs}", saved.durationMs in expected - 250..expected + 5_000)
            val windows = CaptureTakeTimeline.windows(CaptureScript.fear, 3, saved.durationMs)
            assertEquals(3, windows.size)
            assertTrue(windows.all { it.durationMs >= CaptureScript.fear.durationMs - 250 })
            assertEquals(CaptureScript.RECOVERY_MS, windows[1].startMs - windows[0].endMs)
            assertEquals(expected, windows.last().endMs)
            checkVideo(saved)
            val events = owner.store.events(saved.id).readLines()
            assertTrue(events.any { it.startsWith("cue:2:0\t") })
            assertTrue(events.any { it.startsWith("cue:3:0\t") })
            for (ordinal in 1..3) {
                val startIndex = events.indexOfFirst { it.startsWith("music-start:$ordinal\t") }
                assertTrue("Missing music start for take $ordinal", startIndex >= 0)
                val start = events[startIndex].split('\t')
                val startRequestIndex = events.indexOfFirst { it.startsWith("music-start-request:$ordinal\t") }
                assertTrue("Missing start request", startRequestIndex >= 0 && startRequestIndex < startIndex)
                val startRequest = events[startRequestIndex].split('\t')
                assertTrue("Player was not at zero before start: $startRequest", startRequest[3].toLong() in 0..100)
                val window = windows[ordinal - 1]
                assertTrue("Music start outside take boundary: $start", start[2].toLong() in window.startMs..window.startMs + 250)
                if (ordinal > 1) {
                    val requestIndex = events.indexOfFirst { it.startsWith("music-seek-request:$ordinal\t") }
                    val completeIndex = events.indexOfFirst { it.startsWith("music-seek-complete:$ordinal\t") }
                    assertTrue("Start must follow completed seek", requestIndex >= 0 && completeIndex > requestIndex && startRequestIndex > completeIndex)
                    for (index in listOf(requestIndex, completeIndex)) {
                        val videoMs = events[index].split('\t')[2].toLong()
                        assertTrue("Music rewind must finish within recovery", videoMs in windows[ordinal - 2].endMs until window.startMs)
                    }
                }
            }
            owner.store.events(saved.id).copyTo(File(context.getExternalFilesDir("camera-evidence"),
                "continuous-music-timeline.tsv"), overwrite = true)
            assertFalse("Original must not be stored in disposable cache", owner.store.recording(saved.id).path.startsWith(context.cacheDir.path))
        }
    }

    @Test fun heartbeatSingleTakeFinalizesWithItsOwnCuesAndMusic() {
        val script = CaptureScript.heartbeat
        open(script = script).use { screen ->
            ready(screen)
            start(screen)
            await("Heartbeat automatic finalization", 70_000) { owner.state.value!!.phase == CaptureRecordingSession.Phase.READY }
            val saved = requireNotNull(owner.state.value!!.session)
            assertEquals(script.styleId, saved.styleId)
            assertEquals(CaptureMode.MUSIC, saved.mode)
            assertTrue("duration=${saved.durationMs}", saved.durationMs in script.durationMs - 250..script.durationMs + 5_000)
            assertTrue(CaptureTakeTimeline.windows(script, 1, saved.durationMs).single().complete)
            checkVideo(saved)
            val events = owner.store.events(saved.id).readLines()
            for (cue in script.cues) assertTrue("Missing Heartbeat cue $cue", events.any { it.startsWith("cue:1:${cue.atMs}\t") })
            assertEquals(1, events.count { it.startsWith("music-start:1\t") })
            assertFalse(events.any { it.startsWith("music-seek-request:") || it.startsWith("recovery:") })
            val start = events.single { it.startsWith("music-start-request:1\t") }.split('\t')
            assertTrue(start[3].toLong() in 0..100)
            owner.store.events(saved.id).copyTo(File(context.getExternalFilesDir("camera-evidence"),
                "heartbeat-music-timeline.tsv"), overwrite = true)
        }
    }

    @Test fun musicInterruptionKeepsFullTakeAndShowsReasonAfterRecreation() {
        val reason = "Музыка прервана. Запись остановлена"
        open(CaptureMode.BEST_TAKE).use { screen ->
            ready(screen)
            start(screen)
            await("first complete take", 60_000) { owner.state.value!!.elapsedMs >= 27_300 }
            instrumentation.runOnMainSync { owner.stop(reason) }
            val saved = awaitSaved()
            checkVideo(saved)
            assertEquals(reason, CaptureSessionStore(context.filesDir).load(saved.id)?.stopReason)
            assertTrue(CaptureTakeTimeline.windows(CaptureScript.fear, 3, saved.durationMs).any { it.complete })
            fun assertReason() {
                var visible = false
                await("saved reason remains on review") {
                    screen.onActivity { activity ->
                        visible = activity.findViewById<TextView>(R.id.captureDetail).text.contains(reason) &&
                            activity.findViewById<TextView>(R.id.captureTitle).text == context.getString(R.string.capture_saved)
                    }
                    visible
                }
            }
            assertReason()
            screen.recreate()
            assertReason()
            screenshot("camera-music-interruption.png")
        }
    }

    private fun open(mode: CaptureMode = CaptureMode.MUSIC, script: CaptureScript = CaptureScript.fear): ActivityScenario<CaptureActivity> {
        await("previous recording released", 20_000) { owner.state.value?.busy != true }
        return ActivityScenario.launch(Intent(context, CaptureActivity::class.java)
            .putExtra(CaptureActivity.EXTRA_STYLE, script.styleId)
            .putExtra(CaptureActivity.EXTRA_MODE, mode.name))
    }

    private fun ready(screen: ActivityScenario<CaptureActivity>) {
        var enabled = false
        await("camera available", 30_000) {
            screen.onActivity { enabled = it.findViewById<TextView>(R.id.capturePrimary).let { v ->
                v.isEnabled && v.text == context.getString(R.string.capture_start) &&
                    it.findViewById<PreviewView>(R.id.capturePreview).previewStreamState.value == PreviewView.StreamState.STREAMING
            } }
            enabled
        }
    }
    private fun start(screen: ActivityScenario<CaptureActivity>) {
        ready(screen)
        tap(R.id.capturePrimary)
        await("recording begins", 25_000) { owner.state.value!!.phase == CaptureRecordingSession.Phase.RECORDING }
    }
    private fun awaitSaved(): CaptureSessionStore.Session {
        await("source finalized", 20_000) { owner.state.value!!.phase == CaptureRecordingSession.Phase.READY }
        return requireNotNull(owner.state.value!!.session)
    }
    private fun checkVideo(session: CaptureSessionStore.Session) {
        val file = owner.store.recording(session.id)
        assertTrue(file.length() > 1_000)
        assertFalse(owner.store.staging(session.id).exists())
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val types = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            assertEquals(1, types.count { it.startsWith("video/") })
            assertEquals(0, types.count { it.startsWith("audio/") })
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val frame = retriever.getFrameAtTime(1_000_000)
            assertNotNull("Native decoder must read a recorded frame", frame)
            frame?.recycle()
        } finally { retriever.release() }
    }
    private fun tap(id: Int) = onView(withId(id)).perform(click())
    private fun screenshot(name: String) {
        UiDevice.getInstance(instrumentation).takeScreenshot(File(context.getExternalFilesDir("camera-evidence"), name))
    }
    private fun await(description: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            if (SystemClock.elapsedRealtime() >= until) fail("Timed out: $description; state=${owner.state.value}")
            SystemClock.sleep(80)
        }
    }
}
