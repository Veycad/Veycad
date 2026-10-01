package com.example.autoedit

import android.app.NotificationManager
import android.content.Context
import android.provider.MediaStore
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The render seam replaces inference/montage work only. Imports, progress/state, results,
 * local publication, gallery, collection and notifications run through production code. */
@RunWith(AndroidJUnit4::class)
class FeatureIntegrationTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun duality_import_order_full_hd_render_survives_recreation_and_saves_into_collection() {
        ui.launch()
        ui.click(R.id.settingsButton)
        ui.awaitDisplayed(R.id.settingsContent)
        ui.click(R.id.qualitySetting)
        onView(withText("1080p")).perform(click())
        ui.awaitText(R.id.settingsQualityValue, "1080p")
        ui.click(R.id.backFromSettingsButton)
        ui.selectStyle("DUALITY")
        ui.importVideos("z-selected-first.mp4", "a-selected-second.mp4")
        ui.awaitText(R.id.videoSelection, "z-selected-first.mp4")
        ui.awaitText(R.id.videoSelection, "a-selected-second.mp4")

        ui.startRender()
        val request = requireNotNull(ui.renderer.currentRequest)
        assertEquals(MontageStyleCatalog.Recipe.DUALITY_LOOP, request.recipe)
        assertEquals(1080, request.width)
        assertEquals(1920, request.height)
        assertEquals(8_000_000, request.bitrate)
        assertTrue(request.sourceFile.name.startsWith("source-0-"))
        assertTrue(requireNotNull(request.secondarySourceFile).name.startsWith("source-1-"))
        assertTrue(request.sourceFile.length() > 0L)
        assertTrue(requireNotNull(request.secondarySourceFile).length() > 0L)
        val importFixtures = ui.context.getDir("ui-imports", 0)
        val selectedFirst = java.io.File(importFixtures, "z-selected-first.mp4").readBytes()
        val selectedSecond = java.io.File(importFixtures, "a-selected-second.mp4").readBytes()
        assertFalse("Ordering needs distinct video bytes", selectedFirst.contentEquals(selectedSecond))
        assertArrayEquals(selectedFirst, request.sourceFile.readBytes())
        assertArrayEquals(selectedSecond, requireNotNull(request.secondarySourceFile).readBytes())
        assertTrue(DualityLoopProfile.matchesAudio(request.musicFile))
        val importedNames = ui.context.getSharedPreferences("source_draft", Context.MODE_PRIVATE)
        assertEquals("z-selected-first.mp4", importedNames.getString("display_name", null))
        assertEquals("a-selected-second.mp4", importedNames.getString("display_name_2", null))

        ui.recreate()
        ui.awaitDisplayed(R.id.progressPanel)
        assertSame(request, ui.renderer.currentRequest)
        ui.renderer.complete()
        ui.awaitDisplayed(R.id.resultContent)
        ui.awaitIdle()
        val entry = requireNotNull(CompletedRenderStore.latest(ui.context.filesDir))
        val bytes = entry.file.readBytes()
        ui.awaitText(R.id.resultSummary, "DUALITY")
        ui.click(R.id.saveButton)
        ui.awaitText(R.id.saveButton, "Сохранено")
        ui.awaitIdle()
        ui.assertGalleryCopy(entry.file.name)
        assertEquals(1, galleryCopyCount(entry.file.name))

        ui.click(R.id.newEditButton)
        ui.awaitDisplayed(R.id.directorContent)
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
        ui.awaitText(R.id.selectStyleButton, "DUALITY")
        ui.awaitText(R.id.myEditsButton, "1")
        ui.click(R.id.myEditsButton)
        ui.awaitDisplayed(R.id.myEditsContent)
        onView(withText(entry.file.nameWithoutExtension)).perform(scrollTo(), click())
        ui.awaitText(R.id.resultSummary, "Сохранённый монтаж")
        pressBack()
        ui.awaitDisplayed(R.id.myEditsContent)
        ui.recreate()
        ui.awaitDisplayed(R.id.resultContent)
        ui.awaitText(R.id.saveButton, "Сохранено")
        assertTrue(requireNotNull(CompletedRenderStore.latest(ui.context.filesDir)).saved)
        assertArrayEquals(bytes, entry.file.readBytes())
        assertEquals(1, galleryCopyCount(entry.file.name))
        pressBack()
        ui.awaitDisplayed(R.id.directorContent)
        ui.click(R.id.settingsButton)
        ui.awaitText(R.id.settingsStyleValue, "DUALITY")
        ui.awaitText(R.id.settingsQualityValue, "1080p")
    }

    @Test fun auto_save_and_ready_notification_publish_once_and_survive_navigation_and_recreation() {
        ui.launch()
        ui.click(R.id.settingsButton)
        ui.awaitDisplayed(R.id.settingsContent)
        ui.click(R.id.autoSaveSwitch)
        ui.click(R.id.notificationSwitch)
        ui.click(R.id.backFromSettingsButton)
        ui.selectStyle("FEAR")
        ui.importVideos("automatic-save-source.mp4")
        ui.startRender()
        val request = requireNotNull(ui.renderer.currentRequest)
        assertEquals(MontageStyleCatalog.Recipe.FEAR_STROBE, request.recipe)
        assertEquals(720, request.width)
        assertEquals(1280, request.height)
        assertTrue(FearStrobeProfile.matchesAudio(request.musicFile))
        ui.renderer.complete()
        ui.awaitDisplayed(R.id.resultContent)
        ui.awaitText(R.id.saveButton, "Сохранено")
        ui.awaitIdle()

        val entry = requireNotNull(CompletedRenderStore.latest(ui.context.filesDir))
        assertTrue(entry.saved)
        ui.assertGalleryCopy(entry.file.name)
        assertEquals(1, galleryCopyCount(entry.file.name))
        val manager = ui.context.getSystemService(NotificationManager::class.java)
        assertEquals(listOf(1201), manager.activeNotifications.map { it.id })
        assertEquals("Монтаж готов", manager.activeNotifications.single().notification.extras
            .getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals(entry.file.path, ui.context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("last_notified_render", null))

        ui.click(R.id.newEditButton)
        ui.click(R.id.myEditsButton)
        ui.awaitDisplayed(R.id.myEditsContent)
        onView(withText(entry.file.nameWithoutExtension)).perform(scrollTo(), click())
        ui.awaitText(R.id.resultSummary, "Сохранённый монтаж")
        pressBack()
        ui.awaitDisplayed(R.id.myEditsContent)
        ui.recreate()
        ui.awaitText(R.id.saveButton, "Сохранено")
        ui.awaitIdle()
        assertEquals(1, galleryCopyCount(entry.file.name))
        assertEquals(listOf(1201), manager.activeNotifications.map { it.id })
        assertTrue(requireNotNull(CompletedRenderStore.latest(ui.context.filesDir)).saved)
    }

    private fun galleryCopyCount(name: String): Int = requireNotNull(ui.context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID),
        "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(name), null
    )).use { it.count }
}
