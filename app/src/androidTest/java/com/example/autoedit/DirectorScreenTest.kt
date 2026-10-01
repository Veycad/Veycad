package com.example.autoedit

import android.content.Context
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises real screen actions, gallery callbacks, local copies and bundled music selection. */
@RunWith(AndroidJUnit4::class)
class DirectorScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun splash_opens_a_fresh_director_with_render_disabled_until_a_source_is_imported() {
        ui.launch()
        ui.awaitDisplayed(R.id.directorContent)
        onView(withId(R.id.splashContent)).check(matches(not(isDisplayed())))
        onView(withId(R.id.resultContent)).check(matches(not(isDisplayed())))
        onView(withId(R.id.settingsContent)).check(matches(not(isDisplayed())))
        onView(withId(R.id.myEditsContent)).check(matches(not(isDisplayed())))
        onView(withId(R.id.selectVideoButton)).check(matches(isEnabled()))
        onView(withId(R.id.selectStyleButton)).check(matches(isEnabled()))
        onView(withId(R.id.myEditsButton)).check(matches(isEnabled()))
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
        onView(withId(R.id.selectStyleButton)).check(matches(withSubstring("Heartbeat")))
        onView(withId(R.id.videoSelection)).check(matches(withText("1 видео · от 15 секунд")))
        onView(withId(R.id.renderHint)).check(matches(withText("Сначала добавь видео")))
        onView(withId(R.id.appVersion)).check(matches(withSubstring(BuildConfig.VERSION_NAME)))
        onView(withId(R.id.progressPanel)).check(matches(not(isDisplayed())))
    }

    @Test fun single_video_import_copies_a_real_file_and_readies_the_selected_music() {
        ui.launch()
        ui.importVideos("portrait.mp4")
        onView(withId(R.id.videoSelection)).check(matches(withText("portrait.mp4")))
        onView(withId(R.id.musicSelection)).check(matches(withSubstring("Heartbeat")))
        onView(withId(R.id.musicSelection)).check(matches(withSubstring("120 BPM")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        onView(withId(R.id.renderHint)).check(matches(withText("Всё готово к монтажу")))
        onView(withId(R.id.progressPanel)).check(matches(not(isDisplayed())))
        val draft = RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty()
        assertEquals(1, draft.size)
        assertTrue(draft.single().isFile)
        assertTrue(draft.single().length() > 0L)
        assertEquals("mp4", draft.single().extension)
        assertArrayEquals(File(ui.context.getDir("ui-imports", 0), "portrait.mp4").readBytes(), draft.single().readBytes())
    }

    @Test fun replacing_an_import_removes_only_the_previous_draft_and_updates_its_display_name() {
        ui.launch()
        ui.importVideos("first.mp4")
        val previous = RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().single()
        ui.importVideos("replacement.mp4")
        onView(withId(R.id.videoSelection)).check(matches(withText("replacement.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        assertFalse("Successful replacement must not retain an obsolete source", previous.exists())
        val current = RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty()
        assertEquals(1, current.size)
        assertTrue(current.single().length() > 0L)
        assertArrayEquals(File(ui.context.getDir("ui-imports", 0), "replacement.mp4").readBytes(), current.single().readBytes())
        assertNotEquals(previous.name, current.single().name)
        assertEquals("replacement.mp4", ui.context.getSharedPreferences("source_draft", Context.MODE_PRIVATE)
            .getString("display_name", null))
    }

    @Test fun duality_requires_two_sources_and_keeps_the_picker_order_after_recreation() {
        ui.launch()
        ui.selectStyle("DUALITY")
        onView(withId(R.id.selectVideoButton)).check(matches(withText("Добавить 2 видео")))
        onView(withId(R.id.videoSelection)).check(matches(withText("2 видео · каждое от 6 секунд")))
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
        onView(withId(R.id.renderHint)).check(matches(withText("Сначала добавь два видео")))
        ui.importVideos("first-scene.mp4", "second-scene.mp4")
        onView(withId(R.id.videoSelection)).check(matches(withText("1 · first-scene.mp4\n2 · second-scene.mp4")))
        onView(withId(R.id.musicSelection)).check(matches(withSubstring("108 BPM")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        val names = ui.context.getSharedPreferences("source_draft", Context.MODE_PRIVATE)
        assertEquals("first-scene.mp4", names.getString("display_name", null))
        assertEquals("second-scene.mp4", names.getString("display_name_2", null))
        val imported = RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty()
        assertEquals(2, imported.size)
        assertArrayEquals(File(ui.context.getDir("ui-imports", 0), "first-scene.mp4").readBytes(),
            imported.single { it.name.startsWith("source-0-") }.readBytes())
        assertArrayEquals(File(ui.context.getDir("ui-imports", 0), "second-scene.mp4").readBytes(),
            imported.single { it.name.startsWith("source-1-") }.readBytes())
        ui.recreate()
        ui.awaitDisplayed(R.id.directorContent)
        ui.awaitText(R.id.musicSelection, "108 BPM")
        onView(withId(R.id.videoSelection)).check(matches(withText("1 · first-scene.mp4\n2 · second-scene.mp4")))
        onView(withId(R.id.selectStyleButton)).check(matches(withSubstring("DUALITY")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
    }

    @Test fun switching_single_dual_and_single_styles_preserves_sources_and_routes_matching_music() {
        ui.launch()
        ui.importVideos("original.mp4")
        ui.selectStyle("DUALITY")
        onView(withId(R.id.videoSelection)).check(matches(withText("Выбрано 1 из 2 · original.mp4")))
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
        ui.importVideos("primary.mp4", "secondary.mp4")
        ui.selectStyle("FEAR")
        ui.awaitText(R.id.musicSelection, "122 BPM")
        onView(withId(R.id.selectVideoButton)).check(matches(withText("Добавить видео")))
        onView(withId(R.id.videoSelection)).check(matches(withText("primary.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        ui.selectStyle("DUALITY")
        ui.awaitText(R.id.musicSelection, "108 BPM")
        onView(withId(R.id.videoSelection)).check(matches(withText("1 · primary.mp4\n2 · secondary.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
    }

    @Test fun recreation_keeps_the_imported_source_style_and_ready_state() {
        ui.launch()
        ui.selectStyle("FEAR")
        ui.importVideos("motion.mp4")
        val source = RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().single()
        ui.recreate()
        ui.awaitDisplayed(R.id.directorContent)
        ui.awaitText(R.id.musicSelection, "122 BPM")
        onView(withId(R.id.selectStyleButton)).check(matches(withSubstring("FEAR")))
        onView(withId(R.id.videoSelection)).check(matches(withText("motion.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        assertTrue(source.exists())
        assertEquals(source, RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().single())
    }

    @Test fun opening_the_empty_collection_and_returning_does_not_discard_a_ready_source() {
        ui.launch()
        ui.importVideos("portrait.mp4")
        ui.click(R.id.myEditsButton)
        ui.awaitDisplayed(R.id.myEditsContent)
        onView(withSubstring("Здесь появятся эдиты")).check(matches(isDisplayed()))
        pressBack()
        ui.awaitDisplayed(R.id.directorContent)
        onView(withId(R.id.videoSelection)).check(matches(withText("portrait.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
    }

    @Test fun cancelling_single_video_selection_preserves_the_existing_ready_draft() {
        ui.launch()
        ui.importVideos("portrait.mp4")
        val source = RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().single()
        ui.cancelVideoPicker()
        ui.awaitIdle()
        onView(withId(R.id.videoSelection)).check(matches(withText("portrait.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        assertEquals(source, RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().single())
    }

    @Test fun duality_rejects_wrong_unique_source_counts_without_importing_partial_drafts() {
        ui.launch()
        ui.selectStyle("DUALITY")
        val first = ui.videoUri("one.mp4")
        val second = ui.videoUri("two.mp4")
        val third = ui.videoUri("three.mp4")
        for (uris in listOf(emptyList(), listOf(first), listOf(first, first), listOf(first, second, third))) {
            ui.returnVideos(uris)
            ui.awaitIdle()
            onView(withId(R.id.videoSelection)).check(matches(withText("2 видео · каждое от 6 секунд")))
            onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
            onView(withId(R.id.selectVideoButton)).check(matches(isEnabled()))
            assertTrue("Rejected selection must not create source files", RenderWorkspace.sourceDraftDirectory(ui.context.filesDir)
                .listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun duplicate_picker_uris_are_deduplicated_without_changing_their_first_occurrence_order() {
        ui.launch()
        ui.selectStyle("DUALITY")
        val primary = ui.videoUri("primary.mp4")
        val secondary = ui.videoUri("secondary.mp4")
        ui.returnVideos(listOf(secondary, primary, secondary), waitForReady = true)
        onView(withId(R.id.videoSelection)).check(matches(withText("1 · secondary.mp4\n2 · primary.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        assertEquals(2, RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().size)
        val names = ui.context.getSharedPreferences("source_draft", Context.MODE_PRIVATE)
        assertEquals("secondary.mp4", names.getString("display_name", null))
        assertEquals("primary.mp4", names.getString("display_name_2", null))
    }

    @Test fun cancelling_dual_selection_preserves_both_names_files_and_readiness() {
        ui.launch()
        ui.selectStyle("DUALITY")
        ui.importVideos("first.mp4", "second.mp4")
        val sources = RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().toSet()
        ui.cancelVideoPicker()
        ui.awaitIdle()
        onView(withId(R.id.videoSelection)).check(matches(withText("1 · first.mp4\n2 · second.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        assertEquals(sources, RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().toSet())
    }

    @Test fun an_unreadable_import_does_not_destroy_the_previous_draft_or_leave_partial_files() {
        ui.launch()
        ui.importVideos("portrait.mp4")
        val source = RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().single()
        val missing = ui.videoUri("unreadable.mp4").buildUpon().appendPath("missing.mp4").build()
        ui.returnVideos(listOf(missing))
        ui.awaitIdle()
        onView(withId(R.id.videoSelection)).check(matches(withText("portrait.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        onView(withId(R.id.progressPanel)).check(matches(not(isDisplayed())))
        assertEquals(source, RenderWorkspace.sourceDraftDirectory(ui.context.filesDir).listFiles().orEmpty().single())
        assertEquals("portrait.mp4", ui.context.getSharedPreferences("source_draft", Context.MODE_PRIVATE)
            .getString("display_name", null))
    }
}
