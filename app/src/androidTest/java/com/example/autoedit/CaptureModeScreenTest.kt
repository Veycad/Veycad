package com.example.autoedit

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureModeScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

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
