package com.veycad.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.Activity
import android.app.Instrumentation
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import org.hamcrest.Matchers.allOf
import org.json.JSONObject
import java.io.IOException
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.hamcrest.Matchers.not
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Operates the real settings views and checks durable preferences and file effects. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val ui = UiTestFixtureRule()

    @Test fun clean_settings_show_owned_defaults_and_both_back_actions_keep_the_draft() {
        ui.launch()
        openSettings()
        onView(withId(R.id.settingsStyleValue)).check(matches(withText("Пульс под музыку  ›")))
        onView(withId(R.id.settingsQualityValue)).check(matches(withText("720p  ›")))
        onView(withId(R.id.autoSaveSwitch)).check(matches(not(isChecked())))
        onView(withId(R.id.notificationSwitch)).check(matches(not(isChecked())))
        onView(withId(R.id.directorContent)).check(matches(not(isDisplayed())))
        ui.click(R.id.backFromSettingsButton)
        ui.awaitDisplayed(R.id.directorContent)

        ui.importVideos("settings-draft.mp4")
        openSettings()
        pressBack()
        ui.awaitDisplayed(R.id.directorContent)
        onView(withId(R.id.videoSelection)).check(matches(withText("settings-draft.mp4")))
        onView(withId(R.id.renderButton)).check(matches(isEnabled()))
        openSettings()
        ui.click(R.id.backFromSettingsButton)
        ui.awaitDisplayed(R.id.directorContent)
        onView(withId(R.id.videoSelection)).check(matches(withText("settings-draft.mp4")))
    }

    @Test fun default_style_cancel_preserves_selection_and_apply_updates_both_screens_after_recreation() {
        ui.launch()
        openSettings()
        ui.click(R.id.defaultStyleSetting)
        choosePendingStyle("FEAR")
        onView(withText("Отмена")).inRoot(isDialog()).perform(click())
        onView(withId(R.id.settingsStyleValue)).check(matches(withText("Пульс под музыку  ›")))
        assertEquals("heartbeat", stylePreferences().getString("selected", "heartbeat"))

        ui.click(R.id.defaultStyleSetting)
        choosePendingStyle("FEAR")
        onView(withText("Применить")).inRoot(isDialog()).perform(click())
        ui.awaitText(R.id.settingsStyleValue, "Динамичный бит")
        assertEquals("fear_strobe", stylePreferences().getString("selected", null))
        ui.click(R.id.backFromSettingsButton)
        ui.awaitText(R.id.selectStyleButton, "FEAR")
        ui.recreate()
        ui.awaitText(R.id.selectStyleButton, "FEAR")
        openSettings()
        onView(withId(R.id.settingsStyleValue)).check(matches(withText("Динамичный бит  ›")))
        assertEquals("fear_strobe", stylePreferences().getString("selected", null))
    }

    @Test fun quality_choices_and_cancel_preserve_the_selected_resolution_after_recreation() {
        ui.launch()
        openSettings()
        ui.click(R.id.qualitySetting)
        onView(withText("720p")).inRoot(isDialog()).check(matches(isChecked()))
        onView(withText("1080p")).inRoot(isDialog()).check(matches(not(isChecked())))
        onView(withId(android.R.id.button2)).perform(click())
        onView(withId(R.id.settingsQualityValue)).check(matches(withText("720p  ›")))
        assertFalse(settingsPreferences().contains("render_quality"))

        for (quality in listOf("1080p", "720p")) {
            ui.click(R.id.qualitySetting)
            onView(withText(quality)).inRoot(isDialog()).perform(click())
            onView(withId(R.id.settingsQualityValue)).check(matches(withText("$quality  ›")))
            assertEquals(quality, settingsPreferences().getString("render_quality", null))
            ui.recreate()
            openSettings()
            onView(withId(R.id.settingsQualityValue)).check(matches(withText("$quality  ›")))
            ui.click(R.id.qualitySetting)
            onView(withText(quality)).inRoot(isDialog()).check(matches(isChecked()))
            onView(withId(android.R.id.button2)).perform(click())
            assertEquals(quality, settingsPreferences().getString("render_quality", null))
        }
    }

    @Test fun automatic_save_and_ready_notifications_are_independent_durable_switches() {
        ui.launch()
        openSettings()
        ui.click(R.id.autoSaveSwitch)
        onView(withId(R.id.autoSaveSwitch)).check(matches(isChecked()))
        onView(withId(R.id.notificationSwitch)).check(matches(not(isChecked())))
        assertTrue(settingsPreferences().getBoolean("auto_save", false))
        assertFalse(settingsPreferences().getBoolean("notifications", false))
        ui.click(R.id.notificationSwitch)
        onView(withId(R.id.notificationSwitch)).check(matches(isChecked()))
        assertTrue(settingsPreferences().getBoolean("notifications", false))
        ui.recreate()
        openSettings()
        onView(withId(R.id.autoSaveSwitch)).check(matches(isChecked()))
        onView(withId(R.id.notificationSwitch)).check(matches(isChecked()))

        ui.click(R.id.autoSaveSwitch)
        onView(withId(R.id.notificationSwitch)).check(matches(isChecked()))
        assertFalse(settingsPreferences().getBoolean("auto_save", true))
        assertTrue(settingsPreferences().getBoolean("notifications", false))
        ui.click(R.id.notificationSwitch)
        ui.recreate()
        openSettings()
        onView(withId(R.id.autoSaveSwitch)).check(matches(not(isChecked())))
        onView(withId(R.id.notificationSwitch)).check(matches(not(isChecked())))
        assertFalse(settingsPreferences().getBoolean("auto_save", true))
        assertFalse(settingsPreferences().getBoolean("notifications", true))
    }

    @Test fun temporary_file_clear_requires_confirmation_and_preserves_finished_and_saved_video_bytes() {
        ui.launch()
        ui.importVideos("cache-draft.mp4")
        ui.seedCompleted("completed.mp4")
        ui.seedSaved("saved.mp4")
        val completed = File(ui.context.filesDir, "completed-renders/completed.mp4")
        val saved = File(ui.context.filesDir, "my-edits/saved.mp4")
        val completedBytes = completed.readBytes()
        val savedBytes = saved.readBytes()
        val sources = File(ui.context.filesDir, "source-draft").listFiles()!!
            .filter(File::isFile).associateWith(File::readBytes)
        assertTrue("The test must have a real imported source", sources.isNotEmpty())
        val analysis = File(ui.context.cacheDir, "ui-analysis/cache.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10_000) { (it % 251).toByte() })
        }
        val pending = File(ui.context.filesDir, "pending-renders/unfinished.mp4.partial").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(3, 5, 7))
        }
        val analysisBytes = analysis.readBytes()
        openSettings()
        onView(withId(R.id.settingsCacheValue)).check(matches(withText(not("0 Б"))))
        ui.click(R.id.clearCacheButton)
        onView(withText("Очистить временные файлы?")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withId(android.R.id.button2)).perform(click())
        sources.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertArrayEquals(analysisBytes, analysis.readBytes())
        assertArrayEquals(byteArrayOf(3, 5, 7), pending.readBytes())
        assertArrayEquals(completedBytes, completed.readBytes())
        assertArrayEquals(savedBytes, saved.readBytes())
        assertEquals("cache-draft.mp4", ui.context.getSharedPreferences("source_draft", Context.MODE_PRIVATE)
            .getString("display_name", null))

        ui.click(R.id.clearCacheButton)
        onView(withId(android.R.id.button1)).perform(click())
        ui.awaitIdle()
        sources.keys.forEach { assertFalse("Imported source must be removed: ${it.name}", it.exists()) }
        assertFalse(analysis.exists())
        assertFalse(pending.exists())
        assertTrue(ui.context.getSharedPreferences("source_draft", Context.MODE_PRIVATE).all.isEmpty())
        assertArrayEquals(completedBytes, completed.readBytes())
        assertArrayEquals(savedBytes, saved.readBytes())
        onView(withId(R.id.settingsCacheValue)).check(matches(withText("0 Б")))
        ui.click(R.id.backFromSettingsButton)
        ui.awaitDisplayed(R.id.directorContent)
        onView(withId(R.id.renderButton)).check(matches(not(isEnabled())))
        onView(withId(R.id.videoSelection)).check(matches(withText("1 видео · от 15 секунд")))
    }

    @Test fun helpExportsCaptureFailureDetailsAndCancellationLeavesTheReportUntouched() {
        ui.launch()
        val original = File(ui.context.filesDir, "private-original.mov").apply { writeText("PRIVATE_MEDIA_BYTES") }
        val reportFile = File(ui.context.cacheDir, "exported-report.json")
        CaptureDiagnostics.failure(ui.context, CaptureDiagnostics.Stage.TAKE_SELECTION,
            RuntimeException(IOException(original.path)), mapOf("style" to "fear_strobe"))
        LocalDiagnostics.record(ui.context, "render_inspector_failed", mapOf("detail" to original.path))
        LocalDiagnostics.record(ui.context, "capture_test_unknown_fields", mapOf("file" to original.path,
            "error_message" to "PRIVATE_MEDIA_BYTES"))
        Intents.release()
        Intents.init()
        intending(allOf(hasAction(Intent.ACTION_CREATE_DOCUMENT), hasType("application/json")))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(reportFile))))
        openSettings()
        ui.click(R.id.helpButton)
        val screenshots = requireNotNull(ui.context.getExternalFilesDir("camera-evidence"))
        androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(screenshots, "capture-report-help.png"))
        onView(withText(R.string.capture_save_report)).inRoot(isDialog()).perform(click())
        ui.waitUntil("complete diagnostic export") {
            runCatching { JSONObject(reportFile.readText()).has("events") }.getOrDefault(false)
        }
        intended(allOf(hasAction(Intent.ACTION_CREATE_DOCUMENT), hasType("application/json")))
        val text = reportFile.readText()
        File(screenshots, "capture-exported-report.json").writeText(text)
        val report = JSONObject(text)
        assertEquals(ui.context.packageName, report.getString("package"))
        assertEquals(BuildConfig.VERSION_NAME, report.getString("version_name"))
        val events = report.getJSONArray("events")
        val failure = (0 until events.length()).map(events::getJSONObject).first { it.getString("event") == "capture_failed" }
        assertEquals("TAKE_SELECTION", failure.getString("stage"))
        assertEquals(IOException::class.java.name, failure.getString("root_exception"))
        assertFalse(text.contains("private-original.mov"))
        assertFalse(text.contains("PRIVATE_MEDIA_BYTES"))
        assertFalse(text.contains("render_inspector_failed"))
        assertTrue(original.isFile)
        assertEquals("PRIVATE_MEDIA_BYTES", original.readText())
        val saved = reportFile.readBytes()
        ui.recreate()
        Intents.release()
        Intents.init()
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        openSettings()
        ui.click(R.id.helpButton)
        onView(withText(R.string.capture_save_report)).inRoot(isDialog()).perform(click())
        assertArrayEquals(saved, reportFile.readBytes())
        ui.awaitDisplayed(R.id.settingsContent)
    }

    private fun openSettings() {
        ui.click(R.id.settingsButton)
        ui.awaitDisplayed(R.id.settingsContent)
    }

    private fun choosePendingStyle(title: String) {
        onView(withText(title)).inRoot(isDialog()).perform(scrollTo(), click())
    }

    private fun settingsPreferences() = ui.context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private fun stylePreferences() = ui.context.getSharedPreferences("montage_style", Context.MODE_PRIVATE)
}
