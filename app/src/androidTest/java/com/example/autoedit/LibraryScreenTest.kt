package com.veycad.app

import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun empty_collection_shows_help_and_both_back_routes_return_to_director() {
        ui.launch()
        ui.click(R.id.myEditsButton)
        ui.awaitDisplayed(R.id.myEditsContent)
        onView(withText(containsString("Здесь появятся эдиты"))).check(matches(isDisplayed()))
        assertEquals(1, libraryRows().size)

        ui.click(R.id.backFromEditsButton)
        ui.awaitDisplayed(R.id.directorContent)
        ui.click(R.id.myEditsButton)
        ui.awaitDisplayed(R.id.myEditsContent)
        pressBack()
        ui.awaitDisplayed(R.id.directorContent)
    }

    @Test fun unsaved_completed_render_is_accessible_without_being_counted_as_saved() {
        ui.seedCompleted("unsaved-library-result.mp4")
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        val file = requireNotNull(CompletedRenderStore.latest(ui.context.filesDir)).file
        val bytes = file.readBytes()
        pressBack()
        ui.awaitDisplayed(R.id.directorContent)
        ui.click(R.id.myEditsButton)
        ui.awaitDisplayed(R.id.myEditsContent)

        val label = "Последний монтаж · ещё не сохранён в галерею"
        assertEquals(listOf(label), libraryRows())
        onView(withText(label)).perform(scrollTo(), click())
        ui.awaitDisplayed(R.id.resultContent)
        onView(withId(R.id.saveButton)).perform(scrollTo()).check(matches(isDisplayed()))
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(File(ui.context.filesDir, "my-edits").listFiles().orEmpty().isEmpty())
    }

    @Test fun saved_collection_is_sorted_counted_and_returns_from_preview_to_collection() {
        ui.seedSaved("older-edit.MP4")
        ui.seedSaved("newer-edit.mp4")
        val older = File(ui.context.filesDir, "my-edits/older-edit.MP4")
        val newer = File(ui.context.filesDir, "my-edits/newer-edit.mp4")
        assertTrue(older.setLastModified(1_000_000L))
        assertTrue(newer.setLastModified(2_000_000L))
        val originalBytes = newer.readBytes()
        ui.launch()
        ui.awaitText(R.id.myEditsButton, "2")
        ui.click(R.id.myEditsButton)
        ui.awaitDisplayed(R.id.myEditsContent)
        assertEquals(listOf("newer-edit", "older-edit"), libraryRows())

        onView(withText("newer-edit")).perform(scrollTo(), click())
        ui.awaitDisplayed(R.id.resultContent)
        ui.awaitText(R.id.resultSummary, "Сохранённый монтаж")
        onView(withId(R.id.saveButton)).check(matches(not(isDisplayed())))
        onView(withId(R.id.shareButton)).perform(scrollTo()).check(matches(isDisplayed()))
        assertArrayEquals(originalBytes, newer.readBytes())
        pressBack()
        ui.awaitDisplayed(R.id.myEditsContent)
        assertEquals(listOf("newer-edit", "older-edit"), libraryRows())
        onView(withId(R.id.backFromEditsButton)).perform(scrollTo()).check(matches(isDisplayed()))
        ui.scenario.onActivity { activity ->
            val action = activity.findViewById<android.view.View>(R.id.backFromEditsButton)
            val position = IntArray(2).also(action::getLocationOnScreen)
            val root = action.rootView
            val rootPosition = IntArray(2).also(root::getLocationOnScreen)
            val bars = requireNotNull(ViewCompat.getRootWindowInsets(action)).getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            assertTrue("Last collection action must remain above the navigation bar",
                position[1] + action.height <= rootPosition[1] + root.height - bars.bottom)
        }
        ui.click(R.id.backFromEditsButton)
        ui.awaitDisplayed(R.id.directorContent)
        ui.awaitText(R.id.myEditsButton, "2")
    }

    @Test fun unsaved_entry_precedes_saved_rows_without_hiding_saved_collection() {
        ui.seedSaved("existing-saved.mp4")
        ui.seedCompleted("latest-unsaved.mp4")
        ui.launch()
        ui.awaitDisplayed(R.id.resultContent)
        pressBack()
        ui.awaitDisplayed(R.id.directorContent)
        ui.awaitText(R.id.myEditsButton, "1")
        ui.click(R.id.myEditsButton)
        ui.awaitDisplayed(R.id.myEditsContent)
        assertEquals(listOf("Последний монтаж · ещё не сохранён в галерею", "existing-saved"), libraryRows())
        onView(withText("existing-saved")).perform(scrollTo(), click())
        ui.awaitText(R.id.resultSummary, "Сохранённый монтаж")
        pressBack()
        ui.awaitDisplayed(R.id.myEditsContent)
        assertEquals(2, libraryRows().size)
    }

    private fun libraryRows(): List<String> {
        var rows = emptyList<String>()
        ui.scenario.onActivity { activity ->
            val layout = activity.findViewById<LinearLayout>(R.id.myEditsList)
            rows = (0 until layout.childCount).map { (layout.getChildAt(it) as TextView).text.toString() }
        }
        return rows
    }
}
