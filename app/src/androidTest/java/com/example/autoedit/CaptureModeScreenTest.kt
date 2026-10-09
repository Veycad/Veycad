package com.example.autoedit

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureModeScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun failedFullCaptureShowsItsTakeReasonWithoutOpeningLibraryAgain() {
        val previous = ui.seedCompleted("before-capture-rejection.mp4")
        val previousBytes = previous.file.readBytes()
        val store = CaptureSessionStore(ui.context.filesDir)
        val draft = store.create(CaptureScript.fear, CaptureMode.MUSIC, 1, true, 0)
        SyntheticVideo.create(store.staging(draft.id), CaptureScript.fear.durationMs)
        val saved = store.markReady(draft.copy(status = CaptureSessionStore.Status.FINALIZING),
            CaptureRecordingSession.readableVideoDurationMs(store.staging(draft.id)))
        val sourceBytes = store.recording(saved.id).readBytes()
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        pressBack()
        ui.click(R.id.myEditsButton)
        val label = ui.context.getString(R.string.capture_session_entry, "FEAR",
            ui.context.getString(R.string.capture_saved))
        onView(org.hamcrest.Matchers.allOf(isClickable(), withText(startsWith(label))))
            .perform(scrollTo(), click())
        onView(withId(R.id.capturePrimary)).check(matches(isEnabled())).perform(click())
        ui.waitUntil("native capture assessment ends", 90_000) {
            EditSession.get(ui.context).state.value?.let { !it.busy && it.error != null } == true
        }
        ui.awaitDisplayed(R.id.directorContent)
        onView(withId(R.id.operationError)).perform(scrollTo())
            .check(matches(withSubstring("Не удалось уверенно обнаружить человека")))
        assertTrue(ui.text(R.id.operationError).contains("Дубль 1"))
        assertTrue(ui.text(R.id.renderHint).contains("Не удалось уверенно обнаружить человека"))
        assertEquals("Rejected material must not reach the renderer", 0, ui.renderer.requestCount)
        assertArrayEquals(sourceBytes, store.recording(saved.id).readBytes())
        assertArrayEquals(previousBytes, previous.file.readBytes())
        assertEquals(CaptureSessionStore.Status.READY, store.load(saved.id)?.status)
        assertEquals(listOf(previous.file), CompletedRenderStore.list(ui.context.filesDir).map { it.file })
        val failures = LocalDiagnostics.recent(ui.context).map(::JSONObject)
            .filter { it.optString("event") == "capture_failed" }
        assertTrue(failures.any { it.optString("material_code") == "insufficient_human_evidence" })
        assertTrue(failures.any { it.optString("material_code") == "capture_no_eligible_take" })
    }

    @Test fun autoSelectionSurvivesRecreationAndGalleryDraftIsPreserved() {
        ui.launch()
        ui.importVideos("gallery-draft.mp4")
        ui.click(R.id.autoModeButton)
        ui.click(R.id.captureBestMode)
        ui.recreate()
        onView(withId(R.id.autoCaptureOptions)).check(matches(isDisplayed()))
        onView(withId(R.id.captureBestMode)).check(matches(isSelected()))
        onView(withId(R.id.selectVideoButton)).check(matches(not(isDisplayed())))
        assertEquals(ui.context.getString(R.string.capture_open), ui.text(R.id.renderButton))
        ui.click(R.id.galleryModeButton)
        assertEquals("gallery-draft.mp4", ui.text(R.id.videoSelection))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
    }

    @Test fun priorResultsRemainAccessibleAndCanStillBeSaved() {
        val first = ui.seedCompleted("first-take.mp4")
        val second = ui.seedCompleted("second-take.mp4")
        assertTrue(first.file.setLastModified(1_000_000))
        assertTrue(second.file.setLastModified(2_000_000))
        assertEquals(setOf(first.file.name, second.file.name), CompletedRenderStore.list(ui.context.filesDir).map { it.file.name }.toSet())
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        androidx.test.espresso.Espresso.pressBack()
        ui.click(R.id.myEditsButton)
        // The older entry must have its own save target, independent of the latest EditSession entry.
        onView(org.hamcrest.Matchers.allOf(withText(first.summary), isClickable()))
            .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click())
        ui.click(R.id.saveButton)
        ui.awaitIdle()
        assertTrue(CompletedRenderStore.list(ui.context.filesDir).single { it.file == first.file }.saved)
        assertFalse(CompletedRenderStore.list(ui.context.filesDir).single { it.file == second.file }.saved)
    }
}
