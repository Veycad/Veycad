package com.veycad.app

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import android.widget.VideoView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real silent video and saved intervals; only review/navigation is under test. */
@RunWith(AndroidJUnit4::class)
class CaptureReviewScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun savedPortraitPreviewIsCenteredBeforeAndAfterRecreation() {
        val store = CaptureSessionStore(ui.context.filesDir)
        val draft = store.create(CaptureScript.fear, CaptureMode.MUSIC, 1, true, 0)
        SyntheticVideo.create(store.staging(draft.id), 3_000)
        val saved = store.markReady(draft.copy(status = CaptureSessionStore.Status.FINALIZING),
            CaptureRecordingSession.readableVideoDurationMs(store.staging(draft.id)))
        val bytes = store.recording(saved.id).readBytes()
        openSession(saved).use { screen ->
            fun assertCentered() {
                awaitReview(screen)
                awaitCondition(screen, "native portrait video has its decoded aspect ratio") {
                    val video = it.findViewById<VideoView>(R.id.capturePlayback)
                    video.width > 0 && video.height > 0 &&
                        kotlin.math.abs(video.width * 3 - video.height * 2) <= 3
                }
                screenshot("camera-portrait-review.png")
                screen.onActivity {
                    val frame = it.findViewById<View>(R.id.capturePreviewFrame)
                    val video = it.findViewById<VideoView>(R.id.capturePlayback)
                    assertTrue("Portrait video must leave room at both sides", video.width < frame.width)
                    assertTrue("Horizontal centre must match: left=${video.left}, width=${video.width}, frame=${frame.width}",
                        kotlin.math.abs(video.left * 2 + video.width - frame.width) <= 1)
                    assertTrue("Vertical centre must match",
                        kotlin.math.abs(video.top * 2 + video.height - frame.height) <= 1)
                    assertTrue(video.left >= 0 && video.top >= 0 &&
                        video.right <= frame.width && video.bottom <= frame.height)
                }
            }
            assertCentered()
            screen.recreate()
            assertCentered()
            assertArrayEquals(bytes, store.recording(saved.id).readBytes())
        }
    }

    @Test fun automaticChoiceSurvivesRecreationAfterOpeningSpecificResultTake() {
        val store = CaptureSessionStore(ui.context.filesDir)
        val original = store.create(CaptureScript.fear, CaptureMode.BEST_TAKE, 3, true, 0)
        // Two full attempts, with a real five-second recovery between them.
        val duration = CaptureScript.fear.durationMs * 2 + CaptureScript.RECOVERY_MS
        SyntheticVideo.create(store.staging(original.id), duration)
        val saved = store.markReady(original.copy(status = CaptureSessionStore.Status.FINALIZING),
            CaptureRecordingSession.readableVideoDurationMs(store.staging(original.id)))
        ActivityScenario.launch<CaptureActivity>(Intent(ui.context, CaptureActivity::class.java)
            .putExtra(CaptureActivity.EXTRA_STYLE, saved.styleId)
            .putExtra(CaptureActivity.EXTRA_MODE, saved.mode.name)
            .putExtra(CaptureActivity.EXTRA_SESSION, saved.id)
            .putExtra(CaptureActivity.EXTRA_TAKE, 2)).use { screen ->
            awaitReview(screen)
            ui.click(R.id.captureTakePicker)
            onView(withText(startsWith("Дубль 2"))).check(matches(isChecked()))
            onView(withText(R.string.capture_auto_select)).perform(click())
            screen.recreate()
            awaitReview(screen)
            ui.click(R.id.captureTakePicker)
            onView(withText(R.string.capture_auto_select)).check(matches(isChecked()))
            // A subsequent explicit choice must also survive, independently of the original intent.
            onView(withText(startsWith("Дубль 1"))).perform(click())
            screen.recreate()
            awaitReview(screen)
            ui.click(R.id.captureTakePicker)
            onView(withText(startsWith("Дубль 1"))).check(matches(isChecked()))
        }
    }

    @Test fun failedCaptureKeepsBothReasonsAfterInspectionFailureAndRecreation() {
        val store = CaptureSessionStore(ui.context.filesDir)
        val draft = store.create(CaptureScript.fear, CaptureMode.MUSIC, 1, true, 0)
        val bytes = ByteArray(512) { 0x7f }
        store.staging(draft.id).writeBytes(bytes)
        val error = "Не удалось завершить запись: ошибка кодировщика"
        val stop = ui.context.getString(R.string.capture_source_lost)
        val failed = draft.copy(status = CaptureSessionStore.Status.FAILED, error = error, stopReason = stop)
        store.save(failed)
        openSession(failed).use { screen ->
            awaitCheckButton(screen)
            fun assertReasons() {
                screen.onActivity {
                    val detail = it.findViewById<TextView>(R.id.captureDetail).text.toString()
                    assertTrue("Container failure was hidden: $detail", detail.contains(error))
                    assertTrue("Stop reason was hidden: $detail", detail.contains(stop))
                    assertNotEquals(ui.context.getString(R.string.capture_saved), it.findViewById<TextView>(R.id.captureTitle).text.toString())
                }
            }
            assertReasons()
            screenshot("camera-failed-container.png")
            onView(withId(R.id.capturePrimary)).perform(click())
            awaitCondition(screen, "failed recovery remains visible") {
                it.findViewById<TextView>(R.id.captureDetail).text.contains(ui.context.getString(R.string.capture_recovery_failed))
            }
            assertReasons()
            screen.recreate()
            awaitCheckButton(screen)
            assertReasons()
            screen.onActivity {
                assertTrue(it.findViewById<TextView>(R.id.captureDetail).text.contains(ui.context.getString(R.string.capture_recovery_failed)))
            }
            assertArrayEquals(bytes, store.staging(failed.id).readBytes())
            screenshot("camera-failed-recovery.png")
            assertEquals(CaptureSessionStore.Status.FAILED, store.load(failed.id)?.status)
        }
    }

    @Test fun readableFailedContainerCanBeCheckedWithoutErasingTheOriginalBytes() {
        val store = CaptureSessionStore(ui.context.filesDir)
        val draft = store.create(CaptureScript.heartbeat, CaptureMode.MUSIC, 1, true, 0)
        SyntheticVideo.create(store.staging(draft.id), 3_000)
        val bytes = store.staging(draft.id).readBytes()
        val failed = draft.copy(status = CaptureSessionStore.Status.FAILED, error = "Сохранение было прервано")
        store.save(failed)
        openSession(failed).use { screen ->
            awaitCheckButton(screen)
            onView(withId(R.id.capturePrimary)).perform(click())
            awaitReview(screen)
            assertArrayEquals(bytes, store.recording(failed.id).readBytes())
            val recovered = requireNotNull(store.load(failed.id))
            assertEquals(CaptureSessionStore.Status.READY, recovered.status)
            assertNull(recovered.error)
            screen.onActivity {
                assertFalse(it.findViewById<TextView>(R.id.captureDetail).text.contains("Сохранение было прервано"))
                assertFalse(it.findViewById<TextView>(R.id.capturePrimary).isEnabled)
            }
        }
    }

    private fun openSession(session: CaptureSessionStore.Session) = ActivityScenario.launch<CaptureActivity>(
        Intent(ui.context, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_STYLE, session.styleId)
            .putExtra(CaptureActivity.EXTRA_MODE, session.mode.name).putExtra(CaptureActivity.EXTRA_SESSION, session.id))

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle(3_000)
        assertTrue(device.takeScreenshot(File(ui.context.getExternalFilesDir("camera-evidence"), name)))
    }

    private fun awaitCheckButton(screen: ActivityScenario<CaptureActivity>) = awaitCondition(screen, "capture awaiting check") {
        it.findViewById<TextView>(R.id.capturePrimary).text == ui.context.getString(R.string.capture_check_saved)
    }

    private fun awaitCondition(screen: ActivityScenario<CaptureActivity>, description: String, condition: (CaptureActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (true) {
            var done = false
            screen.onActivity { done = condition(it) }
            if (done) return
            if (SystemClock.elapsedRealtime() >= deadline) fail(description)
            SystemClock.sleep(50)
        }
    }

    private fun awaitReview(screen: ActivityScenario<CaptureActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (true) {
            var restored = false
            screen.onActivity { restored = it.findViewById<TextView>(R.id.captureTitle).text ==
                ui.context.getString(R.string.capture_saved) }
            if (restored) return
            if (SystemClock.elapsedRealtime() >= deadline) fail("Saved capture did not open")
            SystemClock.sleep(50)
        }
    }
}
