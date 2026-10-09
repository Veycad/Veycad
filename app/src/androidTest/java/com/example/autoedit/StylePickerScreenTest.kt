package com.example.autoedit

import android.content.Context
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StylePickerScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    private fun pickPending(title: String) {
        onView(allOf(isClickable(), hasDescendant(withText(title)))).perform(scrollTo(), click())
    }

    @Test fun all_styles_opens_the_same_picker_and_cancel_preserves_the_selected_style() {
        ui.launch()
        ui.click(R.id.allStylesButton)
        onView(withText("Выбрать стиль")).check(matches(isDisplayed()))
        pickPending("FEAR")
        assertEquals(null, ui.context.getSharedPreferences("montage_style", Context.MODE_PRIVATE)
            .getString("selected", null))
        onView(withText("Отмена")).perform(click())
        onView(withId(R.id.selectStyleButton)).check(matches(withSubstring("Heartbeat")))
        assertEquals(null, ui.context.getSharedPreferences("montage_style", Context.MODE_PRIVATE)
            .getString("selected", null))
    }

    @Test fun system_back_discards_a_pending_choice_and_keeps_the_imported_source() {
        ui.launch()
        ui.importVideos("portrait.mp4")
        ui.click(R.id.selectStyleButton)
        pickPending("DUALITY")
        pressBack()
        onView(withId(R.id.selectStyleButton)).check(matches(withSubstring("Heartbeat")))
        onView(withId(R.id.videoSelection)).check(matches(withText("portrait.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
    }

    @Test fun apply_persists_the_chosen_style_and_recreation_restores_it() {
        ui.launch()
        ui.selectStyle("FEAR")
        onView(withId(R.id.selectStyleButton)).check(matches(withSubstring("FEAR")))
        assertEquals("fear_strobe", ui.context.getSharedPreferences("montage_style", Context.MODE_PRIVATE)
            .getString("selected", null))
        ui.recreate()
        ui.awaitDisplayed(R.id.directorContent)
        onView(withId(R.id.selectStyleButton)).check(matches(withSubstring("FEAR")))
        onView(withId(R.id.videoSelection)).check(matches(withText("1 видео · от 15 секунд")))
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
    }

    @Test fun paused_sigma_and_every_upcoming_row_are_visible_but_cannot_be_selected() {
        ui.launch()
        ui.click(R.id.selectStyleButton)
        val unavailable = listOf("Сигма", "Upcoming", "Upcoming Lite", "Next Motion", "Next Film", "Next Rhythm", "Next Portrait")
        for (title in unavailable) {
            val row = withContentDescription(startsWith("$title."))
            onView(row).perform(scrollTo())
            onView(row).check(matches(not(isEnabled())))
            onView(row).check(matches(not(isClickable())))
            onView(row).check(matches(not(isSelected())))
        }
        onView(withText("Применить")).perform(click())
        onView(withId(R.id.selectStyleButton)).check(matches(withSubstring("Heartbeat")))
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
    }

    @Test fun scrolling_the_last_choice_keeps_apply_and_cancel_fully_accessible() {
        ui.launch()
        ui.click(R.id.selectStyleButton)
        onView(withText("Next Portrait")).perform(scrollTo())
        onView(withText("Next Portrait")).check(matches(isDisplayed()))
        onView(withText("Применить")).check(matches(isCompletelyDisplayed()))
        onView(withText("Отмена")).check(matches(isCompletelyDisplayed()))
        onView(withText("Отмена")).perform(click())
        ui.awaitDisplayed(R.id.directorContent)
    }

    @Test fun all_available_styles_apply_their_own_source_requirements_and_bundled_music() {
        ui.launch()
        val choices = listOf(Triple("FEAR", "Добавить видео", "122 BPM"),
            Triple("DUALITY", "Добавить 2 видео", "108 BPM"),
            Triple("Heartbeat", "Добавить видео", "120 BPM"))
        for ((title, importLabel, tempo) in choices) {
            ui.selectStyle(title)
            ui.awaitText(R.id.musicSelection, tempo)
            onView(withId(R.id.selectStyleButton)).check(matches(withSubstring(title)))
            onView(withId(R.id.selectVideoButton)).check(matches(withText(importLabel)))
            onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
        }
    }

    @Test fun switching_styles_keeps_unrelated_preferences_and_uses_the_final_music_callback() {
        val settings = ui.context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        settings.edit().putString("render_quality", "1080p").putBoolean("auto_save", true).commit()
        ui.launch()
        ui.importVideos("portrait.mp4")
        ui.selectStyle("FEAR")
        ui.selectStyle("Heartbeat")
        ui.awaitText(R.id.musicSelection, "120 BPM")
        onView(withId(R.id.videoSelection)).check(matches(withText("portrait.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        assertEquals("1080p", settings.getString("render_quality", null))
        assertEquals(true, settings.getBoolean("auto_save", false))
        assertEquals("heartbeat", ui.context.getSharedPreferences("montage_style", Context.MODE_PRIVATE)
            .getString("selected", null))
    }
}
